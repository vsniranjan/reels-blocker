# Technical notes

Internals of [Reels Blocker](../README.md): how detection works, how the app is built, and what to do when Instagram renames things.

## How blocking works

An `AccessibilityService` watches only `com.instagram.android`. When the fullscreen reels viewer (or a selected Reels tab) is on screen, it covers the reel with a `TYPE_ACCESSIBILITY_OVERLAY` window.

The overlay **never auto-navigates**. A wrongly shown overlay is a cosmetic glitch; an automatic BACK can exit Instagram entirely, which an earlier navigation-based version did at random. Escape is always user-initiated: the bottom tab bar stays exposed below the overlay, and the "Back to feed" button clicks the Home tab (or sends BACK in a pushed viewer).

A reel viewer only counts when it is visible and covers at least ~60% of the screen. Instagram keeps off-screen and preloaded clips fragments in the tree, and feed posts embed clips containers, so the bare presence of an ID means nothing.

## Which reels are allowed

Two viewers are allowed, each for exactly one reel:

| Opened from | Marker | Grant ends when |
|---|---|---|
| A DM | reply-to-sender bar (`reply_bar_edittext`) | by itself — the bar belongs to that shared reel |
| The home feed | Reels/Friends tab strip (`action_bar_tab_layout`, visible) | a different reel is on screen |

Telling the entry paths apart, verified on-device 2026-07-31 across all three:

| Surface | bottom tab bar | `action_bar_tab_layout` | `clips_viewer_action_bar_title` |
|---|---|---|---|
| Feed-opened | absent | **visible** ("Reels"/"Friends") | absent |
| Explore-opened | absent | vis=false, parked at x=-1301 | visible, "Explore" |
| Reels tab | present, `clips_tab` selected | visible | absent |

`findAnyById()` filters on `isVisibleToUser`, so the strip is a usable marker as-is. The Reels tab shows the same strip, so it is only ever consulted for a pushed viewer.

### Why the feed grant is anchored to the author, not to a scroll

The grant is anchored to the handle under the video (`clips_author_username`) and is spent when a different name appears. Off-screen neighbours in the pager carry the same ID but report `vis=false`, so a visibility-filtered lookup always names the reel being watched.

Scroll events look like the obvious signal and cannot answer the question. Both readings were tried and both failed on device:

- **"Any pager scroll ends the grant"** kills it on arrival — opening a reel from partway down the feed makes the pager settle onto it and report scrolls.
- **"The page index changed"** misses the first swipe — a quick flick reports only the index it lands on, so there is nothing to compare against yet. A slower drag reports the old index first, which is why this one survived automated testing and failed in real use.

Both try to infer which reel is on screen from motion. The author label states it. `typeViewScrolled` is deliberately not in the service config.

## Pauses and cooldowns

Mechanically two stored deadlines and nothing else — no alarms, no background service. Both are recomputed from the clock on every read, so a pause and a cooldown survive app restarts, service restarts and reboots for free, and expire on their own with nothing running.

Ending a pause early is charged on the time actually paused, so resuming a day-long pause after 3 hours costs 6 hours, not 2 days. Switching blocking off and straight back on does **not** launder an active cooldown — that deadline is absolute.

A running cooldown locks every option (`Prefs.pausesLocked`), five minutes included, and the button that opens the picker is disabled with it. `cooldownFactor == 0` on the five-minute option means only that taking it charges nothing; it never meant the option stays reachable inside a lockout someone else's pause bought. Without that, a 1-day pause could be topped up five minutes at a time for the whole two days it was supposed to cost.

Winding the system clock backwards is handled by clamping: a pause can never read as longer than the option it was started with.

## Build

Requires JDK 21 (Gradle 8.9 / AGP 8.7.3 don't support newer) and an Android SDK with platform 35:

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/Android/Sdk ./gradlew assembleDebug
```

APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Install over an existing copy with `adb install -r`, which keeps the accessibility service enabled; a `force-stop` drops the service from `enabled_accessibility_services` and it does not come back on its own.

## Cutting a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `Changelog.RELEASES` entry with **the same versionCode**. That number is what decides whether a given install has already seen the entry, so an entry with a stale or missing code never announces itself, and a version bump without an entry updates people silently.
3. Build, tag, and publish with the APK attached.

`Prefs.lastSeenVersion` holds the newest code whose notes have been shown. `MainActivity.maybeAnnounceUpdate()` records it before the sheet is dismissed rather than after, so a rotation or a back press cannot queue the same notice twice, and it treats 0 as a first install — someone installing the app has no previous version to be told about.

## When an Instagram update breaks detection

Instagram renames internal view IDs a few times a year. The app notifies you if Instagram is in use but no reel surface has been seen for 4 days.

1. In the app: **⋮ → Dump Instagram screens**.
2. Take the free 5-minute pause, then open a reel by each route you care about (feed, explore, Reels tab, DM).
3. Pull the dumps: `adb pull /sdcard/Android/data/dev.niranjan.reelsblocker/files/dumps/`
4. Find the new viewer/tab/marker IDs and update `app/src/main/java/dev/niranjan/reelsblocker/Detection.kt`, then rebuild and reinstall.

All detection constants live in `Detection.kt` — nothing else needs touching. Dump mode also prints the firing detection rule on the overlay itself.

Two things that will waste your time otherwise:

- In-app navigation (switching tabs, opening a viewer) fires no `TYPE_WINDOW_STATE_CHANGED`, so the dumper captures nothing. Force a dump by pressing HOME and relaunching Instagram on the screen you want.
- `adb shell uiautomator dump` fails with "could not get idle state" while a reel is playing. Pausing playback sometimes helps; the HOME-and-relaunch trick is more reliable.

## Known limitations

- View IDs in `Detection.kt` were verified on-device in July–August 2026; they will drift with Instagram updates.
- DM and feed detection are heuristic. A missed marker blocks a legitimate DM or feed reel — reopening usually works.
- Sponsored reels open a viewer without the tab strip, so they are blocked like any other reel.
- The feed grant compares author handles, so two consecutive reels **by the same account** are not noticed and the second plays.
- Nothing caps how often the feed allowance is spent: back out, tap the next reel, repeat. The friction is the only limit — the same bet the DM path makes.
- On the Reels tab the overlay window lands at `[0,126][1080,2329]`: the height matches the tab bar top, but the window is shifted down by the status-bar inset and covers the tab bar despite `fitInsetsTypes = 0`. The exit button still works (it clicks the Home tab through the accessibility tree), so this is cosmetic rather than a trap.
- The Reels tab content-description fallback assumes an English locale.
- Not affiliated with Instagram/Meta. Reading another app's accessibility tree and overlaying it likely sits outside Instagram's ToS — personal use at your own risk.
