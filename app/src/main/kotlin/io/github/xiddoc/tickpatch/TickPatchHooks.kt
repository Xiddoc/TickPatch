/*
 * TickPatchHooks — the LSPosed entry point (registered via assets/xposed_init).
 *
 * It dogfoods the full Rosetta path. On load into TickTick it:
 *
 *   1. waits for a real Application (so it has a Context — needed to read the
 *      app identity and the toggle), by hooking `Application#onCreate`;
 *   2. reads the running app's identity from PackageManager (version_code +
 *      the signing-cert hashes);
 *   3. loads EVERY bundled Rosetta map (enumerated from maps/index.json, so
 *      there is no hand-maintained version list to forget) and selects the one
 *      whose version_code matches the running app, with the map's
 *      `signer_sha256` enforced fail-closed — degrading LOUDLY to an unverified
 *      bind if the signer does not match (so a dogfood on a differently-signed
 *      build still works, with a warning, instead of silently doing nothing);
 *   4. resolves the Pro gate (`User#isPro`, `User#isActiveTeamUser`,
 *      `User#getProType`, `ProHelper#isPro`) by REAL name and hooks each
 *      through the framework-agnostic Hooker seam.
 *
 * There is not a single hard-coded obfuscated name here — that is the whole
 * point. When TickTick rotates these names, only the bundled map changes.
 *
 * The hooks are installed ONCE; whether they actually force Pro is decided LIVE,
 * per call, by the in-app toggle ([Prefs]) read through XSharedPreferences. So
 * flipping the switch in [MainActivity] takes effect on TickTick's next Pro
 * check; the "Force-restart TickTick" button guarantees a clean re-read by
 * sending [Prefs.ACTION_RESTART], which the receiver registered here turns into
 * a process kill. Every step is wrapped fail-soft so a miss only means "no Pro
 * override", never a TickTick crash.
 */
package io.github.xiddoc.tickpatch

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

class TickPatchHooks : IXposedHookLoadPackage {
    /** Installs the Pro hooks EXACTLY once even though Application#onCreate may fire repeatedly. */
    private val installed = AtomicBoolean(false)

