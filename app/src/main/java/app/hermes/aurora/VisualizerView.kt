package app.hermes.aurora

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Music visualiser background. It listens to the box's real audio output (Android's Visualizer on the
 * global output mix, so it works for Spotify, YouTube Music, AirPlay ... anything) and only runs while a
 * media session is playing. The colours come from the album cover that is playing (theme colours when
 * there is none), and a simple beat detector makes the scenes pulse on the kick drum.
 * Needs RECORD_AUDIO (one adb grant). Everything is drawn with a few pre-rendered soft sprites and
 * additive blending, so it stays light enough for a 2 GB box.
 */
internal class VisualizerView(private val a: MainActivity) : View(a) {

    companion object {
        val MODES = arrayOf("Off", "Flow", "Orb", "Stardust", "Silk", "Pixels", "Wave", "Desert", "Cyberpunk", "Tron Legacy", "Matrix", "Blade Runner", "Interstellar")
        /** the scenes also run by themselves when nothing is playing */
        fun isScene(mode: Int) = mode >= 7
        fun allowed(a: MainActivity) = a.checkSelfPermission("android.permission.RECORD_AUDIO") == android.content.pm.PackageManager.PERMISSION_GRANTED
        private const val N = 56
        private const val TAU = (2 * PI).toFloat()
    }

    /** screensaver: -1 = off, -2 = rotate through the scenes, 7..12 = that scene, drawn full-screen */
    var ambient = -1
        set(v) { field = v; rotMode = -1; evaluate() }
    private var rotMode = -1
    private var rotSince = 0L
    private var rotKey = ""
    private var xfade = 0f

    private fun rotating() = ambient == -2 || (ambient == -1 && a.store.vizRotate != 0 && a.store.visualizer > 0)

    /** the mode actually drawn: the screensaver's, the auto-rotation's, or the chosen one */
    fun mode(): Int = when {
        ambient >= 0 -> ambient
        rotating() -> if (rotMode > 0) rotMode else a.store.visualizer
        else -> a.store.visualizer
    }

    private fun rotateCheck() {
        if (!rotating()) { rotMode = -1; rotKey = ""; return }
        val now = SystemClock.uptimeMillis()
        val scenes = ambient == -2
        val period = if (scenes) 120_000L else when (a.store.vizRotate) { 2 -> 300_000L; 3 -> 900_000L; else -> Long.MAX_VALUE }
        val key = if (!scenes && a.store.vizRotate == 1) NowPlaying.playingKey() else ""
        var go = rotMode <= 0 && scenes
        if (rotMode <= 0 && !scenes) { rotMode = a.store.visualizer; rotSince = now; rotKey = key }
        else if (period != Long.MAX_VALUE && now - rotSince > period) go = true
        else if (key.isNotEmpty() && rotKey.isNotEmpty() && key != rotKey) go = true
        if (key.isNotEmpty()) rotKey = key
        if (!go) return
        val list = if (scenes) intArrayOf(7, 8, 9, 10, 11, 12) else IntArray(12) { it + 1 }
        var m: Int
        do { m = list[rnd.nextInt(list.size)] } while (m == rotMode && list.size > 1)
        rotMode = m; rotSince = now; xfade = 1f
        android.util.Log.d("AuroraViz", "rotate -> " + MODES[m] + (if (scenes) " (screensaver)" else ""))
    }

    /** true while the visualiser is on screen (a mode is chosen, something is playing, capture works) */
    var active = false
        private set

    private var viz: Visualizer? = null
    private val target = FloatArray(N)
    private val bands = FloatArray(N)
    private var peak = 0.35f

    // loudness regions, smoothed, and the beat pulse (1 on a kick, decays)
    private var bass = 0f
    private var mid = 0f
    private var treble = 0f
    private var beat = 0f
    private var bassAvg = 0f
    private var lastBeat = 0L
    private var t = 0f
    private var lastFrame = 0L

    private val h = Handler(Looper.getMainLooper())
    private val rect = RectF()
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- palette: three colours, eased toward the target so a new song fades its colours in
    private val cur = IntArray(3)
    private val tgt = IntArray(3)
    private var lastArt: Bitmap? = null
    private var paletteInit = false
    private val screen = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    private val tint = Array(4) { Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply { xfermode = screen } }

