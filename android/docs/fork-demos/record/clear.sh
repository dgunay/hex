#!/bin/bash
# Empties the field, then a delete on the keyboard itself so its strip resets (not logged: no touch dot).
adb -s emulator-5554 shell "input keycombination 113 29; input keyevent 67"; sleep 0.4
adb -s emulator-5554 shell "input tap 966 2025"; sleep 0.6
