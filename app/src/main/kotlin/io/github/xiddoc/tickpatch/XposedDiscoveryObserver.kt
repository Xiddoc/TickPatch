/*
 * XposedDiscoveryObserver — the irreducible logging edge of Rosetta's discovery
 * observability seam (rosetta-xposed#22), routed through `XposedBridge.log` so
 * the self-heal path is visible in the LSPosed log.
 *
 * The observability LOGIC — telling a fresh DexKit scan apart from a persistent
 * cache hit, and reporting an invalidation on a version/signer change — lives in
 * the library ([DiscoveryObserver] / [PersistentDiscoveryCache]) and is fully
 * unit-tested on a plain JVM. This class only formats each outcome as a log
 * line, the way the rosetta-xposed example's `LogcatDiscoveryObserver` does —
 * but TickPatch has no UI for logcat, so the LSPosed bridge log is the natural
 * channel. It is a pure side-channel: it never changes resolution and never
 * throws through (the backend invokes it via `DiscoveryObserver.safe`).
 */
package io.github.xiddoc.tickpatch

import de.robv.android.xposed.XposedBridge
import io.github.xiddoc.rosetta.xposed.DiscoveryObserver
import io.github.xiddoc.rosetta.xposed.DiscoveryOutcome
import io.github.xiddoc.rosetta.xposed.InvalidationReason

internal class XposedDiscoveryObserver : DiscoveryObserver {
    override fun onOutcome(
        realName: String,
        obfName: String,
        outcome: DiscoveryOutcome,
    ) {
        val marker =
            when (outcome) {
                DiscoveryOutcome.DISCOVERED -> "DISCOVERED (fresh DexKit scan)"
                DiscoveryOutcome.SERVED_FROM_CACHE -> "SERVED_FROM_CACHE (no scan)"
            }
        XposedBridge.log("TickPatch self-heal: $marker — $realName -> $obfName")
    }

    override fun onCacheInvalidated(reason: InvalidationReason) {
        val kind =
            when (reason) {
                InvalidationReason.FIRST_RUN -> "first run (no cache yet)"
                InvalidationReason.FINGERPRINT_CHANGED -> "version/signer change — stale cache dropped"
            }
        XposedBridge.log("TickPatch self-heal: CACHE_INVALIDATED ($kind); names will be re-discovered.")
    }
}
