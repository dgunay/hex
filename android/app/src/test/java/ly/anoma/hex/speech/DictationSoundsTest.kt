package ly.anoma.hex.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labs › Dictation › Dictation sounds' sets and when they sound. */
class DictationSoundsTest {
    @Test fun everySetStartsStopsCancelsAndFails() {
        for (set in SoundSet.entries) for (cue in listOf(DictationCue.Start, DictationCue.Stop, DictationCue.Cancel, DictationCue.Error))
            assertNotNull("$set $cue", set.sound(cue))
        assertNull("Classic has no done sound, as on desktop Hex", SoundSet.Classic.sound(DictationCue.Done))
        assertNull(SoundSet.Thock.sound(DictationCue.Done))
        assertEquals("Classic, until another is chosen", SoundSet.Classic, SoundSet.of(null))
        for (set in SoundSet.entries) assertEquals(set, SoundSet.of(set.key))
    }

    @Test fun onlyFailuresSoundTheError() {
        assertTrue(failed("Couldn’t transcribe. Try again."))
        assertTrue(failed("Microphone unavailable. Try again."))
        assertFalse("nothing said ends quietly", failed("No speech heard"))
        assertFalse(failed("Stopped at 1 minute"))
        assertFalse(failed(null))
    }

    @Test fun aWavsLengthFromItsHeader() {
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0); "data".toByteArray().copyInto(header, 36)
        fun put(at: Int, v: Int) { for (i in 0..3) header[at + i] = (v shr (8 * i)).toByte() }
        put(24, 48_000); header[32] = 2; put(40, 48_000 * 2 / 10)
        assertEquals(100L, wavLength(header))
        assertEquals("not a WAV", 0L, wavLength(ByteArray(44)))
    }
}
