# Mochi APK — test build (Mochi-New)

Patched Mochi 1.1.11 (`com.mochitv.mochi`, versionCode 74) for testing the
"Generate Your Own Series" blank-screen fix. Built 2026-11-18.

## What this build changes

- **Blank-screen fix (the only code patch).** The app asks Mochi's server for a
  creation draft; when the server returns no data, the app throws
  `Error("getMySeriesCreationDraft returned no data")` and the screen has no
  error UI, so it renders blank dark gray. The patch neutralizes that throw
  so the screen gets an empty series instead. Whether the form renders with
  an empty series is unverified on a device.
- **No gem/coin change.** Gems and coins are validated server-side
  (`gemBalance`/`coinBalance` are never assigned locally; spend returns
  `insufficient_coins`). Nothing here fakes them.
- **No in-app Download button.** Adding a new button needs new Hermes
  bytecode instructions; there is no assembler/round-trip for Hermes v98,
  so new UI code cannot be added. The white icon (`download-icon-white.png`)
  is included as an asset only.

## Files

- `patch_bundle.py` — applies the blank-screen byte patch to the stock
  Hermes v98 bundle (same-length replacement, 28 bytes). Verified to
  reproduce the patched bundle (MD5 `78f5eaa415c3142e5da62f6ef0339c03`).
  Only change vs. the stock bundle: the `getMySeriesCreationDraft` throw is
  neutralized (29-byte window at offsets 6730527–6730555).
- `build-mochi.sh` — reproduces the full build from the stock APK.

## Build (see build-mochi.sh)

1. Get the full Mochi 1.1.11 XAPK (APKPure) — it contains the base APK plus
   `config.arm64_v8a` (native libs), `config.en`, `config.mdpi`. The base APK
   alone has no `.so` files and will crash on launch.
2. Decode the base APK with apktool; copy in the patched bundle; remove
   `android:requiredSplitTypes` from `AndroidManifest.xml` (apktool otherwise
   adds `isSplitRequired=true`, causing `INSTALL_FAILED_MISSING_SPLIT`).
3. Merge `lib/arm64-v8a/*.so` from `config.arm64_v8a.apk` into the decoded
   tree and add `so` to `apktool.yml` `doNotCompress` — the app sets
   `extractNativeLibs=false`, so the `.so` files must be STORED (uncompressed)
   and page-aligned.
4. Rebuild with apktool, `zipalign -f -p 4`, sign (any keystore; the original
   Play signature is not available, so updates need an uninstall first).

## Install

Uninstall the original Mochi first (different signature), then install the
built APK. The app is expected to show a Play Store / integrity message
because the signature differs from the Play Store build.
