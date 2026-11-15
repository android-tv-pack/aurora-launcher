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
        val MODES = arrayOf("Off", "Flow", "Pixels", "Wave", "Desert", "Cyberpunk", "Blade Runner")
        /** the first scene; the modes before it need music */
        const val FIRST_SCENE = 4
        /** the scenes also run by themselves when nothing is playing */
        fun isScene(mode: Int) = mode >= FIRST_SCENE
        fun allowed(a: MainActivity) = a.checkSelfPermission("android.permission.RECORD_AUDIO") == android.content.pm.PackageManager.PERMISSION_GRANTED
        private const val N = 56
        private const val TAU = (2 * PI).toFloat()
    }

    /** screensaver: -1 = off, -2 = rotate through the scenes, 4..6 = that scene, drawn full-screen */
    var ambient = -1
        set(v) { field = v; rotMode = -1; evaluate() }
    /** Customize shows a mode in its little picture: > 0 = that mode, moving also without music */
    var preview = 0
        set(v) { if (field != v) { field = v; evaluate() } }
    private var rotMode = -1
    private var rotSince = 0L
    private var rotKey = ""
    private var xfade = 0f

    private fun rotating() = ambient == -2 || (ambient == -1 && a.store.vizRotate != 0 && a.store.visualizer > 0)

    /** the mode actually drawn: the screensaver's, the auto-rotation's, or the chosen one */
    fun mode(): Int = when {
        preview > 0 -> preview
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
        val list = if (scenes) IntArray(MODES.size - FIRST_SCENE) { it + FIRST_SCENE } else IntArray(MODES.size - 1) { it + 1 }
        var m: Int
        do { m = list[rnd.nextInt(list.size)] } while (m == rotMode && list.size > 1)
        rotMode = m; rotSince = now; xfade = 1f
        android.util.Log.d("AuroraViz", "rotate -> " + MODES[m] + (if (scenes) " (screensaver)" else ""))
    }

    /** a full-screen picture lies over the visualiser: it keeps its state but stops drawing */
    var covered = false
        set(v) {
            if (field == v) return
            field = v
            if (!v) { lastFrame = SystemClock.uptimeMillis(); invalidate() }
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

    private val rnd = java.util.Random(7)

    // ---- Pixels
    private val peaks = FloatArray(64)

    private val poll = object : Runnable {
        override fun run() { evaluate(); h.postDelayed(this, 500) }
    }
    private val frame = object : Runnable {
        override fun run() {
            if (!active) return
            if (!covered) {
                tick()
                invalidate()
            }
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
        // the scenes keep moving without music; the others need a signal
        setActive(mode > 0 && (viz != null || isScene(mode) || preview > 0))
    }

    private fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        visibility = if (on) VISIBLE else GONE
        h.removeCallbacks(frame)
        if (on) { lastFrame = SystemClock.uptimeMillis(); h.post(frame) }
        else { target.fill(0f); bands.fill(0f); bass = 0f; mid = 0f; treble = 0f; beat = 0f }
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
        if (!active || covered) return
        val w = width.toFloat()
        val hh = height.toFloat()
        if (w <= 0f) return
        val hit = beatHit
        beatHit = false
        when (mode()) {
            1 -> flow(c, w, hh)
            2 -> pixels(c, w, hh)
            3 -> waves(c, w, hh)
            4 -> desert(c, w, hh, hit)
            5 -> cyber(c, w, hh, hit)
            6 -> runner(c, w, hh, hit)
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

    // ------------------------------------------------------------------ Wave (the classic) and the scenes

    private var wave = ByteArray(0)
    private var idle = true
    private var beatHit = false
    private var idleBeatAt = 0f
    private var dtF = 0.036f

    /** true while the visualiser owns the background: real audio, or a scene (which also runs without music) */
    val live: Boolean get() = active && (!idle || isScene(mode()))
    /** true only while it reacts to real audio (a scene running without music is not) */
    val hearing: Boolean get() = active && !idle

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

}
