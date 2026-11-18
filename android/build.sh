#!/bin/sh
# Builds build/rxconnect.apk with the Android SDK command-line tools (no Gradle), like rxhome.
# The call page itself is ../web/index.html, bundled as assets/web.
# Install: adb install -r build/rxconnect.apk
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk17}
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build/apk
mkdir -p build/apk/gen build/apk/classes build/apk/dex build/apk/assets
cp -r ../web build/apk/assets/web

"$BT/aapt2" compile --dir res -o build/apk/res.zip
# Version = commits so far: each release is newer for the updater (see ../release.sh).
VERSION=$(git rev-list --count HEAD)
# Target 34, not 35: 35 forces edge-to-edge and the call buttons would sit under the nav bar.
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/apk/gen \
    --version-code "$VERSION" --version-name "$VERSION" --replace-version \
    --min-sdk-version 26 --target-sdk-version 34 -A build/apk/assets -o build/apk/unsigned.apk build/apk/res.zip

javac --release 8 -classpath "$JAR" -Xlint:-options -d build/apk/classes \
    $(find src build/apk/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/apk/dex $(find build/apk/classes -name '*.class')

cp build/apk/unsigned.apk build/apk/app.apk
(cd build/apk/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f 4 build/apk/app.apk build/apk/aligned.apk

[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android \
    -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=rxconnect
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:android --out build/rxconnect.apk build/apk/aligned.apk
echo "Built build/rxconnect.apk"
