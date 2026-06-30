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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class TickPatchHooks : IXposedHookLoadPackage {
    /** Installs the Pro hooks EXACTLY once even though Application#onCreate may fire repeatedly. */
    private val installed = AtomicBoolean(false)

    /** Latches once DexKit's native lib is loaded into this process so a second self-heal skips the work. */
    @Volatile
    private var dexKitNativeReady = false

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
        if (!ensureDexKitNativeLoaded(app)) return

        // Scan TickTick's IN-MEMORY dex via its class loader (robust against
        // split / reinforced APKs whose Pro classes are not in base.apk),
        // falling back to the base.apk path. Fail-soft: a create() failure means
        // "no self-heal on this device", never a host crash.
        val bridge =
            runCatching { DexKitBridge.create(classLoader, true) }
                .recoverCatching { DexKitBridge.create(app.applicationInfo.sourceDir) }
                .getOrElse { e ->
                    XposedBridge.log(
                        "TickPatch: DexKitBridge.create failed " +
                            "(${e.javaClass.simpleName}: ${e.message}); self-heal aborted.",
                    )
                    return
                }
        bridge.use {
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
     * calls straight into it) — fail-soft, latched in [dexKitNativeReady].
     *
     * The module runs inside TickTick's process. TickTick's `java.library.path`
     * does NOT contain the module's `libdexkit.so`, and under LSPosed the module
     * class loader (an in-memory `LspModuleClassLoader`) has an empty native
     * search path too — so [System.loadLibrary] almost always misses. The fix is
     * to [System.load] the MODULE's OWN extracted `.so` by absolute path from the
     * module's `nativeLibraryDir`, discovered three ways (first hit wins):
     *
     *   1. `createPackageContext(self).applicationInfo.nativeLibraryDir` — the
     *      module's extracted lib dir, which LSPosed grants the host visibility to;
     *   2. `PackageManager.getApplicationInfo(self).nativeLibraryDir` — same value
     *      via PM (cross-package, so on Android 11+ it may be `<queries>`-filtered,
     *      hence not the only path);
     *   3. the module class loader's `DexPathList.nativeLibraryDirectories` read by
     *      reflection — a visibility-independent fallback that needs no PM grant.
     *
     * The lib must be an EXTRACTED file on disk (not an APK-embedded entry), which
     * is why the build sets `jniLibs.useLegacyPackaging = true`. We never copy the
     * `.so` to a writable dir and load from there — SELinux W^X / `execmod` blocks
     * executing a lib from an app-writable path. Under embedded LSPatch (no
     * extracted `.so`) all three may miss, in which case self-heal is simply
     * unavailable on that device (the open on-device gap tracked in
     * rosetta-xposed#25). Returns true when the native is (now) loadable.
     */
    private fun ensureDexKitNativeLoaded(app: Application): Boolean {
        if (dexKitNativeReady) return true
        if (runCatching { System.loadLibrary("dexkit") }.isSuccess) {
            dexKitNativeReady = true
            return true
        }
        val soName = System.mapLibraryName("dexkit")
        for ((source, dir) in moduleNativeLibDirs(app)) {
            val so = File(dir, soName)
            if (so.isFile && runCatching { System.load(so.absolutePath) }.isSuccess) {
                XposedBridge.log("TickPatch: DexKit native loaded from ${so.absolutePath} [$source].")
                dexKitNativeReady = true
                return true
            }
        }
        XposedBridge.log(
            "TickPatch: DexKit native not loadable inside $TARGET_PACKAGE (tried System.loadLibrary, " +
                "PackageManager, and the module class loader); self-heal unavailable on this device " +
                "(see rosetta-xposed#25). Pro override inactive.",
        )
        return false
    }

    /**
     * The module's own `nativeLibraryDir` candidates (absolute paths to the dir
     * holding the extracted `libdexkit.so`), paired with the source that found
     * each — deduplicated, first-seen order, so [ensureDexKitNativeLoaded] tries
     * the most reliable source first. See its KDoc for why three independent
     * sources are consulted.
     */
    private fun moduleNativeLibDirs(app: Application): List<Pair<String, String>> {
        val pkg = BuildConfig.APPLICATION_ID
        val found = LinkedHashMap<String, String>()
        fun consider(source: String, dir: String?) {
            if (!dir.isNullOrBlank()) found.putIfAbsent(dir, source)
        }
        runCatching {
            consider(
                "createPackageContext",
                app.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).applicationInfo.nativeLibraryDir,
            )
        }
        runCatching {
            consider("getApplicationInfo", app.packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir)
        }
        runCatching { classLoaderNativeLibDirs().forEach { consider("classLoaderDexPathList", it) } }
        return found.entries.map { it.value to it.key }
    }

    /**
     * The module class loader's native library directories, read by reflection
     * from its `DexPathList.nativeLibraryDirectories`. A visibility-independent
     * fallback (needs no PackageManager grant): the module class loader is the
     * one that loaded THIS class, so its lib dirs are the module's own. Returns
     * empty if the loader is not a [BaseDexClassLoader] or the internals shift.
     */
    private fun classLoaderNativeLibDirs(): List<String> {
        val cl = TickPatchHooks::class.java.classLoader as? BaseDexClassLoader ?: return emptyList()
        val pathList =
            BaseDexClassLoader::class.java
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
                .get(cl) ?: return emptyList()
        val raw =
            pathList.javaClass
                .getDeclaredField("nativeLibraryDirectories")
                .apply { isAccessible = true }
                .get(pathList)
        val files: List<*> =
            when (raw) {
                is List<*> -> raw
                is Array<*> -> raw.toList()
                else -> emptyList<Any?>()
            }
        return files.mapNotNull { (it as? File)?.path }
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
