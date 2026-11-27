# Keyboard changes in this fork

This fork (`dgunay/hex`, branch `android-keyboard-tweaks`) adds keyboard features on top of upstream `dev`. They
came from switching to Hex's keyboard after SwiftKey and missing what it did. This page describes each one so it
can be rebuilt upstream without taking this code: what it does, where its switch is, why it exists, how it
works (the thresholds and Android APIs it relies on), its known limits, and its commit.

Every change is off by default, except the Android 17 voice hand-back, a fix that changes nothing for anyone who
never hits the case (listed below). Each setting is a toggle in
**Settings › Keyboard**, or in Labs where it extends an existing lab. With everything off, the keyboard behaves as
upstream's does.

Each setting's commits still use the names it had when it was added. Commit hashes are on the fork's branch and change when the branch is rebased; the subject lines stay the same.

## Layout

### Remove bottom margin

- **Switch:** Settings › Keyboard › Remove bottom margin (off). Changing it rebuilds the keyboard in place.
- **What:** removes the gap under the bottom row, so the bottom row's keycaps end where the system's navigation
  bar begins. With it off, they end Gboard's 12.2 dp above the bar, as upstream measured.
- **Why:** SwiftKey has no gap there. On a phone with gesture navigation, the gap makes the bottom row feel
  floating and pushes the space bar up.
- **How:** `KeyPlaneView.bottomExtra`, the extra height under the bottom row, becomes `-insetBottom`. That removes
  both the fixed bottom gap and the lower half of the row's own spacing, so the keycap's bottom edge meets the
  bar.
- **Commit:** `639df77d` Android Keyboard: Keys down to the bar removes the gap under the bottom row.

### Key click volume

- **Switch:** Settings › Keyboard › Key click volume (off: the phone's own level, as before). On, a Volume slider
  under it (5–100%, 50% to start) sets the level; while it is dragged a click repeats at a steady pace at the level shown.
- **What:** the key click plays quieter than Android's own key sound.
- **Why:** upstream plays the system's click at the level the phone chooses for it, and on a Galaxy that's louder
  than other keyboards' clicks. Gboard has its own Volume on keypress.
- **How:** `KeyCap` plays `AudioManager.playSoundEffect(sound, volume)` with the slider's scalar (`clickScalar`:
  100% is full level, every 10% below is 3 dB less) instead of the level-less call. That call doesn't check Touch
  sounds, so `click` reads the setting itself first. The System volume still scales it.
- **Commit:** Android Keyboard: Key click volume.

### Steadier suggestion strip, and More suggestions (Labs)

