package dev.niranjan.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class ReelsBlockerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ReelsBlockerService? = null

        private const val DUMP_THROTTLE_MS = 2000L
        private const val BLOCK_COUNT_GAP_MS = 10_000L
        private const val BFS_NODE_CAP = 600
        private const val PAGER_ANCESTOR_CAP = 6
        private const val TAG = "ReelsBlocker"
        private const val REEL_SIGNAL_WRITE_THROTTLE_MS = 60_000L
        private const val BROKEN_AFTER_MS = 4 * 24 * 60 * 60 * 1000L   // 4 days without any reel signal
        private const val WARN_INTERVAL_MS = 3 * 24 * 60 * 60 * 1000L  // re-warn at most every 3 days
        private const val WATCHDOG_CHANNEL = "watchdog"
    }

    private lateinit var prefs: Prefs

    private var lastDumpAt = 0L
    private var lastReelSignalWriteAt = 0L
    private var lastBlockRecordAt = 0L

    private var overlay: LinearLayout? = null
    private var overlayShown = false

    /**
     * Set when the user swipes off the reel they opened from the feed, cleared when
     * they leave the viewer entirely. A feed-opened reel is allowed once per open:
     * without this the check below would simply re-allow the next reel, and one tap
     * would buy an endless scroll.
     */
    private var feedSwipedAway = false

    /** Reel the pager is parked on, -1 before the first scroll of a viewer session. */
    private var pagerIndex = -1

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        prefs.ensureBaseline()
        createNotificationChannel()
        // A reboot or a stray dismissal wipes the shade countdown, and a day-long
        // pause should stay resumable from there without opening the app.
        PauseNotification.ensureChannel(this)
        PauseNotification.show(this, prefs)
        overlay = buildOverlay()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        hideOverlay()
        overlay = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        hideOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()

        // Our own overlay being added/removed and its button emit events too —
        // reacting to them creates a hide/show feedback loop.
        if (pkg == packageName) return

        if (pkg == null) {
            // System windows carry no package. Hide only when the active window or
            // window order actually changed (notification shade, app switch) — the
            // ADDED/REMOVED churn from overlay windows must not yank ours.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                (event.windowChanges and (AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
                    AccessibilityEvent.WINDOWS_CHANGE_LAYER)) != 0
            ) {
                hideOverlay()
            }
            return
        }

        if (pkg != Detection.INSTAGRAM_PACKAGE) {
            hideOverlay()
            return
        }

        // Scrolls are frequent — feed, comments, explore all fire them. Decide from
        // the event alone and get out, before paying for a tree walk; only a swipe
        // on the reel pager changes anything, and that one falls through so the
        // overlay lands on the next reel immediately.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (!isReelPagerScroll(event)) return
            // Opening a reel from partway down the feed settles the pager onto it,
            // which reports scrolls without ever leaving the page. Landing is not
            // swiping: only a move to a different reel spends the grant.
            val index = event.toIndex
            if (pagerIndex != -1 && index != pagerIndex) feedSwipedAway = true
            pagerIndex = index
        }

        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != Detection.INSTAGRAM_PACKAGE) {
            hideOverlay()
            return
        }

        if (prefs.dumpMode && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            dumpTree(root)
        }

        // A reel viewer counts only when it is actually what the user is looking at:
        // visible and covering most of the screen. Instagram keeps off-screen /
        // preloaded clips fragments in the tree, and feed posts embed clips
        // containers — bare presence of an ID is not enough.
        val viewer = findAnyById(root, Detection.VIEWER_IDS)
        val inViewer = viewer != null && coversMostOfScreen(viewer)
        val reelsTab = findAnyById(root, Detection.REELS_TAB_IDS)
        val reelsTabSelected = reelsTab != null && reelsTab.isVisibleToUser && reelsTab.isSelected
        val reelSurface = inViewer || reelsTabSelected

        if (reelSurface) {
            recordReelSignal()
        } else {
            maybeWarnDetectionBroken()
        }

        // Leaving the viewer ends the session, so the next reel opened from the feed
        // is a fresh grant. Reopening is the price of a second reel.
        if (!inViewer) {
            feedSwipedAway = false
            pagerIndex = -1
        }

        val pushedViewer = inViewer && !reelsTabSelected

        // Viewer with the reply-to-sender bar is a reel shared in a DM. Allowed.
        val dmViewer = pushedViewer && findAnyById(root, Detection.DM_VIEWER_MARKER_IDS) != null

        // Viewer wearing the Reels/Friends tab strip was opened from the home feed.
        // Allowed for that one reel: swiping on into the endless feed re-blocks,
        // the same boundary the DM reply bar draws for a shared reel.
        val feedMarker = pushedViewer &&
            findAnyById(root, Detection.FEED_VIEWER_MARKER_IDS) != null
        val feedViewer = feedMarker && !feedSwipedAway

        if (reelSurface && prefs.blockingEnabled && !dmViewer && !feedViewer) {
            // Blocking a viewer that still carries the feed marker can only mean the
            // grant was swiped away — every other pushed viewer reports its own id.
            val trigger = when {
                !inViewer -> "reels_tab_selected"
                feedMarker -> "feed_grant_revoked"
                else -> viewer?.viewIdResourceName
            }
            showOverlay(root, trigger)
        } else {
            hideOverlay()
        }
    }

    /**
     * True when [event] is a scroll of the reel pager — i.e. a swipe between reels
     * rather than the feed, a comment sheet or the explore grid. On the build tested
     * (2026-07-31) the pager reports scrolls under its own id; the walk up the
     * ancestors is there for the day it reports them from an inner view instead.
     */
    private fun isReelPagerScroll(event: AccessibilityEvent): Boolean {
        val source = event.source ?: return false
        if (prefs.dumpMode) Log.d(
            TAG,
            "scroll src=${source.viewIdResourceName} from=${event.fromIndex} to=${event.toIndex}" +
                " items=${event.itemCount} dy=${event.scrollDeltaY} y=${event.scrollY}"
        )
        var node: AccessibilityNodeInfo? = source
        var depth = 0
        while (node != null && depth < PAGER_ANCESTOR_CAP) {
            if (node.viewIdResourceName in Detection.VIEWER_IDS) return true
            node = node.parent
            depth++
        }
        return false
    }

    /** Visible to the user and covering at least ~60% of screen height and width. */
    private fun coversMostOfScreen(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val screen = windowManager.maximumWindowMetrics.bounds
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.height() * 10 >= screen.height() * 6 && r.width() * 10 >= screen.width() * 6
    }

    /**
     * Cover the reel with an accessibility overlay. Never auto-navigates: a wrongly
     * shown overlay is a cosmetic glitch, whereas a wrongly sent BACK can exit
     * Instagram. When the bottom tab bar is on screen the overlay stops above it,
     * so the user escapes by tapping another tab themselves.
     */
    private fun showOverlay(root: AccessibilityNodeInfo, trigger: String?) {
        val view = overlay ?: return

        // Debug aid: with dump mode on, show which detection rule fired.
        view.findViewWithTag<TextView>("debug")?.apply {
            visibility = if (prefs.dumpMode) View.VISIBLE else View.GONE
            text = "trigger: $trigger"
        }

        val tabBar = findAnyById(root, Detection.TAB_BAR_IDS)
        val overlayHeight = if (tabBar != null) {
            val r = Rect()
            tabBar.getBoundsInScreen(r)
            if (r.top > 0) r.top else ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }

        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // Lay out from screen y=0, not below the status bar — otherwise the
            // window shifts down by the inset and covers the tab bar it was sized
            // to stop above. With targetSdk 30+ the legacy FLAG_LAYOUT_IN_SCREEN
            // is ignored; fitInsetsTypes controls this.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                fitInsetsTypes = 0
            }
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = overlayHeight
            gravity = Gravity.TOP
        }
        // Always offer the escape button: if tab-bar sizing is ever wrong the
        // user must not be trapped behind the overlay with only BACK.
        view.findViewWithTag<Button>("exit")?.visibility = View.VISIBLE

        if (view.parent == null) {
            windowManager.addView(view, lp)
        } else {
            windowManager.updateViewLayout(view, lp)
        }
        if (!overlayShown) {
            overlayShown = true
            // Shade pulls, app switches etc. legitimately re-show the overlay within
            // the same reel session — don't count each flicker as a new block.
            val now = SystemClock.uptimeMillis()
            if (now - lastBlockRecordAt > BLOCK_COUNT_GAP_MS) {
                lastBlockRecordAt = now
                prefs.recordBlocked()
            }
        }
    }

    private fun hideOverlay() {
        overlayShown = false
        val view = overlay ?: return
        if (view.parent != null) windowManager.removeView(view)
    }

    /**
     * A calm wall, not a crash screen: the app's own surface colour, a shield, two
     * words and a way out. Built against a themed context so ?attr colours in the
     * shape drawables resolve, and so the panel follows light/dark like the app.
     */
    private fun buildOverlay(): LinearLayout {
        val ctx = ContextThemeWrapper(this, R.style.Theme_ReelsBlocker)
        val onSurface = resources.getColor(R.color.brand_on_surface, ctx.theme)

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(resources.getColor(R.color.brand_surface, ctx.theme))
        }
        layout.addView(FrameLayout(ctx).apply {
            background = ctx.getDrawable(R.drawable.bg_state_circle)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = ColorStateList.valueOf(
                    resources.getColor(R.color.brand_primary, ctx.theme)
                )
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(96), dp(96)))
        layout.addView(TextView(ctx).apply {
            text = getString(R.string.overlay_title)
            setTextColor(onSurface)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        })
        layout.addView(Button(ctx).apply {
            tag = "exit"
            text = getString(R.string.overlay_exit)
            background = ctx.getDrawable(R.drawable.bg_overlay_button)
            setTextColor(resources.getColor(R.color.brand_on_primary, ctx.theme))
            isAllCaps = false
            textSize = 16f
            setPadding(dp(32), 0, dp(32), 0)
            setOnClickListener {
                // User-initiated escape. Prefer clicking the Home tab; in a pushed
                // viewer (no tab bar) BACK is safe — there is a screen to pop to.
                if (!clickHomeTab()) performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(56)))
        layout.addView(TextView(ctx).apply {
            tag = "debug"
            visibility = View.GONE
            setTextColor(resources.getColor(R.color.brand_on_surface_variant, ctx.theme))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, 0)
        })
        return layout
    }

    private fun clickHomeTab(): Boolean {
        val root = rootInActiveWindow ?: return false
        val home = findAnyById(root, Detection.HOME_TAB_IDS)
            ?: findByContentDesc(root, Detection.HOME_TAB_CONTENT_DESC)
            ?: return false
        var node: AccessibilityNodeInfo? = home
        while (node != null) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent
        }
        return false
    }

    private fun findAnyById(root: AccessibilityNodeInfo, ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            val node = root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    /** Breadth-first search for a node whose content description matches exactly. Capped. */
    private fun findByContentDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < BFS_NODE_CAP) {
            val node = queue.removeFirst()
            visited++
            if (node.contentDescription?.toString() == desc) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun recordReelSignal() {
        val now = System.currentTimeMillis()
        if (now - lastReelSignalWriteAt > REEL_SIGNAL_WRITE_THROTTLE_MS) {
            lastReelSignalWriteAt = now
            prefs.lastReelSignal = now
        }
    }

    private fun maybeWarnDetectionBroken() {
        if (!prefs.blockingEnabled) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastReelSignal < BROKEN_AFTER_MS) return
        if (now - prefs.lastBrokenWarningAt < WARN_INTERVAL_MS) return
        prefs.lastBrokenWarningAt = now

        val nm = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, WATCHDOG_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.watchdog_title))
            .setContentText(getString(R.string.watchdog_body))
            .setStyle(Notification.BigTextStyle())
            .build()
        nm.notify(1, notification)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                WATCHDOG_CHANNEL,
                "Detection watchdog",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
    }

    private fun dumpTree(root: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDumpAt < DUMP_THROTTLE_MS) return
        lastDumpAt = now

        val sb = StringBuilder()
        dumpNode(root, 0, sb)
        val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
        val file = File(dir, "dump_${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        // Keep only the 10 most recent dumps.
        dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (depth > 40 || sb.length > 400_000) return
        val r = Rect()
        node.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
            .append(node.className ?: "?")
            .append(" id=").append(node.viewIdResourceName ?: "-")
            .append(" desc=").append(node.contentDescription ?: "-")
            .append(" text=").append(node.text ?: "-")
            .append(" sel=").append(node.isSelected)
            .append(" vis=").append(node.isVisibleToUser)
            .append(" b=").append(r.toShortString())
            .append('\n')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1, sb) }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
