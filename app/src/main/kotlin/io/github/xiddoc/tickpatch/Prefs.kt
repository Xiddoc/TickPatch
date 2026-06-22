/*
 * The shared preference contract between the two halves of TickPatch, which run
 * in DIFFERENT processes:
 *
 *   - [MainActivity] (the module's own process) WRITES the toggle.
 *   - [TickPatchHooks] (inside TickTick's process) READS it via
 *     `XSharedPreferences`.
 *
 * Both must agree on the file name and key, so they live here. The file is
 * written `MODE_WORLD_READABLE`; LSPosed's `xposedsharedprefs` manifest opt-in
 * makes that readable cross-process (see AndroidManifest.xml).
 */
package io.github.xiddoc.tickpatch

internal object Prefs {
    /** SharedPreferences file name (also the XSharedPreferences `prefFileName`). */
    const val FILE = "tickpatch_prefs"

    /** Boolean: when true, the hook forces TickTick's Pro gate open. */
    const val KEY_PRO_ENABLED = "pro_enabled"

    /**
     * Explicit broadcast action MainActivity sends to ask the in-app hook to
     * end TickTick's process (a cooperative "force-stop"; the UI then relaunches
     * it). Received by the receiver TickPatchHooks registers inside TickTick.
     */
    const val ACTION_RESTART = "io.github.xiddoc.tickpatch.ACTION_RESTART_TICKTICK"
}
