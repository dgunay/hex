#!/bin/bash
# The fork's demo recordings (FORK_CHANGES.md), on the Android emulator with this fork's debug build installed.
#
#   takes.sh NAME   records one take into raw/, then makes its GIF in ../
#
# Setup, once per boot: dark mode (adb shell cmd uimode night yes), the Hex keyboard chosen, and Messages open on a
# conversation with its text field focused (adb shell am start -a android.intent.action.SENDTO -d sms:5550100, then
# tap the field). Coordinates are for the hex-test AVD (1080x2400, 420 dpi, gesture navigation) at the default
# keyboard height. The take functions are exported because rec.sh runs them in its own shell.
set -eu
cd "$(dirname "$0")"
S=$PWD; A="adb -s emulator-5554"; export S A
field() { sleep 2; $A shell input tap 450 2225; sleep 1; }
gif() { python3 mkgif.py "../$1" "${@:2}"; }

colon() { $S/tap.sh 105 2178; sleep 0.4; $S/tap.sh 540 2024; sleep 0.4; $S/tap.sh 84 2179; sleep 0.35; }; export -f colon

case $1 in
stronger-autocorrect)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "that was so embarasing "; sleep 2.5; }; export -f take
  ./prefs.sh autocorrect=true; field; ./rec.sh strong-off 10 take
  ./prefs.sh autocorrect=true strongAutocorrect=true; field; ./rec.sh strong-on 10 take
  gif stronger-autocorrect.gif raw/strong-off raw/strong-on --labels "Stronger autocorrect off" "Stronger autocorrect on" --start 1.9 --end 8.5 ;;
shift-recase)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "meet me in sf"; sleep 1.2; for i in 1 2; do $S/tap.sh 81 2025 110; sleep 1.3; done
           $S/type.sh " tomorrow "; sleep 2.5; }; export -f take
  ./prefs.sh autocorrect=true shiftRecases=true; field; ./rec.sh shift 16 take
  gif shift-recase.gif raw/shift --start 1.9 --end 13 ;;
more-suggestions)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "see you to"; sleep 1.4; $S/slide.sh 790 1572 40 1572 16; sleep 1.3; $S/tap.sh 785 1572 90; sleep 2.5; }; export -f take
  ./prefs.sh autocorrect=true moreSuggestions=true; field; ./rec.sh more 14 take
  gif more-suggestions.gif raw/more --start 1.9 --end 11.5 ;;
delete-words)
  TXT="i think this message got a little too long so lets trim it down "
  take() { sleep 1.2; $S/tap.sh 966 2025 2700; sleep 2.2; }; export -f take
  ./prefs.sh autocorrect=false; field; ./clear.sh; ./type.sh "$TXT" 0.02; sleep 1.5; ./rec.sh del-off 7 take
  ./prefs.sh autocorrect=false holdDeleteWords=true; field; ./clear.sh; ./type.sh "$TXT" 0.02; sleep 1.5; ./rec.sh del-on 7 take
  gif delete-words.gif raw/del-off raw/del-on --labels "Delete words when held off" "Delete words when held on" --start 1.4 --end 7 --top 1285 ;;
fix-earlier-words)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "i want to by a car "; sleep 1.2; $S/tap.sh 350 1422 80; sleep 1.6; $S/tap.sh 430 1572 90; sleep 2.5; }; export -f take
  ./prefs.sh autocorrect=true fixEarlierWords=true; field; ./rec.sh earlier 14 take
  gif fix-earlier-words.gif raw/earlier --start 2.2 --end 11.5 ;;
learn-new-words)
  # "tailscale" must not be in the dictionary yet: long-press it in the strip and tap the notice to remove it.
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "restart tailscale "; sleep 1.4; $S/tap.sh 966 2025 90; sleep 2.2
           $S/type.sh " then ping tailscale "; sleep 2.8; }; export -f take
  ./prefs.sh autocorrect=true learnWords=true strongAutocorrect=true; field; ./rec.sh learn 18 take
  gif learn-new-words.gif raw/learn --start 1.9 --end 15 --top 1285 ;;
