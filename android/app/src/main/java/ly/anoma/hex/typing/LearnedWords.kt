package ly.anoma.hex.typing

/**
 * Keyboard › Learn new words, as SwiftKey learns them: a word you keep as you typed it, over what the keyboard offered
 * or made of it, goes into Android's personal dictionary, so autocorrect leaves it alone and suggests it from then on.
 * Only a spelling no dictionary knows is learned: a word of letters (an apostrophe or hyphen inside it allowed), 2 to
 * 32 long, that is neither the model's nor already yours.
 */
internal object LearnedWords {
    fun learnable(typed: String, decoder: SpatialDecoder): Boolean {
        if (typed.length !in 2..32 || !typed.first().isLetter() || !typed.last().isLetter() ||
            typed.any { !it.isLetter() && it !in "'’-" }) return false
        val key = typed.lowercase().replace('’', '\'')
        val model = decoder.model
        return model.index(key) < 0 && !model.known(key) && decoder.personal?.owns(typed) != true
    }

    /** The spelling to keep for [typed], with [textBefore] the text before it: a capital only the sentence's start
     * gave it ("Tailscale is…") is not kept; one inside the word ("iPhone") or mid-sentence ("Gunay") is. */
    fun spelling(typed: String, textBefore: String): String {
        val end = textBefore.trimEnd()
        val sentenceStart = end.isEmpty() || end.last() in ".!?\n"
        return if (sentenceStart && typed.first().isUpperCase() && typed.drop(1) == typed.drop(1).lowercase())
            typed.replaceFirstChar { it.lowercaseChar() } else typed
    }
}
