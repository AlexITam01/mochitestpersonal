# Mochi Download — floating download-button overlay

A separate app (its own APK, its own signature) that puts a floating,
draggable, transparent download button on top of Mochi. It does **not**
modify Mochi, does not patch Mochi, and does not touch Mochi's signature —
so it cannot trip Play Integrity the way a re-signed Mochi build does.

Package: `com.alex.mochidownload` · App name: "Mochi Download" · minSdk 26.

## How it behaves (exactly what Alex asked for)

1. **Runs concurrently with Mochi.** A foreground service + overlay window,
   alive while Mochi is in the foreground. The button is only drawn over
   Mochi — nowhere else. Foreground is detected with UsageStats *events*
   (MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND) plus the AccessibilityService's
   window-state signal — **not** with `lastTimeUsed`, which goes stale
   within seconds while an app is still in front and used to make the
   button vanish ~3s after Mochi opened. The button is only removed when a
   different app is *positively* known to be in front; "unknown" leaves it
   alone, and it is never removed while Alex is positioning it.
2. **Positioning is two steps.** Long-press the button (~0.5s) to enter
   positioning mode (it gets a dashed white outline), drag it where it
   should go, and **on release the position is recorded and persisted**
   (SharedPreferences) — but it is NOT locked yet. It stays dimmed with
   the dashed outline ("placed, not confirmed") and tapping it only
   reminds Alex to confirm. He then opens the Mochi Download app and
   presses **"Lock position"** — only then does the button become the
   fixed, pressable download button (full opacity, no outline). Once
   locked it is NOT draggable any more: long-press does nothing. To move
   it again he presses **"Move button"** in the app, which unlocks it and
   re-enters positioning mode; he drags it to a new spot and confirms
   again. States: (a) positioning — draggable, not a download button;
   (b) confirmed/locked — fixed, pressable, tap = download; (c) reposition
   — via the app's Move control, back to (a). The app's setup screen shows
   which state the button is in (none placed / placed, not confirmed /
   locked, with coordinates) and enables only the button that applies.
   A long-press can no longer make the button disappear: the visibility
   poll never removes the button while it is being positioned, and the
   foreground check no longer goes stale. The button is 51dp across
   (~80% of the original 64dp — scaled down ~20% at Alex's request).
3. **Closes with Mochi, reopens at the same spot.** When Mochi closes, the
   overlay window is removed. When Mochi opens again, the button reopens
   automatically at that same recorded position — back on the same spot
   where it was left. The service keeps a lightweight watch (UsageStats
   poll) so it can re-show the button, and the AccessibilityService also
   nudges the service awake when Mochi comes to the foreground.
   **A position Alex sets by dragging is authoritative**: once he has
   placed the button, it always comes back to that exact spot. The
   action-column anchor is only used for the first-run placement (before
   he has ever positioned the button) — it never drags the button away
   from a spot he chose.
4. **Alex drives Mochi himself.** The overlay never taps, swipes, scrolls,
   or navigates Mochi for him. He taps/swipes/scrolls to the video he
   wants; the button just sits there waiting. The ONE exception is the
   download itself (see below): when he taps the locked button, the
   AccessibilityService taps Mochi's Share button on his behalf to get
   the video's link. That is the only time the service ever taps anything.
5. **Tap (locked) = download.** Tap the locked button to download the
   video Mochi is currently playing, saved to the device's
   `Downloads/Mochi/` folder — or, if Mochi's own Download is used, saved
   to Photos by Mochi itself. See the download section below.

## Suckerfish anchoring (latching onto Mochi's UI)

The button does not just float free — it latches onto Mochi's interface like
a suckerfish. The AccessibilityService reads Mochi's window/UI hierarchy and
finds the video player's action column (the like / comment / share buttons
on the right side). The overlay then sits just below the bottom-most action
button, centred on it, so it reads as a native fourth action button.

