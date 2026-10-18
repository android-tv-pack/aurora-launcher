package app.hermes.aurora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly drifting neon glow over a dark navy base with a faint tech grid.
 * Pure Canvas, ~25 fps, paused while hidden; can be switched to a static frame.
 */
class AuroraView(context: Context) : View(context) {

    private class Blob(
        val color: Int, val sx: Float, val sy: Float, val px: Float, val py: Float,
        val size: Float, val alpha: Int
    ) {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(this@Blob.color, PorterDuff.Mode.SRC_IN)
            alpha = this@Blob.alpha
        }
    }

    private fun build(c: IntArray) = listOf(
        Blob(c[0], 0.050f, 0.037f, 0.0f, 1.1f, 1.25f, 150),
        Blob(c[1], 0.041f, 0.058f, 2.1f, 0.3f, 1.45f, 150),
        Blob(c[2], 0.033f, 0.045f, 4.0f, 2.2f, 1.00f, 100)
    )

    private var blobs: List<Blob> = build(Themes.ALL[0].colors)

    /** the faint tech grid; the calmer home styles switch it off */
    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var animated: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun setColors(colors: IntArray) {
        blobs = build(colors)
        invalidate()
    }

    private val blobBitmap: Bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { bmp ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(
            96f, 96f, 96f,
            intArrayOf(Color.argb(255, 255, 255, 255), Color.argb(110, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        Canvas(bmp).drawCircle(96f, 96f, 96f, p)
    }

    private val basePaint = Paint().apply { color = Color.rgb(4, 6, 14) }
    private val gridPaint = Paint().apply { color = Color.argb(14, 255, 255, 255); strokeWidth = 1f }
    private val vignette = Paint()
    private var grid = FloatArray(0)
    private val dst = RectF()
    private val frameMs = 40L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val step = 96f * resources.displayMetrics.density / 2f
        val lines = ArrayList<Float>()
        var x = 0f
        while (x <= w) { lines += listOf(x, 0f, x, h.toFloat()); x += step }
        var y = 0f
        while (y <= h) { lines += listOf(0f, y, w.toFloat(), y); y += step }
        grid = lines.toFloatArray()
        vignette.shader = RadialGradient(
            w / 2f, h * 0.45f, maxOf(w, h) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(190, 0, 0, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = if (animated) (SystemClock.uptimeMillis() % 7_200_000L) / 1000f else 40f
        canvas.drawRect(0f, 0f, w, h, basePaint)
        for (b in blobs) {
            val cx = w * (0.5f + 0.40f * sin(t * b.sx * 6.2831855f + b.px))
            val cy = h * (0.5f + 0.34f * cos(t * b.sy * 6.2831855f + b.py))
            val s = b.size * h
            dst.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
            canvas.drawBitmap(blobBitmap, null, dst, b.paint)
        }
        if (showGrid) canvas.drawLines(grid, gridPaint)
        canvas.drawRect(0f, 0f, w, h, vignette)
        if (animated && isShown) postInvalidateDelayed(frameMs)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }
}
