# Mochi Download — floating download-button overlay

A separate app (its own APK, its own signature) that puts a floating,
draggable, transparent download button on top of Mochi. It does **not**
modify Mochi, does not patch Mochi, and does not touch Mochi's signature —
so it cannot trip Play Integrity the way a re-signed Mochi build does.

Package: `com.alex.mochidownload` · App name: "Mochi Download" · minSdk 26.

## How it behaves (exactly what Alex asked for)

1. **Runs concurrently with Mochi.** A foreground service + overlay window,
   alive while Mochi is in the foreground. The button is only drawn over
   Mochi — nowhere else.
2. **Long-press to position.** Long-press the button (~0.5s) to enter
   positioning mode (it gets a dashed white outline), drag it where it
   should go, and **on release the position is recorded and persisted**
   (SharedPreferences). No separate save step. Tap the button in positioning
   mode to lock it without moving.
3. **Closes with Mochi, reopens at the same spot.** When Mochi closes, the
   overlay window is removed. When Mochi opens again, the button reopens
   automatically at that same recorded position — back on the same spot
   where it was left. The service keeps a lightweight watch (UsageStats
   poll) so it can re-show the button, and the AccessibilityService also
   nudges the service awake when Mochi comes to the foreground.
4. **Alex drives Mochi himself.** The overlay never taps, swipes, scrolls,
   or navigates Mochi for him. He taps/swipes/scrolls to the video he
   wants; the button just sits there waiting.
5. **Tap = download.** Tap the button (when locked) to download the video
   Mochi is currently playing, saved to the device's `Downloads/Mochi/`
   folder. See the limits below.

## Suckerfish anchoring (latching onto Mochi's UI)

The button does not just float free — it latches onto Mochi's interface like
a suckerfish. The AccessibilityService reads Mochi's window/UI hierarchy and
finds the video player's action column (the like / comment / share buttons
on the right side). The overlay then sits just below the bottom-most action
button, centred on it, so it reads as a native fourth action button.

- It **tracks** the anchor: if Mochi's layout shifts (e.g. scrolling to the
  next video), the button re-anchors to the new position of the action
  column.
- **Long-press + drag sets Alex's preferred offset** from the anchor. That
  offset is saved, so the button keeps the same relationship to the action
  column even when the column moves.
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

## The download — what it can and cannot do (read this)

Mochi plays video from Mux as HLS: `https://stream.mux.com/<playbackId>.m3u8`.
The `playbackId` lives inside Mochi's app state and in its HTTPS traffic.
A separate overlay app **cannot** read either of those:

- An AccessibilityService can only read what Mochi puts on screen (titles,
  labels, element bounds). The stream URL is not on screen.
- A plain local VPN / packet capture can see that Mochi talks to
  `stream.mux.com`, but HTTPS encrypts the URL path, so the playbackId is
  hidden. Seeing the full URL would need a man-in-the-middle with a
  user-installed CA certificate, and Mochi would very likely block that with
  certificate pinning. This app deliberately does **not** do MITM.

So a tap can only download when a playbackId is actually obtainable:

1. The AccessibilityService saw a playbackId / Mux URL on screen, or
2. Mochi showed a `mochi.tv/watch/<id>` link and the public watch page
   contains the stream URL.

When a playbackId is known, the app first tries the Mux MP4 rendition
(`https://stream.mux.com/<id>/highest.mp4`). If that is not available it
downloads the HLS playlist (`<id>.m3u8`) and says so — the playlist plays in
VLC and can be converted to MP4. Files go to `Downloads/Mochi/`.

**If no link can be resolved, the app says so plainly** (toast + a
notification naming the video title) and does not pretend a download
happened. The reliable way to capture a Mochi video remains **PCAPdroid**:
run it while the video plays, find the `stream.mux.com` request, and save
the file. The overlay's job is to make the button easy to reach; it cannot
conjure a URL Mochi keeps hidden.

## Files

- `app/src/main/java/com/alex/mochidownload/`
  - `MainActivity.kt` — setup screen + three permission shortcuts + status.
  - `OverlayService.kt` — foreground service; draws the button; latches it
    to the action-column anchor and re-anchors on layout shifts; long-press
    + drag + release records the position; closes with Mochi and reopens at
    the recorded spot; tap triggers the download.
  - `MochiAccessibilityService.kt` — reads Mochi's visible UI for a title /
    watch link / playbackId, finds the action-column anchor (bounds), and
    wakes the overlay service when Mochi comes to the foreground. Read-only:
    it never taps or navigates Mochi.
  - `MochiState.kt` — shared best-effort snapshot of what Mochi is showing,
    including the anchor bounds.
  - `VideoDownloader.kt` — resolves a playbackId (screen, or watch page)
    and downloads via DownloadManager to `Downloads/Mochi/`; honest failure
    reporting.
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
under the action column). Long-press it to move it; release to lock the
spot. Tap it to download the video showing.

## What this is NOT

- It does not modify, re-sign, or patch the Mochi APK.
- It does not bypass Play Integrity.
- It does not MITM Mochi's traffic and does not install a CA certificate.
- It does not navigate Mochi for Alex.
- It does not fake a download: if the video URL cannot be resolved, it says
  so instead of showing a fake success.
