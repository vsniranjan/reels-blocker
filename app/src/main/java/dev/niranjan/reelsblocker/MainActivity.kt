package dev.niranjan.reelsblocker

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

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
        // whole app gray; the static harbor-blue scheme is the point.
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.setup_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialSwitch>(R.id.block_switch).setOnClickListener {
            // Click, not checked-change: refresh() drives isChecked itself and a
            // listener would fire on those programmatic writes too. It is also what
            // puts the switch back when the duration dialog is cancelled.
            if (prefs.blockingEnabled) {
                showPauseDialog()
            } else {
                prefs.resume()
                PauseNotification.cancel(this)
                ticker.removeCallbacks(tick)
                tick.run()
            }
        }

        PauseNotification.ensureChannel(this)

        findViewById<MaterialSwitch>(R.id.dump_switch).apply {
            isChecked = prefs.dumpMode
            setOnCheckedChangeListener { _, checked -> prefs.dumpMode = checked }
        }

        findViewById<TextView>(R.id.advanced_toggle).setOnClickListener {
            val section = findViewById<View>(R.id.advanced_section)
            section.visibility =
                if (section.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<Button>(R.id.stats_reset).setOnClickListener {
            prefs.clearBlockedCounts()
            refresh()
        }

        findViewById<TextView>(R.id.dump_path).text =
            getString(R.string.dump_hint, "${getExternalFilesDir(null)}/dumps/")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        tick.run()
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    /**
     * One tap on a row starts that pause. Locked rows are dimmed and unclickable;
     * cancelling leaves the deadline alone, and the dismiss listener restores the
     * switch that flipping open this dialog visually turned off.
     */
    private fun showPauseDialog() {
        val options = PauseOption.values()
        val adapter = object : ArrayAdapter<PauseOption>(
            this, R.layout.item_pause_option, R.id.option_label, options
        ) {
            override fun areAllItemsEnabled() = false

            override fun isEnabled(position: Int) = !prefs.isLocked(options[position])

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val option = options[position]
                val locked = prefs.isLocked(option)
                view.findViewById<TextView>(R.id.option_label).setText(labelOf(option))
                view.findViewById<TextView>(R.id.option_caption).text =
                    if (locked) {
                        getString(
                            R.string.pause_option_locked,
                            formatDuration(prefs.cooldownRemainingMs),
                        )
                    } else {
                        getString(captionOf(option))
                    }
                view.alpha = if (locked) 0.38f else 1f
                return view
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pause_dialog_title)
            .setAdapter(adapter) { _, which ->
                prefs.pause(options[which])
                PauseNotification.show(this, prefs)
            }
            .setNegativeButton(R.string.pause_dialog_cancel, null)
            .setOnDismissListener {
                ticker.removeCallbacks(tick)
                tick.run()
            }
            .show()
    }

    private fun labelOf(option: PauseOption) = when (option) {
        PauseOption.FIVE_MIN -> R.string.pause_option_5m
        PauseOption.FIFTEEN_MIN -> R.string.pause_option_15m
        PauseOption.THIRTY_MIN -> R.string.pause_option_30m
        PauseOption.ONE_DAY -> R.string.pause_option_1d
    }

    private fun captionOf(option: PauseOption) = when (option) {
        PauseOption.FIVE_MIN -> R.string.pause_cost_free
        PauseOption.FIFTEEN_MIN -> R.string.pause_cost_15m
        PauseOption.THIRTY_MIN -> R.string.pause_cost_30m
        PauseOption.ONE_DAY -> R.string.pause_cost_1d
    }

    private fun refresh() {
        val running = ReelsBlockerService.instance != null
        findViewById<MaterialCardView>(R.id.setup_card).visibility =
            if (running) View.GONE else View.VISIBLE

        val remaining = prefs.pauseRemainingMs
        val cooldown = prefs.cooldownRemainingMs
        findViewById<MaterialSwitch>(R.id.block_switch).isChecked = remaining == 0L
        findViewById<TextView>(R.id.status_text).text = when {
            remaining > 0 -> getString(R.string.status_off, formatDuration(remaining))
            cooldown > 0 -> getString(R.string.status_locked, formatDuration(cooldown))
            else -> getString(R.string.status_on)
        }

        findViewById<TextView>(R.id.stat_today).text = prefs.blockedToday.toString()
        findViewById<TextView>(R.id.stat_total).text = prefs.blockedTotal.toString()
    }

    /** Ceiling, so a fresh pause reads "5:00" rather than "4:59". */
    private fun formatDuration(ms: Long): String {
        val seconds = (ms + 999L) / 1000L
        val hours = seconds / 3600
        return when {
            hours >= 24 -> "%dd %dh".format(hours / 24, hours % 24)
            hours >= 1 -> "%dh %02dm".format(hours, (seconds % 3600) / 60)
            else -> "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
