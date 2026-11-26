package ly.anoma.hex.keyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt
import ly.anoma.hex.R
import ly.anoma.hex.useInter

/** Labs › Keyboard › Emoji search: the emoji found, in the emoji panel's grid, between the strip's search line and the
 * letter keys. Two and a half rows show, so a third says the grid scrolls; the best matches lead and the grid fills
 * again with every letter. [pick] gets the tapped emoji. */
internal class EmojiSearchGrid(context: Context, private val pick: (String) -> Unit) : ScrollView(context) {
    private val density = resources.displayMetrics.density
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val cell = (KeyboardMetrics.EMOJI_CELL * density).roundToInt()
    /** The grid's height: two and a half rows. */
    val gridHeight = (cell * 2.5f).roundToInt()

    init {
        isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Shows [results] for [query]; a blank query shows none, and a search that found nothing says so. */
    fun show(query: String, results: List<String>) {
        rows.removeAllViews()
        if (results.isEmpty()) {
            if (query.isNotBlank()) rows.addView(TextView(context).apply {
                text = context.getString(R.string.keyboard_emoji_search_none); useInter(450)
                setTextColor(context.getColor(R.color.quiet)); setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gridHeight))
            return
        }
        // As many columns as the panel's: its cell size across the screen, nine at least.
        val columns = maxOf(9, (resources.displayMetrics.widthPixels / cell))
        for (start in results.indices step columns) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in start until start + columns) {
                val e = results.getOrNull(i)
                row.addView(TextView(context).apply {
                    if (e != null) {
                        text = e; setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26f); includeFontPadding = false
                        isFocusable = true; contentDescription = e; background = pressable()
                        setOnClickListener { pick(e) }
                    }
                    gravity = Gravity.CENTER
                }, LinearLayout.LayoutParams(0, cell, 1f))
            }
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, cell))
        }
        scrollTo(0, 0)
    }

    private fun pressable() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.pressed))
        })
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }
}
