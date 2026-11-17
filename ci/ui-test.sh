#!/bin/bash
# Runs Silk on the emulator and saves screenshots and timings to shots/.
# Screen is 1080x2400 (pixel_6 profile).
set -x
mkdir -p shots
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "shots/$1.png"; }

adb install -r Silk.apk
adb shell cmd package set-home-activity dev.giorgi.silk/.LauncherActivity
adb logcat -c
adb shell input keyevent KEYCODE_HOME
shot 01-home-first-start 10

# Open the drawer; one shot right after the gesture, one once it has settled.
adb shell dumpsys gfxinfo dev.giorgi.silk reset > /dev/null
adb shell input swipe 540 1900 540 700 200
shot 02-drawer-0.3s 0.3
shot 03-drawer-settled 2

adb shell input swipe 950 1300 100 1300 200
shot 04-drawer-page2 1.5

adb shell input tap 540 230
adb shell input text "cal"
shot 05-search 2
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
shot 06-back-home 2

# Open and close the drawer a few times to collect frame statistics.
for i in 1 2 3 4 5; do
  adb shell input swipe 540 1900 540 700 200; sleep 1
  adb shell input swipe 540 700 540 1900 200; sleep 1
done
adb shell dumpsys gfxinfo dev.giorgi.silk > shots/gfxinfo.txt

# Long-press on empty home space.
adb shell input swipe 540 1100 540 1100 1000
shot 07-home-menu 1.5
adb shell input keyevent KEYCODE_BACK

# Swipe down on home: notification shade.
adb shell input swipe 540 900 540 1700 200
shot 08-swipe-down 1.5
adb shell cmd statusbar collapse

# Cold start with a warm icon cache: kill the process, press Home.
adb shell am force-stop dev.giorgi.silk
adb shell input keyevent KEYCODE_HOME
shot 09-cold-start-1s 1

adb logcat -d -v time -s Silk:V AndroidRuntime:E ActivityManager:I > shots/logcat.txt
adb shell dumpsys window windows | grep -i "mCurrentFocus" >> shots/logcat.txt
exit 0
