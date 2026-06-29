# R8 keep rules for the minified release build.
#
# An Xposed module's entry point is loaded REFLECTIVELY by the framework: the
# class name is read from `assets/xposed_init` and instantiated, then its
# IXposedHook* callbacks are invoked by signature. R8 has no static reference to
# any of that, so without these rules it would rename or strip the entry class
# and break module loading.
#
# Note: hook TARGETS (TickTick's obfuscated classes/methods) are resolved at
# RUNTIME inside TickTick's own class loader via the bundled Rosetta map — they
# are not symbols in THIS APK, so R8 here cannot affect them. Only our own
# entry/callback surface needs pinning.

# The entry class is named verbatim in assets/xposed_init — keep it intact.
-keep class io.github.xiddoc.tickpatch.TickPatchHooks { *; }

# The launcher/settings Activity is referenced from the manifest (AGP keeps it
# via the generated manifest rules); pin it explicitly as belt-and-suspenders.
-keep class io.github.xiddoc.tickpatch.MainActivity { *; }

# Keep any class implementing an Xposed hook interface, plus the callback
# methods the framework calls. (de.robv.* is compileOnly/provided, never shipped
# in the APK, so these rules only pin OUR implementors.)
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# Don't warn about the provided-at-runtime Xposed API symbols.
-dontwarn de.robv.android.xposed.**

# ---------------------------------------------------------------------------
# Self-healing path: the on-device DexKit native bridge.
#
# DexKit loads a native `.so` and uses JNI + FlatBuffers, which R8 must not
# rename or strip. The `org.luckypray:dexkit` AAR ships its own consumer
# ProGuard rules (AGP applies them automatically), but these are added as
# belt-and-suspenders so a minified release can never break the self-heal
# fallback — over-keeping costs only a little shrink. The FlatBuffers runtime
# DexKit reads its generated tables through is kept for the same reason.
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**
-keep class com.google.flatbuffers.** { *; }
-dontwarn com.google.flatbuffers.**
