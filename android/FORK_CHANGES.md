# Keyboard changes in this fork

This fork (`dgunay/hex`, branch `android-keyboard-tweaks`) adds keyboard features on top of upstream `dev`. They
came from switching to Hex's keyboard after SwiftKey and missing what it did. This page describes each one so it
can be rebuilt upstream without taking this code: what it does, where its switch is, why it exists, how it
works (the thresholds and Android APIs it relies on), its known limits, and its commit.

Every change is off by default, except two small fixes that change nothing for anyone who never hits the case
(the clause-mark space and the Android 17 voice hand-back, both listed below). Each setting is a toggle in
**Settings › Keyboard**, or in Labs where it extends an existing lab. With everything off, the keyboard behaves as
upstream's does.

Commit hashes are on the fork's branch and change when the branch is rebased; the subject lines stay the same.

## Layout

### Keys down to the bar

- **Switch:** Settings › Keyboard › Keys down to the bar (off). Changing it rebuilds the keyboard in place.
- **What:** removes the gap under the bottom row, so the bottom row's keycaps end where the system's navigation
  bar begins. With it off, they end Gboard's 12.2 dp above the bar, as upstream measured.
- **Why:** SwiftKey has no gap there. On a phone with gesture navigation, the gap makes the bottom row feel
  floating and pushes the space bar up.
- **How:** `KeyPlaneView.bottomExtra`, the extra height under the bottom row, becomes `-insetBottom`. That removes
  both the fixed bottom gap and the lower half of the row's own spacing, so the keycap's bottom edge meets the
  bar.
- **Commit:** `639df77d` Android Keyboard: Keys down to the bar removes the gap under the bottom row.

## Delete

### Hold delete for words

- **Switch:** Settings › Keyboard › Hold delete for words (off). While it's on, two sliders show under it:
  - **First wait:** 200–1000 ms in steps of 50, 500 by default.
  - **Speed-up:** 0–50% in steps of 5, 20 by default.
- **What:** holding delete removes whole words, as SwiftKey's held delete does, starting gently and speeding up
  the longer you hold ("the longer you hold it, the faster it deletes"). A tap still deletes one letter.
- **Why:** deleting a sentence letter by letter at the 50 ms repeat rate either overshoots or takes too long.
  Word steps that speed up are easier to stop on the right word.
- **How:**
  - Delete still waits the usual 400 ms hold threshold (`DELETE_REPEAT_DELAY`, shared with letter repeat). Then it
    takes a word at once: no letters first.
  - Each next word waits the previous gap times `1 − Speed-up`, starting from First wait, down to a floor of
    90 ms (`DELETE_WORD_FASTEST`). With the defaults: 500, 400, 320, 256… ms.
  - A "word" is what upstream's delete slide already takes: the word before the cursor plus the spaces and
    punctuation after it (`DeleteSlide.wordLengths`), deleted with `deleteSurroundingText`.
  - Each word plays `EFFECT_HEAVY_CLICK`, a stronger tap than a letter's, so you can count words by feel. It is
    scaled by Haptic strength like every other tap.
  - The settings are read when delete goes down, so changes apply on the next press.
- **Limits:** password fields, `TYPE_NULL` editors (terminals), a selection, or an unknown cursor position can't
  be read as words, so there it repeats letters as before.
- **Commits:**
  - `0a7180cc` Android Keyboard: Hold delete for words, as SwiftKey's held delete does
  - `ebfb41a3` Android Keyboard: Hold delete for words takes only words, each with a heavy click
  - `b24e58b7` Android Keyboard: Hold delete for words starts slower; Diagnostics names its tap
  - `01f2486b` Android Keyboard: Hold delete for words waits 500 ms for the second word
  - `fc7598bb` Android Keyboard: Hold delete for words' First wait and Speed-up

## Haptics

### Haptic strength

- **Switch:** Settings › Keyboard › Haptic strength, a slider shown only while Haptics is on. It runs from 10 to
  100% in steps of 10, and 100% is the default.
