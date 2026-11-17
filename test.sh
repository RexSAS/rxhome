#!/bin/sh
# Runs the plain-Java tests on the desktop JVM: every src file that doesn't touch android.*, plus test/.
# Live device tests run only when RXHOME_ADB (FireStick IP) / RXHOME_SERVER (Jellyfin URL) are set.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=$HOME/.local/opt/jdk17
export PATH=$JAVA_HOME/bin:$PATH
JSON=$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/20240303/*/json-20240303.jar | head -1)

rm -rf build/test && mkdir -p build/test
SRC=$(grep -L 'import android\.' $(find src -name '*.java'))
TESTS=$(find test -name '*Test.java')
[ -n "$TESTS" ] || { echo "0 tests"; exit 0; }
javac -cp "$JSON" -d build/test $SRC $TESTS
for t in $TESTS; do
    cls=$(echo "$t" | sed 's|^test/||; s|\.java$||; s|/|.|g')
    java -ea -cp "build/test:$JSON" "$cls"
    echo "ok $cls"
done
