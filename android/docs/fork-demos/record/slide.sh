#!/bin/bash
# slide.sh X1 Y1 X2 Y2 [steps] : drag with no fling (the finger stops before it lifts), logged for the touch dots.
x1=$1; y1=$2; x2=$3; y2=$4; n=${5:-14}
c="echo \$(cut -d' ' -f1 /proc/uptime) $x1 $y1 $(( n*35+250 )) $x2 $y2 >> /sdcard/taps.log; input motionevent DOWN $x1 $y1; sleep 0.08; "
for i in $(seq 1 $n); do x=$(( x1 + (x2-x1)*i/n )); y=$(( y1 + (y2-y1)*i/n )); c+="input motionevent MOVE $x $y; "; done
c+="sleep 0.12; input motionevent MOVE $x2 $y2; sleep 0.05; input motionevent UP $x2 $y2"
adb -s emulator-5554 shell "$c"
