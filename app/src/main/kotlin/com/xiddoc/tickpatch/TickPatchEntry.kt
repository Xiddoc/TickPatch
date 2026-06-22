package com.xiddoc.tickpatch

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * TickPatch's Xposed entry point.
 *
 * The LSPosed/LSPatch framework instantiates this class by the name listed in
 * `assets/xposed_init` and calls [handleLoadPackage] once per loaded app. This
 * class and its callback are pinned in `proguard-rules.pro` so the minified R8
 * release build does not rename or strip them.
 *
 * Today this is a minimal, self-contained module that announces itself. The
 * intended next step (see README › "Rosetta integration") is to resolve hook
 * targets through the Rosetta stack — `rosetta-xposed` consuming the per-version
 * maps from `rosetta-maps` — so hooks address obfuscated classes/methods by
 * their REAL names instead of brittle, build-specific smali spellings.
 */
public class TickPatchEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        XposedBridge.log("[TickPatch] loaded in ${lpparam.packageName}")

        // Rosetta resolution would go here, e.g.:
        //   val member = rosetta.resolve(lpparam.classLoader, "com.target.Service#submit")
        //   XposedBridge.hookMethod(member, hooker)
    }
}
