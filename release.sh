#!/bin/sh
# Publishes the current commit as a rxhome update: builds it, pushes, and makes the GitHub release
# (tag v<versionCode>, APK attached) that rxhome on the phone offers to install (see Updater).
set -e
cd "$(dirname "$0")"
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first: the version is the commit count." >&2; exit 1; }
./build.sh
VERSION=$(git rev-list --count HEAD)
git push origin HEAD
gh release create "v$VERSION" build/rxhome.apk --target "$(git rev-parse HEAD)" --title "v$VERSION" --notes "$(git log -1 --format=%s)"
echo "Released v$VERSION: the phone asks to update next time rxhome opens (checks at most hourly)."
