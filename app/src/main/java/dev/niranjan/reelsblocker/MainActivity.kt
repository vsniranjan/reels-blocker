package dev.niranjan.reelsblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PAUSE_CHANNEL = "pause"
        private const val PAUSE_NOTIFICATION_ID = 2
    }

    private lateinit var prefs: Prefs

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            if (prefs.pauseRemainingMs > 0) ticker.postDelayed(this, 1000L)
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
            // listener would fire on those programmatic writes too.
            if (prefs.blockingEnabled) {
                prefs.pause()
                showPauseNotification()
            } else {
                prefs.resume()
                getSystemService(NotificationManager::class.java).cancel(PAUSE_NOTIFICATION_ID)
            }
            ticker.removeCallbacks(tick)
            tick.run()
        }

        createPauseChannel()

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

    private fun refresh() {
        val running = ReelsBlockerService.instance != null
        findViewById<MaterialCardView>(R.id.setup_card).visibility =
            if (running) View.GONE else View.VISIBLE

        val remaining = prefs.pauseRemainingMs
        findViewById<MaterialSwitch>(R.id.block_switch).isChecked = remaining == 0L
        findViewById<TextView>(R.id.status_text).text =
            if (remaining == 0L) getString(R.string.status_on)
            else getString(R.string.status_off, formatRemaining(remaining))

        findViewById<TextView>(R.id.stat_today).text = prefs.blockedToday.toString()
        findViewById<TextView>(R.id.stat_total).text = prefs.blockedTotal.toString()
    }

    /** Ceiling, so a fresh pause reads "5:00" rather than "4:59". */
    private fun formatRemaining(ms: Long): String {
        val seconds = (ms + 999L) / 1000L
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    private fun createPauseChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PAUSE_CHANNEL,
                getString(R.string.pause_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    /**
     * Counts down in the shade via the chronometer, and setTimeoutAfter has the
     * system clear it at expiry even if the app is never reopened. Dismissible —
     * dismissing does not affect the pause, which is purely the stored deadline.
     */
    private fun showPauseNotification() {
        val remaining = prefs.pauseRemainingMs
        if (remaining == 0L) return
        val notification = Notification.Builder(this, PAUSE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_pause)
            .setContentTitle(getString(R.string.pause_notification_title))
            .setContentText(getString(R.string.pause_notification_body))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(prefs.pauseUntil)
            .setShowWhen(true)
            .setTimeoutAfter(remaining)
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(PAUSE_NOTIFICATION_ID, notification)
    }
}
