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
   wants; the button just sits there waiting. The download itself is
   resolved through Mochi's public API from what is already on screen
   (title / creator / episode number) — no tapping inside Mochi. The ONE
   exception is the Share-flow fallback (see below): when the API cannot
   identify the video, the AccessibilityService taps Mochi's Share button
   on his behalf to get the video's link. That is the only time the
   service ever taps anything.
5. **Tap (locked) = download.** Tap the locked button to download the
   video Mochi is currently playing, saved as one .mp4 to the device's
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

Mochi plays video from Mux as HLS. The video's stream URL lives inside
Mochi's app state and in its HTTPS traffic. A separate overlay app cannot
read either of those directly — and a plain VPN cannot either, because
the URL path is inside HTTPS (a VPN sees `stream.mux.com`, not the
playback id). So the overlay resolves the playing video through Mochi's
own public read API instead: the AccessibilityService reads the title /
creator / episode number from the player UI, and the API turns that
into the signed stream URL.

**The source, verified live 2026-11-19 (no login, no auth header):**
Mochi's mobile API is public for reads. `POST /api/mobile/for-you`
(body `{}`) returns the For You feed, and every episode already carries
a signed `playbackUrl` — a Mux `.m3u8` URL with a `?token=` query
param. `GET /api/mobile/search?q=<title>` finds a series (id, slug,
creator). `GET /api/mobile/lookup-episode/<seriesSlug>/<episodeNumber>`
resolves a series + episode to an episode id.
`GET /api/mobile/episodes/<episodeId>/play` returns the signed
playbackUrl for that episode. These are the same endpoints the Mochi
app itself calls to play a video.

**Why the old download was flawed:** the old build constructed a bare
Mux URL from a playback id (`https://stream.mux.com/<id>/highest.mp4`).
That does not work for Mochi's signed playback ids — the static MP4
returns 404, and even `.../highest.mp4?token=<signed JWT>` returns 403.
The only working source is the signed `.m3u8` from `/play` (or from the
feed). And an `.m3u8` is an HLS playlist, not a file — so the overlay
downloads the stream itself: it fetches the master playlist, picks the
highest-bandwidth video rendition (1080p) plus the audio rendition,
downloads the fMP4 init segment + `.m4s` media segments in parallel,
and remuxes them with Android's built-in `MediaExtractor` + `MediaMuxer`
into ONE playable `.mp4` with audio and video. No ffmpeg, no root, no
extra dependency. The video rendition is video-only and the audio
rendition is audio-only, so the remux is what puts sound back with the
picture. If a rendition ever carries both tracks, the remux takes the
audio from the video file instead. The result is saved to
`Downloads/Mochi/<Series Title> - E<number>.mp4` (via MediaStore).

Tap flow (in order — first success wins):

1. **A signed playback URL already seen on screen** (rare). Download it.
2. **For You feed match (the normal no-Share path).** Fetch the public
   For You feed and match the on-screen series title (+ creator, +
   episode number when shown) to a feed episode; take that episode's
   signed playbackUrl and download the stream.
3. **Title search + lookup.** Search the on-screen title, take the series
   slug, resolve (slug, episode number) to an episode id, then `/play`
   for the signed playbackUrl. Needs the episode number from the UI; if
   the player is not showing one, this step is skipped.
4. **An episode id already seen on screen** -> `/play` -> playbackUrl.
5. **A mochi.tv/watch link already seen on screen**
   (`mochi.tv/watch/<seriesSlug>/<episodeNumber>`) -> lookup ->
   `/play` -> playbackUrl.