    // ---- sprites
    private val glow: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(48f, 48f, 48f, intArrayOf(Color.WHITE, Color.argb(120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            floatArrayOf(0f, 0.32f, 1f), Shader.TileMode.CLAMP)
        Canvas(it).drawCircle(48f, 48f, 48f, p)
    }
    /** a soft vertical curtain: fades out toward the top, soft left and right edges */
    private val curtain: Bitmap = Bitmap.createBitmap(64, 256, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        val v = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, 256f, intArrayOf(Color.argb(0, 255, 255, 255), Color.argb(70, 255, 255, 255), Color.argb(235, 255, 255, 255)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, 64f, 256f, v)
        val hz = Paint().apply {
            shader = LinearGradient(0f, 0f, 64f, 0f, intArrayOf(Color.argb(0, 0, 0, 0), Color.BLACK, Color.argb(0, 0, 0, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, 64f, 256f, hz)
    }

    // ---- Stardust particles (polar, flying out of the centre)
    private val pn = 190
    private val pAng = FloatArray(pn)
    private val pDist = FloatArray(pn)
    private val pSpd = FloatArray(pn)
    private val pSize = FloatArray(pn)
    private val pCol = IntArray(pn)
    private val pSwirl = FloatArray(pn)
    private val pAlive = BooleanArray(pn)
    private val rnd = java.util.Random(7)

    // ---- Pixels
    private val peaks = FloatArray(64)

    private val poll = object : Runnable {
        override fun run() { evaluate(); h.postDelayed(this, 500) }
    }
    private val frame = object : Runnable {
        override fun run() {
            if (!active) return
            tick()
            invalidate()
            h.postDelayed(this, 36)
        }
    }

    init {
        isFocusable = false
        isClickable = false
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start() { h.removeCallbacks(poll); h.post(poll) }

    fun stop() {
        h.removeCallbacks(poll)
        h.removeCallbacks(frame)
        release()
        setActive(false)
    }

    /** cheap and idempotent: call whenever the mode or the playback state may have changed */
    fun evaluate() {
        rotateCheck()
        val mode = mode()
        val live = mode > 0 && allowed(a) && NowPlaying.anyPlaying()
        if (live) { if (viz == null) capture() } else release()
        refreshPalette()
        // scenes (desert, ocean, starship) keep moving without music; the others need a signal
        setActive(mode > 0 && (viz != null || isScene(mode)))
    }

    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        visibility = if (on) VISIBLE else GONE
        h.removeCallbacks(frame)
        if (on) { lastFrame = SystemClock.uptimeMillis(); h.post(frame) }
        else { target.fill(0f); bands.fill(0f); bass = 0f; mid = 0f; treble = 0f; beat = 0f; pAlive.fill(false) }
        a.onVisualizerChanged()
    }

    private fun capture() {
        try {
            val v = Visualizer(0)
            val range = Visualizer.getCaptureSizeRange()
            v.enabled = false
            v.captureSize = min(512, range[1]).coerceAtLeast(range[0])
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, w: ByteArray?, rate: Int) { if (w != null) wave = w.copyOf() }
                override fun onFftDataCapture(vis: Visualizer?, fft: ByteArray?, rate: Int) {
                    if (fft != null) analyse(fft)
                }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            viz = v
        } catch (e: Throwable) {
            android.util.Log.w("AuroraViz", "capture failed: $e")
            viz = null
        }
    }

    private fun release() {
        val v = viz ?: return
        viz = null
        try { v.enabled = false } catch (_: Throwable) {}
        try { v.release() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ analysis

    /** FFT bytes (re, im pairs) -> N log-spaced bands, 0..1, plus the beat */
    private fun analyse(fft: ByteArray) {
        val bins = fft.size / 2 - 1
        if (bins < 8) return
        var maxMag = 0f
        for (b in 0 until N) {
            val lo = (bins * 0.75).pow(b.toDouble() / N).toInt().coerceIn(1, bins)
            val hi = (bins * 0.75).pow((b + 1).toDouble() / N).toInt().coerceIn(lo + 1, bins + 1)
            var m = 0f
            for (k in lo until hi) {
                val re = fft[2 * k].toFloat()
                val im = fft[2 * k + 1].toFloat()
                m = max(m, sqrt(re * re + im * im))
            }
            m *= 1f + 1.6f * b / N                       // lift the highs, they are naturally quieter
            target[b] = m
            maxMag = max(maxMag, m)
        }
        peak = max(peak * 0.995f, maxMag.coerceAtLeast(18f))   // auto-gain: quiet and loud tracks fill the screen alike
        for (b in 0 until N) target[b] = sqrt((target[b] / peak).coerceIn(0f, 1f))

        var bs = 0f
        for (i in 0 until 6) bs += target[i]
        bs /= 6f
        val now = SystemClock.uptimeMillis()
        if (bs > bassAvg * 1.28f + 0.07f && bs > 0.3f && now - lastBeat > 170) { beat = 1f; lastBeat = now; beatHit = true }
        bassAvg = bassAvg * 0.92f + bs * 0.08f
    }

    private fun refreshPalette() {
        val art = NowPlaying.playingArt()
        if (paletteInit && art === lastArt) return
        lastArt = art
        val th = Themes.ALL[a.store.theme]
        val p = art?.let { paletteFrom(it) } ?: intArrayOf(th.accent, th.colors[1], th.colors[2])
        for (i in 0 until 3) tgt[i] = p[i]
        if (!paletteInit) { for (i in 0 until 3) cur[i] = tgt[i]; paletteInit = true }
    }

    /** the two dominant, colourful hues of the cover (made vivid), and a light tint of the first; null for greyscale covers */
    private fun paletteFrom(b: Bitmap): IntArray? {
        val s = Bitmap.createScaledBitmap(b, 16, 16, true)
        val hsv = FloatArray(3)
        val wts = FloatArray(12)
        val hue = FloatArray(12)
        for (y in 0 until 16) for (x in 0 until 16) {
            Color.colorToHSV(s.getPixel(x, y), hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val w = hsv[1] * hsv[2]
            val bin = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            wts[bin] += w
            hue[bin] += hsv[0] * w
        }
        if (wts.sum() < 0.8f) return null
        var i1 = 0
        for (i in 1 until 12) if (wts[i] > wts[i1]) i1 = i
        val h1 = hue[i1] / wts[i1]
        var i2 = -1
        for (i in 0 until 12) {
            if (wts[i] < wts[i1] * 0.15f) continue
            val d = abs(((i * 30f + 15f) - h1 + 540f) % 360f - 180f)
            if (d >= 45f && (i2 < 0 || wts[i] > wts[i2])) i2 = i
        }
        val h2 = if (i2 >= 0) hue[i2] / wts[i2] else (h1 + 45f) % 360f
        return intArrayOf(
            Color.HSVToColor(floatArrayOf(h1, 0.82f, 1f)),
            Color.HSVToColor(floatArrayOf(h2, 0.78f, 1f)),
            Color.HSVToColor(floatArrayOf(h1, 0.30f, 1f))
        )
    }

    private fun lerp(c1: Int, c2: Int, f: Float) = Color.rgb(
        (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt(),
        (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt(),
        (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
    )

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0.01f, 0.1f)
        lastFrame = now
        t += dt
        dtF = dt
        idle = viz == null
        if (idle) {
            // nothing playing: a slow, gentle imitation of music so the scenes stay alive
            for (i in 0 until N) target[i] = ((0.2f + 0.14f * sin(t * 0.9f + i * 0.31f) + 0.07f * sin(t * 1.7f - i * 0.13f)) * (1f - i * 0.008f)).coerceIn(0f, 1f)
            if (t - idleBeatAt > 2.6f) { idleBeatAt = t; beat = 0.6f; beatHit = true }
        }
        for (i in 0 until N) {
            val v = target[i]
            bands[i] += (v - bands[i]) * (if (v > bands[i]) 0.6f else 0.16f)
        }
        var lo = 0f; var md = 0f; var hi = 0f
        for (i in 0 until 8) lo += bands[i]
        for (i in 12 until 32) md += bands[i]
        for (i in 34 until N) hi += bands[i]
        bass += (lo / 8f - bass) * 0.35f
        mid += (md / 20f - mid) * 0.3f
        treble += (hi / (N - 34) - treble) * 0.3f
        beat *= 0.9f
        for (i in 0 until 3) cur[i] = lerp(cur[i], tgt[i], 0.05f)
        for (i in 0 until 3) tint[i].colorFilter = PorterDuffColorFilter(cur[i], PorterDuff.Mode.SRC_IN)
        tint[3].colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
    }

    // ------------------------------------------------------------------ drawing

    private fun withA(c: Int, al: Int) = Color.argb(al.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun glowAt(c: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        rect.set(x - r, y - r, x + r, y + r)
        val p = tint[color]
        p.alpha = alpha.coerceIn(0, 255)
        c.drawBitmap(glow, null, rect, p)
    }

    /** spectrum value at 0..1 across the width, smoothed between neighbouring bands */
    private fun spec(u: Float): Float {
        val f = (u.coerceIn(0f, 1f) * (N * 0.85f - 1f))
        val i = f.toInt()
        val fr = f - i
        return bands[i] * (1 - fr) + bands[min(N - 1, i + 1)] * fr
    }

    override fun onDraw(c: Canvas) {
        if (!active) return
        val w = width.toFloat()
        val hh = height.toFloat()
        if (w <= 0f) return
        if (a.store.style == 3) c.drawColor(Color.rgb(3, 6, 11))     // Command has no background view of its own
        val hit = beatHit
        beatHit = false
        when (mode()) {
            6 -> waves(c, w, hh)
            7 -> desert(c, w, hh, hit)
            8 -> cyber(c, w, hh, hit)
            9 -> tron(c, w, hh, hit)
            10 -> matrix(c, w, hh, hit)
            11 -> runner(c, w, hh, hit)
            12 -> interstellar(c, w, hh, hit)
            1 -> flow(c, w, hh)
            2 -> orb(c, w, hh)
            3 -> stardust(c, w, hh)
            4 -> silk(c, w, hh)
            5 -> pixels(c, w, hh)
        }
        if (xfade > 0f) {          // a new scene fades in from black
            c.drawColor(Color.argb((xfade * 255).toInt().coerceIn(0, 255), 0, 0, 0))
            xfade = max(0f, xfade - dtF / 1.1f)
        }
    }

    /** FLOW: three layers of northern-lights curtains rising from the bottom, swaying, swelling with the music */
    private fun flow(c: Canvas, w: Float, hh: Float) {
        val cols = 34
        val step = w / (cols - 1)
        for (layer in 2 downTo 0) {
            val base = hh * (1.04f - layer * 0.07f)
            val sway = step * (0.9f + layer * 0.5f)
            val bw = step * (3.4f - layer * 0.5f)
            val p = tint[layer]
            for (i in 0 until cols) {
                val u = i / (cols - 1f)
                // each layer listens to a different slice of the spectrum
                val lvl = when (layer) {
                    0 -> spec(u * 0.5f)
                    1 -> spec(0.2f + u * 0.6f)
                    else -> spec(0.45f + u * 0.55f)
                }
                val drift = sin(t * (0.5f + layer * 0.23f) + i * 0.42f + layer * 2f)
                val hgt = hh * (0.3f + 0.6f * lvl + 0.15f * bass + 0.05f * drift) * (1f - layer * 0.1f)
                val x = i * step + sin(t * 0.4f + i * 0.3f + layer) * sway
                rect.set(x - bw / 2, base - hgt, x + bw / 2, base)
                p.alpha = (170 + 85 * lvl + 40 * beat - layer * 20).toInt().coerceIn(0, 255)
                c.drawBitmap(curtain, null, rect, p)
            }
        }
        // a soft horizon glow that breathes with the kick
        glowAt(c, w * 0.5f, hh * 1.02f, w * (0.6f + 0.1f * beat), 2, (90 + 110 * bass + 55 * beat).toInt())
    }

    /** ORB: a living blob in the middle, pulsing on the beat, with sparks orbiting it */
    private fun orb(c: Canvas, w: Float, hh: Float) {
        val cx = w * 0.5f
        val cy = hh * 0.47f
        val r0 = min(w, hh) * 0.2f * (1f + 0.16f * beat + 0.1f * bass)
        glowAt(c, cx, cy, r0 * 4.6f, 0, (120 + 120 * bass + 60 * beat).toInt())
        glowAt(c, cx, cy, r0 * 3.0f, 1, (80 + 110 * mid).toInt())

        val n = 72
        for (layer in 0..2) {
            val k = 1f - layer * 0.2f
            val tl = t * (1.1f - layer * 0.3f) + layer * 1.7f
            path.reset()
            var fx = 0f; var fy = 0f
            var px = 0f; var py = 0f
            for (i in 0..n) {
                val u = (i % n) / n.toFloat()
                val ang = u * TAU + tl * 0.25f
                // symmetric spectrum around the circle: the left and right halves mirror each other
                val sv = spec(abs(u * 2f - 1f))
                val rr = r0 * k * (1f + 0.62f * sv * (0.6f + 0.12f * layer) + 0.05f * sin(3f * ang + tl * 1.4f) + 0.035f * sin(5f * ang - tl))
                val x = cx + cos(ang) * rr
                val y = cy + sin(ang) * rr
                if (i == 0) { path.moveTo(x, y); fx = x; fy = y } else path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
                px = x; py = y
            }
            path.quadTo(px, py, (px + fx) / 2f, (py + fy) / 2f)
            path.close()
            line.style = Paint.Style.FILL
            line.xfermode = screen
            line.color = withA(cur[layer], 52 + (beat * 38).toInt() - layer * 8)
            c.drawPath(path, line)
            line.style = Paint.Style.STROKE
            line.strokeWidth = max(3f, hh * (0.008f - layer * 0.0016f))
            line.color = withA(if (layer == 0) cur[2] else cur[layer], 210)
            c.drawPath(path, line)
        }
        line.xfermode = null
        // sparks circling the orb, faster and brighter with the highs
        for (i in 0 until 26) {
            val ph = i * 2.399f
            val ang = ph + t * (0.35f + 0.9f * treble) * (if (i % 2 == 0) 1f else -0.7f)
            val rr = r0 * (1.55f + 0.45f * sin(t * 0.8f + ph * 3f) + 0.25f * bands[(i * 2) % N])
            glowAt(c, cx + cos(ang) * rr, cy + sin(ang) * rr * 0.92f, hh * (0.012f + 0.016f * bands[(i * 2) % N]), i % 3, (120 + 130 * treble).toInt())
        }
    }

    /** STARDUST: a warp-speed starfield flying out of the centre; the kick throws bursts of stars */
    private fun stardust(c: Canvas, w: Float, hh: Float) {
        val cx = w * 0.5f
        val cy = hh * 0.5f
        val maxD = sqrt(cx * cx + cy * cy) * 1.05f
        glowAt(c, cx, cy, hh * (0.45f + 0.3f * beat + 0.2f * bass), 0, (55 + 100 * bass + 80 * beat).toInt())
        glowAt(c, cx, cy, hh * 0.22f, 2, (30 + 60 * bass).toInt())

        var spawn = (1.5f + 7f * (bass + mid) * 0.5f + (if (beat > 0.95f) 22f else 0f)).toInt()
        val dt = 0.036f
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        for (i in 0 until pn) {
            if (!pAlive[i]) {
                if (spawn > 0) {
                    spawn--
                    pAlive[i] = true
                    pAng[i] = rnd.nextFloat() * TAU
                    pDist[i] = hh * (0.02f + 0.06f * rnd.nextFloat())
                    pSpd[i] = hh * (0.18f + 0.5f * rnd.nextFloat())
                    pSize[i] = 0.5f + rnd.nextFloat()
                    pCol[i] = rnd.nextInt(3)
                    pSwirl[i] = (rnd.nextFloat() - 0.5f) * 0.5f
                }
                continue
            }
            val f = (pDist[i] / maxD).coerceIn(0f, 1.2f)
            val speed = pSpd[i] * (0.25f + 2.4f * f * f + 0.9f * bass + 1.3f * beat)
            pDist[i] += speed * dt
            pAng[i] += pSwirl[i] * dt * (0.4f + treble)
            if (pDist[i] > maxD) { pAlive[i] = false; continue }
            val ca = cos(pAng[i]); val sa = sin(pAng[i])
            val x = cx + ca * pDist[i]
            val y = cy + sa * pDist[i]
            val len = speed * dt * (2f + 5f * f)
            val al = (30f + 225f * f.coerceAtMost(1f) * min(1f, f * 4f)).toInt()
            line.strokeWidth = max(1.5f, hh * 0.0035f * pSize[i] * (0.5f + f * 2.2f))
            line.color = withA(cur[pCol[i]], al)
            c.drawLine(x - ca * len, y - sa * len, x, y, line)
            if (f > 0.25f) glowAt(c, x, y, hh * 0.012f * pSize[i] * (0.6f + f * 2f), pCol[i], (al * 0.7f).toInt())
        }
        line.xfermode = null
        line.strokeCap = Paint.Cap.BUTT
    }

    /** SILK: layered ribbons of light flowing across the screen, like a voice assistant waveform */
    private fun silk(c: Canvas, w: Float, hh: Float) {
        val cy = hh * 0.5f
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        val pts = 64
        for (layer in 0..4) {
            val level = when (layer % 3) { 0 -> bass; 1 -> mid; else -> treble }
            val amp = hh * (0.035f + 0.3f * level + 0.05f * beat) * (1f - layer * 0.1f)
            val freq = 1.1f + layer * 0.55f
            val speed = (0.7f + layer * 0.4f) * (if (layer % 2 == 0) 1f else -1f)
            val col = cur[layer % 3]
            path.reset()
            for (i in 0..pts) {
                val u = i / pts.toFloat()
                val env = (1f - (2f * u - 1f) * (2f * u - 1f)).pow(2f)
                val y = cy + amp * env * sin(u * TAU * freq + t * speed) * (0.8f + 0.2f * sin(u * 9f - t * 1.3f + layer))
                val x = u * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val thin = layer >= 3
            // a wide soft halo, a medium body and a bright core give the glow without a blur filter
            line.strokeWidth = hh * (if (thin) 0.016f else 0.05f)
            line.color = withA(col, if (thin) 26 else 36)
            c.drawPath(path, line)
            line.strokeWidth = hh * (if (thin) 0.006f else 0.018f)
            line.color = withA(col, if (thin) 90 else 120)
            c.drawPath(path, line)
            line.strokeWidth = max(2f, hh * (if (thin) 0.0025f else 0.005f))
            line.color = withA(if (thin) col else cur[2], 255)
            c.drawPath(path, line)
        }
        line.xfermode = null
        line.strokeCap = Paint.Cap.BUTT
    }

    /** PIXELS: a glowing LED dot-matrix equaliser with falling peak dots */
    private fun pixels(c: Canvas, w: Float, hh: Float) {
        val cols = 48
        val pitch = w / cols
        val rows = (hh * 0.5f / pitch).toInt().coerceAtLeast(6)
        val x0 = pitch / 2f
        val bottom = hh - pitch * 0.9f
        val rad = pitch * 0.36f
        line.style = Paint.Style.FILL
        line.xfermode = screen
        for (cI in 0 until cols) {
            val lvl = (spec(cI / (cols - 1f)) * (1.0f + 0.3f * beat)).coerceIn(0f, 1f)
            val lit = (lvl * rows).toInt()
            peaks[cI] = max(peaks[cI] - 0.04f, lvl * rows)
            val x = x0 + cI * pitch
            for (r in 0 until rows) {
                val y = bottom - r * pitch
                if (r < lit) {
                    line.color = withA(lerp(cur[0], cur[1], r / rows.toFloat()), 215 - (r * 90 / rows))
                    c.drawCircle(x, y, rad, line)
                } else {
                    line.color = Color.argb(18, 255, 255, 255)
                    c.drawCircle(x, y, rad * 0.8f, line)
                }
            }
            if (lit > 0) glowAt(c, x, bottom - (lit - 1) * pitch, pitch * 2.2f, 1, 130)
            val pk = peaks[cI].toInt()
            if (pk > lit && pk < rows) {
                line.color = withA(cur[2], 255)
                c.drawCircle(x, bottom - pk * pitch, rad, line)
            }
        }
        line.xfermode = null
    }

    // ------------------------------------------------------------------ Wave (the classic) and the three scenes

    private var wave = ByteArray(0)
    private var idle = true
    private var beatHit = false
    private var idleBeatAt = 0f
    private var dtF = 0.036f

    /** true while the visualiser owns the background: real audio, or a scene (which also runs without music) */
    val live: Boolean get() = active && (!idle || isScene(mode()))

    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crest = Path()
    private val ys = FloatArray(220)

    private fun mkP(color: Int, additive: Boolean) = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        if (additive) xfermode = screen
    }
    private val sandP = arrayOf(mkP(Color.rgb(240, 192, 122), false), mkP(Color.rgb(214, 150, 92), false))
    private val waterP = arrayOf(mkP(Color.rgb(196, 242, 255), true), mkP(Color.rgb(92, 204, 244), true))
    private val fireP = arrayOf(mkP(Color.rgb(255, 150, 52), true), mkP(Color.rgb(255, 222, 130), true), mkP(Color.rgb(120, 200, 255), true))

    private fun spr(c: Canvas, x: Float, y: Float, r: Float, p: Paint, alpha: Int) {
        rect.set(x - r, y - r, x + r, y + r)
        p.alpha = alpha.coerceIn(0, 255)
        c.drawBitmap(glow, null, rect, p)
    }

    // scene shaders (they depend on the screen size)
    private var skyD: Shader? = null
    private val duneSh = arrayOfNulls<Shader>(3)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val hh = h.toFloat()
        skyD = LinearGradient(0f, 0f, 0f, hh * 0.72f, intArrayOf(Color.rgb(8, 6, 30), Color.rgb(58, 26, 84), Color.rgb(188, 82, 82), Color.rgb(255, 150, 80)),
            floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP)
        val dTop = intArrayOf(Color.rgb(150, 66, 96), Color.rgb(116, 50, 66), Color.rgb(54, 26, 46))
        val dBot = intArrayOf(Color.rgb(62, 26, 64), Color.rgb(46, 20, 44), Color.rgb(20, 10, 24))
        for (l in 0..2) duneSh[l] = LinearGradient(0f, hh * (0.46f + l * 0.11f), 0f, hh, dTop[l], dBot[l], Shader.TileMode.CLAMP)
        cySky = null     // rebuilt for the new size on the next cyberpunk frame
    }

    // ---- particles shared by the scenes: 0 sand, 1 dust cloud, 2 water drop, 3 / 4 exhaust (orange / blue)
    private val qn = 320
    private val qx = FloatArray(qn)
    private val qy = FloatArray(qn)
    private val qvx = FloatArray(qn)
    private val qvy = FloatArray(qn)
    private val ql = FloatArray(qn)
    private val qs = FloatArray(qn)
    private val qd = FloatArray(qn)
    private val qk = IntArray(qn)
    private var qNext = 0

    private fun spawnQ(x: Float, y: Float, vx: Float, vy: Float, size: Float, kind: Int, decay: Float) {
        for (k in 0 until qn) {
            val i = (qNext + k) % qn
            if (ql[i] <= 0f) {
                qNext = i + 1
                qx[i] = x; qy[i] = y; qvx[i] = vx; qvy[i] = vy
                qs[i] = size; qk[i] = kind; qd[i] = decay; ql[i] = 1f
                return
            }
        }
    }

    private fun stepQ(w: Float, hh: Float) {
        val dt = dtF
        for (i in 0 until qn) {
            if (ql[i] <= 0f) continue
            when (qk[i]) {
                0 -> { qvy[i] += hh * 1.05f * dt; qvx[i] += hh * 0.12f * dt }
                1 -> { qvy[i] += hh * 0.04f * dt; qvx[i] += hh * 0.2f * dt }
                2 -> qvy[i] += hh * 1.35f * dt
            }
            qx[i] += qvx[i] * dt
            qy[i] += qvy[i] * dt
            ql[i] -= qd[i] * dt
            if (qy[i] > hh * 1.05f || qx[i] < -w * 0.1f || qx[i] > w * 1.1f) ql[i] = 0f
        }
    }

    private fun drawQ(c: Canvas) {
        for (i in 0 until qn) {
            val l = ql[i]
            if (l <= 0f) continue
            val al = (l * 255).toInt()
            when (qk[i]) {
                0 -> spr(c, qx[i], qy[i], qs[i] * (0.5f + 0.5f * l), sandP[i % 2], (al * 0.92f).toInt())
                1 -> spr(c, qx[i], qy[i], qs[i] * (1.7f - 0.9f * l), sandP[1], (al * 0.26f).toInt())
                2 -> {
                    spr(c, qx[i], qy[i], qs[i] * (0.6f + 0.6f * l), waterP[i % 2], al)
                    line.style = Paint.Style.STROKE
                    line.xfermode = screen
                    line.strokeCap = Paint.Cap.ROUND
                    line.strokeWidth = max(1.5f, qs[i] * 0.55f)
                    line.color = withA(Color.rgb(220, 248, 255), al / 2)
                    c.drawLine(qx[i], qy[i], qx[i] - qvx[i] * 0.03f, qy[i] - qvy[i] * 0.03f, line)
                    line.strokeCap = Paint.Cap.BUTT
                    line.xfermode = null
                }
                3 -> spr(c, qx[i], qy[i], qs[i] * (1.3f - 0.5f * l), fireP[i % 2], (al * 0.85f).toInt())
                else -> spr(c, qx[i], qy[i], qs[i] * (1.3f - 0.5f * l), fireP[2], (al * 0.7f).toInt())
            }
        }
    }

    // ---- sky stars (desert, ocean)
    private val skyN = 70
    private val skyX = FloatArray(skyN) { rnd.nextFloat() }
    private val skyY = FloatArray(skyN) { rnd.nextFloat() }
    private val skyP = FloatArray(skyN) { rnd.nextFloat() }

    private fun skyStars(c: Canvas, w: Float, hh: Float, zone: Float) {
        line.style = Paint.Style.FILL
        line.xfermode = null
        for (i in 0 until skyN) {
            val tw = 0.5f + 0.5f * sin(t * (1f + skyP[i] * 2f) + skyP[i] * 20f)
            line.color = withA(Color.WHITE, (50 + 170 * tw * (0.45f + treble)).toInt())
            c.drawCircle(skyX[i] * w, skyY[i] * hh * zone, max(1.5f, hh * 0.0026f * (0.6f + skyP[i])), line)
        }
    }

    // ---------------------------------------------------------------- WAVE
    private fun waves(c: Canvas, w: Float, hh: Float) {
        val cy = hh * 0.62f
        val amp = hh * 0.30f
        line.xfermode = null
        for (layer in 0..2) {
            val k = 1f - layer * 0.28f
            path.reset()
            path.moveTo(0f, cy)
            for (i in 0 until N) {
                val x = w * i / (N - 1)
                val wob = sin(t * (1.2f + layer * 0.5f) + i * 0.35f) * 0.04f
                val y = cy - (bands[i] * k + wob * bands[i]) * amp
                if (i == 0) path.lineTo(x, y) else {
                    val px = w * (i - 1) / (N - 1)
                    path.quadTo(px, cy - (bands[i - 1] * k) * amp, (x + px) / 2f, (y + cy - (bands[i - 1] * k) * amp) / 2f)
                }
            }
            path.lineTo(w, cy)
            path.close()
            fillP.shader = LinearGradient(0f, cy - amp, 0f, cy, withA(cur[2], 200 - layer * 40), withA(cur[0], 0), Shader.TileMode.CLAMP)
            c.drawPath(path, fillP)
            fillP.shader = null
        }
        val wv = wave
        if (wv.size > 8) {
            path.reset()
            val n = wv.size
            for (i in 0 until n step 2) {
                val x = w * i / (n - 1)
                val y = cy + ((wv[i].toInt() and 0xFF) - 128) / 128f * hh * 0.09f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.style = Paint.Style.STROKE
            line.strokeWidth = max(2f, hh * 0.004f)
            line.color = withA(cur[2], 255)
            c.drawPath(path, line)
            line.style = Paint.Style.FILL
        }
    }

    // ---------------------------------------------------------------- DESERT
    private fun duneY(layer: Int, x: Float, w: Float, hh: Float): Float {
        val u = x / w
        val base = hh * (0.5f + layer * 0.11f)
        val wind = t * (0.05f + layer * 0.03f)
        val swell = spec(u) * (0.4f + bass) * hh * (0.035f + layer * 0.01f) + beat * hh * 0.012f
        return base + hh * (0.045f * sin(u * 5.2f + layer * 1.9f + wind) + 0.024f * sin(u * 12.5f + layer * 0.7f - wind * 1.6f)) - swell
    }

    private fun desert(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        fillP.shader = skyD
        c.drawRect(0f, 0f, w, hh, fillP)
        fillP.shader = null
        skyStars(c, w, hh, 0.45f)
        // sun, breathing with the bass
        val sx = w * 0.52f
        val sy = hh * 0.3f
        spr(c, sx, sy, hh * (0.34f + 0.05f * bass + 0.04f * beat), fireP[0], (110 + 80 * bass + 70 * beat).toInt())
        line.style = Paint.Style.FILL
        line.xfermode = null
        line.color = Color.rgb(255, 214, 150)
        c.drawCircle(sx, sy, hh * 0.07f * (1f + 0.03f * beat), line)
        // dunes
        val step = 16f
        val n = (w / step).toInt() + 2
        for (layer in 0..2) {
            for (i in 0 until n) ys[i] = duneY(layer, i * step, w, hh)
            path.reset()
            path.moveTo(0f, hh)
            for (i in 0 until n) path.lineTo(i * step, ys[i])
            path.lineTo(w, hh)
            path.close()
            fillP.shader = duneSh[layer]
            c.drawPath(path, fillP)
            fillP.shader = null
            if (layer > 0) {
                crest.reset()
                for (i in 0 until n) if (i == 0) crest.moveTo(0f, ys[0]) else crest.lineTo(i * step, ys[i])
                line.style = Paint.Style.STROKE
                line.strokeWidth = max(2f, hh * 0.004f)
                line.color = withA(Color.rgb(255, 196, 130), 40 + 40 * layer + (140 * beat).toInt())
                c.drawPath(crest, line)
            }
        }
        // the beat throws sand up out of the dunes
        if (hit) {
            val cnt = (16 + 40 * bass).toInt()
            for (k in 0 until cnt) {
                val layer = 1 + rnd.nextInt(2)
                val x = rnd.nextFloat() * w
                spawnQ(x, duneY(layer, x, w, hh) - hh * 0.004f, hh * (0.08f + 0.35f * (rnd.nextFloat() - 0.3f)),
                    -hh * (0.45f + 0.95f * rnd.nextFloat()) * (0.75f + 0.5f * bass), hh * (0.007f + 0.011f * rnd.nextFloat()), 0, 0.8f + 0.6f * rnd.nextFloat())
            }
            for (k in 0 until 5) {
                val x = rnd.nextFloat() * w
                spawnQ(x, duneY(2, x, w, hh), hh * 0.05f, -hh * 0.2f * (0.4f + rnd.nextFloat()), hh * (0.03f + 0.03f * rnd.nextFloat()), 1, 0.7f)
            }
        }
        // wind-blown grains drifting across the near dune
        if (rnd.nextFloat() < 0.35f + treble * 1.4f) {
            val x = rnd.nextFloat() * w * 0.3f
            spawnQ(x, duneY(2, x, w, hh) - rnd.nextFloat() * hh * 0.1f, hh * (0.5f + 0.6f * rnd.nextFloat() + treble), -hh * 0.05f * rnd.nextFloat(),
                hh * (0.004f + 0.005f * rnd.nextFloat()), 0, 0.6f)
        }
        stepQ(w, hh)
        drawQ(c)
    }


    // ---------------------------------------------------------------- CYBERPUNK
    private val cyM = Color.rgb(255, 38, 168)
    private val cyC = Color.rgb(0, 238, 255)
    private val cyY = Color.rgb(255, 214, 90)
    private val cyP = arrayOf(mkP(cyM, true), mkP(cyC, true), mkP(cyY, true))
    private var cySky: Shader? = null
    private var cyFloor: Shader? = null
    private var cySun: Shader? = null
    private var scanSh: Shader? = null
    private var gridScroll = 0f
    private var flicker = 0

    private val cbN = 30
    private val cbW = FloatArray(cbN)     // relative widths, normalised in drawing
    private val cbH = FloatArray(cbN)     // personality: base height 0..1
    private val cbS = IntArray(cbN)       // window seed
    private val cbFar = FloatArray(cbN)
    private val cbNear = FloatArray(cbN)
    private val cbLit = IntArray(cbN)

    private val rainN = 110
    private val rnX = FloatArray(rainN) { rnd.nextFloat() }
    private val rnY = FloatArray(rainN) { rnd.nextFloat() }
    private val rnV = FloatArray(rainN) { 0.7f + rnd.nextFloat() }

    private val carN = 5
    private val carX = FloatArray(carN) { rnd.nextFloat() }
    private val carY = FloatArray(carN) { 0.16f + 0.26f * rnd.nextFloat() }
    private val carV = FloatArray(carN) { (0.05f + 0.1f * rnd.nextFloat()) * (if (rnd.nextBoolean()) 1f else -1f) }
    private val carC = IntArray(carN) { rnd.nextInt(2) }

    init {
        var sum = 0f
        for (i in 0 until cbN) { cbW[i] = 0.6f + rnd.nextFloat(); sum += cbW[i]; cbH[i] = rnd.nextFloat(); cbS[i] = rnd.nextInt(100000) }
        for (i in 0 until cbN) cbW[i] = cbW[i] / sum
    }

    private fun cyberShaders(w: Int, h: Int) {
        val hh = h.toFloat()
        cySky = LinearGradient(0f, 0f, 0f, hh * 0.54f, intArrayOf(Color.rgb(5, 2, 18), Color.rgb(30, 6, 62), Color.rgb(120, 14, 112), Color.rgb(255, 60, 150)),
            floatArrayOf(0f, 0.45f, 0.82f, 1f), Shader.TileMode.CLAMP)
        cyFloor = LinearGradient(0f, hh * 0.52f, 0f, hh, Color.rgb(46, 8, 66), Color.rgb(4, 2, 14), Shader.TileMode.CLAMP)
        val r = hh * 0.17f
        cySun = LinearGradient(0f, -r, 0f, r, intArrayOf(Color.rgb(255, 236, 120), Color.rgb(255, 96, 120), Color.rgb(230, 30, 170)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        b.setPixel(0, 0, Color.argb(90, 0, 0, 0)); b.setPixel(1, 0, Color.argb(90, 0, 0, 0)); b.setPixel(2, 0, Color.argb(90, 0, 0, 0)); b.setPixel(3, 0, Color.argb(90, 0, 0, 0))
        scanSh = android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun cyber(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        if (cySky == null) cyberShaders(w.toInt(), hh.toInt())
        val y0 = hh * 0.52f
        fillP.shader = cySky
        c.drawRect(0f, 0f, w, y0 + 2f, fillP)
        fillP.shader = cyFloor
        c.drawRect(0f, y0, w, hh, fillP)
        fillP.shader = null
        if (hit) flicker++

        // the outrun sun, striped, breathing with the bass
        val r = hh * 0.17f * (1f + 0.05f * bass + 0.05f * beat)
        val sx = w * 0.5f
        val sy = y0 - r * 0.35f
        spr(c, sx, sy, r * 2.4f, cyP[0], (80 + 80 * bass + 70 * beat).toInt())
        c.save()
        path.reset(); path.addCircle(sx, sy, r, Path.Direction.CW)
        c.clipPath(path)
        c.translate(sx, sy)
        fillP.shader = cySun
        c.drawRect(-r, -r, r, r, fillP)
        fillP.shader = null
        // dark bands cut into the lower half, rising slowly
        line.style = Paint.Style.FILL
        line.xfermode = null
        line.color = Color.rgb(58, 8, 84)
        val sc = (t * 0.12f) % 1f
        for (k in 0..6) {
            val f = ((k + sc) / 7f)
            val y = r * (0.05f + 0.95f * f)
            val bh = r * 0.02f + r * 0.11f * f
            c.drawRect(-r, y, r, y + bh, line)
        }
        c.restore()

        // flying cars: a headlight pair and a trail
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        for (i in 0 until carN) {
            carX[i] += carV[i] * dtF * (1f + 1.5f * bass)
            if (carX[i] > 1.1f) carX[i] = -0.1f
            if (carX[i] < -0.1f) carX[i] = 1.1f
            val x = carX[i] * w
            val y = carY[i] * hh
            val dir = if (carV[i] > 0) -1f else 1f
            line.strokeWidth = max(2f, hh * 0.004f)
            line.color = withA(if (carC[i] == 0) cyC else cyM, 150)
            c.drawLine(x, y, x + dir * w * 0.045f, y, line)
            spr(c, x, y, hh * 0.016f, cyP[2], 230)
        }
        line.strokeCap = Paint.Cap.BUTT
        line.xfermode = null

        // two skyline layers: the buildings ARE the equaliser
        for (layer in 0..1) {
            var x = 0f
            val baseY = y0
            for (i in 0 until cbN) {
                val bw = cbW[i] * w
                val u = (x + bw / 2f) / w
                val lvl = spec(u * 0.9f)
                val damp = if (u < 0.42f) 0.3f else 1f             // keep the hero text area calmer
                val target = hh * (if (layer == 0) (0.08f + 0.07f * cbH[i] + 0.1f * lvl) else (0.04f + 0.1f * cbH[i] + 0.2f * lvl + 0.03f * beat)) * damp
                val arr = if (layer == 0) cbFar else cbNear
                arr[i] += (target - arr[i]) * (if (target > arr[i]) 0.5f else 0.12f)
                val top = baseY - arr[i]
                line.style = Paint.Style.FILL
                line.xfermode = null
                line.color = if (layer == 0) Color.rgb(34, 10, 70) else Color.rgb(9, 4, 24)
                val gap = if (layer == 0) 0f else max(1f, w * 0.0015f)
                c.drawRect(x + gap, top, x + bw - gap, baseY + 2f, line)
                // neon roof line
                line.style = Paint.Style.STROKE
                line.strokeWidth = max(2f, hh * (if (layer == 0) 0.003f else 0.004f))
                line.xfermode = screen
                val edge = if ((i + layer) % 3 == 0) cyM else cyC
                line.color = withA(edge, (if (layer == 0) 110 else 190) + (60 * beat).toInt())
                c.drawLine(x + gap, top, x + bw - gap, top, line)
                line.xfermode = null
                // lit windows (near layer only)
                if (layer == 1 && arr[i] > hh * 0.03f) {
                    line.style = Paint.Style.FILL
                    val cols = max(2, (bw / (hh * 0.022f)).toInt())
                    val rows = (arr[i] / (hh * 0.026f)).toInt()
                    val cw = (bw - 2 * gap) / cols
                    for (rr in 0 until rows) for (cc in 0 until cols) {
                        val hsh = (cbS[i] + cc * 131 + rr * 71 + (if ((rr + cc) % 4 == 0) flicker * 17 else 0)) % 11
                        if (hsh < 3) {
                            line.xfermode = screen
                            line.color = withA(when (hsh) { 0 -> cyC; 1 -> cyM; else -> cyY }, 150)
                            val wx = x + gap + cc * cw + cw * 0.22f
                            val wy = baseY - (rr + 1) * hh * 0.026f + hh * 0.004f
                            c.drawRect(wx, wy, wx + cw * 0.5f, wy + hh * 0.012f, line)
                        }
                    }
                    line.xfermode = null
                }
                x += bw
            }
        }

        // retro grid floor, rolling toward the viewer with the bass
        gridScroll += dtF * (0.35f + 1.6f * bass + 1.2f * beat)
        line.style = Paint.Style.STROKE
        line.xfermode = screen
        line.strokeWidth = max(2f, hh * 0.003f)
        val vx = w * 0.5f
        for (k in -14..14) {
            val bx = vx + k * w * 0.11f
            line.color = withA(cyM, 110 + (80 * beat).toInt())
            c.drawLine(vx + (bx - vx) * 0.02f, y0, bx, hh, line)
        }
        for (i in 0..9) {
            val f = ((i + gridScroll) % 10f) / 10f
            val y = y0 + (hh - y0) * f * f
            line.color = withA(cyC, (40 + 150 * f).toInt())
            c.drawLine(0f, y, w, y, line)
        }
        line.xfermode = null

        // neon rain
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        line.strokeWidth = max(1.5f, hh * 0.0022f)
        for (i in 0 until rainN) {
            rnY[i] += rnV[i] * dtF * 1.4f
            rnX[i] -= rnV[i] * dtF * 0.18f
            if (rnY[i] > 1f) { rnY[i] = -0.05f; rnX[i] = rnd.nextFloat() * 1.2f }
            val x = rnX[i] * w
            val y = rnY[i] * hh
            line.color = withA(if (i % 4 == 0) cyM else cyC, 70)
            c.drawLine(x, y, x + hh * 0.012f, y - hh * 0.05f * rnV[i], line)
        }
        line.strokeCap = Paint.Cap.BUTT

        line.xfermode = null
        // scanlines
        fillP.shader = scanSh
        c.drawRect(0f, 0f, w, hh, fillP)
        fillP.shader = null
    }

    // ================================================================ shared helpers for the film scenes
    private var trSky: Shader? = null
    private var trFloor: Shader? = null

    private fun buildMore(w: Int, h: Int) {
        val hh = h.toFloat()
        trSky = LinearGradient(0f, 0f, 0f, hh * 0.52f, intArrayOf(Color.rgb(0, 2, 8), Color.rgb(0, 20, 38), Color.rgb(0, 96, 132)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        trFloor = LinearGradient(0f, hh * 0.52f, 0f, hh, Color.rgb(0, 30, 48), Color.rgb(0, 3, 10), Shader.TileMode.CLAMP)
    }

    /** a neon line: wide faint halo, body, bright core */
    private fun glowLine(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, wd: Float, a: Int) {
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        line.strokeWidth = wd * 4f; line.color = withA(col, a / 6); c.drawLine(x1, y1, x2, y2, line)
        line.strokeWidth = wd * 2f; line.color = withA(col, a / 3); c.drawLine(x1, y1, x2, y2, line)
        line.strokeWidth = wd; line.color = withA(lerp(col, Color.WHITE, 0.55f), a); c.drawLine(x1, y1, x2, y2, line)
        line.strokeCap = Paint.Cap.BUTT
        line.xfermode = null
    }

    // ================================================================ TRON LEGACY
    private val trN = 9
    private val trLane = IntArray(trN)
    private val trF = FloatArray(trN) { -1f }
    private val trV = FloatArray(trN)
    private val trOr = BooleanArray(trN)
    private var trAng = 0f
    private val trC = Color.rgb(40, 220, 255)
    private val trO = Color.rgb(255, 120, 20)

    @Volatile private var trBg: Bitmap? = null
    @Volatile private var trEm: Bitmap? = null
    @Volatile private var trLoading = false
    private var trScrim: Shader? = null
    private var trScrimW = 0f

    private fun loadTr() {
        if (trBg != null || trLoading) return
        trLoading = true
        val ctx = context.applicationContext
        Thread {
            try {
                val o = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                fun bmp(n: String): Bitmap = ctx.assets.open(n).use { android.graphics.BitmapFactory.decodeStream(it, null, o)!! }
                trEm = bmp("tr_emit.webp"); trBg = bmp("tr_bg.webp")
            } catch (e: Exception) { android.util.Log.d("Tron", "asset load failed: $e") }
            trLoading = false
        }.start()
    }

    private fun tron(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        loadTr()
        val y0 = hh * 0.52f
        val vx = w * 0.5f
        val bgB = trBg
        if (bgB == null) { c.drawColor(Color.rgb(0, 6, 14)); return }
        rect.set(0f, 0f, w, hh)
        c.drawBitmap(bgB, null, rect, brPlain)
        // the towers' glowing edges pulse with the music
        trEm?.let {
            brAdd.alpha = (52 + 70 * bass + 60 * beat).toInt().coerceIn(0, 255)
            c.drawBitmap(it, null, rect, brAdd)
        }
        line.style = Paint.Style.FILL; line.xfermode = null; line.color = Color.argb(70, 0, 4, 12)
        c.drawRect(0f, 0f, w, hh, line)
        spr(c, vx, y0, w * 0.5f, cyP[1], (18 + 40 * bass + 50 * beat).toInt())

        // a dark veil on the left keeps the title and buttons readable
        if (trScrimW != w) {
            trScrimW = w
            trScrim = LinearGradient(0f, 0f, w * 0.45f, 0f, intArrayOf(Color.argb(170, 0, 4, 10), Color.argb(80, 0, 4, 10), Color.argb(0, 0, 4, 10)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        fillP.shader = trScrim
        c.drawRect(0f, 0f, w * 0.45f, y0, fillP)
        fillP.shader = null

        // light pillars rising from the horizon: the equaliser (short, in front of the towers)
        val np = 44
        for (i in 0 until np) {
            val u = (i + 0.5f) / np
            val damp = if (u < 0.4f) 0.4f else 1f
            val hgt = hh * (0.03f + 0.2f * spec(u * 0.9f) * damp + 0.03f * beat)
            glowLine(c, u * w, y0, u * w, y0 - hgt, if (i % 9 == 4) trO else trC, max(1.5f, hh * 0.0025f), 190)
        }

        // the identity disc
        val cx = vx
        val cy = y0 - hh * 0.22f
        val r = hh * 0.15f * (1f + 0.05f * bass + 0.07f * beat)
        spr(c, cx, cy, r * 2.6f, cyP[1], (40 + 60 * bass + 80 * beat).toInt())
        trAng += dtF * (25f + 90f * mid)
        line.style = Paint.Style.STROKE
        line.xfermode = screen
        line.strokeWidth = max(2f, hh * 0.004f)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        line.color = withA(trC, 255); c.drawOval(rect, line)
        rect.set(cx - r * 0.84f, cy - r * 0.84f, cx + r * 0.84f, cy + r * 0.84f)
        line.color = withA(trC, 215); c.drawOval(rect, line)
        rect.set(cx - r * 0.64f, cy - r * 0.64f, cx + r * 0.64f, cy + r * 0.64f)
        line.color = withA(trC, 180); c.drawOval(rect, line)
        line.strokeWidth = max(4f, hh * 0.009f)
        rect.set(cx - r * 1.14f, cy - r * 1.14f, cx + r * 1.14f, cy + r * 1.14f)
        line.color = withA(trO, 255)
        for (s in 0 until 10) c.drawArc(rect, trAng + s * 36f, 24f, false, line)
        rect.set(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f)
        line.color = withA(trC, 230)
        for (s in 0 until 6) c.drawArc(rect, -trAng * 1.6f + s * 60f, 38f, false, line)
        line.xfermode = null
        spr(c, cx, cy, r * 0.22f, cyP[1], 230)

        // the grid floor
        for (k in -14..14) {
            val bx = vx + k * w * 0.11f
            glowLine(c, vx + (bx - vx) * 0.02f, y0, bx, hh, trC, max(1.2f, hh * 0.0016f), 70 + (60 * beat).toInt())
        }
        trAng.let { }
        gridScroll += dtF * (0.3f + 1.2f * bass + 1f * beat)
        for (i in 0..9) {
            val f = ((i + gridScroll) % 10f) / 10f
            val y = y0 + (hh - y0) * f * f
            glowLine(c, 0f, y, w, y, trC, max(1.2f, hh * 0.0016f), (20 + 100 * f).toInt())
        }

        // light cycles: a ribbon of light runs down a lane of the grid on every beat
        if (hit) {
            for (i in 0 until trN) if (trF[i] < 0f) {
                trF[i] = 0.03f; trLane[i] = rnd.nextInt(13) - 6; trV[i] = 0.3f + 0.35f * rnd.nextFloat(); trOr[i] = rnd.nextFloat() < 0.3f
                break
            }
        }
        for (i in 0 until trN) {
            if (trF[i] < 0f) continue
            trF[i] += trV[i] * (0.6f + 1.8f * bass) * dtF
            if (trF[i] > 1.12f) { trF[i] = -1f; continue }
            val f = trF[i]
            val f0 = max(0.02f, f - 0.3f)
            val x1 = vx + trLane[i] * w * 0.11f * f * f
            val y1 = y0 + (hh - y0) * f * f
            val x0 = vx + trLane[i] * w * 0.11f * f0 * f0
            val yy0 = y0 + (hh - y0) * f0 * f0
            val col = if (trOr[i]) trO else trC
            glowLine(c, x0, yy0, x1, y1, col, max(2f, hh * (0.003f + 0.008f * f)), 230)
            spr(c, x1, y1, hh * (0.012f + 0.03f * f), if (trOr[i]) fireP[0] else cyP[1], 240)
        }
    }

    // ================================================================ THE MATRIX
    private val mxCols = 50
    private val mxHead = FloatArray(mxCols)
    private val mxSpd = FloatArray(mxCols)
    private val mxLen = IntArray(mxCols)
    private val mxCh = Array(mxCols) { CharArray(48) }
    private val glyphs = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789Z:=*+<>"
    private val mxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }

    init {
        for (i in 0 until mxCols) {
            mxHead[i] = -rnd.nextFloat() * 30f
            mxSpd[i] = 7f + 15f * rnd.nextFloat()
            mxLen[i] = 8 + rnd.nextInt(20)
            for (k in 0 until 48) mxCh[i][k] = glyphs[rnd.nextInt(glyphs.length)]
        }
    }

    private fun matrix(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        c.drawColor(Color.rgb(1, 9, 4))
        val cell = hh * 0.034f
        val rows = (hh / cell).toInt() + 2
        val colW = w / mxCols
        mxPaint.textSize = cell * 0.95f
        for (i in 0 until mxCols) {
            val u = i / (mxCols - 1f)
            val lvl = spec(u * 0.85f)
            mxHead[i] += mxSpd[i] * dtF * (0.6f + 1.6f * bass + 0.8f * lvl) + (if (hit) 0.7f else 0f)
            if (mxHead[i] - mxLen[i] > rows) {
                mxHead[i] = -rnd.nextFloat() * 8f
                mxSpd[i] = 7f + 15f * rnd.nextFloat()
                mxLen[i] = 8 + rnd.nextInt(20)
            }
            if (rnd.nextFloat() < 0.3f) mxCh[i][rnd.nextInt(48)] = glyphs[rnd.nextInt(glyphs.length)]
            val x = (i + 0.5f) * colW
            val head = mxHead[i].toInt()
            val gain = (0.3f + 0.7f * min(1f, lvl * 1.3f + 0.25f)) * (if (u < 0.4f) 0.45f else 1f)
            for (k in 0 until mxLen[i]) {
                val row = head - k
                if (row < 0 || row >= rows) continue
                val f = 1f - k / mxLen[i].toFloat()
                val col: Int
                val al: Int
                when (k) {
                    0 -> { col = Color.rgb(215, 255, 225); al = 235 }
                    1 -> { col = Color.rgb(110, 255, 140); al = 220 }
                    else -> { col = Color.rgb(0, (150 + 90 * f).toInt(), (40 + 30 * f).toInt()); al = (210 * f * f * gain + 18).toInt() }
                }
                val low = if ((row + 1) * cell > hh * 0.6f) 0.5f else 1f
                mxPaint.color = withA(col, (al * low * (if (k == 0 && u < 0.4f) 0.55f else 1f)).toInt())
                c.drawText(mxCh[i], (row * 7 + i * 3) % 48, 1, x, (row + 1) * cell, mxPaint)
            }
        }
        // on the kick a pale code-line flashes across
        if (beat > 0.4f) {
            line.style = Paint.Style.FILL
            line.xfermode = screen
            line.color = withA(Color.rgb(90, 255, 140), (beat * 46).toInt())
            val gy = rnd.nextFloat() * hh
            c.drawRect(0f, gy, w, gy + hh * 0.012f, line)
            line.xfermode = null
        }
    }

    // ================================================================ BLADE RUNNER
    // The city (smog sky, hazed tower layers, pyramid, wet street, window lights, neon signs, fog) is rendered offline by
    // tools/bladerunner.py and shipped as assets; the music drives window glow, sign flicker, fog, flames, searchlights and rain.
    private class Br(val bg: Bitmap, val emit: Bitmap, val signs: Bitmap, val fog: Bitmap, val sp: List<BrSign>)
    private class BrSign(val sx: Int, val sy: Int, val sw: Int, val sh: Int, val cx: Float, val cy: Float, val grp: Int)

    @Volatile private var br: Br? = null
    @Volatile private var brLoading = false
    private val brAdd = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val brPlain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val brSrc = android.graphics.Rect()
    private val rain2N = 140
    private val r2X = FloatArray(rain2N) { rnd.nextFloat() }
    private val r2Y = FloatArray(rain2N) { rnd.nextFloat() }
    private val r2V = FloatArray(rain2N) { 0.8f + rnd.nextFloat() }
    private var lightning = 0f
    private var brScrim: Shader? = null
    private var brScrimW = 0f

    private fun loadBr() {
        if (br != null || brLoading) return
        brLoading = true
        val ctx = context.applicationContext
        Thread {
            try {
                val am = ctx.assets
                val o = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                fun bmp(n: String): Bitmap = am.open(n).use { android.graphics.BitmapFactory.decodeStream(it, null, o)!! }
                val sp = ArrayList<BrSign>()
                am.open("br_signs.txt").bufferedReader().useLines { ls ->
                    for (l in ls) {
                        val p = l.trim().split(" ")
                        if (p.size < 8) continue
                        sp += BrSign(p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toFloat(), p[6].toFloat(), p[7][0] - 'A')
                    }
                }
                br = Br(bmp("br_bg.webp"), bmp("br_emit.webp"), bmp("br_signs.webp"), bmp("br_fog.webp"), sp)
            } catch (e: Exception) {
                android.util.Log.d("BladeRunner", "asset load failed: $e")
            }
            brLoading = false
        }.start()
    }

    private fun flames(c: Canvas, x: Float, top: Float, hh: Float) {
        for (k in 0..4) {
            val ox = x + sin(t * 7f + k * 2f) * hh * 0.004f
            val oy = top - k * hh * 0.011f * (1f + 2f * bass + 2f * beat)
            spr(c, ox, oy, hh * (0.03f + 0.012f * (5 - k) + 0.035f * bass + 0.04f * beat), if (k < 2) fireP[0] else fireP[1], (150 + 80 * beat).toInt())
        }
    }

    private fun runner(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        loadBr()
        val y0 = hh * 0.55f
        val b = br
        if (b == null) { c.drawColor(Color.rgb(8, 12, 18)); return }
        rect.set(0f, 0f, w, hh)
        c.drawBitmap(b.bg, null, rect, brPlain)

        // searchlights sweeping the smog (behind the windows)
        line.style = Paint.Style.FILL
        line.xfermode = screen
        for (k in 0..1) {
            val ox = w * (0.22f + 0.56f * k)
            val a = sin(t * 0.35f + k * 2.2f) * 0.55f
            val len = hh * 0.75f
            for (n in 0..3) {
                val d = 0.058f - 0.014f * n
                path.reset()
                path.moveTo(ox, y0 - hh * 0.02f)
                path.lineTo(ox + sin(a - d) * len, y0 - cos(a - d) * len)
                path.lineTo(ox + sin(a + d) * len, y0 - cos(a + d) * len)
                path.close()
                line.color = withA(Color.rgb(190, 235, 255), 7)
                c.drawPath(path, line)
            }
        }
        line.xfermode = null

        // drifting smog in front of the city, two layers
        val fw = b.fog.width.toFloat()
        val fh = hh * 0.36f
        val scaleF = fh / b.fog.height
        for (lay in 0..1) {
            val off = ((t * (9f + 7f * lay) * (if (lay == 0) 1f else -1f)) % fw + fw) % fw
            brAdd.alpha = (if (lay == 0) 78 + 40 * bass else 44 + 26 * bass).toInt().coerceIn(0, 255)
            val top = y0 - hh * (if (lay == 0) 0.27f else 0.18f)
            var x = -off * scaleF
            while (x < w) {
                rect.set(x, top, x + fw * scaleF, top + fh)
                c.drawBitmap(b.fog, null, rect, brAdd)
                x += fw * scaleF
            }
        }

        // the lights in the towers breathe with the bass
        brAdd.alpha = (96 + 90 * bass + 60 * beat).toInt().coerceIn(0, 255)
        rect.set(0f, 0f, w, hh)
        c.drawBitmap(b.emit, null, rect, brAdd)

        // refinery flame stacks
        for (fx in floatArrayOf(0.10f, 0.93f)) flames(c, fx * w, y0 - hh * 0.3f, hh)

        // a dark veil on the left and under the hero text keeps the title and buttons readable
        if (brScrimW != w) {
            brScrimW = w
            brScrim = LinearGradient(0f, 0f, w * 0.5f, 0f, intArrayOf(Color.argb(190, 3, 5, 9), Color.argb(110, 3, 5, 9), Color.argb(0, 3, 5, 9)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        fillP.shader = brScrim
        c.drawRect(0f, 0f, w * 0.5f, y0 + hh * 0.02f, fillP)
        fillP.shader = null

        // neon signs: each flickers on its own, all flare on the kick; a dim mirrored copy lies on the wet street
        val sc = w / 1600f
        for ((i, s) in b.sp.withIndex()) {
            val fl = if (((t * 3f + i * 1.7f) % 7f) < 0.16f) 0.2f else 1f
            val br_ = (0.66f + 0.34f * min(1f, bass + beat)) * fl
            val dw = s.sw * sc; val dh = s.sh * sc
            val cx = s.cx * w; val cy = s.cy * hh
            brSrc.set(s.sx, s.sy, s.sx + s.sw, s.sy + s.sh)
            brAdd.alpha = (255 * br_).toInt().coerceIn(0, 255)
            rect.set(cx - dw / 2, cy - dh / 2, cx + dw / 2, cy + dh / 2)
            c.drawBitmap(b.signs, brSrc, rect, brAdd)
            // reflection
            val ry = y0 + (y0 - cy) * 0.62f
            c.save()
            c.scale(1f, -0.8f, cx, ry)
            brAdd.alpha = (70 * br_).toInt().coerceIn(0, 255)
            rect.set(cx - dw / 2, ry - dh / 2, cx + dw / 2, ry + dh / 2)
            c.drawBitmap(b.signs, brSrc, rect, brAdd)
            c.restore()
        }

        // a spinner crossing the sky
        val spx = ((t * 0.06f + 0.3f) % 1.4f - 0.2f) * w
        val spy = hh * (0.17f + 0.02f * sin(t * 1.3f))
        val s = hh * 0.012f
        line.style = Paint.Style.FILL
        line.xfermode = screen
        path.reset()
        path.moveTo(spx + s * 3f, spy)
        path.lineTo(spx + s * 9f, spy + s * 18f)
        path.lineTo(spx + s * 1f, spy + s * 18f)
        path.close()
        line.color = withA(Color.rgb(255, 220, 160), 22)
        c.drawPath(path, line)
        line.xfermode = null
        line.color = Color.rgb(10, 14, 20)
        rect.set(spx - s * 3f, spy - s, spx + s * 3f, spy + s)
        c.drawRoundRect(rect, s, s, line)
        spr(c, spx + s * 3f, spy, s * 2.2f, cyP[2], 250)
        spr(c, spx - s * 3f, spy, s * 1.6f, cyP[0], 200)

        // heavy rain
        line.style = Paint.Style.STROKE
        line.strokeCap = Paint.Cap.ROUND
        line.xfermode = screen
        line.strokeWidth = max(1.5f, hh * 0.002f)
        for (i in 0 until rain2N) {
            r2Y[i] += r2V[i] * dtF * 1.7f
            r2X[i] -= r2V[i] * dtF * 0.12f
            if (r2Y[i] > 1f) { r2Y[i] = -0.05f; r2X[i] = rnd.nextFloat() * 1.15f }
            val x = r2X[i] * w
            val y = r2Y[i] * hh
            line.color = withA(Color.rgb(150, 205, 230), 62)
            c.drawLine(x, y, x + hh * 0.008f, y - hh * 0.06f * r2V[i], line)
        }
        line.strokeCap = Paint.Cap.BUTT

        // lightning flash on a hard kick
        if (hit && beat > 0.9f && rnd.nextFloat() < 0.14f) lightning = 1f
        if (lightning > 0.02f) {
            line.style = Paint.Style.FILL
            line.color = withA(Color.rgb(200, 230, 255), (lightning * 60).toInt())
            c.drawRect(0f, 0f, w, hh, line)
            lightning *= 0.82f
        }
        line.xfermode = null
    }

    // ================================================================ INTERSTELLAR (Gargantua)
    // The picture itself is ray-traced offline (tools/gargantua.py: Schwarzschild null geodesics, lensed star field,
    // thin disc with Doppler/gravitational shift) and shipped as assets. At runtime the music drives brightness,
    // bloom, a gravitational ripple and hot-spots that orbit the disc along the *real* lensed light paths.
    private class Gar(
        val discA: Bitmap, val discB: Bitmap, val stars: Bitmap, val glow: Bitmap,
        val lut: ShortArray, val nr: Int, val nph: Int, val rin: Float, val rmax: Float
    )

    private val ihN2 = 150
    private val ihX = FloatArray(ihN2) { rnd.nextFloat() }
    private val ihY = FloatArray(ihN2) { rnd.nextFloat() }
    private val ihP = FloatArray(ihN2) { rnd.nextFloat() }

    @Volatile private var gar: Gar? = null
    @Volatile private var garLoading = false
    private val addP = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val ripP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

    private fun loadGar() {
        if (gar != null || garLoading) return
        garLoading = true
        val ctx = context.applicationContext
        Thread {
            try {
                val am = ctx.assets
                val o = android.graphics.BitmapFactory.Options().apply { inScaled = false }
                fun bmp(n: String): Bitmap = am.open(n).use { android.graphics.BitmapFactory.decodeStream(it, null, o)!! }
                val bytes = am.open("gar_lut.bin").use { it.readBytes() }
                val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val k = bb.int; val nr = bb.int; val nph = bb.int; bb.int
                val rin = bb.float; val rmax = bb.float
                val lut = ShortArray(k * nr * nph * 2)
                bb.asShortBuffer().get(lut)
                gar = Gar(bmp("gar_disc_a.webp"), bmp("gar_disc_b.webp"), bmp("gar_stars.webp"), bmp("gar_glow.webp"), lut, nr, nph, rin, rmax)
            } catch (e: Exception) {
                android.util.Log.d("Gargantua", "asset load failed: $e")
            }
            garLoading = false
        }.start()
    }

    // hot-spots: knots of plasma orbiting at ring radius r (in M), seen through the lens
    private val hsN = 18
    private val hsR = FloatArray(hsN)
    private val hsPh = FloatArray(hsN)
    private val hsAge = FloatArray(hsN)
    private val hsDur = FloatArray(hsN)      // 0 = free slot
    private val hsAmp = FloatArray(hsN)
    private var ripple = -1f
    private var garFlare = 0f
    private val garPt = FloatArray(2)

    private fun hsSpawn(rMin: Float, rMax: Float, amp: Float, dur: Float) {
        for (i in 0 until hsN) if (hsDur[i] <= 0f) {
            hsR[i] = rMin + (rMax - rMin) * rnd.nextFloat(); hsPh[i] = rnd.nextFloat() * TAU
            hsAge[i] = 0f; hsDur[i] = dur; hsAmp[i] = amp
            return
        }
    }

    /** image position (in the 960x480 render, pixels) of ring radius r at disc azimuth ph, for image order k */
    private fun garPos(g: Gar, k: Int, r: Float, ph: Float): Boolean {
        val fr = (r - g.rin) / (g.rmax - g.rin) * g.nr - 0.5f
        if (fr < -0.5f || fr > g.nr - 0.5f) return false
        var fp = ph / TAU * g.nph - 0.5f
        val i0 = Math.floor(fr.toDouble()).toInt(); val tr = fr - i0
        val j0 = Math.floor(fp.toDouble()).toInt(); val tp = fp - j0
        var sx = 0f; var sy = 0f; var sw = 0f
        for (di in 0..1) for (dj in 0..1) {
            val ri = (i0 + di).coerceIn(0, g.nr - 1)
            val pj = ((j0 + dj) % g.nph + g.nph) % g.nph
            val idx = ((k * g.nr + ri) * g.nph + pj) * 2
            val x = g.lut[idx].toInt()
            if (x == -32768) continue
            val wgt = (if (di == 0) 1 - tr else tr) * (if (dj == 0) 1 - tp else tp)
            sx += x / 8f * wgt; sy += g.lut[idx + 1] / 8f * wgt; sw += wgt
        }
        if (sw < 0.45f) return false
        garPt[0] = sx / sw; garPt[1] = sy / sw
        return true
    }

    private fun interstellar(c: Canvas, w: Float, hh: Float, hit: Boolean) {
        c.drawColor(Color.rgb(0, 0, 2))
        loadGar()
        val amb = ambient != -1          // screensaver: the black hole gets the whole screen
        val sc = hh / 1080f * (if (amb) 1.7f else 1.3f) * (1f + 0.010f * bass + 0.018f * beat)
        val cx = w * (if (amb) 0.5f else 0.55f)
        val cy = hh * (if (amb) 0.47f else 0.385f)
        val left = cx - 480f * sc; val right = cx + 480f * sc
        val top = cy - 240f * sc; val bot = cy + 240f * sc

        // a few plain stars outside the traced area
        line.style = Paint.Style.FILL
        line.xfermode = null
        for (i in 0 until ihN2) {
            val x = ihX[i] * w; val y = ihY[i] * hh
            if (x > left - 6f && x < right + 6f && y > top - 6f && y < bot + 6f) continue
            val tw = 0.5f + 0.5f * sin(t * (0.5f + ihP[i] * 1.2f) + ihP[i] * 40f)
            line.color = withA(Color.rgb(215, 228, 255), (45 + 120 * tw * (0.55f + 0.45f * treble)).toInt())
            c.drawCircle(x, y, max(1.2f, hh * 0.0019f * (0.5f + ihP[i])), line)
        }
        val g = gar ?: return

        // events
        if (hit) {
            garFlare = 1f; ripple = 0f
            hsSpawn(2.6f, 5.5f, 1f, 5.5f)
            if (beat > 0.8f) hsSpawn(3f, 8f, 0.8f, 6f)
        }
        garFlare *= 0.9f
        if (treble > 0.3f && rnd.nextFloat() < 0.05f) hsSpawn(6f, 12f, 0.5f, 7f)
        var alive = 0
        for (i in 0 until hsN) if (hsDur[i] > 0f) alive++
        if (alive < 4 && rnd.nextFloat() < 0.04f) hsSpawn(3f, 9f, 0.55f, 9f)

        // layers: stars, wide halo, disc (two plasma textures cross-fading slowly)
        rect.set(left, top, right, bot)
        addP.alpha = (205 + 50 * treble).toInt().coerceIn(0, 255)
        c.drawBitmap(g.stars, null, rect, addP)
        addP.alpha = (95 + 110 * bass + 80 * garFlare).toInt().coerceIn(0, 255)
        c.drawBitmap(g.glow, null, rect, addP)
        val mixB = 0.5f + 0.5f * sin(t * 0.33f)
        val base = 0.88f + 0.12f * min(1f, bass * 1.3f)
        addP.alpha = (255 * base * (1f - mixB)).toInt().coerceIn(0, 255)
        c.drawBitmap(g.discA, null, rect, addP)
        addP.alpha = (255 * base * mixB).toInt().coerceIn(0, 255)
        c.drawBitmap(g.discB, null, rect, addP)
        if (garFlare > 0.05f) {            // the kick: the whole lensed ring flares
            addP.alpha = (garFlare * (70 + 50 * bass)).toInt().coerceIn(0, 255)
            c.drawBitmap(g.discA, null, rect, addP)
        }

        // gravitational ripple leaving the hole on each kick
        if (ripple >= 0f) {
            ripple += dtF * 0.55f
            if (ripple > 1f) ripple = -1f
            else {
                val R = 92f * sc * (1.15f + 1.9f * ripple)
                val a = (1f - ripple) * (1f - ripple)
                ripP.strokeWidth = max(2f, hh * 0.004f)
                ripP.color = withA(Color.rgb(255, 196, 120), (a * 54).toInt())
                c.drawCircle(cx, cy, R, ripP)
                ripP.strokeWidth = max(5f, hh * 0.012f)
                ripP.color = withA(Color.rgb(255, 150, 70), (a * 14).toInt())
                c.drawCircle(cx, cy, R, ripP)
            }
        }

        // hot-spots orbiting along the lensed light paths (primary image k=0, lens-bent copy k=1)
        for (i in 0 until hsN) {
            if (hsDur[i] <= 0f) continue
            hsAge[i] += dtF
            if (hsAge[i] > hsDur[i]) { hsDur[i] = 0f; continue }
            val om = 2f * PI.toFloat() / 3.4f * (hsR[i] / 3f).pow(-1.5f)
            hsPh[i] = (hsPh[i] - om * dtF + TAU) % TAU                  // clockwise: the approaching side is on the right
            val life = hsAge[i] / hsDur[i]
            val fade = min(1f, hsAge[i] / 0.5f) * (1f - life) * (1f - life * 0.3f)
            for (k in 0..1) for (j in 0..9) {
                val ph = (hsPh[i] + j * 0.05f / (0.5f + 0.25f * hsR[i]) + TAU) % TAU
                if (!garPos(g, k, hsR[i], ph)) continue
                val tr = 1f - j / 10f
                val al = hsAmp[i] * fade * tr * tr * (if (k == 0) 1f else 0.8f)
                val x = cx + (garPt[0] - 480f) * sc
                val y = cy + (garPt[1] - 240f) * sc
                val rad = hh * (0.0075f + 0.007f * hsAmp[i]) * (0.55f + 0.45f * tr)
                spr(c, x, y, rad * 2.6f, fireP[0], (al * 120).toInt())
                if (j < 4) spr(c, x, y, rad * 0.9f, fireP[1], (al * 235).toInt())
            }
        }
    }

}
