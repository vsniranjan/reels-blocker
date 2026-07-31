package dev.niranjan.reelsblocker

/**
 * All Instagram view IDs and labels used for detection live here.
 * Instagram renames these every few releases — when blocking stops working,
 * enable dump mode in the app, open a reel, and update these constants from
 * the dump file.
 */
object Detection {

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    /** Fullscreen reels viewer (used by the Reels tab, feed and explore alike). */
    val VIEWER_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/clips_viewer_view_pager",
        "$INSTAGRAM_PACKAGE:id/clips_swipe_refresh_container",
    )

    /** Bottom-navigation Reels tab button. */
    val REELS_TAB_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/clips_tab",
    )

    /** Bottom-navigation Home tab button — used to escape the Reels tab. */
    val HOME_TAB_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/feed_tab",
    )

    /**
     * Any of these present means the bottom tab bar is on screen, i.e. we are on a
     * root tab (not a pushed viewer). BACK from a root tab can exit Instagram, so
     * blocking must escape via a Home-tab click instead. Verified on-device 2026-07-17:
     * the pushed reels viewer (feed/explore/DM) contains none of these.
     */
    val TAB_BAR_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/feed_tab",
        "$INSTAGRAM_PACKAGE:id/clips_tab",
        "$INSTAGRAM_PACKAGE:id/search_tab",
        "$INSTAGRAM_PACKAGE:id/profile_tab",
    )

    /** Content descriptions used as fallback when tab IDs drift. English locale. */
    const val REELS_TAB_CONTENT_DESC = "Reels"
    const val HOME_TAB_CONTENT_DESC = "Home"

    /**
     * Present only in the reels viewer when opened from a DM (the reply-to-sender
     * bar and sender attribution). Verified on-device 2026-07-17. A viewer showing
     * any of these is a DM-shared reel and is allowed.
     */
    val DM_VIEWER_MARKER_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/reply_bar_edittext",
        "$INSTAGRAM_PACKAGE:id/reply_bar_container_scroll_view",
        "$INSTAGRAM_PACKAGE:id/sender_username_or_fullname",
    )

    /**
     * The Reels/Friends tab strip in the viewer's own action bar. A pushed viewer
     * showing it was opened from the home feed; one opened from explore shows a
     * plain `clips_viewer_action_bar_title` reading "Explore" instead, and keeps
     * this strip parked off-screen (vis=false, x=-1301). Verified on-device
     * 2026-07-31 across all three entry paths.
     *
     * The Reels tab shows the same strip, so this must only ever be consulted for
     * a pushed viewer — with the bottom tab bar on screen it means nothing.
     */
    val FEED_VIEWER_MARKER_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/action_bar_tab_layout",
    )

    /**
     * Author label of the reel on screen — the handle under the video. Off-screen
     * neighbours in the pager carry the same id but report vis=false, so a
     * visibility-filtered lookup names the reel currently being watched. That is
     * what tells a feed grant its reel has been swiped away. Verified on-device
     * 2026-08-01.
     */
    val REEL_AUTHOR_IDS = listOf(
        "$INSTAGRAM_PACKAGE:id/clips_author_username",
    )
}
