package dev.giorgi.silk

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A fixed grid. Icons sit centred in one cell; widgets span several cells
 * and fill them (minus a small margin).
 */
class CellLayout(context: Context, private val cols: Int, private val rows: Int) :
    ViewGroup(context) {

    class CellParams(
        val cellX: Int,
        val cellY: Int,
        val spanX: Int = 1,
        val spanY: Int = 1,
        val fill: Boolean = false,
    ) : ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private var cellW = 0
    private var cellH = 0
    private val widgetMargin = context.dp(8)

    fun addAt(child: View, cellX: Int, cellY: Int, spanX: Int = 1, spanY: Int = 1, fill: Boolean = false) {
        addView(child, CellParams(cellX, cellY, spanX, spanY, fill))
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is CellParams
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = CellParams(0, 0)
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = CellParams(0, 0)

    /**
     * Pages fade while swiping. Cells never overlap, so HWUI can apply the
     * alpha per child instead of rendering the whole page offscreen.
     */
    override fun hasOverlappingRendering(): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        cellW = ((width - paddingLeft - paddingRight) / cols).coerceAtLeast(0)
        cellH = ((height - paddingTop - paddingBottom) / rows).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as CellParams
            if (lp.fill) {
                child.measure(
                    MeasureSpec.makeMeasureSpec((cellW * lp.spanX - 2 * widgetMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((cellH * lp.spanY - 2 * widgetMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
                )
            } else {
                child.measure(
                    MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.AT_MOST),
                )
            }
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as CellParams
            val left = paddingLeft + lp.cellX * cellW
            val top = paddingTop + lp.cellY * cellH
            if (lp.fill) {
                child.layout(
                    left + widgetMargin, top + widgetMargin,
                    left + widgetMargin + child.measuredWidth, top + widgetMargin + child.measuredHeight
                )
            } else {
                val y = top + (cellH - child.measuredHeight) / 2
                child.layout(left, y, left + cellW, y + child.measuredHeight)
            }
        }
    }
}