- It **tracks** the anchor: if Mochi's layout shifts (e.g. scrolling to the
  next video), the button re-anchors to the new position of the action
  column. **This tracking only applies before Alex has ever positioned the
  button himself.** Once he long-press + drags it, his saved spot wins and
  the anchor stops moving the button — that is the fix for the button not
  remembering where he put it.
- **Long-press + drag sets Alex's preferred position.** That absolute spot
  is saved and always restored, so the button keeps the same place even
  when the action column moves.
- If no action column is visible on a screen, the button falls back to its
  recorded absolute position (free-floating) until an anchor appears again.

Honest limit: this is still an overlay window drawn by a separate app — it
cannot literally become part of Mochi's view hierarchy (that would require
modifying Mochi). It *looks* native because it sits next to Mochi's own
buttons and moves with them, but it is a separate window on top.

## Setup (all three are granted by Alex in Settings)

1. **"Draw over other apps"** — lets the button sit on Mochi.
2. **"Usage access"** — lets the app tell when Mochi is in the foreground.
3. **Accessibility Service ("Mochi Download")** — lets the app read the
   video title and find the action-column anchor (and wake the overlay when
   Mochi opens).

The MainActivity has a button for each and a live status readout.

## The download — how a tap actually gets the video

Mochi plays video from Mux as HLS: `https://stream.mux.com/<playbackId>.m3u8`.
The `playbackId` lives inside Mochi's app state and in its HTTPS traffic.
A separate overlay app **cannot** read either of those directly — so the
previous build could only download when a link happened to be visible on
screen, which in practice was almost never. That is why the download was
"still flawed". This build fixes it by using Mochi's own Share flow, which
is Mochi's own feature for surfacing a video's link.

Tap flow (in order — first success wins):

1. **A playbackId already seen on screen** (rare). Download it.
2. **A mochi.tv/watch link already seen on screen.** Fetch the public
   watch page and extract the Mux playbackId from it.
3. **Mochi's Share flow (the normal path).** The AccessibilityService taps
   Mochi's Share button in the player action column, then works the share
   sheet that opens:
   - If the sheet has Mochi's own **"Download video"**, it taps it.
     Mochi then downloads the episode itself and saves it to Photos
     ("Saved to your Photos"). No URL is needed at all — this is the best
     outcome. (Mochi's own download can be refused per video — "Downloads
     are off" — in which case the flow carries on to the link.)
   - Otherwise it taps **"Copy link"** and reads the copied link from the
     clipboard, then fetches that watch page as in (2).
   - Otherwise, if the sheet shows a mochi.tv/watch (or Mux) URL as text,
     that is used directly.
   - The sheet is dismissed with one BACK afterwards, so Alex is back to
     watching.
   When a playbackId is known, the app first tries the Mux MP4 rendition
   (`https://stream.mux.com/<id>/highest.mp4`). If that is not available
   it downloads the HLS playlist (`<id>.m3u8`) and says so — the playlist
   plays in VLC and can be converted to MP4. Files go to `Downloads/Mochi/`.

**If a step fails, the app says exactly which step failed** (toast + a
notification with the step code): `share_button_not_found` (no Share button
on this screen), `share_sheet_timeout` (the share menu did not open),
`no_share_option` (the menu had no Download or Copy link), `clipboard_empty`
("Copy link" was tapped but the link could not be read back — Android
restricts background clipboard reads), `watch_page_failed` (the share
page could not be loaded), `watch_page_no_stream` (the page loaded but
had no stream link in it), `a11y_not_enabled`, `mochi_not_foreground`,
`native_download_unavailable` (Mochi refused its own download). It never
pretends a download happened when it did not.

Honest limits that remain:
- The Share flow is the only way the overlay can learn the video's link.
  If Mochi changes its share menu so it has neither Download nor Copy
  link, the overlay cannot resolve the video.
- The watch page is fetched as plain HTML. If Mochi ever makes the watch
  page require sign-in or JavaScript to reveal the stream URL, the page
  fetch will find nothing and report `watch_page_no_stream`.
- Mochi's own "Download video" appears to be for the creator's own
  published episodes (`downloadMyPublished…` in the bundle); it may not be
  offered for every video. When it is offered it is the most reliable
  path, because Mochi does the download itself.
- No MITM, no root, no Mochi modification: the app deliberately does not
  intercept Mochi's HTTPS traffic (it would need a user CA certificate and
  Mochi would likely block it with certificate pinning).

