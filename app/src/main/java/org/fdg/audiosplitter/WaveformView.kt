package org.fdg.audiosplitter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: FloatArray = FloatArray(0)
    private var progress: Float = 0f

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F3140") }
    private val barPlayedPaint = Paint().apply { color = Color.parseColor("#2BD4C8") }
    private val barUnplayedPaint = Paint().apply { color = Color.parseColor("#1E9BB5") }
    private val playheadPaint = Paint().apply {
        color = Color.parseColor("#FF6B6B")
        strokeWidth = 3f
    }

    fun setSamples(newSamples: FloatArray) {
        samples = newSamples
        progress = 0f
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (samples.isEmpty()) return

        val n = samples.size
        val barWidth = width.toFloat() / n
        val midY = height / 2f
        val playedIndex = (progress * n).toInt()

        for (i in 0 until n) {
            val amp = samples[i]
            val barHeight = (amp * height * 0.85f).coerceAtLeast(2f)
            val x = i * barWidth
            val paint = if (i <= playedIndex) barPlayedPaint else barUnplayedPaint
            canvas.drawRect(x, midY - barHeight / 2, x + barWidth.coerceAtLeast(1f) - 1f, midY + barHeight / 2, paint)
        }

        val px = progress * width
        canvas.drawLine(px, 0f, px, height.toFloat(), playheadPaint)
    }
}
