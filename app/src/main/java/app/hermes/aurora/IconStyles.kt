package app.hermes.aurora

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Icon packs, generated on the box from each app's own icon: no downloads, no per-app artwork.
 * The "glyph" styles first separate the logo from its background plate (an adaptive icon's
 * foreground layer, or the colour that fills most of the icon), so Mono and Neon draw the logo itself.
 */
internal object IconStyles {

    val NAMES = arrayOf("Original", "Round", "Mono", "Neon", "Duotone", "Glass")
    /** styles whose look depends on the theme colour */
    fun usesAccent(style: Int) = style == 3 || style == 4
    /** these draw their own round plate, so Horizon's disc behind the icon is not needed */
    fun hasOwnDisc(style: Int) = style == 1 || style == 3 || style == 4 || style == 5

    private const val S = 192

    fun apply(res: Resources, src: Drawable, style: Int, accent: Int): Drawable {
        if (style <= 0 || style >= NAMES.size) return src
        return try {
            BitmapDrawable(res, when (style) {
                1 -> round(full(src))
                2 -> mono(glyph(src))
                3 -> neon(glyph(src), accent)
                4 -> duotone(full(src), accent)
                else -> glass(full(src))
            })
        } catch (e: Throwable) {
            src
        }
    }

    // ------------------------------------------------------------------ source bitmaps