The reliable fallback is still **PCAPdroid**: run it while the video plays,
find the `stream.mux.com` request, and save the file.

## Files

- `app/src/main/java/com/alex/mochidownload/`
  - `MainActivity.kt` — setup screen + three permission shortcuts + status
    + the **Lock position** / **Move button** controls for the two-step
    confirm flow.
  - `OverlayService.kt` — foreground service; draws the button; latches it
    to the action-column anchor and re-anchors on layout shifts; long-press
    + drag + release records the position as PENDING; the app's Lock button
    confirms it (locked, pressable, not draggable) and Move re-enters
    positioning; closes with Mochi and reopens at the recorded spot; tap
    on the locked button triggers the download.
  - `MochiAccessibilityService.kt` — reads Mochi's visible UI for a title /
    watch link / playbackId, finds the action-column anchor (bounds), and
    wakes the overlay service when Mochi comes to the foreground. Read-only
    EXCEPT inside a user-started share flow, where it taps Mochi's Share
    button and then the share sheet's Download video / Copy link on Alex's
    behalf (see the download section). It never taps or navigates Mochi at
    any other time. Its config deliberately has no `packageNames`
    restriction, because the system share sheet can live in another
    package — the service filters in code and only scans other packages'
    windows while a share flow is active.
  - `MochiState.kt` — shared best-effort snapshot of what Mochi is showing,
    including the anchor bounds, plus the share-flow handshake (a latch
    the downloader waits on for the flow's single result string).
  - `VideoDownloader.kt` — resolves a video (screen link, watch page, or
    the share flow) and downloads via DownloadManager to `Downloads/Mochi/`;
    step-specific failure reporting (`share_button_not_found`,
    `share_sheet_timeout`, `no_share_option`, `clipboard_empty`,
    `watch_page_failed`, `watch_page_no_stream`, …).
  - `BootReceiver.kt` — restores the overlay after a reboot if enabled.
- `app/src/main/res/drawable/download_button.png` — the white download icon
  (512×512, downscaled from the 1934×1934 original). **Note:** this PNG is a
  binary asset. The GitHub connector push is text-only, so the PNG is not
  committed here — copy it into `app/src/main/res/drawable/` before building
  (it is the same file as `~/workspace/your_files/download-icon-white.png`,
  downscaled to 512×512). The delivered APK already contains it.
- `build-direct.sh` — builds the APK on this VM with kotlinc + aapt2 + d8 +
  apksigner (Gradle cannot download dependencies here).

## Build

Direct (this VM):

```sh
./build-direct.sh
# -> app/build/outputs/apk/debug/app-debug.apk
```

The script signs with the shared `legalpal` keystore
(`~/workspace/legal-pal/legalpal.keystore`; password in
`~/workspace/legal-pal/signing-password.txt` — never committed).

Gradle / CI: the `build.gradle.kts` files are kept so the project also builds
with `./gradlew assembleDebug` where the network is unrestricted.

## Install

Install the APK, open "Mochi Download", grant the three items, tap
"Start overlay", then open Mochi. The button appears over Mochi (latched
under the action column). Long-press it to move it; release to place it
(it stays dimmed); come back here and press **"Lock position"** to make
it the download button. Tap the locked button in Mochi to download the
video showing. To move it later, press **"Move button"** here, drag it to
a new spot, and lock it again.

**If the button disappears or won't stay put, check Usage access first:**
Settings → Apps → Mochi Download → Usage access (or Settings → Privacy →
Permission manager → Usage access) must show Mochi Download as allowed.
Without it the app cannot tell Mochi is in the foreground. The app's own
status screen also shows "Usage access: granted / NOT granted" — confirm
it says granted before reporting a bug.

## Fixes (2026-11-19) — first test round

Alex tested v1.0 and reported: the button vanished ~3s after Mochi opened;
long-pressing made it disappear; it did not remember its position; and it
was ~20% oversized. Root causes and fixes, all in `OverlayService.kt`:

- **Vanishes in ~3s:** foreground was detected with
  `queryUsageStats()` + `lastTimeUsed < 3000ms`. `lastTimeUsed` is only
  refreshed when an app's usage session is recorded, so it goes stale
  within seconds while the app is still in front — the poll then concluded
  Mochi was gone and removed the button. Fixed by switching to UsageStats
  *events* (`queryEvents`, MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND), which
  report the actual transitions and stay correct indefinitely, plus using
  the AccessibilityService's window-state signal as a second source.
- **Long-press disappears:** the same stale poll removed the button while
  Alex was holding it. Fixed: the poll never removes the button on
  "unknown" foreground and never removes it while positioning (edit mode).
- **Position not remembered:** two causes — the 3s bug removed the button
  before release, so the position was never saved; and even when saved,
  the anchor + offset took priority over the saved absolute spot, so the
  button snapped back to the anchor. Fixed: a dragged position is now
  authoritative (restored first, anchor tracking disabled once one is
  saved); the anchor is only the first-run placement.
- **Oversized ~20%:** button reduced from 64dp to 51dp (~80% of the
  original size).

## Fixes (2026-11-19) — download that actually works + two-step confirm

Alex confirmed the overlay itself works (button stays, position
remembered, size right) but "the download is still flawed". Root cause:
the overlay could only download when a playbackId or watch link happened
to be visible on screen — which Mochi essentially never shows — so a tap
almost always ended in "no link found". Fixed by using Mochi's own Share
flow: the AccessibilityService now taps Mochi's Share button when Alex
taps the locked download button, then in the share sheet taps Mochi's own
"Download video" (Mochi saves it to Photos itself — no URL needed) or
"Copy link" and resolves that link via the public watch page. Failure
reporting is now step-specific (see the download section for the codes),
so a failed tap says exactly which step failed instead of a generic "no
link found". Mochi's bundle also shows a native `startEpisodeDownload`
(render + save to Photos, "Downloads are off" per video) — the sheet tap
is how the overlay reaches it.

Positioning is now two steps, at Alex's request: release after a drag
records the spot as PENDING (dimmed, dashed outline, not a download
button yet) instead of auto-locking. The app's **"Lock position"** button
confirms it — only then is the button fixed and pressable (tap =
download, long-press does nothing). **"Move button"** in the app unlocks
it and re-enters positioning mode; drag to a new spot and confirm again.
The app's setup screen shows the state (none placed / placed, not
confirmed / locked, with coordinates) and enables only the button that
applies. All in `OverlayService.kt` (confirm state + three-state visuals),
`MainActivity.kt` + layout/strings (Lock / Move controls + status), and
`VideoDownloader.kt` + `MochiAccessibilityService.kt` + `MochiState.kt`
(share flow). The accessibility config no longer restricts `packageNames`,
because the system share sheet can live in another package; the service
filters in code and only scans other packages' windows during a
user-started share flow.

## What this is NOT

- It does not modify, re-sign, or patch the Mochi APK.
- It does not bypass Play Integrity.
- It does not MITM Mochi's traffic and does not install a CA certificate.
- It does not navigate Mochi for Alex.
- It does not fake a download: if the video URL cannot be resolved, it says
  so instead of showing a fake success.
