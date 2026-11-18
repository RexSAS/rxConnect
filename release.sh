#!/bin/sh
# Publishes the current commit as an update for both apps: builds the APK, pushes, and makes GitHub release
# v<commit count> with the APK attached. GitHub Actions then adds the Windows exe to it (.github/workflows/release.yml).
# Both apps ask to update the next time they start.
set -e
cd "$(dirname "$0")"
[ -z "$(git status --porcelain)" ] || { echo "Commit your changes first: the version is the commit count." >&2; exit 1; }
android/build.sh
VERSION=$(git rev-list --count HEAD)
git push origin HEAD
gh release create "v$VERSION" android/build/rxconnect.apk --title "v$VERSION" --notes "$(git log -1 --format=%s)"
echo "Released v$VERSION. The Windows exe is added by GitHub Actions in a few minutes."
