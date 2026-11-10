#!/bin/sh
# Builds upiqr.apk with the plain Android SDK command-line tools (no Gradle).
#
#   ./build.sh                 build upiqr.apk
#   ./build.sh <adb-serial>    build and install on that device
#
# Environment:
#   ANDROID_HOME          SDK location (falls back to ANDROID_SDK_ROOT, ~/Android/Sdk)
#   BUILD_TOOLS           build-tools version (default: newest installed)
#   PLATFORM              platform dir (default: newest installed)
#   UPIQR_KEYSTORE        release keystore; without it a local debug key is
#   UPIQR_KEYSTORE_PASS   generated in debug.keystore (git-ignored)
#   UPIQR_KEY_ALIAS       key alias (default upiqr)
#   PKG                   install under a different package name; see README
set -e
cd "$(dirname "$0")"

# first SDK location that actually has a platform installed
for SDK in "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$HOME/Android/Sdk"; do
    [ -n "$SDK" ] && ls "$SDK"/platforms/*/android.jar >/dev/null 2>&1 && break
done
BT=$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}
JAR=$SDK/platforms/${PLATFORM:-$(ls "$SDK/platforms" | sort -V | tail -1)}/android.jar
MIN_API=29

if [ -n "$UPIQR_KEYSTORE" ]; then
    KS=$UPIQR_KEYSTORE
    KS_PASS=${UPIQR_KEYSTORE_PASS:?set UPIQR_KEYSTORE_PASS}
else
    KS=debug.keystore
    KS_PASS=android
    [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias upiqr -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=UPI QR debug" >/dev/null 2>&1
    echo "signing with local debug key (set UPIQR_KEYSTORE for release builds)"
fi

OUT=upiqr${PKG:+-$PKG}.apk
rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml ${PKG:+--rename-manifest-package "$PKG"}
# vendored QR library as-is, then our code with warnings as errors
javac -nowarn --release 11 -d build/classes $(find src/io -name '*.java')
javac -Xlint:all -Werror --release 11 -cp "$JAR:build/classes" -d build/classes $(find src/dev -name '*.java')
"$BT/d8" --release --min-api $MIN_API --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias "${UPIQR_KEY_ALIAS:-upiqr}" \
    --v4-signing-enabled false --out "$OUT" build/aligned.apk 2>/dev/null

echo "built $OUT"
sha256sum "$OUT"
if [ -n "$1" ]; then
    adb -s "$1" install -r "$OUT"
fi