- **What:** while a word is typed, a word already shown in the strip keeps its slot when a later strip for the same
  word comes (the fix after the pause, Neural autocorrect's ranked completions). With Labs › Keyboard › More
  suggestions on (off by default), the strip scrolls right to 8 more words past its three slots, for a word being
  typed, for the next word after a space (8 in all) and for an earlier word (8 in all).
- **Why:** the strips arriving one after another moved the word about to be tapped ("gonna" jumped as "gone" came in),
  and the three slots hid likely words.
- **How:** `Suggestion.place(slots, previous)` keeps a shown word's slot while the word typed so far goes on; only
  what was typed, the fix and the emoji have places of their own (the fix keeps the middle, so a word there moves
  once to a free slot). Before the pause, a word the keyboard doesn't know is laid out as the strip after it will
  be: what was typed on the left, the middle kept for the fix, the likeliest completion on the right
  (`strip(fixLater = true)`), so "gonna" stays put when "gone" comes. `TypingWord.strip(more = 8)` returns the words past the slots in the same order, and
  `StripView` lays the slots at the strip's width inside a `HorizontalScrollView` with the rest after them; a new
  word scrolls back to the start.
- **Commit:** Android Keyboard: Steadier suggestion strip, and More suggestions (Labs).

### Hold delay (Labs)

- **Setting:** Labs › Keyboard › Hold delay, 100–600 ms in steps of 25 (300, upstream's fixed value).
- **What:** how long a key is held before its symbols and accents show (letters, period, comma on =<, the symbol
  pages and the number row). Holding the Hex key, or SwiftKey layout's comma, starts a dictation 50 ms after it,
  never sooner than 250 ms so a slow tap stays a tap: 350 ms at the default, as before.
- **Why:** upstream's 300 ms (Gboard's) is fixed; asked for to make held symbols quicker.
- **How:** `TypingPreferences.holdDelay`/`talkHold`, read at each press, in place of `KeyboardMetrics.HOLD_MS` and
  `TALK_HOLD_MS`.
- **Limits:** the space bar's long press (the language menu) keeps Android's own delay (Accessibility › Touch & hold
  delay).

### SwiftKey layout (Labs)

- **Switch:** Labs › Keyboard › SwiftKey layout (off).
- **What:** Microsoft SwiftKey's arrangement (English QWERTY, Android), for muscle memory carried over from it
  (anomalyco/hex#2748, closed upstream without a change):
  - **Held letters:** SwiftKey's symbols, shown as key hints, whether or not Symbols on letter keys is on:

    ```text
    q w e r t y u i o p    % ^ ~ | [ ] < > { }   (only beside the Number row; otherwise the digits)
    a s d f g h j k l      @ # & * - + = ( )
    z x c v b n m          _ $ " ' : ; /
    ```

  - **Bottom row:** ?123, emoji, comma, space, period, enter (the emoji key before the comma).
  - **Held period:** one row, `! , . ?`, with the period selected.
    A sideways slide opens it at once, with no wait for the hold, and the finger already picks: a flick one key
    left types `,`, two `!`, one right `?`.
  - **Held comma:** on the letters, a dictation as the Hex key held starts one (after 350 ms), finished when the comma
    lifts; it types no comma. SwiftKey's comma holds its voice typing.
  - **123 page:** digits; `@ # $ & _ - ( ) = %`; then `{&=`, `" * ' : / ! ? +` and delete. Its bottom row
    has no emoji key: ABC, comma, a wider space, period, enter.
  - **{&= page:** `£ € ¥ ¢ © ® ™ ~ ¿`; Tab, `[ ] { } < > ^ ¡`; then 123, `` ` ; ÷ \ | ¦ ¬ `` and delete; and
    ABC, space, `× § ¶ °`, enter.
- **How:** `LetterFeedback.letterSymbol(swiftKey)`, `KeyboardLetters.swiftKeyNumbers`/`swiftKeySymbols`, and
  `KeyPlaneView.layBottom`, which moves and hides the bottom row's keys in place (`KeyRowLayout.place`, gone keys
  take no room) so a finger already on ?123 keeps its press. Tab types a tab character.
- **Limits:**
  - Taken from screenshots of SwiftKey on one phone; the symbol pages' keys aren't sized exactly as SwiftKey's.
  - A local currency (Labs) takes $'s place on x and the 123 page, and $ takes its slot on {&=, as on Gboard's
    pages.
  - The comma has no microphone hint, as SwiftKey's does.

### Shift changes a word's case (Labs)

- **Switch:** Labs › Keyboard › Shift changes a word's case (off).
- **What:** with the cursor at the end of a word, the one being typed or one already in the field, a shift tap cycles
  it lowercase → Title → ALL CAPS → lowercase ("sf", "Sf", "SF"), as Microsoft SwiftKey does. The strip offers its
  words in that case, and space keeps it as cased (no autocorrection). A word cased otherwise ("iPhone") goes to
  Title. Away from a word, shift is as before. The keys follow the word: ALL CAPS locks shift, so letters typed
  after it are capitals too, until the word ends (space, a mark, the cursor leaving it); the other cases leave shift
  off. The keys don't flash to capitals while shift is down for a recase.
- **Why:** asked for: a lowercase "sf" the strip offered only in lowercase, wanted as "SF". The keys following the
  word was asked for too: they went back to lowercase on every tap, whatever case the word was in.
- **How:** `KeyboardEngine.shiftDown`/`recase`: at a word, shift only holds (`ShiftState.hold`), so a letter typed
  while it is held is still a capital (a chord) and nothing recases; a tap recases as it lifts. The word being typed
  keeps its place (`TypingWord.recase`, literal from then on); one in the field becomes the word being typed again,
  as an undone autocorrection does. `WordCase` holds the cycle.
- **Limits:**
  - At a word, a tap no longer turns shift on for the next letter (hold shift for a capital mid-word: "iP"), and two
    quick taps there recase twice rather than lock caps. Caps lock still goes off with a tap.
  - The cursor must end the word: inside a word, or after its space, shift is as before.

### Picks and spaces in the middle of text

- **What:** fixes from a day's typing log, all where text follows the cursor.
  - A next word picked right after a pick that held its space (the cursor before an existing space, as in "up to‸
    best") goes in with a space before it. The tap used to do nothing.
  - Space there keeps the held space for the next word, as a space after a pick is swallowed ("up to", space, "be"
    is "up to be", not "up tobe"); a second space goes in.
  - A pick with a letter right after the cursor (Gmail's Smart Compose shows its suggestion as text there) puts the
    word in with its space before that text. The tap used to do nothing.
  - A closing double quote takes the space before it, whether a pick, an autocorrection or you typed it, when a quote
    is open in the paragraph (`"hello there "` then `"` is `"hello there"`). An opening quote keeps its space.
- **Why:** reported: strip taps that stopped registering, and deleting a space before a closing quote by hand.
- **How:** `KeyboardEngine.pickSuggestion` takes the held space first (`takeHeldSpace`), and no longer returns when
  `canEndWord` is false; `space` swallows once at `heldAnchor` (`heldSpaced`); `type` lets
  `SpaceRules.closesQuote` (an odd count of `"` or more `“` than `”` in the paragraph) take the space.
- **Limits:** typing letters straight against the next word ("shapin‸the") still makes one word of them, which the
  strip may then correct as a whole.

## Delete

### Delete words when held

- **Switch:** Settings › Keyboard › Delete words when held (off). While it's on, two sliders show under it:
  - **Initial delay:** 200–1000 ms in steps of 50, 500 by default.
  - **Acceleration:** 0–50% in steps of 5, 20 by default.
- **What:** holding delete removes whole words, as SwiftKey's held delete does, starting gently and speeding up
  the longer you hold ("the longer you hold it, the faster it deletes"). A tap still deletes one letter.
- **Why:** deleting a sentence letter by letter at the 50 ms repeat rate either overshoots or takes too long.
  Word steps that speed up are easier to stop on the right word.
- **How:**
  - Delete still waits the usual 400 ms hold threshold (`DELETE_REPEAT_DELAY`, shared with letter repeat). Then it
    takes a word at once: no letters first.
  - Each next word waits the previous gap times `1 − Acceleration`, starting from Initial delay, down to a floor of
    90 ms (`DELETE_WORD_FASTEST`). With the defaults: 500, 400, 320, 256… ms.
  - A "word" is what upstream's delete slide already takes: the word before the cursor plus the spaces and
    punctuation after it (`DeleteSlide.wordLengths`), deleted with `deleteSurroundingText`.
  - Each word plays `EFFECT_HEAVY_CLICK`, a stronger tap than a letter's, so you can count words by feel. It is
    played only while Haptics is on.
  - The settings are read when delete goes down, so changes apply on the next press.
- **Limits:** password fields, `TYPE_NULL` editors (terminals), a selection, or an unknown cursor position can't
  be read as words, so there it repeats letters as before.
- **Commits:**
  - `0a7180cc` Android Keyboard: Hold delete for words, as SwiftKey's held delete does
  - `ebfb41a3` Android Keyboard: Hold delete for words takes only words, each with a heavy click
  - `b24e58b7` Android Keyboard: Hold delete for words starts slower; Diagnostics names its tap
  - `01f2486b` Android Keyboard: Hold delete for words waits 500 ms for the second word
  - `fc7598bb` Android Keyboard: Hold delete for words' First wait and Speed-up

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
  - The missed-space fallback (a letter typed where the space should be, "tonfind" for "to find") still competes
    with the best reading. Otherwise the higher cost lets a one-space split ("ton find") or a two-edit word
    ("fitness" for "itnwas") win first, and the fallback never runs. Split bench with Stronger on: stray-letter
    word errors 17.25% → 16.70% (11.98% → 11.63% with Neural autocorrect), plain sentences unchanged.
- **Limits:** more unusual words are changed too. For example, "tailscale" becomes "tail scale" and "gunay"
  becomes "Gun at", because a spelling the model doesn't know is now cheaper to replace. Learn new words (below)
  fixes this for words you keep.
- **Commit:** `89c7797a` Android Keyboard: Stronger autocorrect corrects sloppier typing.
  The fallback's contest: Android Keyboard: Stronger autocorrect still reads a letter typed for the space.

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
    next word. Its search keeps 256 partial words at each letter (`REVISIT_BEAM`; typing keeps 48): with 48, a long
    word with one wrong first letter ("dandidate") lost "candidate" to cheaper dead ends and offered nothing.
  - Up to 3 candidates are shown (`REVISIT_WORDS`). Each must be within 10 nats of the word as it stands
    (`REVISIT_MARGIN`), so a word that fits ("the" in "on the table") offers nothing.
  - Replacing the word selects it with `setSelection` and commits the new one in a batch edit. A space follows it
    as after any pick, unless the text after already separates it (a space or a closing mark): then the cursor
    stays before that space, and the next word typed takes it.
- **Limits:**
  - It never shows in password fields, `TYPE_NULL` editors, number pads, or while symbols show.
  - Splits and joins ("deli ate" → "delicate") aren't remembered, since they change more than one word.
- **Commit:** `9446017d` Android Keyboard: Fix earlier words offers other words for one you go back to.
  Its pick's space: Android Keyboard: Fix earlier words' pick takes its space as any pick does.
  The wider search and the model's order: Android Keyboard: Fix earlier words reads the whole sentence with Neural
  autocorrect.
- **With Neural autocorrect:** the model ranks the words for one you went back to by the whole sentence, the words
  after it as well as before. Each reading is scored with the rest of its sentence after it ("buy a new car this
  weekend.", up to 80 characters), weighed against its taps as typing weighs it (1.5), and offered only within 10 of
  the word as it stands, as the pairs' margin. The pairs' strip shows at once; the model's replaces it when it
  answers, in the background. Its likeliest may be one the pairs left out (it reads 8 within 20).
- **Benchmark:** `EarlierWordsBench` (run with `HEX_BENCH_EARLIER=1`): the gate's 2,000 held-out sentences, each
  word the synthetic taps typed wrong (3,127), gone back to with the rest of the sentence right; and 1,074 words typed
  right.

  | | Meant word first | In the strip | Offers on a right word |
  |---|---|---|---|
  | Before (beam 48, pairs) | 81.8% | 84.9% | 31.0% |
  | Beam 256, pairs | 88.4% | 92.5% | 32.8% |
  | Beam 256, model | 92.6% | 93.3% | 11.8% |

  Most of what's still missed has no reading within one edit (two typos, names). The taps are synthetic, not
  recorded.

### Learn new words

- **Switch:** Settings › Keyboard › Learn new words (off).
- **What:** as SwiftKey does, a word you keep the way you typed it joins your personal dictionary, so autocorrect
  leaves it alone and suggests it from then on. You keep a word by:
  - tapping your own spelling (in quotes) in the suggestion strip,
  - undoing an autocorrection of it, with Delete or the Undo tool, or
  - choosing what you first typed in Fix earlier words.

  The strip then says `Added “gunay” to your dictionary. Tap to remove`. Long-pressing one of your dictionary's
  words in the strip offers `Tap to remove “gunay” from your dictionary`.
- **Why:** upstream reads Android's personal dictionary but never adds to it. A name or product the model doesn't
  know ("gunay", "tailscale") is "corrected" every time until it's added by hand in Android's settings, and
  Stronger autocorrect splits such words more readily. Once a word is in the dictionary, both leave it alone.
- **How:**
  - `LearnedWords.learnable`: only a spelling no dictionary knows. It must be 2–32 characters, start and end with a
    letter, and contain only letters, apostrophes and hyphens. It must not be the model's word (`index`/`known`)
    or already yours.
  - `LearnedWords.spelling`: a capital that only the start of a sentence gave the word isn't kept ("Tailscale is…"
    is saved as "tailscale"). Capitals inside a word ("iPhone") or mid-sentence ("Gunay") are kept.
  - It's written with `UserDictionary.Words.addWord` (frequency 250, as Android's own Add uses) under the
    language's locale, off the main thread. A keyboard that's the current input method may write the dictionary
    without a permission.
  - Upstream's existing content observer reloads every model's personal words when the dictionary changes.
  - Removing deletes the dictionary rows with that word (`word = ? COLLATE NOCASE`).
  - Removing also takes the word out of upstream's Labs › Keyboard › SwiftKey words list, if it came from there.
- **Limits:**
  - Never in password fields, or fields that ask not to be learned from (incognito).
  - A word you simply type and keep, without tapping it or undoing a fix, isn't learned. Hex keeps no record of
    what you type, so it can't count repeats the way SwiftKey does.
  - Words go into Android's shared personal dictionary, so other keyboards and spell checkers see them too.
- **Commit:** Android Keyboard: Learn new words, as SwiftKey learns them.

### Neural autocorrect (Labs)

- **Switch:** Labs › Keyboard › Neural autocorrect (off). On, it offers a one-time 145 MB download. The row then
  shows Ready, a Test speed action, and how long space has waited for the model while you typed.
- **What:** English autocorrect decides with a small language model that reads the text before the word (the
  keyboard's last 96 characters), instead of only the word before it. It picks the reading that fits the sentence,
  and it can tell a real word it has never seen ("tailscale") from a typo, because it scores words piece by piece
  rather than looking them up.
- **Why:** upstream's decoder scores each reading with word pairs (a bigram model). That can't use more than one
  word of context and gives every unknown spelling the same flat cost, so names and tech words get split or
  "corrected". Gboard (2024) and iOS 17 both moved to neural language models for this.
- **How:**
  - The model is Hugging Face's SmolLM2-135M (Apache-2.0), 8-bit GGUF, pinned by revision, size and SHA-256 in
    `ModelStore.neural`. It runs in llama.cpp v1.0.0 (pinned by commit and SHA-256), built into a library of its
    own, `libhextyping.so`, like Faster English dictation's. Two patches keep the keyboard's process from
    starting a debugger or loading code from a path (`ggml-no-debugger.patch`, `llama-no-dlopen.patch`).
  - It's a noisy-channel decoder. The taps' cost of each reading stays as upstream computes it. Only the
    word-pair part is replaced by the model's cost in nats, −log P(word | text before) − log P(the word ends
    there), weighed 1.5 (`neuralWeight`). The end-of-word term keeps a prefix ("tail") from beating the whole
    word. Margins and every other rule are unchanged.
  - When space finishes a word, the model scores the spelling as typed and the 9 cheapest readings
    (`NEURAL_READINGS`) in one batch. Readings that begin alike share their tokens (a tree), each word's
    sequence copied from the context's cache. The missed-space fallback ("thankyou" → "thank you") is taken
    only where the model rates the split no less likely than the spelling.
  - Run-together words, only with the model: a letter typed where the space was ("thatshcrazy" → "that's
    crazy") reads as two words spelled as typed around it, at 4 nats more than a missed space
    (`strayLetterCost`), never where that letter finishes a contraction ("canthappen" stays "can't happen").
    The halves, and the words of a run of three or more ("thatssocrazy" → "that's so crazy"), may be
    contractions typed without their apostrophe. The model then judges the split against the spelling, as
    before: "tailscale", "kubernetes", "wireguard" and 20 other tech words checked stay whole.
  - The model reads the text before a word while its first letters are typed (`prepare`), so space only decodes
    the readings. The context's start moves 200 characters at a time, so the cache holds between words.
  - Space waits for it at most 90 ms (`neuralBudget`). Without an answer by then, or before the model has
    loaded, the word pairs decide as with the switch off. The model loads on the first word once the switch
    is on, runs on 2 threads at display priority, and is freed after 5 idle minutes.
  - Swipes too: when the finger lifts, the model scores the swipe decoder's 12 cheapest readings (one batch),
    written as they'd go in, in place of their word pairs, weighed 1.0 (`SwipeReader.neuralWeight`). A loose
    swipe, one whose best fit still costs more than 1.5 a letter (corners cut, "supposed" as a line back and
    forth), has its path cost weighed 0.5 (`neuralPath`, `neuralLoose`), so the sentence counts for more; a
    tidy one's counts in full. It starts reading the text before when the finger goes down. The preview while
    the finger moves still uses the pairs.
  - Completions while a word is typed: at each letter the model reads, without waiting, the word pairs' few
    completions after the word before and the commonest words with those letters, 24 in all, in two batches
    (`TypingWord.rankCompletions`, `NeuralScorer.costsLater`). The second batch is its own task, so a word space
    finishes meanwhile is read first; a newer letter's request takes the place of an older one's. Once answered,
    the strip's completions go likeliest in the sentence first, in whichever strip shows ("We were probably go"
    offers "gonna"); after the pause the likeliest stays beside what space puts in, ahead of the taps' other
    readings ("gon": "gone", then "gonna"). Space then finds the finished word already read.
  - Next-word predictions after a space mid-sentence: the same, for the word pairs' likeliest after the word before
    and the commonest words, 24 in all, read with the whole text before (`TypingWord.rankPredictions`): "I need to
    pick up my" no longer reads only "my". Held-out sentences (2,375 next words): the next word is among the 3 for
    20.1% → **29.3%**, first for 10.7% → **20.2%**.
  - A tapped word you taught the keyboard (Learn new words, your own words) never costs more than it did without
    the model, which barely knows it: it takes the cheaper of the two. A swipe spells nothing to keep, so there
    your words get the model's cost: with thousands of imported SwiftKey words, a vague path would otherwise
    read as one of them.
- **Measured:**
  - English quality gate (2,000 held-out sentences of noisy taps, `quality-gate.py en --neural MODEL
    --neural-lib LIB`): word errors left 4.90% → **3.34%** (reduction 85.50% → 90.13%), correct words changed
    0.32% → **0.05%**. Swipes (`swipe-bench.py en --neural MODEL --neural-lib LIB --neural-weight 1.0`, 12,515
    held-out swipes with the sentence before): first word right 96.9% → **98.1%** (in the strip 99.7% either
    way); 1.0 was the best of 0.5, 1.0 and 1.5 on even sentences and held on odd ones (96.8% → 98.0%). On
    sloppier synthetic paths (`--noise sloppy`, and `vague`, which cuts corners hardest): **82.0% → 89.3%** and
    **77.0% → 85.7%**, against 87.3% and 83.4% with the path weighed in full; tidy paths keep 98.1%. The loose
    rule was chosen on even sentences from each swipe's readings (`--neural-dump`), against fixed weights 0.35–1.0
    and smooth ones; a fixed 0.5 gains about as much on sloppy paths but tidy ones fall to 97.2%. A swipe takes
    33 ms median on the host (45 ms 99th percentile). Run-together words (`split-bench.py`, the gate's sentences
    with one space dropped or typed as a letter): word errors with a dropped space 7.71% → 7.67%, with a stray
    letter 15.7% → **12.2%** (sentences all right 39% → 52%; the word pairs alone 13.1% and 20.5%), the gate's
    own sentences unchanged. Completions (400 held-out sentences, words of 4 letters or more, the strip's 3 slots):
    after 1 letter 25.4% → **43.1%** offer the word, after 2 51.6% → **72.0%**, after 3 80.4% → **91.2%** (12
    candidates, one batch, gave 36.7%, 66.2%, 88.9%). The larger SmolLM2-360M and Qwen3-0.6B were no better on the gate before tuning
    (3.92%, 4.11%), and are 2.7× and 4.4× the download.
  - Galaxy Z Flip6, `hextyping-cli MODEL --bench 2`: an ordinary word 15 ms (95th percentile 19–23 ms); a word
    with 10 readings 46 ms; reading the text before a word 8 ms. Warm and at 19% battery, the same took 20, 28,
    75 and 12 ms. 4 threads were faster typically but stalled up to 200 ms; 6 were slower.
  - Memory: 224 MB while loaded. The weights are read, not mapped: the CPU backend keeps a repacked copy for its
    dot-product kernels (twice as fast), and a mapped file stayed resident beside it (334 MB).
- **Asked ahead:** after each letter, once the strip is drawn, the decoder makes the decision space would make
  now, with a stand-in scorer that only notes the words it would ask the model about (`TypingWord.prescore`). Those
  are read in the background, before the strip's, so space usually finds every cost already read and waits for none.
  The costs are the model's for the same words after the same text, so the correction is the same
  (`theWordsSpaceAsksAboutAreAskedAhead`). A missed space's reading, which space only asks about after the model's
  first answer, still waits. Background reads go 4 words at a time (12 before), so a space typed straight after a
  letter waits behind at most 4.
- **Too late:** a read space gave up waiting for leaves the worker's queue if it hasn't started, so it never holds up
  the reads behind it (the strip's, the next word's).
- **Limits:**
  - English only. It needs the 145 MB download and keeps 224 MB in memory while loaded.
  - The weight isn't tuned yet, and the gate's short Tatoeba sentences can't show what longer context or
    unknown words gain. A messages-style test set with names and tech words is still to be made.
  - Next-word predictions at a sentence's start or after a comma keep
    their fixed lists.
  - A stray letter splits only between two words spelled exactly as typed: with a typo in either half
    ("youbkger" for "you over") it doesn't.
  - Completions come from the 24 commonest words with the letters typed (and the word pairs' few): one rarer
    than that ("gonna" for "g") shows only once more letters leave it among them.
  - Built for ARMv8.2 with dot product (every recent phone). Emulators that translate ARM can't run it.
- **Commit:** Android Keyboard: Neural autocorrect, a small language model reading the sentence (Labs).

## Emoji

### Emoji shortcodes (Labs)

- **Switch:** Labs › Keyboard › Emoji shortcodes (off).
- **What:** type a colon and a name, such as `:fire`, and the strip offers its emoji (🔥, then others the name
  starts or describes: `:piz` offers 🍕). Tap one to put it in place of the `:name`, or type the closing colon
  (`:fire:`) for the emoji the name names exactly.
- **Why:** finding an emoji meant scrolling the AndroidX picker's categories by eye. iPhone Hex has the same lab.
- **How:**
  - `typing/EmojiIndex.kt` reads iPhone Hex's emoji index (`assets/emoji/index-en.txt`, copied from
    `ios/HEXShared/FullKeyboard/Emoji/emoji-index-en.txt`: Unicode's names and CLDR keywords, Unicode License v3,
    plus Hex's chat words), loaded the first time a shortcode is typed. Emoji newer than Android 12's Emoji 13.1
    are kept only where the phone's font draws them.
  - `EmojiShortcode` ports iPhone Hex's rules: a token is a colon after whitespace or the field's start, then a
    name of two or more letters or digits, so `abc:pizza`, `:p`, `:-D` and `10:30` stay text. Exact names and
    aliases come first, then the names and keywords the token starts, one emoji per family.
  - `KeyboardEngine` shows them in the strip after each key, takes a pick over the token, and puts in the exact
    match on a closing colon.
- **Limits:** English names only. Your use doesn't rank them yet.
- **Commit:** Android Keyboard: Emoji shortcodes (Labs).

### Emoji search (Labs)

- **Switch:** Labs › Keyboard › Emoji search (off).
- **What:** a 🔍 key in the emoji panel's bottom row, between ABC and the space bar. Tapping it brings back the
  letter keys under a grid of the emoji found (two and a half rows, scrolling), with a search line in the strip:
  a back button and what you've typed. The grid re-sorts with each letter; before one, it holds Recently used (or
  the smileys). Tap one to put it in; the search stays for another. Back returns to the panel; Return or the emoji
  key ends it.
- **Why:** the AndroidX picker has no search, and can't be given one. Gboard and iPhone Hex search.
- **How:** `KeyboardEngine` holds the search while it runs: letters, digits, space, delete (held: a word) and
  Return edit it instead of the field, and a swipe does nothing. `EmojiIndex.search` (iPhone Hex's rules: each
  word must start a word of the emoji's name or keywords; whole name words first) finds up to 60. `EmojiSearchGrid`, between the
  strip and the keys, shows them. A found emoji goes into the panel's Recently used.
- **Limits:** English words only. Your use doesn't rank the results.
- **Commit:** Android Keyboard: Emoji search (Labs).

## Dictation

### Dictation sounds (Labs)

- **Switch:** Labs › Dictation › Dictation sounds (off). On, a Sound row picks the set: desktop Hex's seven, in its
  order and with its names (Classic keys first and chosen, as on desktop, then Kalimba, Click, Soft tone, Wood,
  Warm synth, Thock). Picking one plays its start and stop.
- **What:** a sound as the microphone opens, as you finish (✓, letting go of the Hex key, or the 1-minute stop),
  when ✕ cancels, and when a dictation fails (no microphone, no model, couldn't transcribe). A text that goes in
  2 s or more after the stop sound plays done, in the sets that have one. Nothing said ends quietly, as on desktop.
  It works wherever Hex dictates: the keyboard, Home, the Quick Settings sheet and apps' speech requests.
- **Why:** desktop Hex confirms each step with a sound; Android Hex was silent.
- **How:** `DictationSounds` plays the sets' WAVs, copied from `resources/audio/sounds/` (Classic mixed down to
  mono; 770 KB in all), through a `SoundPool` as system sounds (`USAGE_ASSISTANCE_SONIFICATION`), so silent and
  vibrate modes quiet them and the System volume sets their level; the Sound page's Volume slider (5–100%,
  100% to start, as Key click volume's: every 10% is 3 dB less) sets theirs below it. `MicCapture` plays each cue. The start sound
  plays as recording begins, and the samples recorded until it ends, plus 120 ms, are zeroed, so it is never taken
  for speech or transcribed. Off, nothing is loaded.
- **Limits:** the first start after the lab turns on can be silent while its set loads.
- **Commit:** Android: Dictation sounds, desktop Hex's sound sets (Labs).

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

## Dropped because upstream built it

- **A clause mark takes autocorrect's space** ("keyboadr", space, "." gave "keyboard." instead of "keyboard .").
  Upstream added the same behaviour as Labs › Keyboard › Punctuation after autocorrect (#2814, off by default), so
  this fork dropped its own always-on version and uses upstream's lab.
- **Haptic strength** (a 10–100% slider for key taps). Removed from the fork as unneeded. Hold delete's heavier
  per-word tap stayed.

## Diagnostics

### Typing log (Labs)

- **Switch:** Labs › Keyboard › Typing log (off), with Delete typing log and its size below it.
- **What:** a plain-text log, on the phone only, of every key (and where it was tapped), strip pick, swipe, delete,
  cursor move and suggestion strip, each with the text around the cursor after it, the word being typed, the
  autocorrection Undo would take back, and which space rules are pending. The Hex menu's **Mark a problem** writes a
  marker with the text as it is then. Settings changes and each field's app and input type are logged too.
- **Why:** to find the spacing and punctuation papercuts from a day of natural typing instead of from descriptions.
- **Privacy:** password fields and fields that ask keyboards not to learn (incognito) are never logged, not even
  their keys. Files are in the app's private storage (`files/typing-log/`), read with
  `adb shell run-as ly.anoma.hex cat files/typing-log/*` on this debug build. Up to 200 MB, oldest files dropped.
- **Model timings:** with Neural autocorrect on, it also logs how long space waited for the model (how long its word
  queued and what it queued behind, how long it ran, for how many words) and each strip batch's and context read's
  time, so the delay felt while typing can be taken apart. Timings only, no text.
- **How:** `TypingLog` writes lines on its own thread. `KeyboardEngine` posts a snapshot after each entry point
  (`logAfter`), so it sees the text as the event left it; `StripView` logs each strip.
- **Limits:** a line is two extra reads of the field's text per event, so typing is a little slower while it's on.
- **Commit:** Android Keyboard: Typing log (Labs).