    private fun bmp() = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)

    /** the icon exactly as the launcher normally draws it */
    private fun full(src: Drawable): Bitmap {
        val b = bmp()
        src.setBounds(0, 0, S, S)
        src.draw(Canvas(b))
        return b
    }

    /** just the logo, on a transparent background, centred and scaled to a common size */
    private fun glyph(src: Drawable): Bitmap {
        val raw = bmp()
        val c = Canvas(raw)
        val fg = if (Build.VERSION.SDK_INT >= 26 && src is AdaptiveIconDrawable) src.foreground else null
        if (fg != null) {
            // the 108dp foreground layer only shows its middle 72dp: enlarge so that part fills the canvas
            val big = (S * 1.5f).toInt()
            val o = (S - big) / 2
            fg.setBounds(o, o, o + big, o + big)
            fg.draw(c)
        } else {
            src.setBounds(0, 0, S, S)
            src.draw(c)
            cutBackground(raw)
        }
        return fit(raw, 0.62f)
    }

    /** makes the dominant flat colour of an opaque icon transparent, leaving the logo */
    private fun cutBackground(b: Bitmap) {
        val n = S * S
        val px = IntArray(n)
        b.getPixels(px, 0, S, 0, 0, S, S)
        val hist = IntArray(4096)
        var opaque = 0
        for (p in px) {
            if (Color.alpha(p) > 200) {
                opaque++
                hist[((Color.red(p) shr 4) shl 8) or ((Color.green(p) shr 4) shl 4) or (Color.blue(p) shr 4)]++
            }
        }
        if (opaque < n * 0.05) return                       // already a logo on transparency
        var best = 0
        for (i in hist.indices) if (hist[i] > hist[best]) best = i
        if (hist[best] < opaque * 0.35) return              // no flat background to remove
        val dr = ((best shr 8) and 15) * 17
        val dg = ((best shr 4) and 15) * 17
        val db = (best and 15) * 17
        val out = IntArray(n)
        var cover = 0L
        for (i in 0 until n) {
            val p = px[i]
            val a = Color.alpha(p)
            if (a == 0) continue
            val dist = sqrt(((Color.red(p) - dr).let { it * it } + (Color.green(p) - dg).let { it * it } + (Color.blue(p) - db).let { it * it }).toFloat())
            val k = ((dist - 34f) / 56f).coerceIn(0f, 1f)
            val na = (a * k).toInt()
            cover += na
            out[i] = Color.argb(na, Color.red(p), Color.green(p), Color.blue(p))
        }
        if (cover < n * 255L * 0.015) return                // nothing but the background: keep the icon whole
        b.setPixels(out, 0, S, 0, 0, S, S)
    }

    /** crops to the visible part and scales it to `frac` of the canvas, centred */
    private fun fit(b: Bitmap, frac: Float): Bitmap {
        var minX = S; var minY = S; var maxX = -1; var maxY = -1
        val px = IntArray(S * S)
        b.getPixels(px, 0, S, 0, 0, S, S)
        for (y in 0 until S) for (x in 0 until S) {
            if (Color.alpha(px[y * S + x]) > 40) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return b
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val scale = S * frac / max(w, h)
        val out = bmp()
        val c = Canvas(out)
        val dw = w * scale
        val dh = h * scale
        val dst = RectF((S - dw) / 2f, (S - dh) / 2f, (S + dw) / 2f, (S + dh) / 2f)
        c.drawBitmap(b, android.graphics.Rect(minX, minY, maxX + 1, maxY + 1), dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    // ------------------------------------------------------------------ styles

    private fun paint(color: Int = Color.WHITE, style: Paint.Style = Paint.Style.FILL, w: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.color = color
            this.style = style
            strokeWidth = w
        }

    private fun disc(c: Canvas, p: Paint, inset: Float = 0f) =
        c.drawCircle(S / 2f, S / 2f, S / 2f - inset, p)

    /** circle crop of the original icon, slightly zoomed so its own plate fills the circle */
    private fun round(src: Bitmap): Bitmap {
        val out = bmp()
        val c = Canvas(out)
        disc(c, paint(Color.rgb(22, 27, 40)), 2f)
        val clip = Path().apply { addCircle(S / 2f, S / 2f, S / 2f - 2f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val z = 1.14f
        val d = S * z
        c.drawBitmap(src, null, RectF((S - d) / 2, (S - d) / 2, (S + d) / 2, (S + d) / 2), paint())
        c.restore()
        disc(c, paint(Color.argb(90, 255, 255, 255), Paint.Style.STROKE, 3f), 2f)
        return out
    }

    private fun mono(g: Bitmap): Bitmap {
        val out = bmp()
        val p = paint().apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        Canvas(out).drawBitmap(g, 0f, 0f, p)
        return out
    }

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun neon(g: Bitmap, accent: Int): Bitmap {
        val out = bmp()
        val c = Canvas(out)
        disc(c, paint(Color.argb(150, 6, 10, 22)), 4f)
        // glow: the logo, tinted and blurred, twice
        val glow = paint().apply {
            colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
            maskFilter = BlurMaskFilter(S * 0.09f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawBitmap(g, 0f, 0f, glow)
        c.drawBitmap(g, 0f, 0f, glow)
        c.drawBitmap(g, 0f, 0f, glow)
        val core = paint().apply { colorFilter = PorterDuffColorFilter(mix(accent, Color.WHITE, 0.6f), PorterDuff.Mode.SRC_IN) }
        c.drawBitmap(g, 0f, 0f, core)
        val ring = paint(Color.argb(235, Color.red(accent), Color.green(accent), Color.blue(accent)), Paint.Style.STROKE, 4f)
        ring.maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.SOLID)
        disc(c, ring, 4f)
        return out
    }

    /** the original in two tones of the theme colour */
    private fun duotone(src: Bitmap, accent: Int): Bitmap {
        val out = bmp()
        val c = Canvas(out)
        disc(c, paint(Color.rgb(14, 18, 30)), 2f)
        val sh = mix(Color.BLACK, accent, 0.22f)
        val hl = mix(accent, Color.WHITE, 0.72f)
        val k = floatArrayOf(0.299f, 0.587f, 0.114f)
        val m = ColorMatrix(floatArrayOf(
            k[0] * (Color.red(hl) - Color.red(sh)) / 255f, k[1] * (Color.red(hl) - Color.red(sh)) / 255f, k[2] * (Color.red(hl) - Color.red(sh)) / 255f, 0f, Color.red(sh).toFloat(),
            k[0] * (Color.green(hl) - Color.green(sh)) / 255f, k[1] * (Color.green(hl) - Color.green(sh)) / 255f, k[2] * (Color.green(hl) - Color.green(sh)) / 255f, 0f, Color.green(sh).toFloat(),
            k[0] * (Color.blue(hl) - Color.blue(sh)) / 255f, k[1] * (Color.blue(hl) - Color.blue(sh)) / 255f, k[2] * (Color.blue(hl) - Color.blue(sh)) / 255f, 0f, Color.blue(sh).toFloat(),
            0f, 0f, 0f, 1f, 0f
        ))
        val clip = Path().apply { addCircle(S / 2f, S / 2f, S / 2f - 2f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        val d = S * 1.14f
        c.drawBitmap(src, null, RectF((S - d) / 2, (S - d) / 2, (S + d) / 2, (S + d) / 2),
            paint().apply { colorFilter = ColorMatrixColorFilter(m) })
        c.restore()
        disc(c, paint(Color.argb(110, 255, 255, 255), Paint.Style.STROKE, 3f), 2f)
        return out
    }

    /** a frosted glass bubble with the icon floating inside */
    private fun glass(src: Bitmap): Bitmap {
        val out = bmp()
        val c = Canvas(out)
        disc(c, paint(Color.argb(52, 255, 255, 255)), 3f)
        val sheen = paint().apply {
            shader = LinearGradient(0f, 0f, 0f, S * 0.6f, Color.argb(130, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        }
        c.save()
        c.clipRect(0f, 0f, S.toFloat(), S * 0.6f)
        disc(c, sheen, 3f)
        c.restore()
        // the icon, a little smaller, with softened corners so a square plate doesn't poke out
        val d = S * 0.62f
        val r = RectF((S - d) / 2, (S - d) / 2, (S + d) / 2, (S + d) / 2)
        val clip = Path().apply { addRoundRect(r, d * 0.24f, d * 0.24f, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        c.drawBitmap(src, null, r, paint())
        c.restore()
        disc(c, paint(Color.argb(150, 255, 255, 255), Paint.Style.STROKE, 3f), 3f)
        return out
    }
}
