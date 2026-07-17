package dev.niranjan.reelsblocker

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var counterView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = dp(20)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        statusView = TextView(this).apply { textSize = 16f }
        layout.addView(statusView)

        layout.addView(Button(this).apply {
            text = "Open accessibility settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        layout.addView(Switch(this).apply {
            text = "Block reels"
            isChecked = prefs.blockingEnabled
            setOnCheckedChangeListener { _, checked -> prefs.blockingEnabled = checked }
        })

        counterView = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        layout.addView(counterView)

        layout.addView(Switch(this).apply {
            text = "Debug: dump Instagram screens"
            isChecked = prefs.dumpMode
            setOnCheckedChangeListener { _, checked -> prefs.dumpMode = checked }
        })

        layout.addView(TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
            text = "Dump files (for updating Detection.kt after Instagram updates):\n" +
                "${getExternalFilesDir(null)}/dumps/\n\n" +
                "If the accessibility toggle is greyed out (sideload restriction): " +
                "App info → ⋮ → Allow restricted settings."
        })

        setContentView(ScrollView(this).apply { addView(layout) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = ReelsBlockerService.instance != null
        statusView.text = if (running) {
            "Service: running ✅"
        } else {
            "Service: NOT enabled ❌\nEnable “Reels Blocker” under Accessibility settings."
        }
        counterView.text = "Blocked today: ${prefs.blockedToday}   ·   total: ${prefs.blockedTotal}"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
