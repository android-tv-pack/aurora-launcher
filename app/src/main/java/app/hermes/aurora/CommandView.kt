package app.hermes.aurora

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * COMMAND: a half-circle dial of apps around the clock, HUD panels on the left.
 * Everything is drawn on one canvas; LEFT/RIGHT rotate the dial, OK opens, hold OK = options.
 */
internal class CommandView(private val a: MainActivity) : View(a) {
    var onUp: (() -> Unit)? = null


    private var apps: List<AppEntry> = emptyList()
    private var icons: List<Drawable> = emptyList()
    private var index = 0
    private var pos = 0f
    private var anim: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint()
    private val box = RectF()
    private val clip = Path()

    private val mono: Typeface = Typeface.MONOSPACE
    private val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private var net = ""
    private var ip = ""
    private var free = ""
    private var ram = 0

    init { isFocusable = true }

    fun setApps(list: List<AppEntry>, focusKey: String?) {
        apps = list
        // private copies, so changing bounds/alpha here never disturbs another view's drawable
        icons = list.map { e -> e.icon.constantState?.newDrawable(a.resources)?.mutate() ?: e.icon }
        index = list.indexOfFirst { it.key == focusKey }.let { if (it < 0) 0 else it }
        pos = index.toFloat()
        refreshInfo()
    }

    fun currentKey(): String? = apps.getOrNull(index)?.key

    fun refreshInfo() {
        net = a.netLabel()
        ip = a.ipAddress()
        free = a.storageFree()
        ram = a.ramUsedPct()
        invalidate()
    }

    fun stop() {
        anim?.cancel()
        anim = null
    }

    // ---------------------------------------------------------------- keys

