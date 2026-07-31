package dev.niranjan.reelsblocker

import android.animation.ArgbEvaluator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    /** What the whole screen is showing. One of five, never a combination. */
    private enum class UiState { NOT_SET_UP, OFF, PROTECTED, LOCKED, PAUSED }

    companion object {
        /** Taps on the badge that reveal the escape hatch. */
        private const val TAPS_TO_REVEAL = 7

        /** A slower run than this is someone poking the screen, not a gesture. */
        private const val TAP_GAP_MS = 2000L
    }

    private lateinit var prefs: Prefs

    private var tapCount = 0
    private var lastTapAt = 0L

    /**
     * Puts the shield back together when a tap streak is abandoned. Without this
     * the damage would sit there until something else repainted the badge — in
     * the Protected state nothing does, so a half-broken shield would just stay.
     */
    private val tapReset = Runnable { resetTaps() }

    /** Drives the fade-through: only a real state change animates, not every tick. */
    private var shownState: UiState? = null

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            // Keeps running through a cooldown and through the off tally too —
            // those countdowns are on screen as well.
            if (prefs.pauseRemainingMs > 0 || prefs.cooldownRemainingMs > 0 || prefs.disabled) {
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
                // Friction to leave, none to come back.
                UiState.OFF -> {
                    prefs.enable()
                    restartTicker()
                }
                UiState.PAUSED -> {
                    prefs.resume()
                    PauseNotification.cancel(this)
                    restartTicker()
                }
                else -> showPauseSheet()
            }
        }

        findViewById<View>(R.id.state_icon_bg).setOnClickListener { onBadgeTapped() }

        findViewById<TextView>(R.id.setup_hint).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.setup_hint_title)
                .setMessage(R.string.setup_hint_body)
                .setPositiveButton(R.string.setup_hint_dismiss, null)
                .show()
        }

        PauseNotification.ensureChannel(this)

        findViewById<View>(R.id.overflow).setOnClickListener { showAdvancedSheet() }

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
        resetTaps()
        super.onPause()
    }

    private fun restartTicker() {
        ticker.removeCallbacks(tick)
        tick.run()
    }

    private fun currentState(): UiState = when {
        ReelsBlockerService.instance == null -> UiState.NOT_SET_UP
        prefs.disabled -> UiState.OFF
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

        // Green guarding, red exposed, grey off or never started. The badge answers
        // "am I protected", so it uses semantic colour rather than the brand orange.
        // Switched off is deliberately drab rather than alarming — it was a choice.
        iconBg.backgroundTintList = colorStateList(
            when (state) {
                UiState.PROTECTED, UiState.LOCKED -> R.color.state_ok_container
                UiState.PAUSED -> R.color.state_off_container
                UiState.OFF, UiState.NOT_SET_UP -> R.color.brand_muted_container
            }
        )
        icon.imageTintList = colorStateList(
            when (state) {
                UiState.PROTECTED, UiState.LOCKED -> R.color.state_ok
                UiState.PAUSED -> R.color.state_off
                UiState.OFF, UiState.NOT_SET_UP -> R.color.brand_on_muted_container
            }
        )

        // A struck-through shield, not a pause glyph: a lone pause symbol in a
        // filled circle reads as a button and invites a tap that does nothing.
        val guardDown = state == UiState.PAUSED || state == UiState.OFF
        icon.setImageResource(if (guardDown) R.drawable.ic_shield_off else R.drawable.ic_shield)
        // Never leave a half-cracked shield behind after the state moves on.
        findViewById<ImageView>(R.id.state_cracks).visibility = View.GONE

        label.text = when (state) {
            UiState.PAUSED -> formatDuration(remaining)
            UiState.NOT_SET_UP -> getString(R.string.state_not_on)
            UiState.OFF -> getString(R.string.state_off)
            else -> getString(R.string.state_protected)
        }
        findViewById<TextView>(R.id.state_sub).text = when (state) {
            UiState.OFF -> getString(R.string.state_off_for, formatDuration(prefs.offForMs))
            else -> getString(R.string.state_left)
        }
        sub.visibility = if (guardDown) View.VISIBLE else View.GONE

        pill.visibility = if (state == UiState.LOCKED) View.VISIBLE else View.GONE
        if (state == UiState.LOCKED) {
            findViewById<TextView>(R.id.cooldown_text).text =
                getString(R.string.state_locked, formatDuration(cooldown))
        }

        button.setText(
            when (state) {
                UiState.NOT_SET_UP -> R.string.action_turn_on
                UiState.OFF -> R.string.action_turn_back_on
                UiState.PAUSED -> R.string.action_resume
                else -> R.string.action_pause
            }
        )
        button.icon = when (state) {
            UiState.NOT_SET_UP, UiState.OFF -> null
            UiState.PAUSED -> getDrawable(R.drawable.ic_play)
            else -> getDrawable(R.drawable.ic_pause)
        }

        // Nothing to count and nothing to configure before the service is running,
        // and nothing being blocked to count while it is switched off.
        val setUp = state != UiState.NOT_SET_UP
        findViewById<MaterialCardView>(R.id.stats_card).visibility =
            visibleIf(setUp && state != UiState.OFF)
        findViewById<View>(R.id.overflow).visibility = visibleIf(setUp)
        findViewById<View>(R.id.setup_hint).visibility = visibleIf(!setUp)

        findViewById<TextView>(R.id.stat_today).text = prefs.blockedToday.toString()
        findViewById<TextView>(R.id.stat_total).text = prefs.blockedTotal.toString()

        if (shownState != null && shownState != state) fadeThrough(findViewById(R.id.hero), button)
        shownState = state

        // A cooldown keeps the ticker running, and refresh() has just repainted the
        // badge — without this, every tick would wipe the damage out from under a
        // tap streak in progress.
        if (tapCount > 0) paintDamage(tapCount)
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

    // ---------------------------------------------------------------------------
    // The escape hatch. Blocking can be switched off for good — it just costs the
    // user their dignity to get there. Every stage is cancellable; only the last
    // one writes anything.
    // ---------------------------------------------------------------------------

    /**
     * Seven taps on the badge. Each one answers immediately — the shield bleeds
     * from green toward red and gains another crack — so nobody is left wondering
     * whether the gesture is working.
     */
    private fun onBadgeTapped() {
        // Only an armed shield can be broken; off or not-yet-set-up have no hatch.
        if (currentState() != UiState.PROTECTED && currentState() != UiState.LOCKED) return

        val now = System.currentTimeMillis()
        if (now - lastTapAt > TAP_GAP_MS) tapCount = 0
        lastTapAt = now
        tapCount++

        val badge = findViewById<View>(R.id.state_icon_bg)
        recoil(badge)
        // The last tap lands harder than the ones before it.
        badge.performHapticFeedback(
            if (tapCount >= TAPS_TO_REVEAL) HapticFeedbackConstants.LONG_PRESS
            else HapticFeedbackConstants.KEYBOARD_TAP
        )
        paintDamage(tapCount)

        if (tapCount >= TAPS_TO_REVEAL) {
            // Deliberately no reset here: healing the shield the instant the
            // dialog appears would read as "nothing happened". It stays broken
            // until the chain ends, one way or the other.
            ticker.removeCallbacks(tapReset)
            stageOne()
            return
        }

        ticker.removeCallbacks(tapReset)
        ticker.postDelayed(tapReset, TAP_GAP_MS)
    }

    /** A short press-in and settle. Enough to feel deliberate, not enough to bounce. */
    private fun recoil(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.08f).scaleY(1.08f)
            .setDuration(90L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(140L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Bleeds the badge toward red and stacks on another fracture. */
    private fun paintDamage(taps: Int) {
        val progress = taps.toFloat() / TAPS_TO_REVEAL
        val evaluator = ArgbEvaluator()
        val icon = evaluator.evaluate(
            progress, getColor(R.color.state_ok), getColor(R.color.state_off)
        ) as Int
        val container = evaluator.evaluate(
            progress, getColor(R.color.state_ok_container), getColor(R.color.state_off_container)
        ) as Int

        findViewById<ImageView>(R.id.state_icon).imageTintList = ColorStateList.valueOf(icon)
        findViewById<View>(R.id.state_icon_bg).backgroundTintList = ColorStateList.valueOf(container)

        val cracks = findViewById<ImageView>(R.id.state_cracks)
        if (taps >= 2) {
            cracks.setImageResource(crackFor(taps))
            cracks.imageTintList = ColorStateList.valueOf(icon)
            cracks.visibility = View.VISIBLE
        } else {
            cracks.visibility = View.GONE
        }
    }

    private fun crackFor(taps: Int) = when (taps) {
        2 -> R.drawable.ic_shield_crack_1
        3 -> R.drawable.ic_shield_crack_2
        4 -> R.drawable.ic_shield_crack_3
        5 -> R.drawable.ic_shield_crack_4
        6 -> R.drawable.ic_shield_crack_5
        else -> R.drawable.ic_shield_crack_6
    }

    /** Puts the shield back together. */
    private fun resetTaps() {
        ticker.removeCallbacks(tapReset)
        tapCount = 0
        lastTapAt = 0L
        if (::prefs.isInitialized) refresh()
    }

    private fun stageOne() = guiltDialog(
        title = R.string.off_1_title,
        body = getString(R.string.off_1_body),
        continueLabel = R.string.off_1_continue,
        step = 1,
    ) { stageTwo() }

    private fun stageTwo() = guiltDialog(
        title = R.string.off_2_title,
        body = getString(R.string.off_2_body, prefs.blockedTotal),
        continueLabel = R.string.off_2_continue,
        step = 2,
    ) { stageThree() }

    private fun stageThree() = guiltDialog(
        title = R.string.off_3_title,
        body = getString(R.string.off_3_body),
        continueLabel = R.string.off_3_continue,
        step = 3,
    ) { stageFour() }

    /**
     * Stage four: type the phrase. Case-insensitive and trimmed, so a keyboard
     * that autocapitalises "i" still passes — the point is the typing, not a
     * spelling test.
     */
    private fun stageFour() {
        val view = layoutInflater.inflate(R.layout.dialog_type_confirm, null)
        val input = view.findViewById<EditText>(R.id.phrase_input)
        val step = 4

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.off_4_title)
            .setView(view)
            .setOnCancelListener { resetTaps() }

        if (continueOnRight(step)) {
            builder.setPositiveButton(R.string.off_4_continue) { _, _ -> stageFive() }
            builder.setNegativeButton(R.string.off_keep) { _, _ -> resetTaps() }
        } else {
            builder.setPositiveButton(R.string.off_keep) { _, _ -> resetTaps() }
            builder.setNegativeButton(R.string.off_4_continue) { _, _ -> stageFive() }
        }
        val dialog = builder.show()

        val continueButton = dialog.getButton(
            if (continueOnRight(step)) android.app.AlertDialog.BUTTON_POSITIVE
            else android.app.AlertDialog.BUTTON_NEGATIVE
        )
        continueButton.isEnabled = false
        styleStage(dialog, step)

        val target = getString(R.string.off_phrase)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                continueButton.isEnabled = s?.toString()?.trim().equals(target, ignoreCase = true)
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
    }

    private fun stageFive() = guiltDialog(
        title = R.string.off_5_title,
        body = getString(R.string.off_5_body),
        continueLabel = R.string.off_5_continue,
        step = 5,
    ) {
        prefs.disable()
        PauseNotification.cancel(this)
        // Clears the tap streak as well, so the grey Off badge arrives uncracked.
        resetTaps()
        restartTicker()
    }

    /**
     * One rung of the ladder. The two actions swap sides every step, so the chain
     * has to be read rather than drummed through from muscle memory — which is the
     * entire reason there are five of them.
     */
    private fun guiltDialog(title: Int, body: String, continueLabel: Int, step: Int, onContinue: () -> Unit) {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setOnCancelListener { resetTaps() }

        if (continueOnRight(step)) {
            builder.setPositiveButton(continueLabel) { _, _ -> onContinue() }
            builder.setNegativeButton(R.string.off_keep) { _, _ -> resetTaps() }
        } else {
            builder.setPositiveButton(R.string.off_keep) { _, _ -> resetTaps() }
            builder.setNegativeButton(continueLabel) { _, _ -> onContinue() }
        }
        styleStage(builder.show(), step)
    }

    /** Even steps put the way out on the right, odd steps on the left. */
    private fun continueOnRight(step: Int) = step % 2 == 0

    /** The way out gets quieter the further in you go, whichever slot it is in. */
    private fun styleStage(dialog: androidx.appcompat.app.AlertDialog, step: Int) {
        val slot = if (continueOnRight(step)) android.app.AlertDialog.BUTTON_POSITIVE
        else android.app.AlertDialog.BUTTON_NEGATIVE
        dialog.getButton(slot)?.apply {
            // Floors on both: quieter each step, but never so small or so faint
            // that the way out stops being usable.
            textSize = (14f - step).coerceAtLeast(11f)
            alpha = (1f - step * 0.1f).coerceAtLeast(0.6f)
            setTextColor(getColor(R.color.brand_on_surface_variant))
        }
    }

    /**
     * Debug tools, one tap off the main screen. A sheet rather than a popup menu
     * because the dump path has to be readable and copyable, which a menu row
     * cannot carry.
     */
    private fun showAdvancedSheet() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_advanced, null)

        view.findViewById<MaterialSwitch>(R.id.dump_switch).apply {
            isChecked = prefs.dumpMode
            setOnCheckedChangeListener { _, checked -> prefs.dumpMode = checked }
        }
        view.findViewById<TextView>(R.id.dump_path).text = "${getExternalFilesDir(null)}/dumps/"
        view.findViewById<MaterialButton>(R.id.stats_reset).setOnClickListener {
            prefs.clearBlockedCounts()
            sheet.dismiss()
        }

        sheet.setOnDismissListener { refresh() }
        sheet.setContentView(view)
        sheet.show()
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
