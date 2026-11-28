package ly.anoma.hex.keyboard

/** Labs › Keyboard › Shift changes a word's case: what a shift tap at a word makes of it, as Microsoft SwiftKey does,
 * lowercase → Title → ALL CAPS → lowercase. A word cased any other way ("iPhone") goes to Title. A single letter has
 * only two cases, so it goes back and forth. */
internal object WordCase {
    fun next(word: String): String {
        val lower = word.lowercase(); val upper = word.uppercase()
        val title = lower.replaceFirstChar { it.titlecase() }
        return when (word) {
            lower -> if (title != word) title else upper
            title -> if (upper != word) upper else lower
            upper -> lower
            else -> title
        }
    }

    /** The word right before the cursor, the text before it ending in letters (an apostrophe may join two), or null. */
    fun ending(before: String): String? {
        var start = before.length
        while (start > 0) {
            val c = before[start - 1]
            if (c.isLetter()) { start--; continue }
            if ((c == '\'' || c == '’') && start - 2 >= 0 && before[start - 2].isLetter() && start < before.length) { start--; continue }
            break
        }
        return before.substring(start).takeIf { it.isNotEmpty() }
    }

    /** The rest of the word the cursor is inside: the letters [after] it starts with (an apostrophe may join two). */
    fun starting(after: String): String {
        var end = 0
        while (end < after.length) {
            val c = after[end]
            if (c.isLetter()) { end++; continue }
            if ((c == '\'' || c == '’') && end > 0 && end + 1 < after.length && after[end + 1].isLetter()) { end++; continue }
            break
        }
        return after.substring(0, end)
    }
}