    private fun move(dir: Int) {
        if (apps.isEmpty()) return
        val ni = (index + dir).coerceIn(0, apps.size - 1)
        if (ni == index) return
        index = ni
        a.lastKey = apps[index].key
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, index.toFloat()).apply {
            duration = 230
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun isOk(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); return true }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); return true }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> { onUp?.invoke(); return true }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { event.startTracking(); return true }
            isOk(keyCode) -> { event.startTracking(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            apps.getOrNull(index)?.let { if (NowPlaying.infoFor(it.pkg) != null) NowPlaying.stopPlayback(it.pkg) }
            return true
        }
        if (isOk(keyCode)) {
            apps.getOrNull(index)?.let { a.showAppMenu(it, "all") }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.isTracking && !event.isCanceled) apps.getOrNull(index)?.let { if (NowPlaying.infoFor(it.pkg) != null) NowPlaying.playPause(it.pkg) }
            return true
        }
        if (isOk(keyCode)) {
            if (event.isTracking && !event.isCanceled) apps.getOrNull(index)?.let { a.openApp(it) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------------------------------------------------------- drawing

    private fun text(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface,
        align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f, alpha: Int = 255
    ) {
        txt.typeface = face
        txt.textSize = size
        txt.letterSpacing = spacing
        txt.textAlign = align
        txt.color = color
        txt.alpha = alpha.coerceIn(0, 255)
        c.drawText(s, x, y, txt)
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        glow.shader = RadialGradient(
            w / 2f, h * 0.9f, h * 0.75f,
            intArrayOf(Color.argb(150, 14, 91, 122), Color.argb(0, 14, 91, 122)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = h / 540f
        val accent = a.accent
        val cx = w / 2f
        val cy = h * 0.80f
        val r = h * 0.40f

        if (!a.viz.active) c.drawColor(Color.rgb(3, 6, 11))      // with the visualiser on, it paints this base itself
        // faint grid, fading upwards
        line.strokeWidth = 1f
        line.color = Color.argb(14, 94, 231, 255)
        var gx = 0f
        while (gx < w) { c.drawLine(gx, h * 0.45f, gx, h, line); gx += 34f * s }
        var gy = h * 0.45f
        while (gy < h) { c.drawLine(0f, gy, w, gy, line); gy += 34f * s }
        c.drawRect(0f, 0f, w, h, glow)

        // arcs
        line.strokeWidth = s * 1f
        line.color = withAlpha(accent, 60)
        box.set(cx - r * 1.18f, cy - r * 1.18f, cx + r * 1.18f, cy + r * 1.18f)
        c.drawArc(box, 180f, 180f, false, line)
        line.strokeWidth = s * 1.6f
        line.color = withAlpha(accent, 130)
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 180f, 180f, false, line)
        // ticks
        var k = 15
        while (k <= 165) {
            val ang = Math.toRadians((180 + k).toDouble())
            val major = k % 15 == 0
            val r1 = r * 1.24f
            val r2 = r * (if (major) 1.30f else 1.27f)
            line.strokeWidth = s * 1f
            line.color = withAlpha(accent, if (major) 170 else 70)
            c.drawLine(cx + r1 * cos(ang).toFloat(), cy + r1 * sin(ang).toFloat(), cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), line)
            k += 3
        }

        // dial nodes
        if (apps.isNotEmpty()) {
            val first = max(0, (pos - 3.5f).toInt())
            val last = min(apps.size - 1, (pos + 3.5f).toInt() + 1)
            // draw the focused one last so it sits on top
            val order = (first..last).sortedByDescending { abs(it - pos) }
            for (i in order) drawNode(c, i, i - pos, cx, cy, r, s, accent)
        }

        // centre: clock, date, continue line
        text(c, a.nowStr(a.clockPattern()), cx, h * 0.645f, h * 0.145f, Color.WHITE, thin, Paint.Align.CENTER, -0.02f)
        text(c, a.nowStr("EEE  ·  d  ·  MMM").uppercase(Locale.getDefault()), cx, h * 0.705f, 13f * s, Color.rgb(159, 180, 196), mono, Paint.Align.CENTER, 0.3f)
        val cur = apps.getOrNull(index)
        val item = cur?.let { a.feed[it.pkg]?.firstOrNull() }
        val np = cur?.let { NowPlaying.infoFor(it.pkg) }
        if (np != null) {
            var t = (if (np.playing) "▶ " else "❚❚ ") + np.title + (if (np.artist.isNotEmpty()) " — " + np.artist else "")
            if (t.length > 46) t = t.substring(0, 45) + "…"
            text(c, t.uppercase(Locale.getDefault()), cx, h * 0.765f, 11f * s, accent, mono, Paint.Align.CENTER, 0.18f)
            val f = np.fraction()
            if (f >= 0f) {
                val bw = 190f * s
                fill.color = Color.argb(46, 255, 255, 255)
                c.drawRoundRect(cx - bw / 2, h * 0.785f, cx + bw / 2, h * 0.785f + 3f * s, 2f * s, 2f * s, fill)
                fill.color = accent
                c.drawRoundRect(cx - bw / 2, h * 0.785f, cx - bw / 2 + bw * f, h * 0.785f + 3f * s, 2f * s, 2f * s, fill)
            }
            text(c, "▼  PAUSE      ·      HOLD ▼  STOP", cx, h * 0.84f, 9f * s, Color.argb(120, 159, 180, 196), mono, Paint.Align.CENTER, 0.16f)
            if (np.playing) postInvalidateDelayed(1000)
        } else if (item != null) {
            var t = item.kind.substringBefore(' ') + " · " + item.title
            if (t.length > 44) t = t.substring(0, 43) + "…"
            text(c, t.uppercase(Locale.getDefault()), cx, h * 0.765f, 11f * s, accent, mono, Paint.Align.CENTER, 0.18f)
            if (item.progress >= 0f) {
                val bw = 190f * s
                fill.color = Color.argb(46, 255, 255, 255)
                c.drawRoundRect(cx - bw / 2, h * 0.785f, cx + bw / 2, h * 0.785f + 3f * s, 2f * s, 2f * s, fill)
                fill.color = accent
                c.drawRoundRect(cx - bw / 2, h * 0.785f, cx - bw / 2 + bw * item.progress, h * 0.785f + 3f * s, 2f * s, 2f * s, fill)
            }
        }

        // HUD panels
        val px = 31f * s
        val pw = 170f * s
        val ph = 70f * s
        val place = a.store.place.uppercase(Locale.getDefault())
        panel(c, px, 81f * s, pw, ph, s, accent, "WEATHER" + (if (place.isNotEmpty()) " · $place" else ""), a.wxTemp(), a.wxDetail(), -1f)
        panel(c, px, 221f * s, pw, ph, s, accent, "NETWORK", net.ifEmpty { "—" }, ip, -1f)
        panel(c, px, 361f * s, pw, ph, s, accent, "SYSTEM", "$free free", "RAM $ram% USED", ram / 100f)

        // top and bottom captions
        text(c, "AURORA ▸ COMMAND", px, 27f * s, 10f * s, accent, mono, Paint.Align.LEFT, 0.3f)
        text(c, (if (net == "Offline") "○ OFFLINE" else "● ONLINE") + (if (net.isNotEmpty() && net != "Offline") "  ·  " + net.uppercase(Locale.getDefault()) else ""),
            w - px, 27f * s, 9.5f * s, Color.rgb(159, 180, 196), mono, Paint.Align.RIGHT, 0.2f)
        text(c, "◀ ▶  ROTATE   ·   OK  LAUNCH   ·   HOLD OK  OPTIONS   ·   ▲  CUSTOMIZE" + (if (np != null) "   ·   ▼  PLAY / PAUSE" else ""), cx, h - 14f * s, 8.5f * s, Color.rgb(91, 115, 133), mono, Paint.Align.CENTER, 0.22f)
    }

    private fun drawNode(c: Canvas, i: Int, d: Float, cx: Float, cy: Float, r: Float, s: Float, accent: Int) {
        val dist = abs(d)
        val fade = 1f - ((dist - 2.2f) / 1.2f).coerceIn(0f, 1f)
        if (fade <= 0.01f) return
        val f = max(0f, 1f - dist)
        val ang = Math.toRadians((-90f + d * 26f).toDouble())
        val x = cx + r * cos(ang).toFloat()
        val y = cy + r * sin(ang).toFloat()
        val nr = s * (20f + 10f * f)
        val alpha = ((0.38f + 0.62f * f) * fade * 255f).toInt()

        fill.color = Color.argb((alpha * 0.22f).toInt(), 255, 255, 255)
        c.drawCircle(x, y, nr, fill)
        val ic = icons[i]
        val pad = nr * 0.2f
        ic.setBounds((x - nr + pad).toInt(), (y - nr + pad).toInt(), (x + nr - pad).toInt(), (y + nr - pad).toInt())
        ic.alpha = alpha
        c.save()
        clip.reset()
        clip.addCircle(x, y, nr, Path.Direction.CW)
        c.clipPath(clip)
        ic.draw(c)
        c.restore()

        if (f > 0.05f) {
            line.strokeWidth = s * 2.6f
            line.color = withAlpha(accent, (f * 255).toInt())
            c.drawCircle(x, y, nr + s * 2f, line)
            line.strokeWidth = s * 1.1f
            line.color = withAlpha(accent, (f * 150).toInt())
            line.pathEffect = DashPathEffect(floatArrayOf(s * 3f, s * 6f), 0f)
            c.drawCircle(x, y, nr + s * 12f, line)
            line.pathEffect = null
            if (f > 0.5f) {
                text(c, apps[i].label.uppercase(Locale.getDefault()), x, y - nr - s * 20f, 12f * s, accent, mono, Paint.Align.CENTER, 0.2f, (f * 255).toInt())
            }
        }
    }

    private fun panel(
        c: Canvas, x: Float, y: Float, w: Float, h: Float, s: Float, accent: Int,
        title: String, big: String, sub: String, bar: Float
    ) {
        fill.color = Color.argb(14, 94, 231, 255)
        c.drawRect(x, y, x + w, y + h, fill)
        line.strokeWidth = s * 1f
        line.color = withAlpha(accent, 90)
        c.drawRect(x, y, x + w, y + h, line)
        // corner brackets
        line.strokeWidth = s * 2f
        line.color = accent
        val b = 8f * s
        c.drawLine(x, y, x + b, y, line); c.drawLine(x, y, x, y + b, line)
        c.drawLine(x + w, y + h, x + w - b, y + h, line); c.drawLine(x + w, y + h, x + w, y + h - b, line)

        text(c, title, x + 9f * s, y + 15f * s, 8f * s, accent, mono, Paint.Align.LEFT, 0.28f)
        text(c, big, x + 9f * s, y + 41f * s, 22f * s, Color.WHITE, light)
        text(c, sub, x + 9f * s, y + 58f * s, 8f * s, Color.rgb(159, 180, 196), mono, Paint.Align.LEFT, 0.05f)
        if (bar >= 0f) {
            fill.color = Color.argb(34, 255, 255, 255)
            c.drawRect(x + 9f * s, y + h - 6f * s, x + w - 9f * s, y + h - 3f * s, fill)
            fill.color = accent
            c.drawRect(x + 9f * s, y + h - 6f * s, x + 9f * s + (w - 18f * s) * bar, y + h - 3f * s, fill)
        }
    }
}

internal class CommandStyle(a: MainActivity) : HomeStyle(a) {
    private val v = CommandView(a)
    private val box = android.widget.FrameLayout(a)
    private val customize = a.customizeButton()
    init {
        box.clipChildren = false
        box.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
        box.addView(customize, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
            setMargins(0, a.u(44), a.u(31), 0)
        })
        v.onUp = { customize.requestFocus() }
        NowPlaying.onChange = { v.invalidate() }
        customize.setOnKeyListener { _, k, e ->
            if (k == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { v.requestFocus(); true } else false
        }
    }
    override val view: View get() = box
    override fun bind(apps: List<AppEntry>, focusKey: String?) = v.setApps(apps, focusKey)
    override fun refocus() { v.post { v.requestFocus() } }
    override fun currentKey(): String? = v.currentKey()
    override fun onTick() = v.refreshInfo()
    override fun onWeather() = v.invalidate()
    override fun onFeed() = v.invalidate()
    override fun destroy() { NowPlaying.onChange = null; v.stop() }
}