    /** The toggle, read live from inside TickTick's process via the world-readable module prefs. */
    private val prefs by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).also { it.makeWorldReadable() }
    }

    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        // No Application (hence no Context) exists this early in
        // handleLoadPackage, but we need one to read identity + the toggle and
        // to enforce the signer guard. Defer the install to Application#onCreate.
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        installOnce(app, lpparam.classLoader)
                    }
                },
            )
        }.onFailure { e ->
            XposedBridge.log("TickPatch: could not defer install: ${e.message}")
        }
    }

    /** Build the Rosetta binding and install the Pro hooks, exactly once. */
    private fun installOnce(
        app: Application,
        classLoader: ClassLoader,
    ) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            // The restart receiver is independent of whether a map matches, so
            // register it first — the UI button must work on any TickTick build.
            registerRestartReceiver(app)

            val identity = AndroidAppIdentity.of(app.packageManager, app.packageName)

            // Load EVERY bundled map (no hand-maintained list — see maps/index.json)
            // and select the one matching the running version_code.
            val maps = bundledMapFiles().map { BundledMaps.load(it) }
            val target = maps.firstOrNull { it.versionCode == identity.versionCode }
            if (target == null) {
                XposedBridge.log(
                    "TickPatch: no bundled map for version_code ${identity.versionCode} " +
                        "(${identity.versionName}); this build ships ${maps.map { it.versionCode }}. " +
                        "Pro override inactive — map this TickTick version in rosetta-maps.",
                )
                return
            }

            // Enforce the signer guard; on a mismatch, degrade LOUDLY to an
            // unverified bind so the dogfood still works on a differently-signed
            // build instead of silently no-opping.
            val rosetta =
                try {
                    RosettaXposed.fromMap(target, classLoader, identity)
                } catch (e: RuntimeException) {
                    XposedBridge.log(
                        "TickPatch: signer guard failed for version_code ${identity.versionCode} " +
                            "(${e.javaClass.simpleName}: ${e.message}); installing UNVERIFIED (dogfood " +
                            "fallback). If this is the genuine TickTick, fix the map's signer_sha256.",
                    )
                    RosettaXposed.fromMapUnverified(target, classLoader)
                }

            // Resolve the Pro gate BY REAL NAME and hook each surface. Hooking
            // the field getter (getProType -> 1) and isActiveTeamUser, not just
            // isPro(), covers feature code that reads pro state directly rather
            // than through the boolean wrapper. ProHelper.isPro(User) is the
            // wrapper most feature code calls (covers the null-user path).
            hookByRealName(rosetta, USER_CLASS, "isPro", null, forceWhenEnabled(true))
            hookByRealName(rosetta, USER_CLASS, "isActiveTeamUser", null, forceWhenEnabled(true))
            hookByRealName(rosetta, USER_CLASS, "getProType", null, forceWhenEnabled(PRO_TYPE_PRO))
            hookByRealName(rosetta, PRO_HELPER_CLASS, "isPro", listOf(USER_CLASS), forceWhenEnabled(true))

            XposedBridge.log(
                "TickPatch: armed for ${identity.packageName}@${identity.versionName} " +
                    "(version_code ${identity.versionCode}); toggle decides Pro per call.",
            )
        }.onFailure { e ->
            // Never crash the host — just stay inactive.
            XposedBridge.log("TickPatch: install skipped (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * A live-gated callback that forces a hooked method's result to [value] (a
     * boxed `true`/`Int`) while the in-app toggle is on. Setting the result in
     * the before phase short-circuits the original method.
     */
    private fun forceWhenEnabled(value: Any): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (proEnabled()) param.result = value
            }
        }

    /**
     * Resolve [realClass]#[realMethod] (optionally disambiguated by [argTypes])
     * through Rosetta and apply [callback], fail-soft. A single unmappable
     * target must not take down the other hooks or the host app.
     */
    private fun hookByRealName(
        rosetta: RosettaXposed,
        realClass: String,
        realMethod: String,
        argTypes: List<String>?,
        callback: XC_MethodHook,
    ) {
        runCatching {
            rosetta.method(realClass, realMethod, argTypes).hook(RosettaLegacyHooker.legacy(callback))
            XposedBridge.log("TickPatch: hooked $realClass#$realMethod by real name.")
        }.onFailure { e ->
            XposedBridge.log("TickPatch: could not hook $realClass#$realMethod: ${e.message}")
        }
    }

    /**
     * Register a receiver that kills TickTick's process when [MainActivity]
     * sends [Prefs.ACTION_RESTART]. A normal app can't force-stop another, but
     * our hook runs INSIDE TickTick, so it can end its own process cooperatively;
     * the UI relaunches it. Exported so the (explicit, package-targeted)
     * broadcast from the module process is delivered.
     */
    private fun registerRestartReceiver(app: Application) {
        runCatching {
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        XposedBridge.log("TickPatch: restart requested — killing ${app.packageName} (pid ${Process.myPid()}).")
                        Process.killProcess(Process.myPid())
                    }
                }
            val filter = IntentFilter(Prefs.ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        }.onFailure { e ->
            XposedBridge.log("TickPatch: could not register restart receiver: ${e.message}")
        }
    }

    /** Re-read the world-readable toggle live (cheap; reload() no-ops if unchanged). */
    private fun proEnabled(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Prefs.KEY_PRO_ENABLED, false)
    }

    /**
     * The bundled map filenames, read from `maps/index.json` (emitted by
     * tools/generate-map.py alongside the maps). Enumerating the index — rather
     * than hard-coding a version list here — means a newly generated map is
     * picked up automatically and a version can never be silently left out.
     */
    private fun bundledMapFiles(): List<String> {
        val text =
            TickPatchHooks::class.java.classLoader
                ?.getResourceAsStream(MAP_INDEX)
                ?.use { it.readBytes().decodeToString() }
                ?: error("bundled $MAP_INDEX missing from the module APK")
        val arr = JSONArray(text)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.ticktick.task"

        /** Manifest of bundled maps (filenames), emitted by tools/generate-map.py. */
        const val MAP_INDEX = "maps/index.json"

        const val USER_CLASS = "com.ticktick.task.data.User"
        const val PRO_HELPER_CLASS = "com.ticktick.task.helper.pro.ProHelper"

        /** `User.proType == 1` means Pro (research/com.ticktick.task/docs/premium.md §2.1). */
        const val PRO_TYPE_PRO = 1
    }
}
