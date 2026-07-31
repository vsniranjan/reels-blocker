# Allow reels opened from the home feed (one reel per tap)

Branch: `feat/allow-feed-reels` (off `main`)

## Context

Today the service blocks every reel surface except one: a viewer opened from a DM, detected by the reply-to-sender bar (`Detection.DM_VIEWER_MARKER_IDS`, used at `ReelsBlockerService.kt:127-135`). Everything else — Reels tab, explore, and a reel tapped in the home feed — gets the overlay.

The home-feed case is a false positive in daily use: seeing a reel in your own feed and wanting to watch *that one* is not doomscrolling, and today it is indistinguishable from opening the Reels tab.

The DM allowance works because it is self-limiting: the reply bar belongs to that one shared reel, so the first swipe into the endless feed re-blocks (README:75). The feed has no such natural boundary — allowing a feed-opened viewer outright would be an unlimited-scroll hole. So this feature reproduces the DM shape deliberately: **one reel per deliberate tap, overlay returns on the first swipe.**

## Decisions (from interview)

| Question | Decision |
|---|---|
| How long is a feed reel allowed? | The tapped reel only. First swipe to the next reel brings the overlay back. |
| Explore / search-opened reels? | Stay blocked. |
| How is "opened from feed" detected? | Dump the device first, look for a marker ID unique to the feed-opened viewer (the DM pattern). Tab-origin tracking only as fallback. |
| User-facing setting? | None. Baked in like the DM allowance. |
| Back out → tap next feed reel → repeat? | Allowed, no cap. Each reel costs a back-out and a deliberate tap; same bet the DM path makes. |
| How is the swipe detected? | `typeViewScrolled` events whose source is the clips pager. |
| If feed and explore turn out identical in the tree *and* origin tracking is unreliable? | **Stop and report.** Do not ship a weaker rule that also opens up explore. |
| Does the swipe-block count in "Reels dodged"? | Yes — it goes through the existing `showOverlay()` path, `BLOCK_COUNT_GAP_MS` dedup already handles flicker. |

## Phase 0 — branch and device dumps (no code)

`git checkout -b feat/allow-feed-reels`

In the app: Advanced → enable **Dump Instagram screens**, and take the free 5-minute pause so reels are actually reachable.

Capture the four entry paths **one at a time**, pulling between each so dumps stay attributable:

1. Home feed → tap a reel in the feed
2. Explore/search tab → tap a reel
3. Reels tab
4. DM → tap a shared reel (control — confirms markers still current)

```sh
adb pull /sdcard/Android/data/dev.niranjan.reelsblocker/files/dumps/
```

Diff (1) against (2) and (3). Looking for an ID present **only** in the feed-opened viewer — the equivalent of `reply_bar_edittext`. Candidates to grep for: anything feed/`main_feed`/`fullscreen`/`media_id` scoped, and any container wrapping the pager that differs between paths.

Outcome decides Phase 1:

- **Marker found** → stateless detection, mirror the DM code path.
- **No marker** → tab-origin tracking (below).
- **No marker and origin tracking looks unreliable on this build** → stop, report the dump findings, leave the branch unmerged.

## Phase 1 — origin detection

**Preferred (marker found).** Add to `Detection.kt`, next to `DM_VIEWER_MARKER_IDS` and documented the same way (what it is, when verified):

```kotlin
/** Present only in the reels viewer when opened from the home feed. Verified on-device <date>. */
val FEED_VIEWER_MARKER_IDS = listOf(/* from dumps */)
```

Consumed exactly like the DM check at `ReelsBlockerService.kt:128`, so both allowances share one shape:

```kotlin
val pushedViewer = inViewer && !reelsTabSelected
val dmViewer   = pushedViewer && findAnyById(root, Detection.DM_VIEWER_MARKER_IDS) != null
val feedViewer = pushedViewer && findAnyById(root, Detection.FEED_VIEWER_MARKER_IDS) != null
```

**Fallback (no marker).** Track origin in the service: whenever the tab bar is on screen (`Detection.TAB_BAR_IDS` present), record which tab is `isSelected` into a field. A pushed viewer inherits the last recorded tab; `feed_tab` means feed origin. Fails closed — an unknown or stale origin (service restart, cold entry straight into the viewer) blocks. No persistence; in-memory only, so a service restart never resurrects a stale grant.

## Phase 2 — the one-reel grant

New service state, in-memory only:

