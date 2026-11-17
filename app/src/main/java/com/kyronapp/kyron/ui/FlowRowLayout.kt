package com.kyronapp.kyron.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/** Simple wrapping row container: lays children left-to-right and wraps
 *  to a new line when they don't fit. Used where buttons must never clip
 *  (Coverage state cards, dialog action rows). */
class FlowRowLayout @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : ViewGroup(ctx, attrs) {

    var hGap: Int = Ui.dp(ctx, 8)
    var vGap: Int = Ui.dp(ctx, 8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingStart - paddingEnd
        var x = 0
        var y = 0
        var rowH = 0
        var usedW = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > 0 && x + cw > maxW) {
                y += rowH + vGap
                x = 0
                rowH = 0
            }
            x += cw + hGap
            rowH = max(rowH, ch)
            usedW = max(usedW, x - hGap)
        }
        setMeasuredDimension(
            resolveSize(usedW + paddingStart + paddingEnd, widthMeasureSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingEnd
        var x = paddingStart
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val cw = c.measuredWidth
            val ch = c.measuredHeight
            if (x > paddingStart && x + cw > maxW) {
                y += rowH + vGap
                x = paddingStart
                rowH = 0
            }
            c.layout(x, y, x + cw, y + ch)
            x += cw + hGap
            rowH = max(rowH, ch)
        }
    }
}
