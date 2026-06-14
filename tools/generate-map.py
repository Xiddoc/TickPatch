#!/usr/bin/env python3
"""Generate the compile-time Rosetta map TickPatch bundles for TickTick.

TickPatch dogfoods the Rosetta toolchain: instead of hard-coding obfuscated
names, the LSPosed hook resolves `com.ticktick.task.data.User#isPro` (and
`ProHelper#isPro`) by their REAL names through a Rosetta map. This script
derives that bundled map from the community knowledge base
(`rosetta-maps-private`, the source of truth) so the obfuscation data and the
APK signer guard come straight from there — never re-typed by hand.

Two adaptations are applied so the map is consumable by the rosetta-xposed
Kotlin client (`io.github.xiddoc.rosetta.core.MapLoader`):

  1. SCHEMA UPGRADE. The source artifact is `schema_version: 2` (class-level
     only). The client hard-gates on `CURRENT_SCHEMA_VERSION` (4) and parses
     strictly (`additionalProperties: false`), so the v2-only authoring fields
     (`confidence`, `anchors` on a class; `confidence` on a source) are dropped
     and `schema_version` is bumped to 4. No field MEANING changes — every
     obfuscated mapping and the `signer_sha256` guard are carried through
     verbatim.

  2. METHOD ENTRIES. The source map is class-level (it identifies the renamed
     classes). The Pro gate hooks two METHODS, so this script injects the two
     method entries the hook resolves. Both `User` and `ProHelper` sit in
     TickTick's preserved-name carve-out (R8 kept their names), so each method's
     obfuscated name equals its real name and the class refs in the descriptor
     are the real FQNs. That self-mapping is exactly the point: the day TickTick
     rotates these names, only the map changes — the hook code does not.

Run from the repo root (rosetta-maps-private must be a sibling checkout):

    python3 tools/generate-map.py

It rewrites app/src/main/resources/maps/<version_code>.json in place.
"""
from __future__ import annotations

import json
import pathlib
import sys

# --- locations -------------------------------------------------------------
HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent
APP_PKG = "com.ticktick.task"
VERSION_CODE = 8081

SOURCE_MAP = (
    REPO.parent / "rosetta-maps-private" / "maps" / APP_PKG / f"{VERSION_CODE}.json"
)
OUT_MAP = REPO / "app" / "src" / "main" / "resources" / "maps" / f"{VERSION_CODE}.json"

# Fields the strict schema_version: 4 client model does NOT carry. They are
# authoring/provenance hints meaningful only inside rosetta-maps; the consumed
# artifact omits them.
CLASS_DROP = ("confidence", "anchors")
SOURCE_DROP = ("confidence",)

# The two method entries the Pro hook resolves by real name. Keyed by the real
# class FQN; each is `realMethodName -> MethodEntry`. Both classes are in
# TickTick's preserved-name carve-out, so obfuscated == real and the User class
# ref in ProHelper.isPro's descriptor is the real FQN.
#
# Gate (research/com.ticktick.task/docs/premium.md §2.1):
#   User.isPro()            -> proType == 1 || isActiveTeamUser()
#   ProHelper.isPro(User)   -> user != null && (user.isPro() || user.isActiveTeamUser())
METHOD_INJECTIONS = {
    "com.ticktick.task.data.User": {
        "isPro": {"obfuscated": "isPro", "signature": "()Z"},
    },
    "com.ticktick.task.helper.pro.ProHelper": {
        "isPro": {
            "obfuscated": "isPro",
            "signature": "(Lcom/ticktick/task/data/User;)Z",
            "static": True,
        },
    },
}


def strip(d: dict, keys) -> dict:
    return {k: v for k, v in d.items() if k not in keys}


def main() -> int:
    if not SOURCE_MAP.exists():
        sys.stderr.write(
            f"error: source map not found at {SOURCE_MAP}\n"
            "Check out rosetta-maps-private as a sibling of this repo.\n"
        )
        return 1

    src = json.loads(SOURCE_MAP.read_text())

    out = {
        "schema_version": 4,
        "app": src["app"],
        "version": src["version"],
        "version_code": src["version_code"],
    }
    if "captured_at" in src:
        out["captured_at"] = src["captured_at"]
    if "signer_sha256" in src:
        out["signer_sha256"] = src["signer_sha256"]

    out["sources"] = [strip(s, SOURCE_DROP) for s in src.get("sources", [])]
    out["sources"].append(
        {
            "tool": "hand-authored",
            "notes": (
                "TickPatch: derived from rosetta-maps-private by tools/generate-map.py "
                "(schema 2->4); injected User.isPro / ProHelper.isPro method entries for "
                "the Pro gate. Class mappings + signer_sha256 are carried through verbatim."
            ),
        }
    )

    classes: dict[str, dict] = {}
    for fqn, entry in src["classes"].items():
        classes[fqn] = strip(entry, CLASS_DROP)

    for fqn, methods in METHOD_INJECTIONS.items():
        if fqn not in classes:
            sys.stderr.write(
                f"error: expected class {fqn} in source map but it is absent; "
                "the Pro gate cannot be mapped.\n"
            )
            return 1
        classes[fqn]["methods"] = methods

    out["classes"] = classes

    OUT_MAP.parent.mkdir(parents=True, exist_ok=True)
    OUT_MAP.write_text(json.dumps(out, indent=2) + "\n")
    print(
        f"wrote {OUT_MAP.relative_to(REPO)} "
        f"({len(classes)} classes, {sum(len(m) for m in METHOD_INJECTIONS.values())} injected methods)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
