# Reels Blocker

Personal Android app. Blocks Instagram Reels via an AccessibilityService; the rest of Instagram is untouched. Reels shared in DMs still play, and a reel tapped in the home feed plays until you swipe off it.

## How it works

An accessibility service watches only `com.instagram.android`. When the fullscreen reels viewer (or a selected Reels tab) is on screen, it covers the reel with a `TYPE_ACCESSIBILITY_OVERLAY` window — unless that one reel is allowed:

- **Opened from a DM**, detected via the reply-to-sender bar.
- **Opened from the home feed**, detected via the Reels/Friends tab strip in the viewer's action bar, which a viewer pushed from explore does not show (it shows a plain "Explore" title instead).

Both allowances cover the reel you opened and nothing more. The DM one ends by itself — the reply bar belongs to the shared reel — and the feed one is ended explicitly: the grant is anchored to the author shown under the video, and once a different reel is on screen it is spent and the overlay comes back. Leaving the viewer resets it, so the next reel you tap in the feed is allowed again. Nothing is persisted; a service restart just means the next reel is blocked until you reopen it.

Scroll events are deliberately *not* what ends the grant, though they look like the obvious signal. Opening a reel from partway down the feed makes the pager settle onto it and report scrolls, while a quick flick to the next reel reports only the index it lands on — so neither "any pager scroll" nor "the page index changed" can tell arriving from leaving. Which reel is on screen can.

Day to day there is no off switch — only **Pause blocking**, which opens a picker of timed pauses: 5 minutes, 15 minutes, 30 minutes or 1 day. Every option but the 5-minute one charges a **cooldown** once it ends, during which the paid options are locked:

| Pause | Cooldown after |
|-------|----------------|
| 5 min | none — always available |
| 15 min | 15 min |
| 30 min | 30 min |
| 1 day | 2 days |

Ending a pause early is cheaper: the cooldown is charged on the time actually paused, so resuming a day-long pause after 3 hours costs 6 hours, not 2 days. Resuming works from the app or from the notification's **Resume blocking now** action.

Mechanically it is still two stored deadlines and nothing else — no alarms, no background service. Both are recomputed from the clock on every read, so a pause and a cooldown survive app restarts, service restarts and reboots for free, and expire on their own with nothing running.

### The escape hatch

Blocking *can* be switched off for good — an app you can't quit is a hostage situation, not a helper. It is just deliberately awkward to reach, and the only hint that it exists is two lines at the bottom of the Advanced sheet.

Tap the state badge **7 times**. Every tap bleeds the shield from green toward red and cracks it a little further; stop for two seconds and it heals. On the seventh tap you get five dialogs in a row, one of which makes you type

> i know i am making a bad decision but i am doing it anyway

(case-insensitive — an autocapitalising keyboard is fine). Turning blocking back on is a single tap with no ceremony at all: friction to leave, none to return. While it is off the shield goes grey and the home screen counts how long you have been unprotected.

Switching off and straight back on does **not** clear an active cooldown — that deadline is absolute and will still be waiting.

The overlay never auto-navigates: a wrongly shown overlay is a cosmetic glitch, whereas an automatic BACK can exit Instagram entirely (an earlier navigation-based version did exactly that). Escape is always user-initiated: the bottom tab bar stays exposed below the overlay, and an "Exit reels" button clicks the Home tab (or sends BACK in a pushed viewer).

## Install

Grab `app-release.apk` from the [latest release](https://github.com/vsniranjan/reels-blocker/releases/latest) and sideload it, then follow the phone setup steps below. Or build it yourself:

## Build

Requires JDK 21 (Gradle 8.9 / AGP 8.7.3 don't support newer) and an Android SDK with platform 35:

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/Android/Sdk ./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Phone setup

Sideload the APK (or `adb install app/build/outputs/apk/debug/app-debug.apk` with developer mode + USB debugging on), then:

1. Open **Reels Blocker** app once (grants notification permission for the watchdog).
2. Settings → Accessibility → **Reels Blocker** → enable.
3. If the toggle is greyed out (Android 13+ sideload restriction):
   App info for Reels Blocker → **⋮** menu → **Allow restricted settings**, then retry step 2.

## When Instagram updates break detection

Instagram renames internal view IDs a few times a year. The app notifies you if Instagram is in use but no reel surface has been seen for 4 days.

To fix:

1. In the app, enable **Debug: dump Instagram screens**.
2. Open Instagram, navigate to a reel (tap **Pause blocking** first and take the free 5-minute pause).
3. Pull the dump: `adb pull /sdcard/Android/data/dev.niranjan.reelsblocker/files/dumps/`
4. Find the new viewer/tab/DM view IDs in the dump, update `app/src/main/java/dev/niranjan/reelsblocker/Detection.kt`, rebuild, reinstall.

All detection constants live in `Detection.kt` — nothing else needs touching. Dump mode also prints the firing detection rule on the overlay itself.

## Known limitations

- View IDs in `Detection.kt` were verified on-device in July 2026; they will drift with Instagram updates (see above).
- DM and feed detection are heuristic: scrolling past an allowed reel into the endless feed is blocked by design, but a missed marker may block a legitimate DM or feed reel (reopen usually works).
- Sponsored reels in the feed open a viewer without the Reels/Friends tab strip, so they are blocked like any other reel.
- The feed grant is anchored to the author handle, so swiping from one reel to the next reel *by the same account* is not noticed and plays. Rare in practice, and it costs a swipe either way.
- Nothing caps how often the feed allowance can be spent — back out, tap the next reel, repeat. The friction of doing that is the only limit, same bet the DM path makes.
- Reels tab content-description fallback assumes English locale.
- Not affiliated with Instagram/Meta. Reading another app's accessibility tree and overlaying it likely sits outside Instagram's ToS — personal use at your own risk.

## License

[MIT](LICENSE)
