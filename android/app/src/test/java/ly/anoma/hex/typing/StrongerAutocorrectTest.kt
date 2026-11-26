package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings › Keyboard › Stronger autocorrect, without Labs › Keyboard › Neural autocorrect's model. */
class StrongerAutocorrectTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0)

    private fun decide(typed: String, strong: Boolean) = SpatialDecoder(model).also { it.strong = strong }.let { d ->
        d.decide(typed, typed.map { c -> grid.centres.getValue(c).let { d.score(grid, it.x, it.y) } }, -1, false).replace
    }

    @Test fun aLetterForTheSpaceStillReadsAsTheSpace() {
        for ((typed, meant) in listOf("tonfind" to "to find", "itnwas" to "it was", "tobsee" to "to see", "inbthe" to "in the")) {
            assertEquals("without Stronger autocorrect", meant, decide(typed, false))
            assertEquals("with it, not \"ton find\" or \"fitness\"", meant, decide(typed, true))
        }
    }

    @Test fun twoSlipsInALongWord() {
        assertEquals("keyboard", decide("kehbaodd", true))
    }
}
