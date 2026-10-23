#!/bin/bash
# Quick Switcher: CleanYourTV <-> Projectivy Launcher
TV_IP="192.168.1.10"

echo "Select which launcher to make default on your TV ($TV_IP):"
echo "1) Projectivy Launcher (With Continue Watching, Dynamic Scenery Wallpapers, Rich Layout)"
echo "2) CleanYourTV (Ultra-minimalist 390KB Apple TV dock, Aurora theme, Live RAM/CPU stats)"
read -p "Enter choice [1 or 2]: " choice

if [ "$choice" == "1" ]; then
    adb -s $TV_IP:5555 shell cmd package set-home-activity --user 0 com.spocky.projengmenu/.ui.home.MainActivity
    adb -s $TV_IP:5555 shell am start -n com.spocky.projengmenu/.ui.home.MainActivity
    echo "Switched to Projectivy Launcher!"
elif [ "$choice" == "2" ]; then
    adb -s $TV_IP:5555 shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
    adb -s $TV_IP:5555 shell am start -n com.gathin.tvhome/.MainActivity
    echo "Switched to CleanYourTV!"
else
    echo "Invalid choice."
fi
