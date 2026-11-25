package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Keyboard › Fix earlier words: which word the cursor is in, and the words around it. */
class EarlierWordTest {
    @Test fun theEndOfAWordDeletedBackInto() {
        assertEquals(EarlierWord("keyboard", "", "my ", "is"), EarlierWord.at("my keyboard", " is"))
        assertEquals(EarlierWord("by", "", "I want to ", null), EarlierWord.at("I want to by", ""))
    }

    @Test fun theMiddleOfATappedWord() {
        assertEquals(EarlierWord("key", "boadr", "my ", "is"), EarlierWord.at("my key", "boadr is"))
        assertEquals(EarlierWord("w", "ord", "a ", null), EarlierWord.at("a w", "ord."))
    }

    @Test fun contractionsAreOneWord() {
        assertEquals("don't", EarlierWord.at("I don't", "")?.text)
        assertEquals("don’t", EarlierWord.at("I don’", "t know")?.text)
    }

    @Test fun nothingBetweenWords() {
        assertNull(EarlierWord.at("my ", "keyboard"))
        assertNull(EarlierWord.at("", ""))
        assertNull(EarlierWord.at("hello.", ""))
    }

    @Test fun handlesPathsAndNumbersAreNotWords() {
        assertNull(EarlierWord.at("@devin", ""))
        assertNull(EarlierWord.at("src/main", ""))
        assertNull(EarlierWord.at("abc123", ""))
        assertNull(EarlierWord.at("anoma", ".ly"))
        assertNull(EarlierWord.at("snake_case", ""))
    }
}
