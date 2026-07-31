package dev.niranjan.reelsblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Resume blocking now" from the shade. No service plumbing needed: the
 * accessibility service re-reads the deadline on its next event.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs(context).resume()
        PauseNotification.cancel(context)
    }
}
