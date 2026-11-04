#!/usr/bin/env bash
# Builds TokTV.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Needs Android build-tools (aapt2, d8, zipalign, apksigner), a JDK 11+, curl and unzip.
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:-/opt/sdk34/android.jar}"
OUT=build

# Libraries (ExoPlayer etc.): download once into libs/, check SHA-1, unpack classes.jar from AARs.
mkdir -p libs/jars
grep -v '^#' libs.txt | while read -r url sha; do
  [ -n "$url" ] || continue
  f="libs/$(basename "$url")"
  [ -f "$f" ] || curl -fsSL -o "$f" "$url"
  echo "$sha  $f" | sha1sum -c --quiet -
  case "$f" in
    *.aar) unzip -p "$f" classes.jar > "libs/jars/$(basename "$f" .aar).jar" ;;
    *.jar) cp "$f" libs/jars/ ;;
  esac
done
JARS=$(ls libs/jars/*.jar | tr '\n' ':')

rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes}
aapt2 compile --dir res -o "$OUT/res/compiled.zip"
aapt2 link -o "$OUT/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
  -A assets --java "$OUT/gen" --min-sdk-version 22 --target-sdk-version 30 \
  --version-code 7 --version-name 2.4 "$OUT/res/compiled.zip"

javac -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR:$JARS" \
  -d "$OUT/classes" $(find src "$OUT/gen" -name '*.java') 2>&1 | grep -v "bootstrap class path\|^warning\|^1 warning\|options.*obsolete\|To suppress\|^Note:" || true
test -n "$(find "$OUT/classes" -name HomeActivity.class)"

d8 --release --min-api 22 --lib "$ANDROID_JAR" --output "$OUT" $(find "$OUT/classes" -name '*.class') libs/jars/*.jar
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
