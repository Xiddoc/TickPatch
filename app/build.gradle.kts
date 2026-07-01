/*
 * The TickPatch LSPosed module. An Xposed-family module is an installable app,
 * so this is `com.android.application`: it ships a one-button settings UI
 * (MainActivity) AND the hook entry point (TickPatchHooks) in one APK.
 *
 * It depends on rosetta-xposed for resolution and on the Xposed API only at
 * COMPILE time (`compileOnly`) — the framework provides that API at runtime
 * (RFC 0001 Decision 2 / CLAUDE.md: Rosetta resolves; the developer's framework
 * owns the hook).
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Build-time map fetch (rosetta-xposed#39). Resolved from the sibling build
    // via `pluginManagement.includeBuild("../rosetta-xposed")` in settings.gradle.kts.
    // The TickTick maps are pulled from rosetta-maps at BUILD time into
    // build/generated/rosetta-maps/maps and bundled into the APK — so this module
    // commits ZERO map JSON and never hand-copies (or hand-injects) it. The
    // on-device runtime path is byte-identical (plain Java resources read by
    // BundledMaps; no runtime download, per RFC 0001).
    id("io.github.xiddoc.rosetta.maps")
}

// Declare WHICH TickTick maps to bundle; the build fetches them verbatim from
// rosetta-maps and bundles them under maps/<version_code>.json. The Pro-gate
// METHOD entries (User.isPro / getProType / isActiveTeamUser, ProHelper.isPro)
// live in those upstream maps, so there is nothing to inject locally. Pinned to
// a rosetta-maps commit SHA for reproducibility/provenance (git
// content-addressing = integrity). Bump this ref to adopt a refreshed/added map.
//
// Only 8100 is bundled: on master the 8080/8081 maps are still class-only (no
// method tables), so their Pro gate can't be resolved from a verbatim fetch. Add
// them back to `versions` once rosetta-maps carries their method entries.
//
// SELF-HEALING (rosetta-xposed#47/#48). The plugin also bakes the app's
// community signatures (signatures/com.ticktick.task.json). On a TickTick
// version with NO bundled map, TickPatchHooks falls back to
// RosettaXposed.fromMapWithSignatures(...) + on-device DexKit: the signatures
// locate `User` / `ProHelper` by their stable string anchors, and the kept-name
// member harvest resolves the stringless Pro-gate methods (isPro / getProType /
// isActiveTeamUser) — so a future rename/version is a map-or-signature update,
// not "module inactive". A mapped version (8100) still takes the fast static
// path and never builds a bridge.
rosettaMaps {
    app.set("com.ticktick.task")
    versions.set(listOf(8100L))
    ref.set("8000d2b93e12b9b6f8b88a4297156d01b686041a")
    // Default is already true; set explicitly because TickPatch now DEPENDS on
    // the baked signatures (BundledSignatures.load) for the self-heal fallback.
    signatures.set(true)
}

android {
    namespace = "io.github.xiddoc.tickpatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.xiddoc.tickpatch"
        minSdk = 24
        targetSdk = 34
        // Versioning is injectable so the release-on-merge CI can stamp the
        // auto-bumped version without committing back to the repo. Local /
        // default builds fall back to the baseline below. `tickpatchVersionCode`
        // is the monotonic CI run number; `tickpatchVersionName` the bumped
        // semver. See .github/workflows/release-apk.yml.
        versionCode = (project.findProperty("tickpatchVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("tickpatchVersionName") as String?) ?: "1.0.0"

        // The INSTALLED package the module hooks into. Defaults to the real
        // TickTick; override with `-PtickpatchTargetPackage=<pkg>` to build a
        // COEXISTENCE variant that targets a renamed TickTick clone so it can be
        // installed alongside a real TickTick for on-device dogfooding (see
        // README.md — "Testing without uninstalling TickTick"). Threaded into:
        //   - BuildConfig.TARGET_PACKAGE — the runtime handleLoadPackage guard,
        //     MainActivity's relaunch target;
        //   - the <queries> package (manifest placeholder) so the UI can see and
        //     relaunch the (possibly renamed) app on Android 11+;
        //   - the LSPosed `xposed_scope` array (via @string/rosetta_target_package)
        //     so a rooted-LSPosed load is scoped to the same package.
        // The DEX namespace (com.ticktick.task.*) is NOT this value — renaming an
        // APK never renames its classes — so resolution/hook targets are unchanged.
        val targetPackage = (project.findProperty("tickpatchTargetPackage") as String?) ?: "com.ticktick.task"
        buildConfigField("String", "TARGET_PACKAGE", "\"$targetPackage\"")
        resValue("string", "rosetta_target_package", targetPackage)
        manifestPlaceholders["targetPackage"] = targetPackage

        ndk {
            // Ship DexKit's native for arm64-v8a ONLY. The `.so` (from the DexKit
            // AAR) is dead weight except on the self-heal path, and every current
            // real device is arm64 — so the other three ABIs (armeabi-v7a, x86,
            // x86_64) would ~4x the native payload for no practical gain. Add them
            // back here if you need 32-bit / emulator self-heal. (The static map
            // path never touches DexKit, so a mapped version is unaffected on any
            // ABI.)
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        // BuildConfig.APPLICATION_ID is used as the XSharedPreferences package
        // name so the in-app toggle is readable from inside TickTick's process.
        buildConfig = true
    }

    buildTypes {
        // Debug stays un-minified for fast, readable on-device iteration.
        debug { isMinifyEnabled = false }
        release {
            // The release APK is a real, minified R8 build (shrink + optimize +
            // obfuscate). The Xposed entry class is loaded REFLECTIVELY by name
            // from assets/xposed_init, so R8 has no static reference to it;
            // proguard-rules.pro pins the entry, the Xposed callback surface,
            // and the (manifest-referenced) MainActivity so the minified module
            // still loads and toggles. Hook TARGETS are resolved at runtime
            // inside TickTick's own class loader via the Rosetta map, so R8 on
            // THIS APK never touches them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign the release APK with the auto-provisioned debug keystore so CI
            // emits an INSTALLABLE artifact without a committed secret. Swap in a
            // real release keystore to publish for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // Extract DexKit's `libdexkit.so` to the module's nativeLibraryDir (the
        // pre-AGP-default behaviour AGP now disables) instead of leaving it
        // compressed inside the APK. The self-heal path runs INSIDE TickTick's
        // process and loads the module's OWN `.so` by absolute path from that
        // extracted, exec-allowed dir (TickPatchHooks.ensureDexKitNativeLoaded /
        // moduleNativeLibDirs, which discover the dir via createPackageContext,
        // PackageManager, and the module class loader's DexPathList) — which
        // requires the lib to be an extracted file on disk, not an APK-embedded
        // entry. See rosetta-xposed#25.
        jniLibs.useLegacyPackaging = true
    }

    // Bundle the fetched maps as Java resources, exactly where the hand-copied
    // maps/<version_code>.json used to live — so BundledMaps.load("8100.json")
    // reads them off the module class loader at runtime, unchanged. The plugin
    // does NOT auto-wire AGP source sets (it never compiles against the Android
    // toolchain); the consumer adds this one srcDir line, where AGP's types are
    // on the classpath. See rosetta-xposed docs/getting-started/build-time-maps.md.
    sourceSets["main"].resources.srcDirs(layout.buildDirectory.dir("generated/rosetta-maps"))
}

// Fetch the maps before anything that consumes resources/dex. `preBuild` is the
// AGP anchor every variant build depends on, so the generated maps exist before
// they are packaged — without depending on AGP-version-specific wiring.
tasks.named("preBuild") { dependsOn("fetchRosettaMaps") }

dependencies {
    // Resolution layer — the one Rosetta coordinate (pulls :core transitively).
    // Substituted from the composite includeBuild in settings.gradle.kts.
    implementation("io.github.xiddoc.rosetta:xposed")

    // Pure-JVM Android-runtime helpers: BundledMaps (loads the bundled map off
    // the module class loader), BundledSignatures (the signature sibling),
    // PersistentDiscoveryCache (cross-restart discovery cache) + AndroidIdentities
    // (signer-hash / AppIdentity assembly). Also from the composite build.
    implementation("io.github.xiddoc.rosetta:android-runtime")

    // DYNAMIC / self-healing path: the on-device DexKit adapter
    // (DexKitBackedIndex). Pulls :xposed transitively. The adapter declares
    // DexKit `compileOnly`, so the AAR is NOT dragged in transitively — this
    // Android module adds the real DexKit AAR itself (next line) so the bridge
    // is present at runtime. Consulted ONLY on the unmapped-version fallback; a
    // mapped version never builds a bridge.
    implementation("io.github.xiddoc.rosetta:dexkit")

    // The real DexKit native bridge AAR. Ships the Android `.so`, but DexKitBridge
    // does NOT auto-load it and — inside the hooked app's process — it is not on
    // the host's library path, so the module loads it itself
    // (TickPatchHooks.ensureDexKitNativeLoaded; see rosetta-xposed#25). Only the
    // module-as-app needs the native; the rosetta-xposed library never depends on it.
    implementation("org.luckypray:dexkit:2.2.0")

    // Legacy Xposed API — provided by the framework at runtime, so compileOnly.
    compileOnly("de.robv.android.xposed:api:82")
}
