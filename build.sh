#!/usr/bin/env bash
# Builds TokTV.apk without Gradle: aapt2 -> javac -> dx -> zipalign -> apksigner.
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-/opt/sdk34/android.jar}"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes}

aapt2 compile --dir res -o "$OUT/res/compiled.zip"
aapt2 link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  -A assets --java "$OUT/gen" --min-sdk-version 22 --target-sdk-version 30 \
  --version-code 1 --version-name 1.0 "$OUT/res/compiled.zip"

javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v "bootstrap class path\|^warning\|^1 warning\|options.*obsolete\|To suppress" || true
test -n "$(find "$OUT/classes" -name MainActivity.class)"

dalvik-exchange --dex --min-sdk-version=22 --output="$OUT/classes.dex" "$OUT/classes"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="${KEYSTORE:-keystore/toktv.jks}"
PASS="${KEYSTORE_PASS:-changeit}"
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias toktv -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass $PASS -keypass $PASS -dname "CN=TokTV" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:$PASS --key-pass pass:$PASS --out TokTV.apk "$OUT/aligned.apk"
apksigner verify TokTV.apk && ls -la TokTV.apk
