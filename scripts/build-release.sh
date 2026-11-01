#!/usr/bin/env bash
# Build a release APK signed with the NotiFire release key from 1Password.
# Needs the 1Password CLI (`op`) signed in with access to the key item.
# The keystore and password only exist in a temp dir for the length of the build.
set -euo pipefail

ITEM="${NOTIFIRE_KEY_ITEM:-op://Private/NotiFire Android release key}"
cd "$(dirname "$0")/.."

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"

op read "$ITEM/notifire-release/p12" --out-file "$tmp/release.p12" >/dev/null
# Passed via the environment rather than -P so the password isn't visible in `ps`.
export ORG_GRADLE_PROJECT_notifireKeystore="$tmp/release.p12"
ORG_GRADLE_PROJECT_notifireKeystorePassword="$(op read "$ITEM/password")"
export ORG_GRADLE_PROJECT_notifireKeystorePassword

./gradlew :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
echo "Signed APK: $apk"
if command -v apksigner >/dev/null; then apksigner verify --print-certs "$apk" | grep -E "DN|SHA-256"; fi
