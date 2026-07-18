package com.ink8.switchprocon.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A round touch joystick. Reports its position as x/y in [-1, 1] with right and **up**
 * positive (screen-y is inverted so it matches the Switch stick axes). Snaps back to
 * center on release.
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Called with normalized (x, y); (0, 0) when centered. */
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2B2F36") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.parseColor("#4A515C")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8B93A1") }

    /** Recolor the ring to the current accent — the on-screen version of RGB lighting. */
    fun setAccent(color: Int) {
        ringPaint.color = color
        ringPaint.setShadowLayer(12f, 0f, 0f, color)
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadow layers need software rendering
        invalidate()
    }

    private var centerX = 0f
    private var centerY = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var baseRadius = 0f
    private var thumbRadius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        thumbX = centerX
        thumbY = centerY
        baseRadius = min(w, h) / 2f - 8f
        thumbRadius = baseRadius / 2.4f
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius, ringPaint)
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val dist = hypot(dx, dy)
                val limit = baseRadius - thumbRadius
                if (dist > limit) {
                    thumbX = centerX + dx / dist * limit
                    thumbY = centerY + dy / dist * limit
                } else {
                    thumbX = event.x
                    thumbY = event.y
                }
                report(limit)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                thumbX = centerX
                thumbY = centerY
                onMove?.invoke(0f, 0f)
            }
        }
        invalidate()
        return true
    }

    private fun report(limit: Float) {
        val nx = (thumbX - centerX) / limit
        val ny = -(thumbY - centerY) / limit // invert: screen-down is stick-down
        onMove?.invoke(nx.coerceIn(-1f, 1f), ny.coerceIn(-1f, 1f))
    }
}