- **What:** makes key taps lighter. Lifting the slider plays a key press at the new strength so you can feel it.
- **Why:** the phone's predefined click is too strong on some phones, and upstream has no way to soften it.
- **How:**
  - At 100% it plays exactly what upstream played: the predefined `EFFECT_CLICK` on press and `EFFECT_TICK` on
    release.
  - Below 100% it plays the same click or tick as a composition primitive (`PRIMITIVE_CLICK`/`PRIMITIVE_TICK`)
    scaled to the strength, where the vibrator supports primitives.
  - Otherwise it plays a one-shot pulse (20 ms for a click, 12 for a tick) at that share of full amplitude, where
    the vibrator has amplitude control.
  - Otherwise it plays the effect unscaled.
  - Every Hex tap goes through it: keys, long-press release, dictation start and end, and Hold delete's word tap.
- **Commit:** `41fd084c` Android Keyboard: Haptic strength slider under Haptics.

## Autocorrect

### Stronger autocorrect

- **Switch:** Settings › Keyboard › Stronger autocorrect, under Autocorrect (off).
- **What:** corrects sloppier typing. "kehbaodd" becomes "keyboard" and "suggesfjkn" becomes "suggestion",
  which upstream leaves as typed.
- **Why:** upstream's decoder allows one edit from a word, so a fast typist's long word with two slips is never
  fixed. SwiftKey fixes these.
- **How:** two changes in `SpatialDecoder`, both gated by its `strong` flag:
  - A word of six letters or more (`TWO_EDITS_FROM`) may be two edits from its reading instead of one.
  - A spelling that is no word at all costs 30 nats (`STRONG_UNKNOWN_COST`) instead of 22, so a real word a
    little further away can beat it.
- **Limits:** more unusual words are changed too. For example, "tailscale" becomes "tail scale" and "gunay"
  becomes "Gun at", because a spelling the model doesn't know is now cheaper to replace. SwiftKey avoids this in
  practice by learning words you keep (see *Next* below).
- **Commit:** `89c7797a` Android Keyboard: Stronger autocorrect corrects sloppier typing.

### A clause mark takes autocorrect's space

- **Switch:** none. It's a fix that always applies.
- **What:** type "keyboadr", space (autocorrect makes it "keyboard "), then "." and you get "keyboard.", not
  "keyboard .".
- **Why:** upstream already pulled a mark back over the space a *picked* suggestion typed, but not over the space
  that came with an *autocorrection*, so every corrected word before a comma or period left a stray space.
- **How:** `KeyboardEngine` remembers the text before the cursor right after autocorrect, or a text replacement,
  types its fix and space (`fixedAnchor`). A clause-ending mark typed there (`SpaceRules.takesPickedSpace`)
  takes that space, as it does for a pick's. A space you typed yourself after an uncorrected word keeps the mark
  apart, as before and as Gboard does.
- **Commit:** `d5c4c8f0` Android Keyboard: A clause mark takes autocorrect's space, as it takes a pick's.

### Fix earlier words

- **Switch:** Settings › Keyboard › Fix earlier words (off).
- **What:** as SwiftKey does, put the cursor back into a finished word, by deleting back into it or tapping it,
  and the suggestion strip offers:
  - what you originally typed, if autocorrect changed it, and
  - other words its letters could be, judged by the words on both sides. "by" in "I want to by a car" offers
    "buy".

  Tapping one replaces the whole word. Choosing what you originally typed also tells autocorrect not to make that
  fix again, as undoing it with Delete does.
- **Why:** upstream only offers alternatives while a word is being typed. Once it's finished, fixing a wrong
  autocorrection or a real-word typo means retyping it.
- **How:**
  - `EarlierWord.at` finds the word around the cursor from the text before and after it (letters and
    apostrophes). It skips handles, paths, numbers and addresses (`@devin`, `src/main`, `abc123`, `anoma.ly`).
  - The field keeps its last 20 one-word autocorrections (`FIXES_KEPT`), so the strip can offer the original
    spelling.
  - The word's letters become taps on their keys' centres and are read by `SpatialDecoder.revisit` within the
    usual edit distance. Each candidate is scored with the language model against the previous word *and* the
    next word.
  - Up to 3 candidates are shown (`REVISIT_WORDS`). Each must be within 10 nats of the word as it stands
    (`REVISIT_MARGIN`), so a word that fits ("the" in "on the table") offers nothing.
  - Replacing the word selects it with `setSelection` and commits the new one in a batch edit.
- **Limits:**
  - It never shows in password fields, `TYPE_NULL` editors, number pads, or while symbols show.
  - Splits and joins ("deli ate" → "delicate") aren't remembered, since they change more than one word.
