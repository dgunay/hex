package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCaseTest {
    private fun cycle(word: String, taps: Int) = generateSequence(word, WordCase::next).take(taps + 1).toList()

    @Test fun shiftCyclesTitleCapsLowercase() {
        assertEquals(listOf("sf", "Sf", "SF", "sf"), cycle("sf", 3))
        assertEquals(listOf("Hello", "HELLO", "hello", "Hello"), cycle("Hello", 3))
        assertEquals(listOf("don't", "Don't", "DON'T", "don't"), cycle("don't", 3))
    }

    @Test fun aSingleLetterGoesBackAndForth() {
        assertEquals(listOf("a", "A", "a"), cycle("a", 2))
    }

    @Test fun otherCasingsGoToTitle() {
        assertEquals("Iphone", WordCase.next("iPhone"))
        assertEquals("Mcdonald", WordCase.next("McDonald"))
    }

    @Test fun theWordEndingAtTheCursor() {
        assertEquals("sf", WordCase.ending("going to sf"))
        assertEquals("don't", WordCase.ending("I don't"))
        assertEquals("it’s", WordCase.ending("well it’s"))
        assertNull(WordCase.ending("going to sf "))
        assertNull(WordCase.ending("done."))
        assertNull(WordCase.ending(""))
        // An apostrophe after the word is a closing quote: the cursor isn't at a word.
        assertNull(WordCase.ending("the dogs'"))
    }

    @Test fun aHeldShiftAtAWordTypesCapitalsWithoutTurningShiftOn() {
        val shift = ShiftState()
        shift.hold()
        assertTrue(shift.uppercase)
        assertFalse(shift.up())            // a tap: the word is recased, shift stays off
        assertFalse(shift.uppercase)
        shift.hold(); shift.typed()
        assertTrue(shift.up())             // a chord: the letter was the capital
        assertFalse(shift.uppercase)
        // A tap at a word never counts towards a double tap's caps lock.
        shift.hold(); shift.up(); shift.down(0); shift.up()
        assertFalse(shift.locked)
    }
}
