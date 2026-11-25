package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ly.anoma.hex.keyboard.KeyboardLetters

/** Keyboard › Fix earlier words: a word you went back to offers other words for it, read with the words on both sides. */
class RevisitTest {
    private val word = TypingWord(SpatialDecoder(TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")), spatial = true)
    private val grid: KeyGrid = KeyboardLetters.rows("en").let { rows ->
        val columns = rows.maxOf { it.length }
        KeyGrid(buildMap {
            for ((row, letters) in rows.withIndex()) for ((i, c) in letters.withIndex())
                if (c.isLetter()) put(c, KeyPoint(((columns - letters.length) / 2.0 + i + 0.5) * 40.0, (row + 0.5) * 56.0))
        }, 40.0, 56.0, KeyRect(64.76, 212.0, 303.1, 257.0))
    }
    private fun offers(before: String, text: String, next: String?) = word.revisit(text, before, next, grid)

    @Test fun theWordAfterPicksTheHomophone() {
        assertEquals("buy", offers("I want to ", "by", "a").first())
    }

    @Test fun aTypoOffersTheWord() {
        assertTrue(offers("my ", "keyboadr", "is").contains("keyboard"))
        assertEquals("Keyboard", offers("", "Keyboadr", null).first())
    }

    @Test fun aWordThatFitsOffersLittle() {
        println("on the table: " + offers("put it on ", "the", "table"))
        println("I want to by a car: " + offers("I want to ", "by", "a"))
        println("their going: " + offers("", "their", "going"))
        println("tail: " + offers("I use ", "tail", "scale"))
        println("form: " + offers("I got a letter ", "form", "my"))
        println("Thai is: " + offers("", "Thai", "is"))
        println("keyboard is: " + offers("my ", "keyboard", "is"))
    }

    @Test fun theStripIsCasedAsTheWordIs() {
        assertTrue(offers("", "By", "the").all { it.first().isUpperCase() })
    }
}
