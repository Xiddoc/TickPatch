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
        // Keep the module un-minified so the entry-point class names referenced
        // from assets/xposed_init and the manifest survive (the same stance the
        // rosetta-xposed example module takes).
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
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
