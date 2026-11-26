package ly.anoma.hex.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import ly.anoma.hex.R

/** What a dictation sounds for: as desktop Hex's tones. */
internal enum class DictationCue { Start, Stop, Cancel, Error, Done }

/** Desktop Hex's sound sets, in its picker's order, with its names; their files are copied from
 * `resources/audio/sounds/<set>/` (Classic mixed down to mono). Classic and Thock have no done sound, and Classic's
 * error is Signature's, as on desktop Hex off macOS. */
internal enum class SoundSet(val key: String, val label: Int, val character: Int, private val sounds: Map<DictationCue, Int>) {
    Classic("classic", R.string.sound_set_classic, R.string.sound_set_classic_detail, mapOf(
        DictationCue.Start to R.raw.dictation_classic_start, DictationCue.Stop to R.raw.dictation_classic_stop,
        DictationCue.Cancel to R.raw.dictation_classic_cancel, DictationCue.Error to R.raw.dictation_signature_error)),
    Signature("signature", R.string.sound_set_signature, R.string.sound_set_signature_detail, mapOf(
        DictationCue.Start to R.raw.dictation_signature_start, DictationCue.Stop to R.raw.dictation_signature_stop,
        DictationCue.Cancel to R.raw.dictation_signature_cancel, DictationCue.Error to R.raw.dictation_signature_error,
        DictationCue.Done to R.raw.dictation_signature_done)),
    Click("click", R.string.sound_set_click, R.string.sound_set_click_detail, mapOf(
        DictationCue.Start to R.raw.dictation_click_start, DictationCue.Stop to R.raw.dictation_click_stop,
        DictationCue.Cancel to R.raw.dictation_click_cancel, DictationCue.Error to R.raw.dictation_click_error,
        DictationCue.Done to R.raw.dictation_click_done)),
    Soft("soft", R.string.sound_set_soft, R.string.sound_set_soft_detail, mapOf(
        DictationCue.Start to R.raw.dictation_soft_start, DictationCue.Stop to R.raw.dictation_soft_stop,
        DictationCue.Cancel to R.raw.dictation_soft_cancel, DictationCue.Error to R.raw.dictation_soft_error,
        DictationCue.Done to R.raw.dictation_soft_done)),
    Wood("wood", R.string.sound_set_wood, R.string.sound_set_wood_detail, mapOf(
        DictationCue.Start to R.raw.dictation_wood_start, DictationCue.Stop to R.raw.dictation_wood_stop,
        DictationCue.Cancel to R.raw.dictation_wood_cancel, DictationCue.Error to R.raw.dictation_wood_error,
        DictationCue.Done to R.raw.dictation_wood_done)),
    Analog("analog", R.string.sound_set_analog, R.string.sound_set_analog_detail, mapOf(
        DictationCue.Start to R.raw.dictation_analog_start, DictationCue.Stop to R.raw.dictation_analog_stop,
        DictationCue.Cancel to R.raw.dictation_analog_cancel, DictationCue.Error to R.raw.dictation_analog_error,
        DictationCue.Done to R.raw.dictation_analog_done)),
    Thock("thock", R.string.sound_set_thock, R.string.sound_set_thock_detail, mapOf(
        DictationCue.Start to R.raw.dictation_thock_start, DictationCue.Stop to R.raw.dictation_thock_stop,
        DictationCue.Cancel to R.raw.dictation_thock_cancel, DictationCue.Error to R.raw.dictation_thock_error));

    /** The raw resource [cue] plays in this set, null for none. */
    fun sound(cue: DictationCue) = sounds[cue]

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Classic
    }
}

/** The notes a dictation ends with that are failures, which Labs › Dictation sounds' error sound follows. No speech
 * heard isn't one: as on desktop Hex, a dictation with nothing said ends quietly. */
internal fun failed(note: String?) = note in FAILURES
private val FAILURES = setOf("Allow the microphone in Hex", "Microphone unavailable. Try again.",
    "Couldn’t transcribe. Try again.", "Speech model unavailable")

/** Done follows a dictation's text only when it went in this long after the stop sound, as on desktop Hex: sooner,
 * the stop already said it. */
internal const val DONE_AFTER_STOP_MS = 2_000L

/** The microphone's audio is left out for this long after the start sound ends: what the speaker plays reaches it
 * a little late. */
internal const val START_SOUND_MARGIN_MS = 120L