closing-quote)
  # SwiftKey layout: " is a held C (on the standard layout the ?123 page's switch forgets the space).
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "she said "; sleep 0.2; $S/tap.sh 433 2025 600; sleep 0.4; $S/type.sh "see you soon "; sleep 1.4
           $S/tap.sh 433 2025 600; sleep 2.8; }; export -f take
  ./prefs.sh autocorrect=true swiftKeyLayout=true; field; ./rec.sh quote 15 take
  gif closing-quote.gif raw/quote --start 1.9 --end 12 ;;
emoji-shortcodes)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "this is "; colon; $S/type.sh "fire"; sleep 1.3; $S/tap.sh 430 1572 90; sleep 1.0
           $S/type.sh "lets get "; colon; $S/type.sh "piz"; sleep 1.3; $S/tap.sh 640 1572 90; sleep 2.8; }; export -f take
  ./prefs.sh autocorrect=true emojiShortcodes=true; field; ./rec.sh emoji 20 take
  gif emoji-shortcodes.gif raw/emoji --start 2.2 --end 17 ;;
emoji-search)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "good night "; sleep 0.5; $S/tap.sh 324 2178; sleep 1.3; $S/tap.sh 264 2179; sleep 1.0
           $S/type.sh "moon"; sleep 1.4; $S/tap.sh 420 1395 90; sleep 1.2; $S/tap.sh 975 2178; sleep 0.8; $S/type.sh " sleep well "; sleep 2.8; }; export -f take
  ./prefs.sh autocorrect=true emojiSearch=true; field; ./rec.sh esearch 20 take
  gif emoji-search.gif raw/esearch --start 2.2 --end 17 --top 1020 ;;
mark-a-problem)
  take() { $S/clear.sh; sleep 0.5; $S/type.sh "lets meet at noon "; sleep 1.0; $S/tap.sh 909 1572 90; sleep 1.3; $S/tap.sh 200 1689 90; sleep 1.2
           $S/type.sh "the strip pick did nothing"; sleep 1.2; $S/tap.sh 1020 1452 90; sleep 1.0; $S/type.sh "ok"; sleep 2.8; }; export -f take
  ./prefs.sh autocorrect=true typingLog=true; field; ./rec.sh note 22 take
  gif mark-a-problem.gif raw/note --start 2.2 --end 19 --top 1230 ;;
swiftkey-layout)
  take() { $S/clear.sh; sleep 0.6; $S/type.sh "see you "; sleep 0.3; $S/tap.sh 111 1872 750; sleep 0.5; $S/type.sh " "; $S/tap.sh 486 1719 750; sleep 0.6
           $S/slide.sh 864 2178 975 2178 8; sleep 1.4; $S/tap.sh 84 2178; sleep 1.6; $S/tap.sh 84 2024; sleep 1.6; $S/tap.sh 84 2180; sleep 2.2; }; export -f take
  ./prefs.sh autocorrect=true swiftKeyLayout=true; field; ./rec.sh swiftkey 18 take
  gif swiftkey-layout.gif raw/swiftkey --start 1.9 --end 15 ;;
remove-bottom-margin)
  F=/usr/share/fonts/noto/NotoSans-Medium.ttf
  for v in false true; do
    ./prefs.sh autocorrect=true snugBottom=$v; field; ./clear.sh; ./type.sh "hello " 0.04; sleep 1.5; $A exec-out screencap -p > raw/bm-$v.png
    L="Remove bottom margin $([ $v = true ] && echo on || echo off)"
    magick raw/bm-$v.png -crop 1080x850+0+1550 -resize 380x \( -size 380x46 xc:'#101114' -font $F -pointsize 19 -fill '#D8DADF' \
      -gravity center -annotate +0+0 "$L" \) +swap -append -bordercolor '#101114' -border 6x0 raw/bm-$v-c.png
  done
  magick raw/bm-false-c.png raw/bm-true-c.png +append -strip ../remove-bottom-margin.png ;;
*) echo "takes.sh: no take $1"; exit 1 ;;
esac
