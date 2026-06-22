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

// Consume the Rosetta resolver + Android-runtime helpers from the sibling repo.
includeBuild("../rosetta-xposed")

include(":app")
