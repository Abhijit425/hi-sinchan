#!/usr/bin/env bash
# Emulator smoke test: install, onboard, memory save -> restart -> recall, avatar tap, wake word start.
set -u
PKG=com.abhijit.hisinchan
OUT=emu-out
mkdir -p $OUT
APK=app/build/outputs/apk/debug/app-debug.apk

shot() { sleep "${2:-2}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1"; }

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml $OUT/ui.xml >/dev/null 2>&1; }

# tap_node <attr> <substring>  (attr = text | content-desc)
tap_node() {
  for i in 1 2 3 4 5; do
    dump
    xy=$(python3 - "$1" "$2" "$OUT/ui.xml" <<'PY'
import sys, re, xml.etree.ElementTree as ET
attr, val, path = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    root = ET.parse(path).getroot()
except Exception:
    sys.exit(0)
for n in root.iter('node'):
    if val.lower() in (n.get(attr) or '').lower():
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $1=$2 at $xy"; return 0; fi
    sleep 2
  done
  echo "NOT FOUND: $1=$2"; return 1
}

has_text() { dump; grep -qi -- "$1" $OUT/ui.xml; }

type_text() { adb shell input text "$(echo "$1" | sed 's/ /%s/g; s/'"'"'/\\'"'"'/g')"; }

RESULT=0
check() { if has_text "$1"; then echo "PASS: $2"; else echo "FAIL: $2 (missing '$1')"; RESULT=1; fi; }

adb logcat -c
adb install -r -g $APK || { echo "install failed"; exit 1; }
adb shell am start -W -n $PKG/.MainActivity
shot 01-first-launch 8
tap_node text "Let's go" || true
shot 02-home 4
check "Good" "home screen greeting rendered"

# Tap the avatar (content description starts with "Sinchan, feeling")
tap_node content-desc "Sinchan, feeling" && shot 03-avatar-tap 1

# Memory: save via text chat (explicit instruction)
tap_node content-desc "Type instead"
sleep 1
tap_node text "Type to Sinchan" || true
type_text "Remember that my name is Abhijit"
adb shell input keyevent 66
shot 04-memory-saved 5
check "remember that your name is Abhijit" "memory save confirmation"

# Restart the app process completely
adb shell am force-stop $PKG
sleep 2
adb shell am start -W -n $PKG/.MainActivity
shot 05-after-restart 6
check "Abhijit" "name recalled in greeting after restart"
tap_node content-desc "Type instead"
sleep 1
tap_node text "Type to Sinchan" || true
type_text "What do you remember about me?"
adb shell input keyevent 66
shot 06-recall 5
check "your name is Abhijit" "memory recalled after restart"

# Memory screen
tap_node content-desc "Memory" && shot 07-memory-screen 3
check "My name is Abhijit" "memory listed in memory manager"
adb shell input keyevent 4
sleep 2

# Settings: enable wake word (Vosk model unpack + foreground service)
tap_node content-desc "Settings" && shot 08-settings 3
tap_node text "Listens fully on-device" && sleep 25
shot 09-wake-enabled 1
adb shell input keyevent 4
shot 10-home-wake 5
check "Wake word on" "wake-word service listening (on-device Vosk)"

adb shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground|foregroundServiceType" | head -5 > $OUT/services.txt
adb logcat -d -v brief > $OUT/logcat-full.txt
grep -E "FATAL|AndroidRuntime|WakeWord|VoskAPI|hisinchan" $OUT/logcat-full.txt | tail -200 > $OUT/logcat-app.txt
if grep -q "FATAL EXCEPTION" $OUT/logcat-full.txt; then echo "FAIL: crash in logcat"; RESULT=1; else echo "PASS: no crashes in logcat"; fi
echo "RESULT=$RESULT"
exit 0
