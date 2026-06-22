/*
 * TickPatch — a small LSPosed / LSPatch module that dogfoods the Rosetta
 * cross-framework obfuscation-map stack (rosetta-xposed + rosetta-maps).
 *
 * Single-module Android build: `:app` IS the installable Xposed module.
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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Legacy Xposed API (de.robv.android.xposed:api) — compileOnly only;
        // the framework provides the real implementation at runtime.
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "TickPatch"

include(":app")
