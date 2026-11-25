package ly.anoma.hex.typing

import android.content.Context
import android.util.Log
import android.os.Process
import android.os.SystemClock
import ly.anoma.hex.speech.ModelStore
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Labs › Keyboard › Neural autocorrect as the keyboard runs it: English's decoder asks it ([SpatialDecoder.neural]) for
 * the model's cost of a finished word's readings. The model ([NeuralModel], [ModelStore.neural]'s file) runs on a thread
 * of its own, loaded at the first word once the switch is on and freed after [IDLE_MS] without a word. Space waits for
 * it at most [budgetMs]: a slower answer, or none yet, leaves the word to the word pairs, as with the switch off. While a
 * word is typed the model reads the text before it ([prepare]), so space decodes only the readings.
 */
internal object NeuralAutocorrect : SpatialDecoder.NeuralScorer {
    private const val IDLE_MS = 5 * 60_000L
    // Measured on a Galaxy Z Flip6 (2026-11-25): 2 threads 16 ms a word, at most 22 ms; 4 were faster typically but
    // stalled up to 200 ms, as the work landed on its slower cores, and 6 were slower.
    private const val THREADS = 2
    private const val KEPT_TIMES = 200

    @Volatile private var enabled = false
    @Volatile var budgetMs = TypingPreferences.NEURAL_BUDGET_DEFAULT.toLong()
    @Volatile private var model: NeuralModel? = null
    private val loading = AtomicBoolean(false)
    @Volatile private var app: Context? = null
    @Volatile private var lastUse = 0L
    private var idleCheck: ScheduledFuture<*>? = null
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); it.run() }, "Hex neural autocorrect")
    }

    /** The switch, read again whenever it changes; off frees the model. */
    @Synchronized fun configure(context: Context, on: Boolean, budget: Int) {
        app = context.applicationContext
        budgetMs = budget.toLong()
        enabled = on
        if (!on) worker.execute(::unload)
    }

    override val active: Boolean
        get() {
            if (!enabled) return false
            if (model != null) return true
            load()
            return false
        }

    private fun load() {
        val context = app ?: return
        // Not downloaded yet: nothing to load, and nothing queued.
        if (ModelStore.neural.installed(context) == null || !loading.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (!enabled || model != null) return@execute
                val file = ModelStore.neural.verified(context) ?: return@execute
                val started = SystemClock.elapsedRealtime()
                model = NeuralModel.open(file.path, THREADS)
                loadMs = SystemClock.elapsedRealtime() - started
                lastUse = SystemClock.elapsedRealtime()
                scheduleIdleCheck()
            } finally { loading.set(false) }
        }
    }

    private fun unload() {
        model?.close(); model = null
        synchronized(memo) { memo.clear(); memoContext = null }
    }

    @Synchronized private fun scheduleIdleCheck() {
        if (idleCheck != null) return
        idleCheck = worker.scheduleWithFixedDelay({
            if (model != null && SystemClock.elapsedRealtime() - lastUse > IDLE_MS) unload()
            if (model == null) synchronized(this) { idleCheck?.cancel(false); idleCheck = null }
        }, 60, 60, TimeUnit.SECONDS)
    }

    private val nextPrepare = AtomicReference<String?>(null)
    private val preparing = AtomicBoolean(false)

    override fun prepare(context: String) {
        lastUse = SystemClock.elapsedRealtime()
        nextPrepare.set(context)
        // One read queued at a time, of the latest text: typing fast never stacks them up.
        if (!preparing.compareAndSet(false, true)) return
        worker.execute {
            preparing.set(false)
            val text = nextPrepare.getAndSet(null) ?: return@execute
            model?.prepare(text)
        }
    }

    // The last context's costs, so a word decided twice (Adaptive touch's guard) asks the model once.
    private val memo = HashMap<String, Double>()
    private var memoContext: String? = null

    override fun costs(context: String, words: List<String>): DoubleArray? {
        lastUse = SystemClock.elapsedRealtime()
        val m = model ?: return null
        val missing = synchronized(memo) {
            if (memoContext != context) { memo.clear(); memoContext = context }
            words.filter { it !in memo }
        }
        if (missing.isNotEmpty()) {
            val started = SystemClock.elapsedRealtime()
            val job: Future<Boolean> = worker.submit<Boolean> {
                val out = m.costs(context, missing) ?: return@submit false
                synchronized(memo) { if (memoContext == context) missing.indices.forEach { memo[missing[it]] = out[it] } }
                true
            }
            val answered = try { job.get(budgetMs, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { null } catch (_: Exception) { false }
            record(SystemClock.elapsedRealtime() - started, answered == null)
            if (answered != true) return null
        }
        return synchronized(memo) { if (memoContext != context) null else DoubleArray(words.size) { memo[words[it]] ?: Double.NaN } }
    }

    private class Later(val context: String, val words: List<String>, val answer: (DoubleArray) -> Unit)
    private val nextLater = AtomicReference<Later?>(null)
    private val laterQueued = AtomicBoolean(false)

    override fun costsLater(context: String, words: List<String>, answer: (DoubleArray) -> Unit) {
        lastUse = SystemClock.elapsedRealtime()
        if (model == null) return
        nextLater.set(Later(context, words, answer))
        // One request queued at a time, the latest: the completions of a word typed on are never decoded.
        if (laterQueued.compareAndSet(false, true)) worker.execute(::later)
    }

    // One batch of the latest request's words, then the next batch as a task of its own, behind any word space has
    // asked for meanwhile; the answer once all are read. A newer request takes the place of the rest.
    private fun later() {
        laterQueued.set(false)
        val later = nextLater.getAndSet(null) ?: return
        val m = model ?: return
        val missing = synchronized(memo) {
            if (memoContext != later.context) { memo.clear(); memoContext = later.context }
            later.words.filter { it !in memo }.distinct()
        }
        if (missing.isNotEmpty()) {
            val batch = missing.take(12)
            val out = m.costs(later.context, batch) ?: return
            synchronized(memo) { if (memoContext == later.context) batch.indices.forEach { memo[batch[it]] = out[it] } }
            if (missing.size > batch.size) {
                if (nextLater.compareAndSet(null, later) && laterQueued.compareAndSet(false, true)) worker.execute(::later)
                return
            }
        }
        val costs = synchronized(memo) {
            if (memoContext != later.context) null else DoubleArray(later.words.size) { memo[later.words[it]] ?: Double.NaN }
        } ?: return
        if (costs.all { it.isFinite() }) later.answer(costs)
    }

    // How long space waited for the model, for Labs › Keyboard › Neural autocorrect's line in Settings.
    private val times = ArrayDeque<Long>()
    private var words = 0
    private var late = 0
    @Volatile private var loadMs = -1L

    @Synchronized private fun record(ms: Long, timedOut: Boolean) {
        // How long, never what was typed.
        Log.d("HexNeural", if (timedOut) "word: no answer within $budgetMs ms" else "word: $ms ms")
        words++; if (timedOut) late++
        times.addLast(ms); if (times.size > KEPT_TIMES) times.removeFirst()
    }

    data class Timing(val words: Int, val late: Int, val medianMs: Long, val p95Ms: Long, val loaded: Boolean, val loadMs: Long)

    /** Since the keyboard started: words the model was asked about, how many it answered too late for, and the
     * median and 95th-percentile wait of the last [KEPT_TIMES]. */
    @Synchronized fun timing(): Timing {
        val sorted = times.sorted()
        fun at(q: Double) = if (sorted.isEmpty()) 0L else sorted[((sorted.size - 1) * q).toInt()]
        return Timing(words, late, at(.5), at(.95), model != null, loadMs)
    }

    /** Settings' speed test: [SAMPLES] scored as space scores a word, after the text before it is read as typing reads
     * it. Loads the model first if it isn't. Each sample's wait, in ms, or null without the model. Off the main thread. */
    fun speedTest(context: Context): List<Long>? {
        val file = ModelStore.neural.verified(context.applicationContext) ?: return null
        val m = model ?: NeuralModel.open(file.path, THREADS) ?: return null
        val own = m !== model
        try {
            return SAMPLES.map { (text, candidates) ->
                worker.submit { m.prepare(text) }.get()
                val started = SystemClock.elapsedRealtime()
                worker.submit { m.costs(text, candidates) }.get()
                SystemClock.elapsedRealtime() - started
            }
        } finally { if (own) m.close() }
    }

    private val SAMPLES = listOf(
        "I want to" to listOf("buy", "by", "bye", "bu", "buyt", "vuy"),
        "Can you send me the" to listOf("file", "fike", "fire", "fine", "fill", "flie"),
        "I'll be there in ten" to listOf("minutes", "minutess", "mintues", "minute", "mines", "minuets"),
        "Thanks for the" to listOf("help", "hepl", "hell", "held", "helo", "hello"),
        "We should meet at the coffee" to listOf("shop", "shoo", "ship", "chop", "shops", "show"),
        "My home server is on" to listOf("tailscale", "tail scale", "tails ale", "Tailscale", "tailsale", "tsilscale"),
        "Did you see the" to listOf("game", "gane", "gamer", "came", "fame", "games"),
        "I'm running a little" to listOf("late", "lare", "kate", "lame", "lake", "later"),
        "Could you pick up some" to listOf("milk", "mikl", "mill", "mild", "mile", "silk"),
        "It was really nice to" to listOf("meet", "meat", "mert", "melt", "met", "mees"),
        "The meeting got moved to" to listOf("tomorrow", "tomorow", "tomorrows", "tomato", "morrow", "tomb"),
        "Let me know when you get" to listOf("home", "hime", "hone", "hope", "come", "homes"),
        "I think we need to" to listOf("talk", "tslk", "tall", "take", "task", "walk"),
        "Happy birthday! Hope you have a great" to listOf("day", "dsy", "das", "dad", "say", "fay"),
        "Sorry, I didn't" to listOf("see", "sew", "sea", "dee", "seem", "sees"),
        "Where did you put the" to listOf("keys", "jeys", "key", "kets", "lets", "keyd"),
        "I'm going to the" to listOf("gym", "gum", "hym", "gin", "fym", "gyms"),
        "Can we reschedule for next" to listOf("week", "weel", "weak", "weeks", "wee", "seek"),
        "That sounds like a good" to listOf("idea", "ides", "idra", "ideal", "ideas", "odea"),
        "Just pushed the fix to" to listOf("main", "maon", "mail", "rain", "man", "mains"),
    )
}
