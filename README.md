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

The bundled maps cover TickTick **8.0.8.0** and **8.0.8.1**. On any other
version the module simply stays inactive (it never crashes the app).

## Build from source

The Rosetta library is consumed as a Gradle composite build, so lay the repos
out side by side:

```
some-dir/
├── TickPatch/
├── rosetta-xposed/
└── rosetta-maps/        # only needed to regenerate the bundled map
```

Then (Android SDK required):

```bash
./gradlew :app:assembleDebug
```

To refresh the bundled map from `rosetta-maps`:

```bash
python3 tools/generate-map.py
```

## Disclaimer

Built as an educational dogfood of the Rosetta obfuscation-map tooling. Support
the developers of the software you use — if you rely on TickTick, buy Pro!
