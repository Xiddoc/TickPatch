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
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
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
import org.json.JSONObject

class MainActivity : Activity() {
    /** Set true while the world-readable prefs open succeeds — our proxy for "LSPosed is live". */
    private var moduleActive = false

    /** The toggle store, opened once in [onCreate] and reused by the diagnostics card. */
    private lateinit var prefs: SharedPreferences

    /** The selectable log/diagnostics surface; refreshed in [onResume] and on every action. */
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = openPrefs()

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
                    Log.i(TAG, "Pro toggle set ${if (isChecked) "ON" else "OFF"}.")
                    refreshLog()
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

        val logCard = buildLogCard()

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
                addView(logCard)
            }

        Log.i(TAG, "TickPatch settings opened.")
        Log.i(TAG, "Resolver path: " + resolverPath(ticktickVersionCode(), bundledMapVersionCodes()))
        refreshLog()

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
        Log.i(TAG, "Force-restart requested for $TICKTICK_PACKAGE.")
        refreshLog()
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
            getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE).also { moduleActive = true }
        } catch (_: SecurityException) {
            moduleActive = false
            getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        }

    /** Re-read the diagnostics whenever the screen comes forward (e.g. back from TickTick). */
    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    /**
     * The scrollable, selectable diagnostics card that replaced the old static
     * blurb. It is a fixed-height [ScrollView] (its own scroll region inside the
     * page scroll) wrapping a monospace, text-selectable [TextView] — long-press
     * to select and copy. [logView] is refreshed by [refreshLog].
     */
    private fun buildLogCard(): View {
        val header =
            TextView(this).apply {
                text = getString(R.string.log_card_title)
                textSize = 13f
                setTextColor(color(R.color.tp_text_primary))
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
            }
        val hint =
            TextView(this).apply {
                text = getString(R.string.log_card_hint)
                textSize = 11f
                setTextColor(color(R.color.tp_text_secondary))
                setPadding(0, dp(2), 0, dp(10))
            }
        logView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor(color(R.color.tp_text_secondary))
                setTextIsSelectable(true)
                setLineSpacing(dp(2).toFloat(), 1f)
            }
        val logScroll =
            ScrollView(this).apply {
                isVerticalScrollBarEnabled = true
                addView(logView)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(200))
            }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(header)
            addView(hint)
            addView(logScroll)
            layoutParams =
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(28)
                }
        }
    }

    /** Recompute the diagnostics block + own-process logcat tail into [logView]. */
    private fun refreshLog() {
        if (!::logView.isInitialized) return
        logView.text = diagnosticsBlock() + "\n\n" + logcatTail()
    }

    /**
     * A live, self-contained status report. Everything here is readable from the
     * MODULE's own process — the in-app hook logs (via XposedBridge) run inside
     * TickTick's process and aren't reachable here without the privileged
     * READ_LOGS permission, so we report what we can see honestly.
     */
    private fun diagnosticsBlock(): String {
        val maps = bundledMapVersionCodes()
        val installed = ticktickVersionCode()
        return buildString {
            appendLine("● Module:   " + if (moduleActive) "active (LSPosed prefs bridge live)" else "not detected — enable TickPatch in LSPosed")
            appendLine("● TickTick: " + (ticktickVersionLabel() ?: "not installed"))
            appendLine("● Maps:     " + if (maps.isEmpty()) "none bundled" else maps.sorted().joinToString(", "))
            appendLine(
                "● Map hit:  " + when {
                    installed == null -> "—"
                    maps.contains(installed) -> "matched ($installed)"
                    else -> "none for $installed"
                },
            )
            appendLine("● Resolver: " + resolverPath(installed, maps))
            append("● Pro:      " + if (prefs.getBoolean(Prefs.KEY_PRO_ENABLED, false)) "ENABLED" else "disabled")
        }
    }

    /**
     * Which Rosetta backend the hook uses for the INSTALLED TickTick, derived from
     * the SAME signals the hook branches on — a bundled map for the version_code,
     * else the bundled community signatures. The module process can't read the
     * hook's runtime logs (they run in TickTick's process), but it CAN read the
     * bundled maps + signatures and the installed version_code, so it reports the
     * path the hook takes without scraping logcat:
     *
     *   - a map matched          → the fast O(1) STATIC path (no DexKit);
     *   - no map but signatures  → SELF-HEALING (signatures + on-device DexKit) —
     *                              the fallback that resolves an unmapped version;
     *   - neither                → unavailable on this version.
     */
    private fun resolverPath(
        installed: Long?,
        maps: List<Long>,
    ): String =
        when {
            installed == null -> "—"
            maps.contains(installed) -> "static map ($installed) — fast O(1), no DexKit"
            hasBundledSignatures() ->
                "SELF-HEALING — no map for $installed; discovering by signatures + on-device DexKit"
            else -> "unavailable — no map or signatures bundled for $installed"
        }

    /**
     * True when the APK bundles community signatures for TickTick (the self-heal
     * input the `io.github.xiddoc.rosetta.maps` plugin bakes). A plain
     * resource-existence check on the module class loader — the same place
     * `BundledSignatures` reads them from inside the hook.
     */
    private fun hasBundledSignatures(): Boolean = javaClass.classLoader?.getResource(SIGNATURES_RESOURCE) != null

    /**
     * The last lines this app logged to logcat (tag [TAG]). An app may read its
     * OWN process logs without `READ_LOGS`; if the read is denied or empty we
     * just say so rather than failing the card.
     */
    private fun logcatTail(): String {
        val lines =
            runCatching {
                Runtime.getRuntime()
                    .exec(arrayOf("logcat", "-d", "-v", "time", "-t", "$LOGCAT_LINES", "$TAG:I", "*:S"))
                    .inputStream
                    .bufferedReader()
                    .useLines { seq -> seq.filter { it.isNotBlank() && !it.startsWith("---") }.toList() }
            }.getOrNull()
        return when {
            lines == null -> "— activity log unavailable on this device —"
            lines.isEmpty() -> "— no activity yet —"
            else -> lines.joinToString("\n")
        }
    }

    /**
     * version_codes of the maps bundled in the APK, read from `maps/index.json`
     * — the manifest the `io.github.xiddoc.rosetta.maps` Gradle plugin emits
     * alongside the fetched maps (`{ "maps": [ { "version_code": N, … } ] }`).
     */
    private fun bundledMapVersionCodes(): List<Long> =
        runCatching {
            val text =
                javaClass.classLoader
                    ?.getResourceAsStream(MAP_INDEX)
                    ?.use { it.readBytes().decodeToString() }
                    ?: return emptyList()
            val maps = JSONObject(text).getJSONArray("maps")
            (0 until maps.length()).map { maps.getJSONObject(it).getLong("version_code") }
        }.getOrDefault(emptyList())

    /** The installed TickTick's version_code, or null if it isn't installed. */
    @Suppress("DEPRECATION")
    private fun ticktickVersionCode(): Long? =
        runCatching {
            val info = packageManager.getPackageInfo(TICKTICK_PACKAGE, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        }.getOrNull()

    /** A human label like "8.1.0.0 (8100)" for the installed TickTick, or null. */
    private fun ticktickVersionLabel(): String? =
        runCatching {
            val info = packageManager.getPackageInfo(TICKTICK_PACKAGE, 0)
            "${info.versionName} (${ticktickVersionCode()})"
        }.getOrElse { if (it is PackageManager.NameNotFoundException) null else throw it }

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
        /**
         * The installed TickTick package this UI relaunches / inspects. Follows
         * the build-time [BuildConfig.TARGET_PACKAGE] (default `com.ticktick.task`)
         * so a renamed coexistence build points at its own patched clone; the
         * `<queries>` entry is generated from the same value.
         */
        val TICKTICK_PACKAGE: String = BuildConfig.TARGET_PACKAGE

        /** Pause after the kill broadcast before relaunching, so the old process is gone. */
        const val RELAUNCH_DELAY_MS = 900L

        /** logcat tag for this app's own diagnostics; the card filters on it. */
        const val TAG = "TickPatch"

        /** Manifest the rosetta-maps Gradle plugin emits next to the fetched maps. */
        const val MAP_INDEX = "maps/index.json"

        /** Baked community signatures resource — the self-heal fallback input. */
        const val SIGNATURES_RESOURCE = "signatures/com.ticktick.task.json"

        /** How many of this app's recent log lines to show in the card. */
        const val LOGCAT_LINES = 200
    }
}
