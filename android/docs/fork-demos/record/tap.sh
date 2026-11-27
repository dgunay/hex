#!/bin/bash
# tap.sh X Y [ms] [X2 Y2] : a press (or a slide to X2,Y2), logged for the touch dots.
x=$1; y=$2; ms=${3:-90}; x2=${4:-$x}; y2=${5:-$y}
adb -s emulator-5554 shell "echo \$(cut -d' ' -f1 /proc/uptime) $x $y $ms $x2 $y2 >> /sdcard/taps.log; input swipe $x $y $x2 $y2 $ms"
