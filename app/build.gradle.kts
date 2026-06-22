/*
 * TickPatch :app — the installable LSPosed/LSPatch module.
 *
 * An Xposed-family module is itself an APK, so this is `com.android.application`.
 * The release build is MINIFIED with R8 (`isMinifyEnabled = true`); the Xposed
 * entry point is referenced BY NAME from `assets/xposed_init`, so it (and the
 * Xposed callback surface) is pinned in `proguard-rules.pro` against renaming
 * and stripping. The Xposed API is `compileOnly` — the framework supplies it at
 * runtime (RFC 0001 Decision 2: Rosetta resolves; the framework owns the hook).
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiddoc.tickpatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xiddoc.tickpatch"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // The release APK is a real, minified R8 build. R8 shrinks,
            // optimizes, and obfuscates; the keep rules in proguard-rules.pro
            // preserve the Xposed entry class (named in assets/xposed_init) and
            // the callback methods the framework invokes by signature.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign the release APK with the auto-provisioned debug keystore so CI
            // emits an INSTALLABLE artifact without committing a signing secret.
            // Swap in a real release keystore (e.g. from CI secrets) to publish.
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
    // Legacy Xposed API — provided by LSPosed/LSPatch at runtime, so compileOnly.
    // 82 is the de.robv API level modern LSPosed exposes.
    compileOnly("de.robv.android.xposed:api:82")

    // Rosetta integration (planned): once rosetta-xposed publishes to Maven
    // Central, the Rosetta resolution layer slots in here so TickPatch's hooks
    // address obfuscated apps by their REAL names via the rosetta-maps artifacts
    // instead of hard-coded smali spellings. See README.md › "Rosetta integration".
    //   implementation("io.github.xiddoc.rosetta:xposed:<version>")
    //   implementation("io.github.xiddoc.rosetta:android-runtime:<version>")
}
