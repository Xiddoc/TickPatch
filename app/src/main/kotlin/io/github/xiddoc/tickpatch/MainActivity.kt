/*
 * The entire TickPatch UI: one switch that enables/disables "Pro" for TickTick.
 *
 * Flipping the switch writes a single boolean into a world-readable
 * SharedPreferences file (see [Prefs]). The hook running inside TickTick reads
 * that boolean live (via XSharedPreferences) and forces `User.isPro()` to return
 * true while it is on — so toggling here changes TickTick's behaviour on its
 * next Pro check (a force-restart of TickTick guarantees a clean re-read).
 *
 * The UI is built in code with platform widgets (no AndroidX / Material
 * dependency) — the app is deliberately plain; the interesting part is the hook.
 */
package io.github.xiddoc.tickpatch

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = openPrefs()

        val status =
            TextView(this).apply {
                textSize = 16f
                setPadding(0, 48, 0, 0)
            }
        val blurb =
            TextView(this).apply {
                text = getString(R.string.blurb)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, 64, 0, 0)
            }
        val toggle =
            Switch(this).apply {
                text = getString(R.string.toggle_label)
                textSize = 18f
                isChecked = prefs.getBoolean(Prefs.KEY_PRO_ENABLED, false)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(Prefs.KEY_PRO_ENABLED, isChecked).apply()
                    status.text = statusText(isChecked)
                }
            }
        status.text = statusText(toggle.isChecked)

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(64, 96, 64, 64)
                addView(toggle)
                addView(status)
                addView(blurb)
            },
        )
    }

    private fun statusText(enabled: Boolean): String =
        getString(if (enabled) R.string.status_on else R.string.status_off)

    /**
     * Open the toggle store `MODE_WORLD_READABLE` so the hook inside TickTick's
     * process can read it through `XSharedPreferences`. On modern Android that
     * mode throws `SecurityException` UNLESS the module is loaded under LSPosed
     * (which intercepts it via the `xposedsharedprefs` opt-in). When this
     * activity is launched WITHOUT LSPosed active (e.g. a plain install for a
     * look), fall back to `MODE_PRIVATE` so the UI still works locally.
     */
    @Suppress("DEPRECATION")
    private fun openPrefs(): SharedPreferences =
        try {
            getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE)
        } catch (_: SecurityException) {
            getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        }
}
