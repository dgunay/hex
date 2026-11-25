package ly.anoma.hex.typing

/**
 * Labs › Keyboard › Neural autocorrect: a small language model (a GGUF file, run by llama.cpp in libhextyping) that
 * scores the words autocorrect is choosing between with the text before them, in place of the word pairs. See
 * `app/src/main/cpp/hextyping.cpp`.
 */
internal class NeuralModel private constructor(private var handle: Long) : SpatialDecoder.NeuralScorer, AutoCloseable {
    @Synchronized override fun costs(context: String, words: List<String>): DoubleArray? {
        if (handle == 0L || words.isEmpty()) return null
        val out = NeuralWords.nativeScore(handle, NeuralWords.context(context), words.toTypedArray()) ?: return null
        return DoubleArray(out.size) { out[it].toDouble() }
    }

    @Synchronized override fun prepare(context: String) {
        if (handle != 0L) NeuralWords.nativePrepare(handle, NeuralWords.context(context))
    }

    @Synchronized override fun close() {
        if (handle != 0L) NeuralWords.nativeFree(handle)
        handle = 0L
    }

    companion object {
        /** The model at [path], or null when it can't be loaded. */
        fun open(path: String, threads: Int): NeuralModel? {
            if (!NeuralWords.loaded) return null
            val handle = NeuralWords.nativeLoad(path, threads)
            return if (handle == 0L) null else NeuralModel(handle)
        }
    }
}

/** libhextyping's entry points. On a desktop JVM (the typing benchmark), `-Dhex.typing.lib=PATH` names the library. */
internal object NeuralWords {
    /** How much of the text before a word the model reads: enough for the sentence and the one before it. */
    private const val CONTEXT_CHARS = 600

    val loaded: Boolean by lazy {
        try {
            System.getProperty("hex.typing.lib")?.let { System.load(it) } ?: System.loadLibrary("hextyping")
            true
        } catch (_: Throwable) { false }
    }

    /** The text before a word as the model reads it, without the trailing space the word goes after: up to
     * [CONTEXT_CHARS] of it, starting at a word. Its start moves on [CONTEXT_STEP] characters at a time, not a word at a
     * time, so the next word's context begins as this one's does and the model's cache of it holds. */
    fun context(before: String): String {
        val text = before.trimEnd()
        if (text.length <= CONTEXT_CHARS) return text
        val cut = ((text.length - CONTEXT_CHARS) / CONTEXT_STEP + 1) * CONTEXT_STEP
        val space = text.indexOf(' ', cut)
        return if (space < 0) text.substring(cut) else text.substring(space + 1)
    }
    private const val CONTEXT_STEP = 200

    @JvmStatic external fun nativeLoad(path: String, threads: Int): Long
    @JvmStatic external fun nativeScore(handle: Long, context: String, words: Array<String>): FloatArray?
    @JvmStatic external fun nativePrepare(handle: Long, context: String): Boolean
    @JvmStatic external fun nativeFree(handle: Long)
}
