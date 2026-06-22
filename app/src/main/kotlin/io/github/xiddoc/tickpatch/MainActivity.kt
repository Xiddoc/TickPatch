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
 * dependency) so the app footprint stays tiny; the dark palette and rounded
 * surfaces are applied programmatically from the values in colors.xml /
 * themes.xml. The interesting part is still the hook — this is just a nice
 * front door for it.
 */
package io.github.xiddoc.tickpatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = openPrefs()

        val icon =
            ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher_round)
                layoutParams =
                    LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
            }
        val title =
            TextView(this).apply {
                text = getString(R.string.app_name)
                textSize = 28f
                setTextColor(color(R.color.tp_text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
        val tagline =
            TextView(this).apply {
                text = getString(R.string.tagline)
                textSize = 14f
                setTextColor(color(R.color.tp_accent))
                gravity = Gravity.CENTER_HORIZONTAL
                letterSpacing = 0.04f
                setPadding(0, dp(2), 0, 0)
            }

        val status =
            TextView(this).apply {
                textSize = 13f
                setTextColor(color(R.color.tp_text_secondary))
                setPadding(0, dp(12), 0, 0)
            }
        val toggle =
            Switch(this).apply {
                text = getString(R.string.toggle_label)
                textSize = 17f
                setTextColor(color(R.color.tp_text_primary))
                isChecked = prefs.getBoolean(Prefs.KEY_PRO_ENABLED, false)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean(Prefs.KEY_PRO_ENABLED, isChecked).apply()
                    status.text = statusText(isChecked)
                }
            }
        status.text = statusText(toggle.isChecked)

        val card =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground()
                setPadding(dp(20), dp(18), dp(20), dp(18))
                addView(toggle)
                addView(status)
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(28)
                    }
            }

        val restart =
            Button(this).apply {
                text = getString(R.string.restart_label)
                textSize = 15f
                isAllCaps = false
                setTextColor(color(R.color.tp_on_accent))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                stateListAnimator = null
                background = buttonBackground()
                setOnClickListener { restartTickTick(it) }
                layoutParams =
                    LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply {
                        topMargin = dp(16)
                    }
            }

        val blurb =
            TextView(this).apply {
                text = getString(R.string.blurb)
                textSize = 12f
                setTextColor(color(R.color.tp_text_secondary))
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(28), 0, 0)
            }

        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(dp(28), dp(48), dp(28), dp(40))
                addView(icon)
                addView(title)
                addView(tagline)
                addView(card)
                addView(restart)
                addView(blurb)
            }

        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(color(R.color.tp_background))
                isFillViewport = true
                addView(content)
            },
        )
    }

    private fun statusText(enabled: Boolean): String =
        getString(if (enabled) R.string.status_on else R.string.status_off)

    /** Rounded, subtly-stroked surface for the toggle card. */
    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(color(R.color.tp_surface))
            setStroke(dp(1), color(R.color.tp_surface_stroke))
        }

    /** Amber, rounded, ripple-on-press background for the restart button. */
    private fun buttonBackground(): RippleDrawable {
        val fill =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color(R.color.tp_accent))
            }
        return RippleDrawable(
            ColorStateList.valueOf(color(R.color.tp_accent_pressed)),
            fill,
            null,
        )
    }

    /**
     * Cooperative force-restart of TickTick: a normal app can't force-stop
     * another, so we ask our in-process hook to kill TickTick (the explicit,
     * package-targeted [Prefs.ACTION_RESTART] broadcast), then relaunch it after
     * a short delay. The relaunch is started from THIS foreground activity (not
     * the killed process) to satisfy background-activity-start limits. If
     * TickTick wasn't running, the broadcast is a no-op and we simply launch it.
     */
    private fun restartTickTick(view: View) {
        sendBroadcast(Intent(Prefs.ACTION_RESTART).setPackage(TICKTICK_PACKAGE))
        val launch = packageManager.getLaunchIntentForPackage(TICKTICK_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, R.string.ticktick_not_installed, Toast.LENGTH_SHORT).show()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Toast.makeText(this, R.string.restarting, Toast.LENGTH_SHORT).show()
        view.postDelayed({ startActivity(launch) }, RELAUNCH_DELAY_MS)
    }

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

    /** dp → px for the code-built layout. */
    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()

    @Suppress("DEPRECATION")
    private fun color(id: Int): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            getColor(id)
        } else {
            resources.getColor(id)
        }

    private companion object {
        const val TICKTICK_PACKAGE = "com.ticktick.task"

        /** Pause after the kill broadcast before relaunching, so the old process is gone. */
        const val RELAUNCH_DELAY_MS = 900L
    }
}
