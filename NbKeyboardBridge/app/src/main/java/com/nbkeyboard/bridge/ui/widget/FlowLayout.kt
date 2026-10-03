package com.nbkeyboard.bridge.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 一个极简的自动换行容器，用来排布「常用快捷键」按钮。
 *
 * 之所以不用 RecyclerView + GridLayoutManager：快捷键文案长短不一（Ctrl+C 与 任务管理器），
 * 固定列宽会留下大片空白；按内容宽度换行更紧凑，也更接近参考截图的观感。
 *
 * 测量与布局共用同一套换行算法（见 [computeLines]），保证两者结果一致。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    var horizontalGap: Int = dp(8)
    var verticalGap: Int = dp(8)

    /** 每个可见子 View 的行号。 */
    private var lineOf: IntArray = IntArray(0)

    /** 每行的行高与已用宽度。 */
    private var lineHeights: IntArray = IntArray(0)
    private var lineWidths: IntArray = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(0)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
            }
        }

        computeLines(available)

        var contentHeight = 0
        for (line in lineHeights.indices) {
            contentHeight += lineHeights[line]
        }
        if (lineHeights.isNotEmpty()) {
            contentHeight += verticalGap * (lineHeights.size - 1)
        }
        val contentWidth = lineWidths.maxOrNull() ?: 0

        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )

        // 宽度不确定（wrap_content）时按实际测得宽度重新换行一次
        if (widthMode != MeasureSpec.EXACTLY) {
            val actual = measuredWidth - paddingLeft - paddingRight
            if (actual != available) {
                computeLines(actual)
                var h = 0
                for (line in lineHeights.indices) h += lineHeights[line]
                if (lineHeights.isNotEmpty()) h += verticalGap * (lineHeights.size - 1)
                setMeasuredDimension(
                    measuredWidth,
                    resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec),
                )
            }
        }
    }

    /** 依据可用宽度计算每个子 View 所在行，并统计每行的宽度与高度。 */
    private fun computeLines(available: Int) {
        val visibleCount = (0 until childCount).count { getChildAt(it).visibility != GONE }
        lineOf = IntArray(childCount) { -1 }
        val heights = ArrayList<Int>()
        val widths = ArrayList<Int>()

        var currentLine = -1
        var used = 0
        var lineHeight = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val needed = if (used == 0) childWidth else used + horizontalGap + childWidth
            if (used > 0 && needed > available) {
                heights += lineHeight
                widths += used
                currentLine += 1
                used = childWidth
                lineHeight = childHeight
            } else {
                if (currentLine < 0) currentLine = 0
                used = needed
                lineHeight = maxOf(lineHeight, childHeight)
            }
            lineOf[index] = currentLine
        }
        if (used > 0 || visibleCount == 0) {
            if (currentLine < 0) currentLine = 0
            heights += lineHeight
            widths += used
        }

        lineHeights = heights.toIntArray()
        lineWidths = widths.toIntArray()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // 相对每行顶部的偏移
        val lineTop = IntArray(lineHeights.size)
        var y = paddingTop
        for (line in lineHeights.indices) {
            lineTop[line] = y
            y += lineHeights[line] + verticalGap
        }

        var x = paddingLeft
        var currentLine = -1
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val line = lineOf.getOrElse(index) { 0 }
            if (line != currentLine) {
                currentLine = line
                x = paddingLeft
            } else {
                x += horizontalGap
            }
            val top = lineTop.getOrElse(line) { paddingTop }
            child.layout(x, top, x + child.measuredWidth, top + child.measuredHeight)
            x += child.measuredWidth
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