/** Labs › Dictation › Dictation sounds: plays [SoundSet]'s sounds through a [SoundPool] as system sounds, so the phone's
 * silent and vibrate modes quiet them and its system volume sets their level. Off, nothing is loaded. One per process. */
internal class DictationSounds private constructor(private val context: Context) {
    private val prefs = SpeechPreferences(context)
    private var pool: SoundPool? = null
    private var loadedSet: SoundSet? = null
    /** Raw resource → the pool's sound, once loaded. */
    private val ids = HashMap<Int, Int>()
    private val ready = HashSet<Int>()
    /** The sound playing now, or last played. */
    private var stream = 0
    /** A sound to play as soon as it has loaded. */
    private var pending: Int? = null
    /** Each raw resource's length in ms, read once. */
    private val lengths = HashMap<Int, Long>()

    val on get() = prefs.dictationSounds

    /** Loads the chosen set, while the lab is on, so its first sound plays without waiting. */
    @Synchronized fun prepare() {
        if (!on) { release(); return }
        load(prefs.soundSet)
    }

    /** Plays [cue] in the chosen set while the lab is on; returns how long it lasts in ms, 0 when nothing plays. */
    fun play(cue: DictationCue): Long = if (on) play(prefs.soundSet, cue) else 0L

    /** Plays [cue] in [set], the lab on or not (the picker's preview). A sound still loading is skipped, or with
     * [late] plays once it has loaded: never a dictation's start, whose sound the recording leaves out as it plays. */
    @Synchronized fun play(set: SoundSet, cue: DictationCue, late: Boolean = false): Long {
        val raw = set.sound(cue) ?: return 0L
        load(set)
        val id = ids[raw] ?: return 0L
        if (id !in ready) { if (late) pending = id; return if (late) length(raw) else 0L }
        val v = level(); stream = pool?.play(id, v, v, 1, 0, 1f) ?: return 0L
        return length(raw)
    }

    /** The slider's level, as Key click volume's: every 10% below full is 3 dB less. */
    private fun level() = ly.anoma.hex.keyboard.clickScalar(prefs.soundVolume)

    /** The volume slider moved: the sound playing now follows it. */
    @Synchronized fun levelChanged() { val v = level(); pool?.setVolume(stream, v, v) }

    @Synchronized fun release() {
        pool?.release(); pool = null; loadedSet = null; ids.clear(); ready.clear(); pending = null
    }

    private fun load(set: SoundSet) {
        if (loadedSet == set && pool != null) return
        release()
        val p = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        p.setOnLoadCompleteListener { pool, id, status ->
            if (status == 0) synchronized(this) {
                ready += id
                if (pending == id) { pending = null; val v = level(); stream = pool.play(id, v, v, 1, 0, 1f) }
            }
        }
        for (cue in DictationCue.entries) set.sound(cue)?.let { raw -> ids[raw] = p.load(context, raw, 1) }
        pool = p; loadedSet = set
    }

    private fun length(raw: Int): Long = lengths.getOrPut(raw) {
        try {
            context.resources.openRawResource(raw).use { input ->
                val header = ByteArray(44); var n = 0
                while (n < header.size) { val r = input.read(header, n, header.size - n); if (r < 0) break; n += r }
                wavLength(header)
            }
        } catch (_: Exception) { 0L }
    }

    companion object {
        @Volatile private var shared: DictationSounds? = null
        fun of(context: Context): DictationSounds =
            shared ?: synchronized(this) { shared ?: DictationSounds(context.applicationContext).also { shared = it } }
    }
}

/** A 16-bit PCM WAV's length in ms from its first bytes (the canonical 44-byte header: rate at 24, block align at
 * 32, data size at 40); 0 when they aren't that. */
internal fun wavLength(header: ByteArray): Long {
    if (header.size < 44 || String(header, 0, 4, Charsets.US_ASCII) != "RIFF" || String(header, 36, 4, Charsets.US_ASCII) != "data") return 0L
    fun int(at: Int) = (header[at].toLong() and 0xff) or ((header[at + 1].toLong() and 0xff) shl 8) or
        ((header[at + 2].toLong() and 0xff) shl 16) or ((header[at + 3].toLong() and 0xff) shl 24)
    val rate = int(24); val align = int(32) and 0xffff; val data = int(40)
    return if (rate <= 0 || align <= 0) 0L else data / align * 1000 / rate
}

/** Elapsed time for [DONE_AFTER_STOP_MS], on the clock the sounds are timed with. */
internal fun soundClock() = SystemClock.elapsedRealtime()
