package ly.anoma.hex.keyboard

import ly.anoma.hex.typing.TypingWord

/**
 * Keyboard › Fix earlier words: the word the cursor sits in or at the end of while nothing is being typed, as
 * SwiftKey offers other words for a word you delete back into or tap. [head] is its part before the cursor and
 * [tail] the part after, [before] the text before the word and [next] the word after it (after one space), if any.
 */
internal data class EarlierWord(val head: String, val tail: String, val before: String, val next: String?) {
    val text get() = head + tail

    companion object {
        /** How much of the text after the cursor is read: the rest of the word, and for Neural autocorrect the rest of
         * its sentence ([TypingWord.REVISIT_REST_CHARS] past the word). */
        const val AFTER_CHARS = 40 + TypingWord.REVISIT_REST_CHARS
        private fun inWord(c: Char) = c.isLetter() || c == '\'' || c == '’'
        // A word run into these is a handle, path, address or number, not a word to offer others for.
        private fun joined(c: Char?) = c != null && (c.isDigit() || c in "_/@#-.:\\")

        /** The word at the cursor, from the text [before] and [after] it, or null when the cursor is not after one
         * of its letters: just before a word is where a new one goes in. */
        fun at(before: String, after: String): EarlierWord? {
            val head = before.takeLastWhile(::inWord)
            if (head.isEmpty()) return null
            val tail = after.takeWhile(::inWord)
            val text = head + tail
            if (text.isEmpty() || !text.first().isLetter() || !text.last().isLetter()) return null
            val left = before.dropLast(head.length).lastOrNull()
            val rest = after.drop(tail.length)
            if (joined(left) || (joined(rest.firstOrNull()) && rest.firstOrNull() != '.') ||
                (rest.firstOrNull() == '.' && rest.getOrNull(1)?.isLetterOrDigit() == true)) return null
            val next = if (rest.startsWith(' ')) rest.drop(1).takeWhile(::inWord).takeIf { it.any(Char::isLetter) } else null
            return EarlierWord(head, tail, before.dropLast(head.length), next)
        }
    }
}