6. **Mochi's Share flow (the fallback).** The AccessibilityService taps
   Mochi's Share button in the player action column, then works the share
   sheet that opens:
   - If the sheet has Mochi's own **"Download video"**, it taps it.
     Mochi then downloads the episode itself and saves it to Photos
     ("Saved to your Photos"). No URL is needed at all — this is the best
     outcome. (Mochi's own download can be refused per video — "Downloads
     are off" — in which case the flow carries on to the link.)
   - Otherwise it taps **"Copy link"** and reads the copied link from the
     clipboard, then resolves that watch link through the API as in (5).
   - Otherwise, if the sheet shows a mochi.tv/watch (or Mux) URL as text,
     that is used directly.
   - The sheet is dismissed with one BACK afterwards, so Alex is back to
     watching.

**If a step fails, the app says exactly which step failed** (toast + a
notification with the step code): `no_title` (no video title is visible,
so the playing video cannot be identified), `feed_no_match` (the For You
feed has no episode with this title — the guest feed is only the first
page), `feed_ambiguous`, `episode_number_unknown` (the series was found
but the player is not showing which episode it is), `lookup_failed`,
`play_failed`, `api_failed` (Mochi's API could not be reached),
`hls_master_failed` / `hls_no_rendition` / `hls_rendition_failed` /
`hls_no_segments` / `hls_segment_failed` / `hls_remux_failed` (the stream
download failed at that step), `save_failed` (the .mp4 was built but
could not be put into Downloads/Mochi/), `share_button_not_found` (no
Share button on this screen), `share_sheet_timeout` (the share menu did
not open), `no_share_option` (the menu had no Download or Copy link),
`clipboard_empty` ("Copy link" was tapped but the link could not be read
back — Android restricts background clipboard reads),
`watch_page_no_stream` (the share link gave no stream URL),
`a11y_not_enabled`, `mochi_not_foreground`,
`native_download_unavailable` (Mochi refused its own download),
`mux_id_unresolved` (the share sheet gave a bare Mux id that could not
be matched to an episode). It never pretends a download happened when
it did not.

Honest limits that remain:
- The For You feed match uses the public (guest) feed, which is only the
  first page of episodes. If the video Alex is watching is not in that
  page, the feed match fails and the overlay falls through to search +
  lookup (which needs the episode number from the UI) and then to the
  Share flow. The Share flow is the most reliable identification, because
  Mochi itself hands over the exact link.
- The episode number is only used when the player shows it. If a series
  has several episodes and the player shows no episode number, the
  overlay cannot tell which one is playing from the title alone — it
  says `episode_number_unknown` and falls back to the Share flow.
- Mochi's own "Download video" appears to be for the creator's own
  published episodes (`downloadMyPublished…` in the bundle); it may not be
  offered for every video. When it is offered it is the most reliable
  path, because Mochi does the download itself.
- No MITM, no root, no Mochi modification: the app deliberately does not
  intercept Mochi's HTTPS traffic (it would need a user CA certificate
  and Mochi would likely block it with certificate pinning). The stream
  URL comes from Mochi's public API, not from its traffic.
- The signed playback URL expires (playbackExpiresAt, roughly a day), so
  a resolved URL is used immediately on the same tap, never stored.

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
    including the anchor bounds, the on-screen title / creator / episode
    number / watch link / playback id, plus the share-flow handshake (a
    latch the downloader waits on for the flow's single result string).
  - `MochiApi.kt` — Mochi's public mobile API (no login): For You feed,
    series search, episode lookup, and the /play endpoint that returns
    the signed playback URL. All verified live; see the file header for
    the exact endpoints and what each returns.
  - `HlsDownloader.kt` — downloads a signed Mux HLS stream (master
    playlist -> best video rendition + audio rendition -> fMP4 segments)
    and remuxes it with MediaExtractor + MediaMuxer into one .mp4.
    Step-specific failure codes (`hls_master_failed`, `hls_no_rendition`,
    `hls_rendition_failed`, `hls_no_segments`, `hls_segment_failed`,
    `hls_remux_failed`).
  - `VideoDownloader.kt` — resolves the playing video (API first: feed
    match, search + lookup, on-screen id/link; Share flow as fallback)
    and downloads the signed stream via HlsDownloader, saving to
    `Downloads/Mochi/` through MediaStore; step-specific failure
    reporting (`no_title`, `feed_no_match`, `episode_number_unknown`,
    `lookup_failed`, `play_failed`, `api_failed`, `hls_*`,
    `save_failed`, `share_button_not_found`, …).
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

## Fixes (2026-11-19) — playback-time capture (download the video that is playing)

Alex confirmed the overlay itself works but "the download is still flawed",
and clarified the video IS downloadable at playback time (it plays like
Instagram Reels, but there is a source when it plays). Root cause: the
overlay constructed a bare Mux MP4 URL from a playback id — and that URL
does not work for Mochi's signed playback ids (static MP4 is 404, and
even with a signed token it is 403). The only working source is the signed
HLS playlist Mochi's own player uses, and the overlay never fetched it.

Fixed by resolving the playing video through Mochi's public read API
(no login) and downloading the HLS stream itself:
- `MochiApi.kt` (new): For You feed (each episode already carries its
  signed playbackUrl), series search, episode lookup, and the /play
  endpoint. Verified live with real episode ids; see the file header.
- `HlsDownloader.kt` (new): fetches the signed master playlist, picks
  the highest video rendition + the audio rendition, downloads the fMP4
  segments in parallel, and remuxes them with MediaExtractor + MediaMuxer
  into one .mp4 with audio and video. No ffmpeg, no root, no MITM.
- `VideoDownloader.kt`: new resolution order — on-screen playback URL,
  For You feed match, title search + lookup, on-screen episode id / watch
  link, then the Share flow as fallback. The bare-Mux-URL download is
  gone (it silently produced nothing). Files are saved to
  `Downloads/Mochi/` via MediaStore. Failure codes are step-specific and
  extended for the API path (`no_title`, `feed_no_match`,
  `episode_number_unknown`, `lookup_failed`, `play_failed`, `api_failed`,
  `hls_*`, `save_failed`, `mux_id_unresolved`).
- `MochiAccessibilityService.kt` / `MochiState.kt`: the service now also
  captures the creator handle (`@username`) and the episode number
  ("Episode 5" / "Ep 5" / "E5") from the player UI, which the API
  resolution needs to identify the playing episode. The watch-link
  format was corrected to `mochi.tv/watch/<seriesSlug>/<episodeNumber>`
  (two path segments); the old single-segment parse was wrong for it.
- The two-step confirm flow, 51dp button, positioning, and all overlay
  behaviour are unchanged.

## What this is NOT

- It does not modify, re-sign, or patch the Mochi APK.
- It does not bypass Play Integrity.
- It does not MITM Mochi's traffic and does not install a CA certificate.
  The stream URL comes from Mochi's public API, not from intercepting
  traffic — and a plain VPN could not see it anyway, because the URL
  path is inside HTTPS.
- It does not navigate Mochi for Alex.
- It does not screen-record (MediaProjection) as a substitute for
  downloading the file.
- It does not fake a download: if the video URL cannot be resolved, it says
  so instead of showing a fake success.
