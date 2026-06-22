/*
 * The [Hooker] glue: hands a Rosetta-resolved Member to the legacy
 * XposedBridge hook API. RFC 0001 Decision 2 — Rosetta resolves the name; the
 * developer owns the hook call. Swapping to libxposed would change ONLY this
 * file; the resolution (`rosetta.method(...).hook(...)`) is identical.
 */
package io.github.xiddoc.tickpatch

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.xiddoc.rosetta.xposed.Hooker
import io.github.xiddoc.rosetta.xposed.Unhook

internal object RosettaLegacyHooker {
    /** Apply a legacy XposedBridge callback to the resolved member. */
    fun legacy(callback: XC_MethodHook): Hooker =
        Hooker { member ->
            val unhook = XposedBridge.hookMethod(member, callback)
            Unhook { unhook.unhook() }
        }
}
