package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keyboard › Learn new words: which kept spellings join the personal dictionary, and what that changes. */
class LearnedWordsTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private fun decoder(vararg yours: String) = SpatialDecoder(model).also { it.personal = PersonalWords(model, yours.toList(), emptyMap()) }

    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0)

    @Test fun onlySpellingsNoDictionaryKnows() {
        val d = decoder("Zorbex")
        assertTrue(LearnedWords.learnable("gunay", d))
        assertTrue(LearnedWords.learnable("Tailscale", d))
        assertTrue("an apostrophe or hyphen inside", LearnedWords.learnable("y'all-ish", d))
        assertFalse("the model's word", LearnedWords.learnable("keyboard", d))
        assertFalse("the model's word, capitalised", LearnedWords.learnable("Keyboard", d))
        assertFalse("already yours", LearnedWords.learnable("zorbex", d))
        assertFalse("one letter", LearnedWords.learnable("q", d))
        assertFalse("digits", LearnedWords.learnable("abc123", d))
        assertFalse("a handle", LearnedWords.learnable("@devin", d))
        assertFalse("a trailing mark", LearnedWords.learnable("gunay-", d))
    }

    @Test fun aCapitalOnlyTheSentenceGaveIsNotKept() {
        assertEquals("tailscale", LearnedWords.spelling("Tailscale", ""))
        assertEquals("tailscale", LearnedWords.spelling("Tailscale", "It works. "))
        assertEquals("Gunay", LearnedWords.spelling("Gunay", "I asked "))
        assertEquals("iPhone", LearnedWords.spelling("iPhone", ""))
        assertEquals("NASA", LearnedWords.spelling("NASA", ""))
    }

    @Test fun aLearnedWordIsLeftAloneByStrongerAutocorrect() {
        fun typed(text: String, d: SpatialDecoder): String? {
            val w = TypingWord(d, spatial = true)
            for (c in text) { val p = grid.centres.getValue(c); w.letter(c.toString(), p.x, p.y, grid) }
            return w.replacement()
        }
        val before = decoder().also { it.strong = true }
        assertEquals("Stronger autocorrect splits it while no dictionary knows it", "gun at", typed("gunay", before)?.lowercase())
        val after = decoder("gunay").also { it.strong = true }
        assertNull("kept once learned", typed("gunay", after))
    }
}
