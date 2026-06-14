/*
 * TickPatchHooks — the LSPosed entry point (registered via assets/xposed_init).
 *
 * It dogfoods the full Rosetta path. On load into TickTick it:
 *
 *   1. waits for a real Application (so it has a Context — needed to read the
 *      app identity and the toggle), by hooking `Application#onCreate`;
 *   2. reads the running app's identity from PackageManager (version_code +
 *      the signing-cert hashes);
 *   3. selects + loads the BUNDLED Rosetta map by version_code, with the map's
 *      `signer_sha256` ENFORCED fail-closed against the real signing cert
 *      (RosettaXposed.fromRegistry → SignerGuard);
 *   4. resolves `com.ticktick.task.data.User#isPro` and
 *      `com.ticktick.task.helper.pro.ProHelper#isPro` by their REAL names and
 *      hooks them through the framework-agnostic Hooker seam.
 *
 * There is not a single hard-coded obfuscated name here — that is the whole
 * point. When TickTick rotates these names, only the bundled map changes.
 *
 * The hooks are installed ONCE; whether they actually force Pro is decided LIVE,
 * per call, by the in-app toggle ([Prefs]) read through XSharedPreferences. So
 * flipping the switch in [MainActivity] takes effect on TickTick's next Pro
 * check (a force-restart guarantees a clean re-read) without re-installing
 * anything. Every step is wrapped fail-soft so a miss only means "no Pro
 * override", never a TickTick crash.
 */
package io.github.xiddoc.tickpatch

import android.annotation.SuppressLint
import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.core.version.MapRegistry
import io.github.xiddoc.rosetta.xposed.RosettaXposed
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
            val identity = AndroidAppIdentity.of(app.packageManager, app.packageName)

            // Build a registry from every bundled map and let Rosetta select the
            // one matching the running version_code. The signer guard inside
            // fromRegistry verifies TickTick's real signing cert against the
            // selected map's signer_sha256 (fail-closed).
            val maps = BUNDLED_MAPS.map { BundledMaps.load(it) }
            val rosetta = RosettaXposed.fromRegistry(MapRegistry.of(*maps.toTypedArray()), identity, classLoader)
            if (rosetta == null) {
                XposedBridge.log(
                    "TickPatch: no bundled map for version_code ${identity.versionCode} " +
                        "(${identity.versionName}); this build ships ${maps.map { it.versionCode }}. " +
                        "Pro override inactive — add a map for this TickTick version.",
                )
                return
            }

            // The shared, live-gated callback: force the boolean result to true
            // ONLY while the in-app toggle is on. Setting result in the before
            // phase short-circuits the original, so isPro() returns true.
            val forceProWhenEnabled =
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (proEnabled()) param.result = true
                    }
                }

            // Resolve BY REAL NAME through the map and hook. User.isPro() is the
            // canonical gate; ProHelper.isPro(User) is the wrapper most feature
            // code calls — hooking both covers the null-user path too.
            hookByRealName(rosetta, USER_CLASS, IS_PRO, null, forceProWhenEnabled)
            hookByRealName(rosetta, PRO_HELPER_CLASS, IS_PRO, listOf(USER_CLASS), forceProWhenEnabled)

            XposedBridge.log(
                "TickPatch: armed for ${identity.packageName}@${identity.versionName} " +
                    "(version_code ${identity.versionCode}); toggle decides Pro per call.",
            )
        }.onFailure { e ->
            // Most likely a signer mismatch (repackaged TickTick) or a version
            // with no bundled map. Never crash the host — just stay inactive.
            XposedBridge.log("TickPatch: install skipped (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * Resolve [realClass]#[realMethod] (optionally disambiguated by [argTypes])
     * through Rosetta and apply [callback], fail-soft. A single unmappable
     * target must not take down the other hook or the host app.
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

    /** Re-read the world-readable toggle live (cheap; reload() no-ops if unchanged). */
    private fun proEnabled(): Boolean {
        prefs.reload()
        return prefs.getBoolean(Prefs.KEY_PRO_ENABLED, false)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.ticktick.task"

        /**
         * Bundled maps under app/src/main/resources/maps/, one per supported
         * TickTick version_code (8.0.8.0 / 8.0.8.1). Rosetta selects the one
         * matching the running app; keep in sync with tools/generate-map.py.
         */
        val BUNDLED_MAPS = listOf("8080.json", "8081.json")

        const val USER_CLASS = "com.ticktick.task.data.User"
        const val PRO_HELPER_CLASS = "com.ticktick.task.helper.pro.ProHelper"
        const val IS_PRO = "isPro"
    }
}
