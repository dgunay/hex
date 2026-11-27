package ly.anoma.hex.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosingQuoteTest {
    @Test fun aDoubleQuoteClosesOneOpenedInTheParagraph() {
        assertTrue(SpaceRules.closesQuote('"', "And she's like \"my feelings aren't hurt "))
        assertTrue(SpaceRules.closesQuote('"', "she said “hi "))
        assertTrue(SpaceRules.closesQuote('”', "she said “hi "))
        // An opening quote: none open, or every one closed.
        assertFalse(SpaceRules.closesQuote('"', "And she's like "))
        assertFalse(SpaceRules.closesQuote('"', "he said \"no\" and she said "))
        assertFalse(SpaceRules.closesQuote('"', "\"an earlier paragraph\nthen "))
        assertFalse(SpaceRules.closesQuote('\'', "it's 'quoted "))
    }
}
