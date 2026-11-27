#!/bin/bash
# rec.sh NAME SECONDS COMMAND... : records the screen while COMMAND runs (starting 1 s in), into raw/NAME.mp4.
A="adb -s emulator-5554"; D=$(dirname "$0"); n=$1; t=$2; shift 2
mkdir -p $D/raw; $A shell rm -f /sdcard/$n.mp4
$A shell rm -f /sdcard/taps.log
$A shell "cut -d' ' -f1 /proc/uptime > /sdcard/rec0; exec screenrecord --bit-rate 16000000 --time-limit $t /sdcard/$n.mp4" & p=$!
sleep 1; "$@"; wait $p; $A pull /sdcard/$n.mp4 $D/raw/$n.mp4 >/dev/null && echo "raw/$n.mp4"
$A shell cat /sdcard/rec0 > $D/raw/$n.rec0; $A shell cat /sdcard/taps.log > $D/raw/$n.taps 2>/dev/null; wc -l < $D/raw/$n.taps
