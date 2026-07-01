<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="TickPatch icon">

# TickPatch

A tiny **LSPosed / Xposed module** that unlocks
[TickTick](https://ticktick.com) **Pro** with a single switch.

**[⬇️ Download the latest APK from CI ⬇️](https://github.com/xiddoc/TickPatch/releases/latest)**

</div>

## Install

1. Install the APK and enable **TickPatch** in the LSPosed manager (its scope is
   already restricted to `com.ticktick.task`).
2. Force-stop TickTick so LSPosed loads the module into it.
3. Open **TickPatch** and flip **Enable Pro for TickTick** on.
4. Tap **Force-restart TickTick**. Pro unlocks — flip it off and restart again
   to revert.

## How it works

TickTick is obfuscated, so the Pro check hides behind shuffled names that rotate
every release. Instead of hard-coding those names, TickPatch resolves the gate
by its **real** name (`com.ticktick.task.data.User#isPro`) through a bundled
[Rosetta](https://github.com/xiddoc/rosetta-xposed) map. When TickTick renames
things in a new version, TickPatch just needs a new map — not a new build.

The bundled map covers TickTick **8.1.0.0** — that version takes a fast,
offline, O(1) static lookup. On **any other version** the module now
**self-heals** instead of going inactive: it spins up an on-device
[DexKit](https://github.com/LuckyPray/DexKit) scan driven by the bundled
[community signatures](https://github.com/xiddoc/rosetta-maps/blob/master/signatures/com.ticktick.task/signatures.yaml)
(fetched at build time alongside the maps), locates `User` / `ProHelper` by the
stable string constants they reference, and resolves the kept-name Pro-gate
methods live — all through `RosettaXposed.fromMapWithSignatures(...)`. So a
TickTick update that rotates names is a map-or-signature refresh upstream, not a
new TickPatch build. It still degrades fail-soft: if discovery can't find a
target (or the device has no DexKit native), the override is simply inactive and
TickTick never crashes.

## Non-root (LSPatch)

TickPatch is a standard Xposed module, so it also runs **without root** via
[LSPatch](https://github.com/LSPosed/LSPatch), which embeds the module into a
patched TickTick APK. The static-map path works out of the box. The
**self-healing** path additionally needs DexKit's native library loaded inside
TickTick's process, which non-root makes tricky (the module isn't installed, so
there's no native-lib dir, and stock Android 10+ forbids executing an extracted
copy). Rosetta solves it by mapping the `.so` straight out of the installed host
APK — you just embed it once after patching:

```bash
# after: java -jar lspatch.jar TickTick.apk -m TickPatch-arm64.apk -o out/ …
rosetta-xposed/tools/lspatch/embed-dexkit-native.sh out/TickTick-*.apk TickPatch-arm64.apk
# install the produced *-dexkit.apk
```

See rosetta-xposed's
[**Self-healing under non-root LSPatch**](https://github.com/xiddoc/rosetta-xposed/blob/master/docs/reference/lspatch-non-root.md)
for the full story. (The module ships DexKit's native for **arm64-v8a only** by
default — every real device is arm64; add other ABIs in `app/build.gradle.kts`
under `ndk { abiFilters }` if you need them.)

### Testing without uninstalling TickTick

Installing a patched TickTick normally collides with a real TickTick already on
the device (same package name). To run both side by side, build a **coexistence
variant** that targets a *renamed* TickTick clone:

```bash
# 1. Build TickPatch pointed at the renamed package:
./gradlew :app:assembleDebug -PtickpatchTargetPackage=com.ticktick.task.rosetta

# 2. Rename the TickTick APK's package to com.ticktick.task.rosetta (e.g. with
#    apktool: edit the manifest `package`, rebuild, then LSPatch-embed step 1).
# 3. Embed the DexKit native (above) and install — it sits next to real TickTick.
```

`-PtickpatchTargetPackage` re-points the hook guard, the `<queries>` entry, the
LSPosed scope, and the in-app relaunch button at the renamed clone. Only the
manifest *package* changes — the DEX classes are still `com.ticktick.task.*`, so
resolution and the Pro-gate hooks are unchanged.

## Build from source

The Rosetta library is consumed as a Gradle composite build, so lay the repos
out side by side:

```
some-dir/
├── TickPatch/
├── rosetta-xposed/      # the resolver + the build-time map-fetch plugin
└── rosetta-maps/        # the source of truth the plugin fetches maps from
```

Then (Android SDK required):

```bash
./gradlew :app:assembleDebug
```

The TickTick maps are **not committed here** — they're fetched at build time
from `rosetta-maps` by the
[`io.github.xiddoc.rosetta.maps`](https://github.com/xiddoc/rosetta-xposed/blob/master/docs/getting-started/build-time-maps.md)
Gradle plugin (`fetchRosettaMaps`, wired into `preBuild`) and bundled into the
APK under `maps/<version_code>.json`. The on-device runtime never downloads
anything — the maps are plain Java resources read by `BundledMaps`.

To change which versions are bundled, or to pick up a refreshed/added map, edit
the `rosettaMaps { }` block in `app/build.gradle.kts` (its `versions` list and
the pinned rosetta-maps `ref`):

```kotlin
rosettaMaps {
    app.set("com.ticktick.task")
    versions.set(listOf(8100L))
    ref.set("<rosetta-maps commit SHA>")
}
```

## Disclaimer

Built as an educational dogfood of the Rosetta obfuscation-map tooling. Support
the developers of the software you use — if you rely on TickTick, buy Pro!
