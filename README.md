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
