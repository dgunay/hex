package ly.anoma.hex.keyboard

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.roundToInt

/** One row of keys on a grid of cells. Each key's keycap spans its cells less half the gap on each
 * side; its touch target runs to the middle of the gaps around it, and the first and last keys'
 * reach the row's ends, so every point of the row types a key. A key's padding is its keycap's
 * place inside its target. Add keys left to right. */
internal class KeyRowLayout(context: Context, private val cells: Float, private val side: Float,
                            private val gap: Float, private val insetTop: Int, private val insetBottom: Int) : ViewGroup(context) {
    private class Span(val start: Float, val width: Float) : LayoutParams(MATCH_PARENT, MATCH_PARENT)

    fun add(view: View, start: Float, width: Float) = addView(view, Span(start, width))
    /** Moves a key already in the row, without taking it out (a finger on it keeps its press). Keys gone
     * ([View.GONE]) take no place, and the rest are laid out by where they start, not the order they were added. */
    fun place(view: View, start: Float, width: Float) { view.layoutParams = Span(start, width) }

    private fun keys(): List<View> = (0 until childCount).map(::getChildAt).filter { it.visibility != GONE }
        .sortedBy { (it.layoutParams as Span).start }
    private fun capLeft(k: View, cell: Float): Int = (k.layoutParams as Span).let { (side + it.start * cell + gap / 2).roundToInt() }
    private fun capRight(k: View, cell: Float): Int = (k.layoutParams as Span).let { (side + (it.start + it.width) * cell - gap / 2).roundToInt() }
    private fun hitLeft(keys: List<View>, i: Int, cell: Float) = if (i == 0) 0 else (capRight(keys[i - 1], cell) + capLeft(keys[i], cell)) / 2
    private fun hitRight(keys: List<View>, i: Int, cell: Float, width: Int) =
        if (i == keys.size - 1) width else (capRight(keys[i], cell) + capLeft(keys[i + 1], cell)) / 2
    private fun cell(width: Int) = (width - 2 * side) / cells

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cell = cell(width)
        val keys = keys()
        for ((i, child) in keys.withIndex()) {
            val left = hitLeft(keys, i, cell); val right = hitRight(keys, i, cell, width)
            child.setPadding(capLeft(child, cell) - left, insetTop, right - capRight(child, cell), insetBottom)
            child.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = cell(r - l)
        val keys = keys()
        for ((i, child) in keys.withIndex()) child.layout(hitLeft(keys, i, cell), 0, hitRight(keys, i, cell, r - l), b - t)
    }
}
