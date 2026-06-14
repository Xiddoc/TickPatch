# TickPatch

A tiny **LSPosed / Xposed module** that flips [TickTick](https://ticktick.com)'s
**Pro** gate on or off from a single switch — built to **dogfood the
[Rosetta](https://github.com/xiddoc/rosetta-xposed) toolchain**.

The interesting part isn't the toggle; it's *how* the hook finds its target.
TickTick is R8-obfuscated, so the Pro check lives behind shuffled class/method
names that rotate every release. Instead of hard-coding those names, TickPatch
resolves the gate **by its real name** —
`com.ticktick.task.data.User#isPro()` — through a bundled **Rosetta map**.
When TickTick renames things in a future version, TickPatch needs a new *map*,
not a new *build*.

## How it works

```
┌─ MainActivity (module process) ──────────┐      ┌─ TickPatchHooks (inside TickTick) ─────────────┐
│  one Switch → writes pro_enabled          │      │  1. wait for Application (Context)             │
│  to world-readable SharedPreferences      │ ───▶ │  2. read AppIdentity from PackageManager      │
└───────────────────────────────────────────┘ Xshared 3. select bundled map by version_code;       │
                                              prefs │     signer_sha256 enforced fail-closed         │
                                                    │  4. resolve User#isPro BY REAL NAME via Rosetta│
                                                    │  5. hook it; force `true` while toggle is on   │
                                                    └────────────────────────────────────────────────┘
```

- **Resolution, not hard-coding.** `TickPatchHooks` calls
  `rosetta.method("com.ticktick.task.data.User", "isPro").hook(...)`. There is
  not one obfuscated name (`j8.a`, `y7.c`, …) in the Kotlin — they all live in
  the map. (`com.ticktick.task.helper.pro.ProHelper#isPro(User)`, the wrapper
  most feature code calls, is hooked too, covering the null-user path.)
- **The map is the contract.** `app/src/main/resources/maps/8081.json`
  (`schema_version: 4`) is **derived from the
  [`rosetta-maps-private`](https://github.com/xiddoc/rosetta-maps-private)
  knowledge base** by [`tools/generate-map.py`](tools/generate-map.py) — the
  obfuscation mappings and TickTick's `signer_sha256` come straight from there.
- **Live toggle.** The hooks are installed once; whether they actually force
  Pro is decided *per call* by the toggle, read live through
  `XSharedPreferences`. Flip the switch and TickTick's next Pro check changes
  (force-restart TickTick for a clean re-read).
- **Fail-soft & fail-closed.** A version with no bundled map, or a signer
  mismatch (repackaged TickTick), simply leaves Pro untouched — never a crash.
  The signer guard means the map only applies to the genuine TickTick build it
  was authored for.

## Layout

```
TickPatch/
├── app/
│  └── src/main/
│     ├── kotlin/io/github/xiddoc/tickpatch/
│     │  ├── MainActivity.kt        ← the one-switch settings UI
│     │  ├── TickPatchHooks.kt      ← LSPosed entry: resolve + hook the Pro gate
│     │  ├── AndroidAppIdentity.kt  ← PackageManager → Rosetta AppIdentity
│     │  ├── RosettaLegacyHooker.kt ← Hooker seam (XposedBridge)
│     │  └── Prefs.kt               ← the cross-process toggle contract
│     ├── assets/xposed_init        ← registers TickPatchHooks
│     └── resources/maps/           ← bundled Rosetta maps + index.json (8.0.8.0 / 8.0.8.1)
├── tools/generate-map.py           ← regenerates the map from rosetta-maps-private
└── settings.gradle.kts             ← composite-builds ../rosetta-xposed
```

## Building

The Rosetta library (`rosetta-xposed`) isn't on Maven yet, so it's consumed as a
Gradle **composite build** from a sibling checkout. Lay the repos out side by
side:

```
some-dir/
├── TickPatch/
├── rosetta-xposed/
└── rosetta-maps-private/     # only needed to regenerate the map
```

Then (Android SDK required — `compileSdk 34`, `build-tools 34.0.0`):

```bash
# Fresh/cloud environment without an SDK? Provision one:
../rosetta-xposed/scripts/setup-android-sdk.sh
export ANDROID_HOME="$HOME/android-sdk" ANDROID_SDK_ROOT="$HOME/android-sdk"

./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

To refresh the bundled map after `rosetta-maps-private` updates:

```bash
python3 tools/generate-map.py
```

## Using it

1. Install the APK and enable **TickPatch** in the LSPosed manager; its scope is
   already restricted to `com.ticktick.task`.
2. Force-stop TickTick so LSPosed loads the module into it.
3. Open **TickPatch**, flip **Enable Pro for TickTick** on.
4. Tap **Force-restart TickTick** (a cooperative kill from inside TickTick, then
   a relaunch — no root needed). Pro features unlock; flip the switch off and
   restart again to revert.

### Troubleshooting

The module logs every decision to the LSPosed log under the `TickPatch:` prefix —
check there first:

- `armed for com.ticktick.task@… (version_code …)` + `hooked …#isPro by real
  name` → the hooks are live; the toggle controls Pro per call.
- `no bundled map for version_code …` → your TickTick isn't 8080/8081. The map
  only resolves the version it was built from, so this version must be mapped in
  `rosetta-maps-private` (or wait for the DexKit self-healing backend). This is
  the most common reason Pro doesn't change.
- `signer guard failed …; installing UNVERIFIED` → your TickTick's signing cert
  doesn't match the map's `signer_sha256`; the hooks still install (dogfood
  fallback), but the map's signer field should be corrected.

> The bundled maps target **TickTick 8.0.8.0 and 8.0.8.1** (`version_code`
> 8080 / 8081) — the TickTick versions currently mapped in
> `rosetta-maps-private`. Rosetta picks the one matching the installed app; on
> any other version the module logs that it has no matching map and stays
> inactive. To extend coverage, add that version's map to `rosetta-maps-private`,
> list its `version_code` in `tools/generate-map.py` and `TickPatchHooks`, and
> regenerate.

## Disclaimer

Built as an educational dogfood of the Rosetta obfuscation-map tooling. Support
the developers of the software you use — if you rely on TickTick, buy Pro.
