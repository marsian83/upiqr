#!/bin/sh
# Builds upiqr.apk without gradle. Usage: [PKG=com.example.upi] ./build.sh [adb-serial-to-install-on]
# PKG installs a copy under another package name, for merchant apps that only
# list UPI apps from a hardcoded allowlist.
set -e
cd "$(dirname "$0")"
SDK=~/Android/Sdk
BT=$SDK/build-tools/37.0.0
JAR=$SDK/platforms/android-37.0/android.jar
rm -rf build && mkdir -p build/classes build/dex

[ -f upiqr.keystore ] || keytool -genkeypair -keystore upiqr.keystore -storepass upiqrpass -keypass upiqrpass \
    -alias upiqr -keyalg RSA -keysize 2048 -validity 10000 -dname CN=upiqr >/dev/null

OUT=upiqr${PKG:+-$PKG}.apk
$BT/aapt2 link -o build/base.apk -I $JAR --manifest AndroidManifest.xml ${PKG:+--rename-manifest-package $PKG}
javac -nowarn --release 11 -cp $JAR -d build/classes $(find src -name '*.java')
$BT/d8 --release --min-api 24 --lib $JAR --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner sign --ks upiqr.keystore --ks-pass pass:upiqrpass --out $OUT build/aligned.apk
echo built $OUT
[ -n "$1" ] && adb -s "$1" install -r $OUT
