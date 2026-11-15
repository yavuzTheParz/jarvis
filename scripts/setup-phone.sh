#!/usr/bin/env bash
# Installs the debug build on the phone connected over USB (or the emulator) and grants what can be granted
# from adb, so only the steps Android keeps for the person holding the phone are left.
# Usage: scripts/setup-phone.sh [path/to/app-debug.apk]
set -euo pipefail
PKG=app.jarvis
APK=${1:-app/build/outputs/apk/debug/app-debug.apk}

command -v adb >/dev/null || { echo "adb not found: install Android platform-tools (or put \$ANDROID_HOME/platform-tools on PATH)"; exit 1; }
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "No phone: connect it with USB debugging on (Settings > Developer options), then accept the prompt on the phone."; exit 1; }
[ -f "$APK" ] || { echo "No APK at $APK: build it first with ./gradlew assembleDebug"; exit 1; }

echo "Installing $APK"
adb install -r -g "$APK" >/dev/null

try() { adb shell "$@" >/dev/null 2>&1 && echo "  ok: $*" || echo "  skipped (do it by hand, see README): $*"; }
echo "Granting permissions"
try pm grant $PKG android.permission.RECORD_AUDIO
try pm grant $PKG android.permission.POST_NOTIFICATIONS
# Sideloaded apps need this before Android lets their accessibility service be switched on (Android 13+).
try appops set $PKG ACCESS_RESTRICTED_SETTINGS allow
try appops set $PKG GET_USAGE_STATS allow
# The vault in the shared "Jarvis" folder, so a sync app can carry it to a PC.
try appops set $PKG MANAGE_EXTERNAL_STORAGE allow
try dumpsys deviceidle whitelist +$PKG

echo "Turning on the accessibility service"
current=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
service="$PKG/$PKG.WatcherService"
if [[ "$current" != *"$service"* ]]; then
  if [[ -z "$current" || "$current" == null ]]; then new="$service"; else new="$current:$service"; fi
  try settings put secure enabled_accessibility_services "$new"
  try settings put secure accessibility_enabled 1
fi

echo "Making Jarvis the home screen"
try cmd role add-role-holder android.app.role.HOME $PKG
try cmd role add-role-holder android.app.role.ASSISTANT $PKG

echo
echo "Done. On the phone, check:"
echo "  - Pressing Home opens Jarvis (if not: Settings > Apps > Default apps > Home app > Jarvis)"
echo "  - Settings > Accessibility > Installed apps > Jarvis is on"
echo "  - Settings > Apps > Jarvis > Battery > Unrestricted"
echo "To undo everything: adb uninstall $PKG"
