/*
 * TickPatchHooks — the LSPosed entry point (registered via assets/xposed_init).
 *
 * It dogfoods the full Rosetta path. On load into TickTick it:
 *
 *   1. waits for a real Application (so it has a Context — needed to read the
 *      app identity and the toggle), by hooking `Application#onCreate`;
 *   2. reads the running app's identity from PackageManager (version_code +
 *      the signing-cert hashes);
 *   3. picks a resolution backend by the running version_code:
 *        - a bundled Rosetta map exists (`maps/<version_code>.json`, fetched
 *          from rosetta-maps at build time by the `io.github.xiddoc.rosetta.maps`
 *          Gradle plugin) → the fast O(1) STATIC path, with the map's
 *          `signer_sha256` enforced fail-closed (degrading LOUDLY to an
 *          unverified bind on a mismatch so a differently-signed dogfood build
 *          still works, with a warning, instead of silently doing nothing);
 *        - NO bundled map → the SELF-HEALING path: a real on-device DexKit
 *          bridge over TickTick's APK, driven by the bundled community
 *          signatures (`signatures/com.ticktick.task.json`), via
 *          `RosettaXposed.fromMapWithSignatures(...)`. The signatures locate
 *          `User` / `ProHelper` by their stable string anchors and Rosetta's
 *          kept-name member harvest resolves the stringless Pro-gate methods —
 *          so an UNMAPPED TickTick version heals live instead of going inactive;
 *   4. forces Pro by REAL name through the framework-agnostic Hooker seam — by
 *      pinning the underlying STATE rather than the boolean gate: it coerces
 *      `User#setProType` to 1 (so `User#isPro` is true consistently and survives
 *      a server sync) and forces `User#getProType` -> 1 and `ProHelper#isPro`. It
 *      deliberately does NOT force `User#isPro` / `User#isActiveTeamUser` — both
 *      crash the home screen on launch (see [installProHooks] for why).
 *
 * There is not a single hard-coded obfuscated name here — that is the whole
 * point. When TickTick rotates these names, a bundled map (if any) or the
 * community signatures track them; the code never changes. This module is a
 * pure dogfood of rosetta-xposed — it uses only Rosetta features, no bespoke
 * discovery logic of its own.
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
import dalvik.system.BaseDexClassLoader
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.xiddoc.rosetta.android.BundledMaps
import io.github.xiddoc.rosetta.android.BundledSignatures
import io.github.xiddoc.rosetta.android.PersistentDiscoveryCache
import io.github.xiddoc.rosetta.core.model.CURRENT_SCHEMA_VERSION
import io.github.xiddoc.rosetta.core.model.RosettaMap
import io.github.xiddoc.rosetta.dexkit.DexKitBackedIndex
import io.github.xiddoc.rosetta.xposed.AppIdentity
import io.github.xiddoc.rosetta.xposed.DiscoveryConfig
import io.github.xiddoc.rosetta.xposed.RosettaXposed
import org.luckypray.dexkit.DexKitBridge
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

            // Select the backend by the running version_code: the plugin bundles
            // each map as `maps/<version_code>.json`. A HIT takes the fast O(1)
            // STATIC path (no DexKit). A MISS falls back to SELF-HEALING discovery
            // driven by the bundled community signatures — so an unmapped TickTick
            // version resolves the Pro gate live instead of going inactive.
            val bundledMap = runCatching { BundledMaps.load("${identity.versionCode}.json") }.getOrNull()
            if (bundledMap != null) {
                installStaticBacked(bundledMap, identity, classLoader)
            } else {
                installSelfHealing(app, identity, classLoader)
            }
        }.onFailure { e ->
            // Never crash the host — just stay inactive.
            XposedBridge.log("TickPatch: install skipped (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * STATIC path: a bundled map covers the running version. Enforce the signer
     * guard; on a mismatch degrade LOUDLY to an unverified bind so the dogfood
     * still works on a differently-signed build instead of silently no-opping.
     */
    private fun installStaticBacked(
        map: RosettaMap,
        identity: AppIdentity,
        classLoader: ClassLoader,
    ) {
        val rosetta =
            try {
                RosettaXposed.fromMap(map, classLoader, identity)
            } catch (e: RuntimeException) {
                XposedBridge.log(
                    "TickPatch: signer guard failed for version_code ${identity.versionCode} " +
                        "(${e.javaClass.simpleName}: ${e.message}); installing UNVERIFIED (dogfood " +
                        "fallback). If this is the genuine TickTick, fix the map's signer_sha256.",
                )
                RosettaXposed.fromMapUnverified(map, classLoader)
            }
        installProHooks(rosetta)
        XposedBridge.log(
            "TickPatch: armed (static map) for ${identity.packageName}@${identity.versionName} " +
                "(version_code ${identity.versionCode}); toggle decides Pro per call.",
        )
    }

    /**
     * SELF-HEALING path: no bundled map for this version, so discover the Pro
     * gate live from the bundled community signatures via on-device DexKit
     * (`RosettaXposed.fromMapWithSignatures`). Discovery runs EAGERLY as each
     * target is resolved+hooked, so the whole bind happens INSIDE the
     * `DexKitBridge.create(...).use { }` block — the resolved Members outlive the
     * bridge, so it is safe to close once the hooks are installed. Fail-soft: any
     * failure (no signatures, no DexKit native, a discovery miss) only means "no
     * Pro override", never a TickTick crash.
     */
    private fun installSelfHealing(
        app: Application,
        identity: AppIdentity,
        classLoader: ClassLoader,
    ) {
        val signatures =
            runCatching { BundledSignatures.load(TARGET_PACKAGE) }
                .getOrElse {
                    XposedBridge.log(
                        "TickPatch: no bundled map for version_code ${identity.versionCode} " +
                            "(${identity.versionName}) AND no bundled signatures to self-heal from — " +
                            "Pro override inactive. Add this version to rosettaMaps { versions }, or ship " +
                            "signatures for $TARGET_PACKAGE in rosetta-maps.",
                    )
                    return
                }

        // DexKitBridge.create calls straight into the native lib, so make it
        // loadable first (fail-soft). A miss here means "no DexKit on this
        // device" — self-heal unavailable, never a crash.
        if (!ensureDexKitNativeLoaded()) return

        DexKitBridge.create(app.applicationInfo.sourceDir).use { bridge ->
            val observer = XposedDiscoveryObserver()
            // A cross-restart discovery cache keyed on (app, version_code, signer):
            // a healed name is reused next launch without re-scanning the dex, and
            // a version/signer bump invalidates it automatically.
            val prefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            val cache = PersistentDiscoveryCache.create(SharedPreferencesStore(prefs), identity, observer)

            val rosetta =
                RosettaXposed.fromMapWithSignatures(
                    // No published map for this version → an empty base map; the
                    // signatures cover the names. The empty map declares no
                    // signer, so passing `identity` enforces nothing here (there
                    // is no map signer to verify against on an unmapped version).
                    map = emptyMapFor(identity),
                    index = DexKitBackedIndex(bridge),
                    signatures = signatures,
                    classLoader = classLoader,
                    identity = identity,
                    discovery = DiscoveryConfig(cache = cache, observer = observer),
                )
            installProHooks(rosetta)
            XposedBridge.log(
                "TickPatch: armed (self-healing via community signatures) for " +
                    "${identity.packageName}@${identity.versionName} (version_code ${identity.versionCode}); " +
                    "obfuscated names discovered on-device. Toggle decides Pro per call.",
            )
        }
    }

    /**
     * Make DexKit's native library loadable BEFORE [DexKitBridge.create] (which
     * calls straight into it) — fail-soft. The module runs inside TickTick's
     * process, whose `java.library.path` does NOT contain the module's
     * `libdexkit.so`, so [System.loadLibrary] usually misses; fall back to
     * [System.load] of the MODULE's own extracted `.so`, located via the module
     * class loader's [BaseDexClassLoader.findLibrary] (visibility-independent — it
     * reads the module APK's own lib dir, which is why the build sets
     * `jniLibs.useLegacyPackaging = true` so the `.so` is an extracted file).
     *
     * Loading the DexKit native inside a hooked process is the open on-device gap
     * tracked in rosetta-xposed#25; this is its documented "runtime load-by-path"
     * best-effort and may still fail under embedded LSPatch (no extracted `.so`),
     * in which case self-heal is simply unavailable on that device. Returns true
     * when the native is (now) loadable.
     */
    private fun ensureDexKitNativeLoaded(): Boolean {
        if (runCatching { System.loadLibrary("dexkit") }.isSuccess) return true
        return runCatching {
            val moduleCl =
                TickPatchHooks::class.java.classLoader as? BaseDexClassLoader
                    ?: error("module class loader is not a BaseDexClassLoader")
            val soPath =
                moduleCl.findLibrary("dexkit")
                    ?: error("module APK exposes no libdexkit.so (extractNativeLibs?)")
            System.load(soPath)
            true
        }.getOrElse { e ->
            XposedBridge.log(
                "TickPatch: DexKit native not loadable inside $TARGET_PACKAGE (${e.message}); " +
                    "self-heal unavailable on this device (see rosetta-xposed#25). Pro override inactive.",
            )
            false
        }
    }

    /**
     * Force Pro BY REAL NAME, pinning the underlying STATE rather than the boolean
     * gate (TickPatch#10). Shared by the static and self-healing paths — the
     * resolution call is identical; only the backend behind it differs.
     *
     *   - `setProType(int)` is coerced to [PRO_TYPE_PRO] (1) so the backing
     *     `User.proType` field genuinely holds Pro: `User.isPro()`
     *     (== `proType == 1 || isActiveTeamUser()`) is then true CONSISTENTLY and
     *     SURVIVES a server-status sync — which would otherwise write `proType = 0`
     *     and revert the user to free ~a minute after launch. Pinning the field
     *     (rather than forcing `isPro()` true while the field stays 0) also avoids
     *     the inconsistency that made TickTick tear its home screen down on launch.
     *   - `getProType() -> 1` and `ProHelper.isPro(User) -> true` cover feature
     *     code that reads the tier int / wrapper gate directly, including transient
     *     `User` instances that never passed through `setProType`. `ProHelper.isPro`
     *     is the gate `LimitHelper`'s quota selection and most feature code call.
     *
     * It deliberately does NOT force `User.isPro()` or `User.isActiveTeamUser()`:
     * forcing `isPro()` true while `proType` stays 0 is the inconsistency above, and
     * forcing `isActiveTeamUser()` true flips the user into the TEAM tier
     * (accountType 2) whose Limits/workspace data a non-team account lacks — both
     * crash the home screen on launch (research/com.ticktick.task/docs/premium.md
     * §2.1 / §2.3 / §5).
     */
    private fun installProHooks(rosetta: RosettaXposed) {
        hookByRealName(rosetta, USER_CLASS, "setProType", null, pinProTypeWhenEnabled())
        hookByRealName(rosetta, USER_CLASS, "getProType", null, forceWhenEnabled(PRO_TYPE_PRO))
        hookByRealName(rosetta, PRO_HELPER_CLASS, "isPro", listOf(USER_CLASS), forceWhenEnabled(true))
    }

    /**
     * An empty base [RosettaMap] for the running version, the input
     * [RosettaXposed.fromMapWithSignatures] expects when no published map exists:
     * it carries no classes (so every name resolves via the signatures) and no
     * `signer_sha256` (so the signer guard is a no-op even with an identity).
     */
    private fun emptyMapFor(identity: AppIdentity): RosettaMap =
        RosettaMap(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            app = identity.packageName,
            version = identity.versionName ?: "unknown",
            versionCode = identity.versionCode,
            classes = emptyMap(),
        )

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
     * A live-gated callback that COERCES the first argument of
     * `User.setProType(int)` to [PRO_TYPE_PRO] while the toggle is on — pinning
     * the backing field so a server-status sync (which writes the real free
     * `proType`) cannot revert Pro, and so `User.isPro()` stays true consistently
     * without forcing it (which crashes). Rewriting the arg in the before phase
     * feeds the coerced value to the original setter.
     */
    private fun pinProTypeWhenEnabled(): XC_MethodHook =
        object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (proEnabled() && param.args.isNotEmpty()) param.args[0] = PRO_TYPE_PRO
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

    private companion object {
        const val TARGET_PACKAGE = "com.ticktick.task"

        const val USER_CLASS = "com.ticktick.task.data.User"
        const val PRO_HELPER_CLASS = "com.ticktick.task.helper.pro.ProHelper"

        /** `User.proType == 1` means Pro (research/com.ticktick.task/docs/premium.md §2.1). */
        const val PRO_TYPE_PRO = 1

        /** SharedPreferences file backing the self-heal cross-restart discovery cache. */
        const val CACHE_PREFS = "tickpatch_disc_cache"
    }
}
