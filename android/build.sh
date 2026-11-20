#!/bin/sh
# Builds build/rxconnect.apk with Gradle (build.gradle). Needs google-services.json from the Firebase project.
# Install: adb install -r build/rxconnect.apk
set -e
cd "$(dirname "$0")"

export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/jdk17}
export PATH=$JAVA_HOME/bin:$PATH
[ -f google-services.json ] || { echo "Missing android/google-services.json: download it from the Firebase console." >&2; exit 1; }

[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android \
    -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=rxconnect
./gradlew -q assembleRelease -PrxVersion="$(git rev-list --count HEAD)"
cp build/outputs/apk/release/*-release.apk build/rxconnect.apk
echo "Built build/rxconnect.apk"
