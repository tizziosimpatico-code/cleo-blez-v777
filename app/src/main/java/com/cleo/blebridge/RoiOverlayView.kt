package com.cleo.blebridge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Overlay trasparente sopra la preview della fotocamera: l'utente trascina un dito
 * per disegnare il rettangolo dove si trova il numero della velocità sul display della Cleo.
 * Il rettangolo selezionato è esposto in coordinate normalizzate (0..1) tramite [normalizedRect].
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var startX = 0f
    private var startY = 0f
    private var currentRect: RectF? = null
    private var drawing = false

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
    }

    /** Rettangolo selezionato, in frazioni 0..1 rispetto alla vista (null se non ancora scelto). */
    var normalizedRect: RectF? = null
        private set

    fun reset() {
        currentRect = null
        normalizedRect = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                drawing = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (drawing) {
                    currentRect = RectF(
                        minOf(startX, event.x), minOf(startY, event.y),
                        maxOf(startX, event.x), maxOf(startY, event.y)
                    )
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                drawing = false
                currentRect?.let { rect ->
                    if (width > 0 && height > 0) {
                        normalizedRect = RectF(
                            rect.left / width, rect.top / height,
                            rect.right / width, rect.bottom / height
                        )
                    }
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = currentRect ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, width.toFloat(), rect.bottom, dimPaint)
        canvas.drawRect(rect, boxPaint)
    }
}
