# TickPatch

A small **LSPosed / LSPatch module** that dogfoods the Rosetta cross-framework
obfuscation-map stack ([`rosetta-xposed`](https://github.com/Xiddoc/rosetta-xposed)
+ [`rosetta-maps`](https://github.com/Xiddoc/rosetta-maps)). It is itself an
installable Xposed-family app: the framework loads the entry class named in
`app/src/main/assets/xposed_init` and runs its hooks inside the target app's JVM.

## Layout

```
TickPatch/
├── app/
│   ├── build.gradle.kts            ← minified R8 release build config
│   ├── proguard-rules.pro          ← keeps the Xposed entry under R8
│   └── src/main/
│       ├── AndroidManifest.xml     ← Xposed module metadata + app icon
│       ├── assets/xposed_init      ← names the entry class
│       ├── kotlin/.../TickPatchEntry.kt
│       └── res/mipmap-*/           ← launcher icon (all densities)
└── .github/workflows/release-apk.yml
```

## Build

The library/build needs the Android SDK + Android Gradle Plugin.

```bash
# Minified R8 release APK (installable; debug-signed — see below)
./gradlew :app:assembleRelease

# Debug APK
./gradlew :app:assembleDebug
```

The release APK lands in `app/build/outputs/apk/release/`.

### Minified R8 release

`release` sets `isMinifyEnabled = true` + `isShrinkResources = true`, so
`assembleRelease` runs the full R8 pipeline (shrink → optimize → obfuscate).
Because the Xposed framework loads the entry **reflectively** (by the name in
`xposed_init`), R8 has no static reference to it; `app/proguard-rules.pro` pins
the entry class and the `IXposedHook*` callback surface so the minified build
still loads. The release APK is signed with the **debug keystore** so CI can
emit an installable artifact without a committed secret — swap in a real
release keystore to publish for distribution.

## CI

`.github/workflows/release-apk.yml` builds the minified R8 release APK on every
push/PR and uploads it as a workflow artifact. Pushing a `v*` tag additionally
attaches the APK to a GitHub Release.

## App icon

The launcher icon (`res/mipmap-*/ic_launcher.png` + `ic_launcher_round.png`) is
a squircle tick-on-circuit-board mark, rendered at every density. The squircle
shape is baked into the artwork, so the manifest points `android:icon` directly
at the bitmap rather than masking it again through an adaptive icon.

## Rosetta integration (planned)

Today `TickPatchEntry` is a minimal, self-contained module that announces
itself on load. The intended next step is to resolve hook targets through
Rosetta — `rosetta-xposed` consuming the per-version maps published in
`rosetta-maps` — so hooks address obfuscated classes/methods by their **real**
names instead of brittle, build-specific smali spellings. Once `rosetta-xposed`
publishes to Maven Central, uncomment the Rosetta coordinates in
`app/build.gradle.kts` and wire resolution where `TickPatchEntry` marks it.
