#!/usr/bin/env bash
# Direct (no-Gradle) build for Mochi Download overlay.
# Used because this VM's egress proxy blocks Java/Gradle downloads, so
# Gradle cannot fetch the Android Gradle Plugin or libraries here.
# The Gradle project files are kept for CI (GitHub Actions), where the
# network is unrestricted. This script builds the exact same sources
# with kotlinc + aapt2 + d8 + apksigner.
#
# Usage: ./build-direct.sh   (run from ~/workspace/mochi/repo/mochi-overlay)
# Output: app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$HOME/workspace/legal-pal/tools/android-sdk"
BT="$SDK/build-tools/34.0.0/android-14"
PLATFORM="$SDK/platforms/android-34/android.jar"
KOTLINC="$HOME/workspace/.sdk/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$HOME/workspace/.sdk/kotlinc/lib/kotlin-stdlib.jar"

OUT="$ROOT/app/build/outputs/apk/debug"
WORK="$ROOT/app/build/direct"
rm -rf "$WORK" "$OUT"
mkdir -p "$WORK/compiled-res" "$WORK/classes" "$OUT"

export PATH="$HOME/workspace/.sdk/jdk17/bin:$PATH"

# 1. Compile resources -> .flat files.
find "$ROOT/app/src/main/res" -type f | while read -r f; do
    "$BT/aapt2" compile -o "$WORK/compiled-res" "$f"
done

# 2. Link resources + manifest -> unsigned APK + R.java.
"$BT/aapt2" link \
    -o "$WORK/app-unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
    --java "$WORK/generated" \
    --version-code 1 \
    --version-name "1.0" \
    --auto-add-overlay \
    "$WORK/compiled-res"/*.flat

# 3. Compile Kotlin (+ generated R.java) against android.jar.
KOTLIN_FILES=$(find "$ROOT/app/src/main/java" -name '*.kt')
R_JAVA=$(find "$WORK/generated" -name 'R.java')
"$KOTLINC" -cp "$PLATFORM" -d "$WORK/classes" $KOTLIN_FILES $R_JAVA

# 4. Dex: app classes + Kotlin stdlib (needed at runtime).
"$BT/d8" --release --output "$WORK" \
    --lib "$PLATFORM" \
    $(find "$WORK/classes" -name '*.class') \
    "$KOTLIN_STDLIB"

# 5. Add classes.dex to the APK.
cp "$WORK/app-unsigned.apk" "$WORK/app-debug.apk"
cd "$WORK" && zip -q app-debug.apk classes.dex && cd "$ROOT"

# 6. Sign with the shared legalpal keystore.
#    Password lives only in ~/workspace/legal-pal/signing-password.txt
#    (chmod 600, never committed). apksigner needs the password on two
#    lines when it is used for both the keystore and the key, so duplicate
#    it into a temp file and delete the temp file right after signing.
KEYSTORE="$HOME/workspace/legal-pal/legalpal.keystore"
PW_FILE="$HOME/workspace/legal-pal/signing-password.txt"
TMP_PW="$(mktemp)"
cat "$PW_FILE" "$PW_FILE" > "$TMP_PW"
trap 'rm -f "$TMP_PW"' EXIT

# 7. Zipalign + sign.
"$BT/zipalign" -f -p 4 "$WORK/app-debug.apk" "$OUT/app-debug.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "file:$TMP_PW" \
    --key-pass "file:$TMP_PW" --ks-key-alias legalpal \
    --out "$OUT/app-debug.apk" "$OUT/app-debug.apk"

"$BT/apksigner" verify "$OUT/app-debug.apk" && echo "apksigner verify: OK"
ls -la "$OUT/app-debug.apk"
