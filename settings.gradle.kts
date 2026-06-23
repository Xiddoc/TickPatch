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
    // :gradle-plugin) is a plugin produced by an included build, so it must be
    // contributed through `pluginManagement.includeBuild` — that is what makes the
    // plugin resolvable from the `plugins { }` block in app/build.gradle.kts.
    //
    // This includeBuild contributes the PLUGIN only; it does NOT substitute the
    // `io.github.xiddoc.rosetta:{xposed,android-runtime}` library coordinates. The
    // top-level `includeBuild` below does that. Declaring the same build in both
    // places is fine — Gradle de-duplicates them into one included build (one for
    // plugin resolution, one for dependency substitution).
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

// Consume the Rosetta resolver + Android-runtime helpers from the sibling repo:
// this top-level inclusion is what SUBSTITUTES the `io.github.xiddoc.rosetta:*`
// coordinates in app/build.gradle.kts with the included build's projects.
// (The `pluginManagement.includeBuild` above contributes the map-fetch PLUGIN;
// the two declarations of the same path are de-duplicated by Gradle.)
includeBuild("../rosetta-xposed")

include(":app")
