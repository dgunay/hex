#!/bin/bash
# prefs.sh key=true otherKey=300 name=s:text ... : the keyboard's settings become exactly these (plus autocorrect on).
A="adb -s emulator-5554"
x="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
for kv in "$@"; do k=${kv%%=*}; v=${kv#*=}
  case $v in true|false) x+="    <boolean name=\"$k\" value=\"$v\" />\n";; s:*) x+="    <string name=\"$k\">${v#s:}</string>\n";; *) x+="    <int name=\"$k\" value=\"$v\" />\n";; esac
done
x+="</map>\n"
$A shell am force-stop ly.anoma.hex
printf "$x" | $A shell "run-as ly.anoma.hex sh -c 'cat > shared_prefs/typing.xml'"
$A shell ime set ly.anoma.hex/.keyboard.HexKeyboard >/dev/null
