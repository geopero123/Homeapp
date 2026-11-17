package dev.giorgi.silk

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A fixed grid. Each child says which cell it lives in and is centred inside it.
 * Home pages and the dock are both CellLayouts (the dock has one row).
 */
class CellLayout(context: Context, private val cols: Int, private val rows: Int) :
    ViewGroup(context) {

    class CellParams(val cellX: Int, val cellY: Int) :
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private var cellW = 0
    private var cellH = 0

    fun addAt(child: View, cellX: Int, cellY: Int) {
        addView(child, CellParams(cellX, cellY))
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is CellParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        cellW = (width - paddingLeft - paddingRight) / cols
        cellH = (height - paddingTop - paddingBottom) / rows
        val childW = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.AT_MOST)
        for (i in 0 until childCount) getChildAt(i).measure(childW, childH)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as CellParams
            val left = paddingLeft + lp.cellX * cellW
            val top = paddingTop + lp.cellY * cellH + (cellH - child.measuredHeight) / 2
            child.layout(left, top, left + cellW, top + child.measuredHeight)
        }
    }
}