- `feedGrant: Boolean` — this viewer session's reel is allowed.
- Cleared whenever `inViewer` goes false (leaving the viewer ends the session; the next open re-grants).
- Set when a pushed viewer appears with feed origin and no grant decision has been made for this session yet.
- Cleared permanently for the session by a swipe.

Blocking condition at `ReelsBlockerService.kt:131` becomes:

```kotlin
if (reelSurface && prefs.blockingEnabled && !dmViewer && !feedGrant)
```

**Swipe detection.** Add `typeViewScrolled` to `app/src/main/res/xml/accessibility_service_config.xml`. Handle it early in `onAccessibilityEvent`, *after* the Instagram-package check but *before* the `rootInActiveWindow` fetch, so scroll storms from other apps cost nothing:

- If `event.source?.viewIdResourceName` is in `Detection.VIEWER_IDS` (or the inner scrollable id the dumps/logs reveal) → `feedGrant = false`, then fall through to the normal evaluation so the overlay appears immediately.
- The service deliberately does **not** filter `packageNames` (it needs non-Instagram events to hide the overlay) — do not add a filter to quiet the new events.

The exact id the scroll event carries is unknown until observed: ViewPager2 usually reports the inner RecyclerView, not `clips_viewer_view_pager`. Add a temporary `Log.d` of `event.source?.viewIdResourceName` gated on `prefs.dumpMode`, read it with `adb logcat`, then pin the constant in `Detection.kt` and keep the log behind dump mode (it is the same debugging affordance the overlay's `trigger:` line already provides).

If the id cannot be pinned, the loose fallback is "any scroll while `inViewer`" — correct for swipes, but a comments-sheet scroll would also revoke the grant. Fail-closed and acceptable; note it in README limitations if it ships that way.

**Dump-mode trigger string.** `showOverlay()` already reports which rule fired; pass something distinguishable (`"feed_grant_revoked"`) so on-device debugging stays readable.

## Phase 3 — docs

`README.md`: line 3 (one-liner), line 7 (how it works), and the limitations list at line 75 — the "scrolling past a DM-shared reel is blocked by design" note now covers the feed path too.

## Files

- `app/src/main/java/dev/niranjan/reelsblocker/Detection.kt` — new marker list (and the scroll-source id)
- `app/src/main/java/dev/niranjan/reelsblocker/ReelsBlockerService.kt` — grant state, scroll handling, blocking condition
- `app/src/main/res/xml/accessibility_service_config.xml` — `typeViewScrolled`
- `README.md`

No `Prefs` changes, no new strings, no UI changes, no new settings.

## Verification

Build:

```sh
JAVA_HOME=~/Android/toolchain/jdk21 ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb install -r` keeps the accessibility service enabled. If it gets force-stopped, restore with:

```sh
adb shell settings put secure enabled_accessibility_services \
  "bitpit.launcher/.lock_screen.LockScreenService:dev.niranjan.reelsblocker/dev.niranjan.reelsblocker.ReelsBlockerService"
```

On-device matrix — every row must pass before merge (screenshot via `adb exec-out screencap -p` and `adb shell dumpsys window windows | grep reelsblocker` to confirm whether the overlay window exists):

| Case | Expected |
|---|---|
| Home feed → tap reel | Plays, no overlay |
| …then swipe to next reel | Overlay appears |
| …exit, tap another feed reel | Plays again, no overlay |
| Explore → tap reel | Overlay on open |
| Reels tab | Overlay on open, tab bar still exposed below it |
| DM → tap shared reel | Plays (unchanged) |
| …then swipe | Overlay appears (unchanged) |
| Overlay "Back to feed" button | Pops back to the feed, does not exit Instagram |
| Switch to another app and back mid-allowed-reel | Still allowed (grant is in-memory, service alive) |
| Blocking paused | Everything plays, no overlay anywhere |

Stats check:

```sh
adb shell run-as dev.niranjan.reelsblocker cat /data/data/dev.niranjan.reelsblocker/shared_prefs/reelsblocker.xml
```

`blockedToday` should increment on the swipe-revoke, once per reel session, not per overlay flicker.

## Accepted risks

- **The loop is open by design**: back out, tap the next feed reel, repeat. Bounded only by the friction of doing it.
- **Origin lost on service restart** blocks a feed reel that should have been allowed. Fail-closed; reopening fixes it.
- **`typeViewScrolled` is system-wide** (no `packageNames` filter). Mitigated by handling scrolls before any tree work.
- **Never reintroduce auto-navigation.** No `GLOBAL_ACTION_BACK`, no programmatic tab clicks outside the user-tapped exit button — an earlier navigation-based version randomly closed Instagram.
