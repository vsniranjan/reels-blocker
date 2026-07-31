# Reels Blocker

Personal Android app. Blocks Instagram Reels via an AccessibilityService; the rest of Instagram is untouched. Reels shared in DMs still play.

## How it works

An accessibility service watches only `com.instagram.android`. When the fullscreen reels viewer (or a selected Reels tab) is on screen, it covers the reel with a `TYPE_ACCESSIBILITY_OVERLAY` window — unless the viewer was opened from a DM (detected via the reply-to-sender bar), in which case that reel is allowed.

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
- DM detection is heuristic: scrolling past a DM-shared reel into the endless feed is blocked by design, but a missed marker may block a legitimate DM reel (reopen usually works).
- Reels tab content-description fallback assumes English locale.
- Not affiliated with Instagram/Meta. Reading another app's accessibility tree and overlaying it likely sits outside Instagram's ToS — personal use at your own risk.

## License

[MIT](LICENSE)
