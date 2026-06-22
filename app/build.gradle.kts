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
}

android {
    namespace = "io.github.xiddoc.tickpatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.xiddoc.tickpatch"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
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
}

dependencies {
    // Resolution layer — the one Rosetta coordinate (pulls :core transitively).
    // Substituted from the composite includeBuild in settings.gradle.kts.
    implementation("io.github.xiddoc.rosetta:xposed")

    // Pure-JVM Android-runtime helpers: BundledMaps (loads the bundled map off
    // the module class loader) + AndroidIdentities (signer-hash / AppIdentity
    // assembly). Also from the composite build.
    implementation("io.github.xiddoc.rosetta:android-runtime")

    // Legacy Xposed API — provided by the framework at runtime, so compileOnly.
    compileOnly("de.robv.android.xposed:api:82")
}
