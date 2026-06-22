# R8 keep rules for the minified release build.
#
# An Xposed module's entry point is loaded REFLECTIVELY by the framework: the
# class name is read from `assets/xposed_init` and instantiated, then its
# IXposedHook* callbacks are invoked by signature. R8 has no static reference to
# any of that, so without these rules it would rename or strip the entry class
# and break module loading. Keep the entry class (name + members) and the Xposed
# callback contract.

# The entry class is named verbatim in assets/xposed_init — keep it intact.
-keep class com.xiddoc.tickpatch.TickPatchEntry { *; }

# Keep any class implementing an Xposed hook interface, plus the callback
# methods the framework calls. (de.robv.* is compileOnly/provided, never shipped
# in the APK, so these rules only pin OUR implementors.)
-keep class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
-keep class * implements de.robv.android.xposed.IXposedHookZygoteInit { *; }
-keep class * implements de.robv.android.xposed.IXposedHookInitPackageResources { *; }

# Don't warn about the provided-at-runtime Xposed API symbols.
-dontwarn de.robv.android.xposed.**
