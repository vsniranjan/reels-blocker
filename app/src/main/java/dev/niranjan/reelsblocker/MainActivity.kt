package dev.niranjan.reelsblocker

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    /** What the whole screen is showing. One of four, never a combination. */
    private enum class UiState { NOT_SET_UP, PROTECTED, LOCKED, PAUSED }

    private lateinit var prefs: Prefs

    /** Drives the fade-through: only a real state change animates, not every tick. */
    private var shownState: UiState? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            // Keeps running through a cooldown too — that countdown is on screen as well.
            if (prefs.pauseRemainingMs > 0 || prefs.cooldownRemainingMs > 0) {
                ticker.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // No DynamicColors: monochrome wallpaper palettes (Nothing phones) wash the
        // whole app gray; the static ember scheme is the point.
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        findViewById<MaterialButton>(R.id.primary_button).setOnClickListener {
            when (currentState()) {
                UiState.NOT_SET_UP -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                UiState.PAUSED -> {
                    prefs.resume()
                    PauseNotification.cancel(this)
                    restartTicker()
                }
                else -> showPauseSheet()
            }
        }

        findViewById<TextView>(R.id.setup_hint).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_hint_title)
                .setMessage(R.string.setup_hint_body)
                .setPositiveButton(R.string.setup_hint_dismiss, null)
                .show()
        }

        PauseNotification.ensureChannel(this)

        findViewById<MaterialSwitch>(R.id.dump_switch).apply {
            isChecked = prefs.dumpMode
            setOnCheckedChangeListener { _, checked -> prefs.dumpMode = checked }
        }

        findViewById<View>(R.id.advanced_toggle).setOnClickListener {
            val section = findViewById<View>(R.id.advanced_section)
            val opening = section.visibility != View.VISIBLE
            section.visibility = if (opening) View.VISIBLE else View.GONE
            findViewById<ImageView>(R.id.advanced_chevron)
                .animate().rotation(if (opening) 180f else 0f).setDuration(180L).start()
        }

        findViewById<MaterialButton>(R.id.stats_reset).setOnClickListener {
            prefs.clearBlockedCounts()
            refresh()
        }

        findViewById<TextView>(R.id.dump_path).text = "${getExternalFilesDir(null)}/dumps/"

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        restartTicker()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun restartTicker() {
        ticker.removeCallbacks(tick)
        tick.run()
    }

    private fun currentState(): UiState = when {
        ReelsBlockerService.instance == null -> UiState.NOT_SET_UP
        prefs.pauseRemainingMs > 0 -> UiState.PAUSED
        prefs.cooldownRemainingMs > 0 -> UiState.LOCKED
        else -> UiState.PROTECTED
    }

    private fun refresh() {
        val state = currentState()
        val remaining = prefs.pauseRemainingMs
        val cooldown = prefs.cooldownRemainingMs

        val icon = findViewById<ImageView>(R.id.state_icon)
        val iconBg = findViewById<View>(R.id.state_icon_bg)
        val label = findViewById<TextView>(R.id.state_label)
        val sub = findViewById<View>(R.id.state_sub)
        val pill = findViewById<View>(R.id.cooldown_pill)
        val button = findViewById<MaterialButton>(R.id.primary_button)

        // Green guarding, red exposed, grey never started. The badge answers "am I
        // protected", so it uses semantic colour rather than the brand orange.
        iconBg.backgroundTintList = colorStateList(
            when (state) {
                UiState.PROTECTED, UiState.LOCKED -> R.color.state_ok_container
                UiState.PAUSED -> R.color.state_off_container
                UiState.NOT_SET_UP -> R.color.brand_muted_container
            }
        )
        icon.imageTintList = colorStateList(
            when (state) {
                UiState.PROTECTED, UiState.LOCKED -> R.color.state_ok
                UiState.PAUSED -> R.color.state_off
                UiState.NOT_SET_UP -> R.color.brand_on_muted_container
            }
        )

        // A struck-through shield, not a pause glyph: a lone pause symbol in a
        // filled circle reads as a button and invites a tap that does nothing.
        icon.setImageResource(
            if (state == UiState.PAUSED) R.drawable.ic_shield_off else R.drawable.ic_shield
        )
        label.text = when (state) {
            UiState.PAUSED -> formatDuration(remaining)
            UiState.NOT_SET_UP -> getString(R.string.state_not_on)
            else -> getString(R.string.state_protected)
        }
        sub.visibility = if (state == UiState.PAUSED) View.VISIBLE else View.GONE

        pill.visibility = if (state == UiState.LOCKED) View.VISIBLE else View.GONE
        if (state == UiState.LOCKED) {
            findViewById<TextView>(R.id.cooldown_text).text =
                getString(R.string.state_locked, formatDuration(cooldown))
        }

        button.setText(
            when (state) {
                UiState.NOT_SET_UP -> R.string.action_turn_on
                UiState.PAUSED -> R.string.action_resume
                else -> R.string.action_pause
            }
        )
        button.icon = when (state) {
            UiState.NOT_SET_UP -> null
            UiState.PAUSED -> getDrawable(R.drawable.ic_play)
            else -> getDrawable(R.drawable.ic_pause)
        }

        // Nothing to count and nothing to configure before the service is running.
        val setUp = state != UiState.NOT_SET_UP
        findViewById<MaterialCardView>(R.id.stats_card).visibility = visibleIf(setUp)
        findViewById<View>(R.id.advanced_toggle).visibility = visibleIf(setUp)
        findViewById<View>(R.id.advanced_section).visibility =
            if (setUp) findViewById<View>(R.id.advanced_section).visibility else View.GONE
        findViewById<View>(R.id.setup_hint).visibility = visibleIf(!setUp)

        findViewById<TextView>(R.id.stat_today).text = prefs.blockedToday.toString()
        findViewById<TextView>(R.id.stat_total).text = prefs.blockedTotal.toString()

        if (shownState != null && shownState != state) fadeThrough(findViewById(R.id.hero), button)
        shownState = state
    }

    /**
     * Material fade-through for state changes. Runs on ValueAnimator, so the
     * system "Remove animations" setting collapses it to an instant cut for free.
     */
    private fun fadeThrough(vararg views: View) {
        views.forEach { view ->
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(200L).start()
        }
    }

    private fun visibleIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun colorStateList(colorRes: Int) =
        android.content.res.ColorStateList.valueOf(getColor(colorRes))

    /**
     * Ceiling, so a fresh pause reads "5:00" rather than "4:59". Widens as the
     * value grows: a day-long pause would otherwise render as "1440:00".
     */
    private fun formatDuration(ms: Long): String {
        val seconds = (ms + 999L) / 1000L
        val hours = seconds / 3600
        return when {
            hours >= 24 -> "%dd %dh".format(hours / 24, hours % 24)
            hours >= 1 -> "%dh %02dm".format(hours, (seconds % 3600) / 60)
            else -> "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }

    /**
     * One tap on a row starts that pause. Locked rows are dimmed and unclickable;
     * dismissing leaves the deadline alone.
     */
    private fun showPauseSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_pause, null)
        val list = view.findViewById<android.view.ViewGroup>(R.id.option_list)

        val rows = PauseOption.values().map { option ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_pause_option, list, false)
            bindOption(row, option, sheet)
            list.addView(row)
            option to row
        }

        // The lockout chips are countdowns like the one on the home screen, so they
        // tick too — and a row that unlocks while the sheet is open becomes usable
        // without the user having to close and reopen it.
        val sheetTicker = object : Runnable {
            override fun run() {
                rows.forEach { (option, row) -> bindOption(row, option, sheet) }
                if (prefs.cooldownRemainingMs > 0) ticker.postDelayed(this, 1000L)
            }
        }
        sheet.setOnShowListener { ticker.postDelayed(sheetTicker, 1000L) }
        sheet.setOnDismissListener {
            ticker.removeCallbacks(sheetTicker)
            restartTicker()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    /** Row state for one option, re-applied every second while the sheet is open. */
    private fun bindOption(row: View, option: PauseOption, sheet: BottomSheetDialog) {
        val locked = prefs.isLocked(option)

        row.findViewById<ImageView>(R.id.option_icon).apply {
            setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_timer)
            // The free option is the always-available one; give it the accent.
            imageTintList = colorStateList(
                if (option.cooldownFactor == 0 && !locked) R.color.brand_primary
                else R.color.brand_on_surface_variant
            )
        }
        row.findViewById<TextView>(R.id.option_label).setText(labelOf(option))
        row.findViewById<TextView>(R.id.option_chip).text =
            if (locked) formatDuration(prefs.cooldownRemainingMs) else getString(costOf(option))

        row.alpha = if (locked) 0.38f else 1f
        row.isEnabled = !locked
        row.setOnClickListener(
            if (locked) null else View.OnClickListener {
                prefs.pause(option)
                PauseNotification.show(this, prefs)
                sheet.dismiss()
            }
        )
        // Must follow setOnClickListener: that call force-sets clickable even when
        // handed null, which would leave a locked row rippling under the finger.
        row.isClickable = !locked
    }

    private fun labelOf(option: PauseOption) = when (option) {
        PauseOption.FIVE_MIN -> R.string.pause_option_5m
        PauseOption.FIFTEEN_MIN -> R.string.pause_option_15m
        PauseOption.THIRTY_MIN -> R.string.pause_option_30m
        PauseOption.ONE_DAY -> R.string.pause_option_1d
    }

    private fun costOf(option: PauseOption) = when (option) {
        PauseOption.FIVE_MIN -> R.string.pause_cost_free
        PauseOption.FIFTEEN_MIN -> R.string.pause_cost_15m
        PauseOption.THIRTY_MIN -> R.string.pause_cost_30m
        PauseOption.ONE_DAY -> R.string.pause_cost_1d
    }
}
