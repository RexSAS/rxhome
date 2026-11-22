#!/bin/sh
# Builds build/rxhome.apk with the Android SDK command-line tools (no Gradle).
# Install: ~/.local/opt/platform-tools/adb install -r build/rxhome.apk
set -e
cd "$(dirname "$0")"

SDK=$HOME/.local/opt/android-sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH

./keys.sh
mkdir -p assets && cp build/rxhome-keys.dex assets/
rm -rf build/apk
mkdir -p build/apk/gen build/apk/classes build/apk/dex

"$BT/aapt2" compile --dir res -o build/apk/res.zip
# Version = commits so far: each new commit is a newer version for the updater (see release.sh).
VERSION=$(git rev-list --count HEAD)
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml --java build/apk/gen \
    --version-code "$VERSION" --version-name "$VERSION" --replace-version \
    --min-sdk-version 26 --target-sdk-version 30 -A assets -o build/apk/unsigned.apk build/apk/res.zip

javac --release 8 -classpath "$JAR" -Xlint:-options -d build/apk/classes \
    $(find src build/apk/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/apk/dex $(find build/apk/classes -name '*.class')

cp build/apk/unsigned.apk build/apk/app.apk
(cd build/apk/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f 4 build/apk/app.apk build/apk/aligned.apk

[ -f signing.keystore ] || keytool -genkeypair -keystore signing.keystore -storepass android \
    -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname CN=rxhome
"$BT/apksigner" sign --ks signing.keystore --ks-pass pass:android --out build/rxhome.apk build/apk/aligned.apk
echo "Built build/rxhome.apk"
