#!/bin/sh
# Builds build/rxhome-keys.dex: the key helper that runs on the FireStick (see tv/.../Keys.java).
set -e
cd "$(dirname "$0")"
SDK=$HOME/.local/opt/android-sdk
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH

rm -rf build/keys && mkdir -p build/keys/classes build/keys/dex
javac --release 8 -classpath "$JAR" -Xlint:-options -d build/keys/classes $(find tv -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/keys/dex $(find build/keys/classes -name '*.class')
cp build/keys/dex/classes.dex build/rxhome-keys.dex