- **Commit:** `9446017d` Android Keyboard: Fix earlier words offers other words for one you go back to.

## Dictation from another keyboard

### Voice-only mode answers apps' speech requests

- **Switch:** Labs › Keyboard › Voice-only mode (upstream's lab, off). This fork extends what it does.
- **What:** SwiftKey's mic key now dictates with Hex. A Hex dictation bar appears over the app and starts
  recording at once:
  - ✓ types the text into the field.
  - ✕, tapping outside the bar, or leaving the app types nothing.
- **Why:** upstream's Voice-only mode adds a voice-only keyboard subtype for keyboards that switch to a voice
  keyboard. SwiftKey only switches to one whose package starts with `com.google.android`, so it never picks Hex.
  With Google Voice Typing declined, SwiftKey's mic instead launches the standard speech request,
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, which any app may answer.
- **How:**
  - `VoiceInputActivity` declares an intent filter for `RECOGNIZE_SPEECH`. It's a translucent activity with
    upstream's `VoiceBarView` at the bottom and upstream's `MicCapture`/`LocalSpeech` behind it, the same path
    as the keyboard's dictation.
  - It returns `RESULT_OK` with `EXTRA_RESULTS` (one result) and `EXTRA_CONFIDENCE_SCORES` (1.0), or
    `RESULT_CANCELED`.
  - The activity is enabled with `PackageManager.setComponentEnabledSetting` only while the lab is on, so with
    the lab off Hex never shows up as a speech handler.
- **Setup on the phone:** the first time, Android asks which app should handle speech. Choose Hex and "Always".
- **Commit:** `afe6de6b` Android Keyboard: Voice-only mode answers apps' speech requests.

### Getting the text into the field on Android 17

- **Switch:** none; part of the above.
- **What:** makes SwiftKey reliably type the result. Before this, on Android 17 the text was often lost, or typed
  seconds later into whichever field was focused next (in another app).
- **Why:** SwiftKey doesn't type the result when it arrives. It stores it and types it the next time its
  keyboard starts on a field (`VoiceIntentApiTrigger` in `onStartInputView`). It tries once, reading the field
  with `getExtractedText`, and drops the text if the field can't be read. Two Android 17 behaviours break that:
  - If the speech window never took the keyboard from the app (`FLAG_ALT_FOCUSABLE_IM`), returning to the app is
    a focus change Android only reports (`WINDOW_FOCUS_GAIN_REPORT_ONLY`). The keyboard doesn't start again, so
    the text waits for the next field that starts it.
  - If the speech window does take the keyboard, Compose and React Native apps (Claude, Contacts) restart their
    field about 10–20 ms after getting focus back. SwiftKey's one try then lands on the connection being torn
    down.
- **How:**
  1. While the bar listens, its window keeps `FLAG_ALT_FOCUSABLE_IM`, so the keyboard stays with the app's field.
  2. When the text is ready, the bar hides and clears that flag, so the bar's window takes the keyboard for a
     moment.
  3. It waits until the keyboard's insets (`WindowInsets.Type.ime()`) show on its own window, plus 50 ms, then
     finishes. It gives up waiting after 400 ms.
  4. The app's field then reconnects once, with nothing for the app to restart, and SwiftKey's one try succeeds.
- **Verified:** on an Android 17 emulator with SwiftKey, the text was lost 3 of 3 times before and typed 10 of 10
  after. Also checked on Android 16, and on an Android 17 phone in the Claude app.
- **Limits:**
  - This works around SwiftKey's one-try behaviour; other keyboards that answer speech results differently were
    not tested.
  - While listening, the bar sits over the dimmed keyboard's bottom row.
- **Commits:**
  - `e9fc2c44` Android Keyboard: Hex's speech window takes input focus. An intermediate step, replaced by the next
    commit.
  - `f06fc06f` Android Keyboard: SwiftKey types Hex's dictation on Android 17.

## Next

Planned, not built yet:

- **Learning words you keep, SwiftKey-style.** Keeping or tapping your own spelling of an unknown word in the
  strip ("gunay") teaches it, and long-pressing a suggestion removes it. Upstream reads Android's personal
  dictionary but never adds to it, so an unknown word is "corrected" every time until it's added by hand in
  Settings. This should also fix most of Stronger autocorrect's unwanted splits.
