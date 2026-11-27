#!/bin/bash
# type.sh "text" [gap seconds] : types on the Hex keyboard (QWERTY letters, space) at a human pace.
A="adb -s emulator-5554"; G=${2:-0.09}
declare -A X Y
r1=qwertyuiop; for i in $(seq 0 9); do X[${r1:$i:1}]=$((58+107*i)); Y[${r1:$i:1}]=1719; done
r2=asdfghjkl; for i in $(seq 0 8); do X[${r2:$i:1}]=$((111+107*i)); Y[${r2:$i:1}]=1872; done
r3=zxcvbnm; for i in $(seq 0 6); do X[${r3:$i:1}]=$((218+107*i)); Y[${r3:$i:1}]=2025; done
X[" "]=593; Y[" "]=2178
s="$1"; cmds=""
for ((i=0;i<${#s};i++)); do c="${s:$i:1}"; j=$(( (RANDOM % 7) - 3 )); x=$((${X[$c]}+j*3)); y=$((${Y[$c]}+j*2)); cmds+="echo \$(cut -d\" \" -f1 /proc/uptime) $x $y 70 >> /sdcard/taps.log; input swipe $x $y $x $y 70; sleep $G; "; done
$A shell "$cmds"
