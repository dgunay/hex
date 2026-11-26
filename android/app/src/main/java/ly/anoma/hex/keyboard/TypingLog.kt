package ly.anoma.hex.keyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Labs › Keyboard › Typing log: every key, pick, strip, and the text around the cursor after it, in plain text files
 * in the app's own storage, for reading back over adb (`run-as ly.anoma.hex`) to find what went wrong with spaces,
 * punctuation and picks. Nothing leaves the phone. Password fields and fields that ask not to be learned from are
 * never logged. Files roll over at [FILE_BYTES] and the oldest go once all of them pass [CAP_BYTES]. Off, nothing is
 * written or read. One per process; lines are written in order on their own thread. */
internal object TypingLog {
    const val DIR = "typing-log"
    const val CAP_BYTES = 200L shl 20
    private const val FILE_BYTES = 8L shl 20

    @Volatile var on = false
        private set
    /** The field in front isn't logged: a password, or one that asks keyboards not to learn from it. */
    @Volatile var skipping = false
    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "HexTypingLog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    // Only on the writer's thread.
    private var out: FileOutputStream? = null
    private var written = 0L
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** Where the files are, for Settings (another component of the same process) before the keyboard runs. */
    fun attach(context: Context) { if (dir == null) dir = File(context.applicationContext.filesDir, DIR) }

    fun configure(context: Context, on: Boolean) {
        attach(context)
        if (this.on != on) { this.on = on; if (on) write("== log on") else writer.execute { close() } }
    }

    /** Writes [line] when the log is on and the field is one it logs; [line] is only built then. */
    inline fun event(line: () -> String) { if (on && !skipping) write(line()) }

    /** Writes [line] when the log is on, whatever the field (where a field begins: never its text). */
    fun write(line: String) {
        val at = System.currentTimeMillis()
        writer.execute {
            try {
                val o = out ?: open() ?: return@execute
                val bytes = (stamp.format(Date(at)) + " " + line + "\n").toByteArray(Charsets.UTF_8)
                o.write(bytes); written += bytes.size
                if (written >= FILE_BYTES) { close(); trim() }
            } catch (_: Exception) { close() }
        }
    }

    /** The log's files, oldest first. */
    fun files(): List<File> = dir?.listFiles { f -> f.name.startsWith("log-") }?.sortedBy { it.name }.orEmpty()
    fun size(): Long = files().sumOf { it.length() }

    /** Settings' Delete typing log: every file goes, and a log still on starts a new one. */
    fun clear(done: () -> Unit = {}) = writer.execute { close(); files().forEach { it.delete() }; done() }

    private fun open(): FileOutputStream? {
        val d = dir ?: return null
        d.mkdirs()
        val f = File(d, "log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
        written = f.length()
        return FileOutputStream(f, true).also { out = it }
    }
    private fun close() { try { out?.close() } catch (_: Exception) {}; out = null; written = 0 }
    private fun trim() {
        val all = files().toMutableList()
        var total = all.sumOf { it.length() }
        while (total > CAP_BYTES && all.size > 1) { val f = all.removeAt(0); total -= f.length(); f.delete() }
    }

    /** [s] quoted on one line: a newline as \n, and quotes and backslashes escaped. */
    fun q(s: String?): String = if (s == null) "null" else
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""
}
