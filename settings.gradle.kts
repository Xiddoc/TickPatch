/*
 * TickPatch — an LSPosed module that flips TickTick's Pro gate, built to
 * dogfood the Rosetta toolchain (rosetta-xposed + rosetta-maps).
 *
 * The module resolves `com.ticktick.task.data.User#isPro` by its REAL name
 * through a bundled Rosetta map instead of hard-coding the obfuscated name, so
 * a future TickTick rename is a map swap, not a code change.
 *
 * rosetta-xposed is not published to Maven yet, so it is consumed as a Gradle
 * COMPOSITE BUILD from a sibling checkout (`../rosetta-xposed`). The
 * `io.github.xiddoc.rosetta:{xposed,android-runtime}` coordinates in
 * app/build.gradle.kts are substituted by Gradle for the included build's
 * projects. Building the APK needs the Android SDK + AGP (run
 * ../rosetta-xposed/scripts/setup-android-sdk.sh in a fresh environment).
 */
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
    }
    // The build-time map fetcher (`io.github.xiddoc.rosetta.maps`, rosetta-xposed
    // :gradle-plugin) is consumed from the sibling checkout. A plugin produced by
    // an included build must be contributed through `pluginManagement.includeBuild`
    // — and that same inclusion ALSO provides the dependency substitution for the
    // `io.github.xiddoc.rosetta:{xposed,android-runtime}` coordinates in
    // app/build.gradle.kts, so the build is included here ONCE (including it again
    // at the top level would be a duplicate-inclusion error).
    includeBuild("../rosetta-xposed")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed API (de.robv.android.xposed:api) — compileOnly only;
        // the framework provides it at runtime.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "tickpatch"

// rosetta-xposed is included via `pluginManagement.includeBuild` above (it must
// be, to contribute the `io.github.xiddoc.rosetta.maps` plugin) — that single
// inclusion also substitutes the Rosetta resolver + Android-runtime coordinates,
// so there is no second top-level `includeBuild` here.

include(":app")
