package dev.niranjan.reelsblocker

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        // No DynamicColors: monochrome wallpaper palettes (Nothing phones) wash the
        // whole app gray; the static harbor-blue scheme is the point.
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.setup_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<MaterialSwitch>(R.id.block_switch).apply {
            isChecked = prefs.blockingEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.blockingEnabled = checked
                refresh()
            }
        }

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
        refresh()
    }

    private fun refresh() {
        val running = ReelsBlockerService.instance != null
        findViewById<MaterialCardView>(R.id.setup_card).visibility =
            if (running) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.status_text).setText(
            if (prefs.blockingEnabled) R.string.status_on else R.string.status_off
        )
        findViewById<TextView>(R.id.stat_today).text = prefs.blockedToday.toString()
        findViewById<TextView>(R.id.stat_total).text = prefs.blockedTotal.toString()
    }
}
