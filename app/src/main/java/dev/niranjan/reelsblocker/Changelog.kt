package dev.niranjan.reelsblocker

/**
 * What changed, in the user's terms rather than the commit log's. Shown from the
 * Advanced sheet, and once by itself after an update.
 *
 * When cutting a release, add an entry here with the same versionCode as
 * build.gradle.kts — that number is what decides whether someone has already
 * seen this entry, so an entry without it would never announce itself.
 *
 * Keep each line to something a person would say out loud. Anything that needs a
 * view ID or a class name to explain belongs in docs/technical.md instead.
 */
object Changelog {

    data class Release(val version: String, val versionCode: Int, val changes: List<String>)

    /** Newest first — the order the sheet renders in. */
    val RELEASES = listOf(
        Release(
            "2.1", 5,
            listOf(
                "This list. The app now keeps its own summary of what changed in each version, and shows it once after an update.",
                "You can reopen it any time from the ⋮ menu, under What's new.",
            ),
        ),
        Release(
            "2.0", 4,
            listOf(
                "Reels you tap in your own feed now play, one reel at a time. Swipe to the next and the block screen is back.",
                "A cooldown now locks pausing completely. Five minutes used to stay open during one, which let a day-long pause be topped up forever.",
                "The whole app was redrawn: one state on screen at a time, a shield that goes green when you're protected.",
                "Blocking can be switched off for good now, if you get through the seven taps and five confirmations.",
            ),
        ),
        Release(
            "1.2", 3,
            listOf(
                "Pauses come in four lengths: 5 minutes, 15, 30, or a day.",
                "Anything longer than five minutes charges a cooldown once it ends.",
                "Resume early and you're only charged for the time you actually used.",
            ),
        ),
        Release(
            "1.1", 2,
            listOf(
                "Turning blocking off became a five-minute pause that ends by itself.",
                "The pause counts down in the app and in the notification shade.",
            ),
        ),
        Release(
            "1.0", 1,
            listOf(
                "First release. Reels get covered by a block screen; the rest of Instagram is untouched.",
                "Reels shared in DMs still play.",
            ),
        ),
    )
}
