#!/bin/bash
# 1-Click Restore Script for TCL 32" (C06 / AR2101)
TV_IP="192.168.1.10"

echo "Connecting to TV at $TV_IP:5555..."
adb connect $TV_IP:5555

echo "Re-enabling stock Google TV launcher..."
adb -s $TV_IP:5555 shell pm enable com.google.android.tvlauncher
adb -s $TV_IP:5555 shell cmd package set-home-activity --user 0 com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity

echo "Re-enabling services..."
for pkg in \
  com.google.android.tvrecommendations \
  com.tcl.usercenter \
  com.tcl.guard \
  com.tcl.partnercustomizer \
  com.tcl.waterfall.overseas \
  com.tcl.esticker \
  com.tcl.audioplayer \
  com.tcl.imageplayer \
  com.tcl.copydatatotv \
  com.google.android.feedback \
  com.google.android.play.games \
  com.google.android.videos \
  com.google.android.youtube.tvmusic
do
  echo "Re-enabling $pkg..."
  adb -s $TV_IP:5555 shell pm enable "$pkg"
done

echo "Resetting animation speeds to default 1.0x..."
adb -s $TV_IP:5555 shell settings put global window_animation_scale 1.0
adb -s $TV_IP:5555 shell settings put global transition_animation_scale 1.0
adb -s $TV_IP:5555 shell settings put global animator_duration_scale 1.0

echo "Restore complete! Rebooting TV..."
adb -s $TV_IP:5555 reboot
