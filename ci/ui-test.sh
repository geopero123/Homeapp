#!/bin/bash
# Runs Silk on the emulator and saves screenshots and timings to shots/.
# Screen is 1080x2400 (pixel_6 profile).
set -x
mkdir -p shots
shot() { sleep "${2:-1}"; adb exec-out screencap -p > "shots/$1.png"; }

adb install -r Silk.apk
adb shell cmd role add-role-holder --user 0 android.app.role.HOME dev.giorgi.silk 0
adb shell cmd package set-home-activity dev.giorgi.silk/.LauncherActivity
adb shell pm grant dev.giorgi.silk android.permission.READ_MEDIA_IMAGES
adb shell appops set --uid dev.giorgi.silk MANAGE_EXTERNAL_STORAGE allow
adb logcat -c
adb shell input keyevent KEYCODE_HOME
shot 01-home-first-start 10

# Open the drawer; one shot right after the gesture, one once it has settled.
adb shell dumpsys gfxinfo dev.giorgi.silk reset > /dev/null
adb shell input swipe 540 1900 540 700 200
shot 02-drawer-0.3s 0.3
shot 03-drawer-settled 2
# Close and reopen without touching search, to separate drawer effects from keyboard effects.
adb shell input swipe 540 700 540 1900 200
shot 03b-closed-no-search 2
adb shell input swipe 540 1900 540 700 200
sleep 2

adb shell input swipe 950 1300 100 1300 200
shot 04-drawer-page2 1.5

adb shell input tap 540 230
adb shell input text "cal"
shot 05-search 2
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
shot 06-back-home 2

# Long-press an app icon (first icon row under the widgets): glass context menu.
adb shell input swipe 155 1500 155 1500 1000
shot 06c-icon-menu 1.5
adb shell input keyevent KEYCODE_BACK
sleep 1

# Swipe to home page 2.
adb shell input swipe 950 1200 100 1200 200
shot 06b-home-page2 1.5
adb shell input swipe 100 1200 950 1200 200
sleep 1

# Long-press on empty home space.
adb shell input swipe 540 1640 540 1640 1000
shot 07-home-menu 1.5
adb shell input keyevent KEYCODE_BACK
sleep 1

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
