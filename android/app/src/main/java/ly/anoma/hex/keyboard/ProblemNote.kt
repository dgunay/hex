package ly.anoma.hex.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ly.anoma.hex.R
import ly.anoma.hex.useInter
import kotlin.math.roundToInt

/** Labs › Keyboard › Typing log › Mark a problem's note: a text box of the keyboard's own, above the strip, that the
 * keyboard types into as into any field while it's open (its engine's [RoutedTarget] points at [NoteTarget]), so
 * autocorrect, the strip, delete and Undo all work in it. The app's field is untouched meanwhile. */
internal class NoteText {
    val text = StringBuilder()
    var start = 0
        private set
    var end = 0
        private set
    fun select(from: Int, to: Int) { start = from.coerceIn(0, text.length); end = to.coerceIn(start, text.length) }
}

/** What the engine reads and does while the note is open: [NoteText], with each change reported back as an editor's
 * selection update is ([changed], posted, as the app's arrive), and Enter's action keeping the note ([done]). */
internal class NoteTarget(private val note: NoteText, private val changed: (Int, Int, Int, Int) -> Unit,
                          private val done: () -> Unit) : KeyboardTextTarget {
    private val main = Handler(Looper.getMainLooper())
    private var batch = 0
    private var from = 0 to 0
    override val connected = true
    override fun before(length: Int) = note.text.substring(maxOf(0, note.start - length), note.start)
    override fun after(length: Int) = note.text.substring(note.end, minOf(note.text.length, note.end + length))
    override fun selected() = note.text.substring(note.start, note.end)
    override fun capsMode(mask: Int) = TextUtils.getCapsMode(note.text, note.start, mask)
    override fun commit(text: String) = edit { note.text.replace(note.start, note.end, text); note.start + text.length }
    override fun deleteBefore(length: Int) = edit {
        val from = maxOf(0, note.start - length); note.text.delete(from, note.start); from
    }
    override fun deleteCodePointsBefore(count: Int) = edit {
        val from = note.text.offsetByCodePoints(note.start, -minOf(count, note.text.codePointCount(0, note.start)))
        note.text.delete(from, note.start); from
    }
    override fun beginBatch() { if (batch++ == 0) from = note.start to note.end }
    override fun endBatch() { if (--batch == 0) report() }
    override fun setSelection(start: Int, end: Int): Boolean {
        if (batch == 0) from = note.start to note.end
        note.select(start, end); if (batch == 0) report(); return true
    }
    override fun performEditorAction(id: Int): Boolean { done(); return true }
    override fun defaultEditorAction(): Boolean { done(); return true }
    override fun sendKey(code: Int) {}

    private inline fun edit(change: () -> Int): Boolean {
        if (batch == 0) from = note.start to note.end
        val at = change(); note.select(at, at)
        if (batch == 0) report()
        return true
    }
    private fun report() {
        val (oldStart, oldEnd) = from; val start = note.start; val end = note.end
        main.post { changed(oldStart, oldEnd, start, end) }
    }

    companion object {
        /** The note as a field: a sentence, corrected, one line, Return keeps it. */
        fun editorInfo() = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_DONE
            packageName = "ly.anoma.hex.note"
            initialSelStart = 0; initialSelEnd = 0
        }
    }
}

/** The engine's text target: the app's field, or the note while it's open. */
internal class RoutedTarget(private val field: KeyboardTextTarget) : KeyboardTextTarget {
    var note: KeyboardTextTarget? = null
    private val to get() = note ?: field
    override val connected get() = to.connected
    override fun before(length: Int) = to.before(length)
    override fun after(length: Int) = to.after(length)
    override fun selected() = to.selected()
    override fun capsMode(mask: Int) = to.capsMode(mask)
    override fun commit(text: String) = to.commit(text)
    override fun deleteBefore(length: Int) = to.deleteBefore(length)
    override fun deleteCodePointsBefore(count: Int) = to.deleteCodePointsBefore(count)
    override fun beginBatch() = to.beginBatch()
    override fun endBatch() = to.endBatch()
    override fun setSelection(start: Int, end: Int) = to.setSelection(start, end)
    override fun performEditorAction(id: Int) = to.performEditorAction(id)
    override fun defaultEditorAction() = to.defaultEditorAction()
    override fun sendKey(code: Int) = to.sendKey(code)
}

/** The note's box: what's typed with a blinking cursor (the prompt while empty), ✕ to drop it and ✓ to keep it. */
internal class NoteRow(context: Context, private val discard: () -> Unit, private val keep: () -> Unit) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).roundToInt()
    private val ink = context.getColor(R.color.ink)
    private val quiet = context.getColor(R.color.quiet)
    private val line = TextView(context)
    private var shown: NoteText? = null
    private var caretOn = true
    private val blink = object : Runnable {
        override fun run() { caretOn = !caretOn; draw(); postDelayed(this, 530) }
    }
    val rowHeight = dp(48)

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(context.getColor(R.color.keyboard))
        line.apply {
            useInter(500); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(8), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat(); setColor(context.getColor(R.color.key))
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(line, LayoutParams(0, dp(38), 1f).apply { setMargins(dp(8), 0, dp(4), 0) })
        addView(button(R.drawable.ic_cancel, R.string.keyboard_note_discard, discard), LayoutParams(dp(44), -1))
        addView(button(R.drawable.ic_check, R.string.keyboard_note_done, keep), LayoutParams(dp(44), -1))
        visibility = GONE
    }

    /** Shows [note] (null hides the box). */
    fun show(note: NoteText?) {
        shown = note
        visibility = if (note != null) VISIBLE else GONE
        removeCallbacks(blink)
        if (note != null) { caretOn = true; postDelayed(blink, 530) }
        draw()
    }
    /** The note changed: the cursor shows steadily while typing, as a field's does. */
    fun changed() { caretOn = true; removeCallbacks(blink); if (shown != null) postDelayed(blink, 530); draw() }

    private fun draw() {
        val note = shown ?: return
        // The cursor is a thin bar drawn in place of one character: a "│" glyph would add its side bearings, a gap
        // on each side.
        val caret = SpannableStringBuilder("\u2060").apply {
            setSpan(CaretSpan(if (caretOn) ink else 0, maxOf(2, dp(1) * 3 / 2)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        line.contentDescription = note.text.ifEmpty { context.getString(R.string.keyboard_note_hint) }
        line.text = if (note.text.isEmpty()) SpannableStringBuilder(caret).append(context.getString(R.string.keyboard_note_hint)).apply {
            setSpan(ForegroundColorSpan(quiet), 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else SpannableStringBuilder(note.text, 0, note.end).append(caret).append(note.text, note.end, note.text.length).apply {
            setSpan(ForegroundColorSpan(ink), 0, note.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ink), note.end + 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** A text cursor: [width] px wide, the line's ascent to descent, in [color]. */
    private class CaretSpan(private val color: Int, private val width: Int) : android.text.style.ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int,
                             fm: android.graphics.Paint.FontMetricsInt?) = width
        override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int,
                          y: Int, bottom: Int, paint: android.graphics.Paint) {
            if (color == 0) return
            val was = paint.color; paint.color = color
            canvas.drawRect(x, y + paint.ascent(), x + width, y + paint.descent(), paint)
            paint.color = was
        }
    }

    private fun button(res: Int, label: Int, action: () -> Unit) = FrameLayout(context).apply {
        isFocusable = true; contentDescription = context.getString(label)
        addView(ImageView(context).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(ink) },
            FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { action() }
    }
}
