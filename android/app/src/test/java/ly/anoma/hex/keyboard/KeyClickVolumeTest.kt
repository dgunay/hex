package ly.anoma.hex.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Keyboard › Key click volume's slider. */
class KeyClickVolumeTest {
    @Test fun fullAtTheTopAndQuieterAllTheWayDown() {
        assertEquals(1f, clickScalar(100), 1e-6f)
        assertEquals("50% is 15 dB down", -15.0, 20 * Math.log10(clickScalar(50).toDouble()), 1e-6)
        val levels = (5..100 step 5).map(::clickScalar)
        assertTrue(levels.zipWithNext().all { (a, b) -> b > a })
        assertTrue(levels.first() > 0f)
    }
}
