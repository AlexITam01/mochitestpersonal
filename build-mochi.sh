#!/usr/bin/env bash
# build-mochi.sh — reproduce the Mochi-New.apk test build (Mochi 1.1.11).
# Run on a Linux machine with: java 17, apktool, Android build-tools 34
# (aapt, apksigner, zipalign), python3, unzip.
# Inputs (place next to this script, or set the vars below):
#   - Mochi 1.1.11 full XAPK (APKPure) containing base + config.arm64_v8a
#   - patch_bundle.py (in this repo) — applies the blank-screen byte patch
#     to the stock bundle from the XAPK; no pre-patched binary is shipped.
#   - a signing keystore (any key; original Play signature is unavailable)
set -euo pipefail

XAPK="${XAPK:-Mochi-1.1.11-full.apk}"
KEYSTORE="${KEYSTORE:-$HOME/.mochi-signing.keystore}"
KEY_ALIAS="${KEY_ALIAS:-mochi}"
APKTOOL="${APKTOOL:-apktool}"
BT="${BUILD_TOOLS:-$HOME/android-sdk/build-tools/34.0.0}"

WORK="$(pwd)/mochi-build"
rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"

echo "[1] Unpacking XAPK"
unzip -o -q "$OLDPWD/$XAPK"
# base APK is com.mochitv.mochi.apk; native libs are in config.arm64_v8a.apk
BASE_APK="com.mochitv.mochi.apk"

echo "[2] Decoding base APK"
"$APKTOOL" d -f "$BASE_APK" -o mochi-decoded

echo "[3] Applying blank-screen patch to the stock bundle"
python3 "$OLDPWD/patch_bundle.py" mochi-decoded/assets/index.android.bundle mochi-decoded/assets/index.android.bundle.patched
mv mochi-decoded/assets/index.android.bundle.patched mochi-decoded/assets/index.android.bundle

echo "[4] Removing requiredSplitTypes (prevents apktool adding isSplitRequired)"
python3 - "$WORK/mochi-decoded/AndroidManifest.xml" <<'PY'
import re, sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
s = s.replace(' android:requiredSplitTypes="base__abi,base__density"', "")
open(p, "w", encoding="utf-8").write(s)
PY

echo "[5] Merging native libs (config.arm64_v8a) and marking .so as STORED"
mkdir -p /tmp/mochi-lib && unzip -o -q config.arm64_v8a.apk "lib/*" -d /tmp/mochi-lib
mkdir -p mochi-decoded/lib
cp -r /tmp/mochi-lib/lib/* mochi-decoded/lib/
# apktool must store .so uncompressed (extractNativeLibs=false)
python3 - "$WORK/mochi-decoded/apktool.yml" <<'PY'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
if "\n- so\n" not in s:
    s = s.replace("doNotCompress:\n- arsc", "doNotCompress:\n- arsc\n- so", 1)
open(p, "w", encoding="utf-8").write(s)
PY

echo "[6] Rebuilding"
"$APKTOOL" b mochi-decoded -o Mochi-New-unsigned.apk

echo "[7] Zipalign + sign"
"$BT/zipalign" -f -p 4 Mochi-New-unsigned.apk Mochi-New-aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
  --out Mochi-New.apk Mochi-New-aligned.apk
"$BT/apksigner" verify Mochi-New.apk
"$BT/zipalign" -c -p 4 Mochi-New.apk

echo "[8] Verify patched bundle"
unzip -p Mochi-New.apk assets/index.android.bundle | md5sum
echo "Done: $WORK/Mochi-New.apk"
echo "Uninstall the original Mochi first (different signature), then install."
