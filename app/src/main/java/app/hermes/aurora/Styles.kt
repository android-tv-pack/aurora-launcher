package app.hermes.aurora

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

/** A complete home-screen layout. The Classic screen lives in MainActivity; the others are subclasses. */
internal abstract class HomeStyle(protected val a: MainActivity) {
    abstract val view: View
    abstract fun bind(apps: List<AppEntry>, focusKey: String?)
    abstract fun refocus()
    abstract fun currentKey(): String?
    open fun onTick() {}
    open fun onWeather() {}
    open fun onFeed() {}
    open fun destroy() {}

    protected fun u(n: Float): Int = a.u(n)
    protected fun u(n: Int): Int = a.u(n)

    protected fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    protected fun label(size: Float, color: Int = Color.WHITE, face: String = "sans-serif", spacing: Float = 0f) =
        TextView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
            setTextColor(color)
            typeface = Typeface.create(face, Typeface.NORMAL)
            letterSpacing = spacing
            includeFontPadding = false
        }

    protected fun matchParent() = FrameLayout.LayoutParams(-1, -1)
}

// ======================================================================================
//  CINEMA: the focused app drives the whole screen; apps shrink to a slim dock.
// ======================================================================================
internal class CinemaStyle(a: MainActivity) : HomeStyle(a) {

    private val root = FrameLayout(a)
    override val view: View get() = root

    private val backdrop = ImageView(a)
    private val scrim = View(a)
    private val clock = label(40f, Color.WHITE, "sans-serif-thin")
    private val date = label(12f, Color.argb(170, 255, 255, 255), "sans-serif", 0.08f)
    private val wx1 = label(20f, Color.WHITE, "sans-serif-light")
    private val wx2 = label(11f, Color.argb(160, 255, 255, 255), "sans-serif", 0.05f)
    private val customize = a.customizeButton()

    private val kicker = label(11f, a.accent, "sans-serif-medium", 0.3f)
    private val title = label(34f, Color.WHITE, "sans-serif-light").apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val desc = label(13f, Color.argb(210, 255, 255, 255)).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val track = FrameLayout(a)
    private val fill = View(a)
    private val resumeBtn = button(true)
    private val openBtn = button(false)
    private val prevBtn = button(false).apply { text = "⏮"; visibility = View.GONE }
    private val nextBtn = button(false).apply { text = "⏭"; visibility = View.GONE }
    private val stopBtn = button(false).apply { text = "⏹  Stop"; visibility = View.GONE }
    private var lastBackdrop: android.graphics.Bitmap? = null
    private var lastAmbient: android.graphics.Bitmap? = null
    /** album cover for the now-playing hero, shown as a rounded card on the right */
    private val cover = ImageView(a)

    private val moreLabel = label(10f, Color.argb(170, 255, 255, 255), "sans-serif-medium", 0.3f)
    private val cardsRow = LinearLayout(a)
    private val cardsScroll = HorizontalScrollView(a)
    private val dockRow = LinearLayout(a)
    private val dockScroll = HorizontalScrollView(a)

    private val dockCells = ArrayList<View>()
    private var apps: List<AppEntry> = emptyList()
    private var current: AppEntry? = null
    private var startKey: String? = null
    private var shown: TvFeed.Item? = null
    private val bindRunnable = Runnable { current?.let { showApp(it) } }

    init {
        root.clipChildren = false
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.alpha = 0f
        backdrop.visibility = View.GONE
        root.addView(backdrop, matchParent())

        val left = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(230, 0, 0, 0), Color.argb(130, 0, 0, 0), Color.argb(20, 0, 0, 0)))
        val bottom = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(245, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        scrim.background = LayerDrawable(arrayOf(left, bottom))
        scrim.alpha = 0f
        scrim.visibility = View.GONE
        root.addView(scrim, matchParent())

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = rounded(Color.argb(40, 255, 255, 255), u(16))
        cover.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        cover.clipToOutline = true
        cover.elevation = u(14).toFloat()
        cover.visibility = View.GONE
        root.addView(cover, FrameLayout.LayoutParams(u(190), u(190), Gravity.TOP or Gravity.END).apply { setMargins(0, u(122), u(96), 0) })

        // header
        val head = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        head.addView(clock)
        head.addView(date, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(3), u(2), 0, 0) })
        root.addView(head, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            setMargins(u(44), u(26), 0, 0)
        })
        val wx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        wx.addView(wx1)
        wx.addView(wx2, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(3), 0, 0) })
        wx.addView(customize, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(10), 0, 0) })
        root.addView(wx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            setMargins(0, u(30), u(44), 0)
        })

        // hero text
        val hero = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        hero.addView(kicker)
        hero.addView(title, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(8), 0, u(6)) })
        hero.addView(desc)
        track.setBackgroundColor(Color.argb(60, 255, 255, 255))
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        fill.setBackgroundColor(Color.WHITE)
        hero.addView(track, LinearLayout.LayoutParams(u(170), maxOf(2, u(3))).apply { setMargins(0, u(14), 0, 0) })
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        buttons.addView(prevBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, u(10), 0) })
        buttons.addView(resumeBtn)
        buttons.addView(nextBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(10), 0, 0, 0) })
        buttons.addView(stopBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(10), 0, 0, 0) })
        buttons.addView(openBtn, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(10), 0, 0, 0) })
        hero.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(14), 0, 0) })
        hero.clipChildren = false
        root.addView(hero, FrameLayout.LayoutParams(
            (a.resources.displayMetrics.widthPixels * 0.5f).toInt(), -2, Gravity.TOP or Gravity.START
        ).apply { setMargins(u(44), u(112), 0, 0) })

        // bottom block: more-from label, cards, dock
        val bottomBlock = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        moreLabel.setPadding(u(48), 0, 0, u(2))
        bottomBlock.addView(moreLabel)
        setupScroll(cardsScroll, cardsRow, u(38))
        bottomBlock.addView(cardsScroll, LinearLayout.LayoutParams(-1, -2))
        setupScroll(dockScroll, dockRow, u(38))
        bottomBlock.addView(dockScroll, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(4), 0, u(22)) })
        root.addView(bottomBlock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        moreLabel.visibility = View.GONE
        cardsScroll.visibility = View.GONE
        refreshHeader()
        NowPlaying.onChange = { current?.let { showApp(it) } }
    }

    private val npTick = object : Runnable {
        override fun run() {
            val np = current?.let { NowPlaying.infoFor(it.pkg) } ?: return
            setProgress(np.fraction())
            if (np.playing) a.handler.postDelayed(this, 1000)
        }
    }

    private fun setProgress(f: Float) {
        if (f < 0f) { track.visibility = View.GONE; return }
        track.visibility = View.VISIBLE
        (fill.layoutParams as FrameLayout.LayoutParams).width = (u(170) * f).toInt().coerceAtLeast(2)
        fill.requestLayout()
    }

    private fun showNowPlaying(e: AppEntry, np: NowPlaying.Info) {
        shown = null
        kicker.text = (if (np.playing) "NOW PLAYING" else "PAUSED") + "  ·  " + e.label.uppercase(Locale.getDefault())
        kicker.setTextColor(a.accent)
        title.text = np.title
        desc.text = np.subtitle()
        desc.visibility = if (desc.text.isEmpty()) View.GONE else View.VISIBLE
        setProgress(np.fraction())
        a.handler.removeCallbacks(npTick)
        if (np.playing) a.handler.postDelayed(npTick, 1000)
        resumeBtn.text = if (np.playing) "❚❚  Pause" else "▶  Play"
        resumeBtn.setOnClickListener { NowPlaying.playPause(e.pkg) }
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE
        stopBtn.visibility = View.VISIBLE
        stopBtn.setOnClickListener { NowPlaying.stopPlayback(e.pkg) }
        prevBtn.setOnClickListener { NowPlaying.prev(e.pkg) }
        nextBtn.setOnClickListener { NowPlaying.next(e.pkg) }
        openBtn.text = "Open " + e.label
        openBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.VISIBLE
        val art = np.art
        if (art != null) {
            cover.setImageBitmap(art)
            cover.visibility = View.VISIBLE
            a.viz.evaluate()
            // the visualiser replaces the colour wash behind the cover
            if (a.viz.live) { hideBackdrop(); lastAmbient = null } else setAmbient(art)
        } else {
            cover.visibility = View.GONE
            hideBackdrop()
        }
    }

    /** a soft wash of the cover's colours behind everything (the cover shrunk to a few pixels, then stretched) */
    private fun setAmbient(art: android.graphics.Bitmap) {
        if (art === lastAmbient && backdrop.visibility == View.VISIBLE) return
        lastAmbient = art
        lastBackdrop = null
        // halve repeatedly: every step averages 2x2 pixels, so details melt into a clean colour wash
        var tiny = art
        while (tiny.width > 8) tiny = android.graphics.Bitmap.createScaledBitmap(tiny, tiny.width / 2, tiny.height / 2, true)
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.setPadding(0, 0, 0, 0)
        backdrop.setImageBitmap(tiny)
        backdrop.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        backdrop.animate().alpha(0.62f).setDuration(380).start()
        scrim.animate().alpha(0.55f).setDuration(380).start()
    }

    private fun setupScroll(s: HorizontalScrollView, row: LinearLayout, side: Int) {
        s.isHorizontalScrollBarEnabled = false
        s.overScrollMode = View.OVER_SCROLL_NEVER
        s.clipChildren = false
        s.clipToPadding = false
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        row.setPadding(side, u(8), side, u(8))
        s.addView(row, ViewGroup.LayoutParams(-2, -2))
    }

    private fun button(primary: Boolean): TextView = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12f).toFloat())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(6, 18, 26) else Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(u(18), u(8), u(18), u(8))
        isFocusable = true
        isClickable = true
        val normal = rounded(if (primary) Color.WHITE else Color.argb(40, 255, 255, 255), u(18),
            if (primary) 0 else u(1), Color.argb(70, 255, 255, 255))
        val focus = rounded(if (primary) Color.WHITE else Color.argb(90, 255, 255, 255), u(18), u(3), a.accent)
        background = normal
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focus else normal
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
        }
    }

    // ---------------------------------------------------------------- data

    override fun bind(apps: List<AppEntry>, focusKey: String?) {
        this.apps = apps
        startKey = focusKey
        dockRow.removeAllViews()
        dockCells.clear()
        for (e in apps) {
            val c = dockCell(e)
            dockCells.add(c)
            dockRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(3), 0, u(3), 0) })
        }
        refreshHeader()
    }

    override fun currentKey(): String? = current?.key ?: startKey

    override fun refocus() {
        val cell = dockCells.firstOrNull { it.tag == (current?.key ?: startKey) } ?: dockCells.firstOrNull()
        cell?.post { cell.requestFocus() }
    }

    override fun onTick() = refreshHeader()
    override fun onWeather() = refreshHeader()
    override fun onFeed() { current?.let { showApp(it) } }
    override fun destroy() { a.handler.removeCallbacks(bindRunnable); a.handler.removeCallbacks(npTick); NowPlaying.onChange = null }

    private fun refreshHeader() {
        clock.text = a.nowStr(a.clockPattern())
        date.text = a.nowStr("EEEE, d MMMM")
        wx1.text = a.wxMain()
        wx2.text = a.wxSub()
    }

    // ---------------------------------------------------------------- dock

    private fun dockCell(e: AppEntry): View {
        val icon = ImageView(a).apply { setImageDrawable(e.icon) }
        // Mono / Neon / Glass icons are light or glowing: a white focus plate would swallow them
        val darkPlate = a.store.iconStyle != 0
        val name = label(12f, if (darkPlate) Color.WHITE else Color.rgb(6, 18, 26), "sans-serif-medium").apply {
            text = e.label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            tag = e.key
            setPadding(u(7), u(6), u(7), u(6))
            alpha = 0.85f
        }
        cell.addView(icon, LinearLayout.LayoutParams(u(30), u(30)))
        cell.addView(name, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(9), 0, u(6), 0) })
        cell.setOnClickListener { a.openApp(e) }
        cell.setOnLongClickListener { a.showAppMenu(e, "all"); true }
        cell.setOnFocusChangeListener { v, has ->
            if (has) {
                v.background = if (darkPlate) rounded(Color.argb(205, 14, 18, 30), u(16), u(3), a.accent)
                else rounded(Color.argb(245, 255, 255, 255), u(16), u(3), a.accent)
                name.visibility = View.VISIBLE
                v.alpha = 1f
                current = e
                a.lastKey = e.key
                // debounced: sweeping across the dock doesn't load a picture for every icon passed
                a.handler.removeCallbacks(bindRunnable)
                a.handler.postDelayed(bindRunnable, 260)
            } else {
                v.background = null
                name.visibility = View.GONE
                v.alpha = 0.85f
            }
        }
        return cell
    }

    // ---------------------------------------------------------------- hero + cards

    private fun showApp(e: AppEntry) {
        val np = NowPlaying.infoFor(e.pkg)
        val items = a.feed[e.pkg].orEmpty()
        val cardItems = items.take(10)
        buildCards(cardItems, e)
        if (np != null) { showNowPlaying(e, np); return }
        a.handler.removeCallbacks(npTick)
        prevBtn.visibility = View.GONE
        nextBtn.visibility = View.GONE
        stopBtn.visibility = View.GONE
        cover.visibility = View.GONE
        lastAmbient = null
        if (items.isEmpty()) showPlain(e) else showItem(items[0], e)
    }

    private fun showPlain(e: AppEntry) {
        shown = null
        kicker.text = "APP"
        kicker.setTextColor(a.accent)
        title.text = e.label
        desc.visibility = View.GONE
        track.visibility = View.GONE
        resumeBtn.text = "▶  Open"
        resumeBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.GONE
        hideBackdrop()
    }

    private fun showItem(item: TvFeed.Item, e: AppEntry) {
        shown = item
        if (!item.square) { cover.visibility = View.GONE; lastAmbient = null }
        kicker.text = item.kind + "  ·  " + e.label.uppercase(Locale.getDefault())
        kicker.setTextColor(a.accent)
        title.text = item.title
        desc.text = item.desc
        desc.visibility = if (item.desc.isEmpty()) View.GONE else View.VISIBLE
        if (item.progress >= 0f) {
            track.visibility = View.VISIBLE
            (fill.layoutParams as FrameLayout.LayoutParams).width = (u(170) * item.progress).toInt().coerceAtLeast(2)
            fill.requestLayout()
        } else {
            track.visibility = View.GONE
        }
        resumeBtn.text = if (item.kind == "CONTINUE WATCHING") "▶  Resume" else "▶  Play"
        resumeBtn.setOnClickListener { a.playItem(item, e) }
        openBtn.text = "Open " + e.label
        openBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.VISIBLE

        val url = item.art
        Images.load(a, url, if (item.square) 640 else 1280) { bmp ->
            if (shown !== item || bmp == null) return@load
            if (item.square) {
                // a playlist / album cover: cover card + the cover's colours as a soft wash
                cover.setImageBitmap(bmp)
                cover.visibility = View.VISIBLE
                // the visualiser is the backdrop while it runs: a colour wash on top would dim it
                if (a.viz.live) { hideBackdrop(); lastAmbient = null } else setAmbient(bmp)
            } else if (a.viz.live) hideBackdrop() else setBackdrop(bmp)
        }
    }

    private fun setBackdrop(bmp: android.graphics.Bitmap) {
        if (bmp === lastBackdrop && backdrop.visibility == View.VISIBLE) return
        lastBackdrop = bmp
        val wide = bmp.width >= bmp.height * 1.25f
        backdrop.scaleType = if (wide) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_END
        backdrop.setPadding(0, 0, if (wide) 0 else u(70), 0)
        val wasHidden = backdrop.visibility != View.VISIBLE || backdrop.alpha < 0.05f
        backdrop.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        if (wasHidden) {
            backdrop.setImageBitmap(bmp)
            backdrop.animate().alpha(1f).setDuration(380).start()
            scrim.animate().alpha(1f).setDuration(380).start()
        } else {
            backdrop.animate().alpha(0f).setDuration(180).withEndAction {
                backdrop.setImageBitmap(bmp)
                backdrop.animate().alpha(1f).setDuration(300).start()
            }.start()
        }
    }

    private fun hideBackdrop() {
        if (backdrop.visibility != View.VISIBLE) return
        backdrop.animate().alpha(0f).setDuration(240).withEndAction { backdrop.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(240).withEndAction { scrim.visibility = View.GONE }.start()
    }

    private fun buildCards(items: List<TvFeed.Item>, e: AppEntry) {
        cardsRow.removeAllViews()
        val show = items.size > 1
        moreLabel.visibility = if (show) View.VISIBLE else View.GONE
        cardsScroll.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        moreLabel.text = "MORE FROM " + e.label.uppercase(Locale.getDefault())
        for (item in items) {
            val sq = item.square
            cardsRow.addView(card(item, e), LinearLayout.LayoutParams(if (sq) u(88) else u(136), if (sq) u(88) else u(77)).apply { setMargins(u(5), u(6), u(5), u(6)) })
        }
    }

    private fun card(item: TvFeed.Item, e: AppEntry): View {
        val r = u(10)
        val f = FrameLayout(a).apply {
            background = rounded(Color.argb(50, 255, 255, 255), r)
            foreground = rounded(Color.TRANSPARENT, r, u(1), Color.argb(40, 255, 255, 255))
            clipToOutline = true
            isFocusable = true
            isClickable = true
        }
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(img, matchParent())
        Images.load(a, item.art, 420) { bmp -> if (bmp != null) img.setImageBitmap(bmp) }
        val shade = View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(220, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }
        f.addView(shade, FrameLayout.LayoutParams(-1, u(46), Gravity.BOTTOM))
        val t = label(10.5f, Color.WHITE, "sans-serif-medium").apply {
            text = item.title
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        f.addView(t, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(u(8), 0, u(8), u(8)) })
        if (item.progress >= 0f) {
            val bar = View(a).apply { setBackgroundColor(Color.WHITE) }
            f.addView(bar, FrameLayout.LayoutParams((u(136) * item.progress).toInt().coerceAtLeast(2), maxOf(2, u(2)), Gravity.BOTTOM))
        }
        f.setOnClickListener { a.playItem(item, e) }
        f.setOnFocusChangeListener { v, has ->
            v.foreground = if (has) rounded(Color.TRANSPARENT, r, u(3), a.accent)
            else rounded(Color.TRANSPARENT, r, u(1), Color.argb(40, 255, 255, 255))
            v.animate().scaleX(if (has) 1.07f else 1f).scaleY(if (has) 1.07f else 1f).setDuration(130).start()
            if (has) {
                a.handler.removeCallbacks(bindRunnable)   // stay on this title
                showItem(item, e)
            }
        }
        return f
    }
}

// ======================================================================================
//  HORIZON: huge calm clock, time-of-day colour, one row of round icons.
// ======================================================================================
internal class HorizonStyle(a: MainActivity) : HomeStyle(a) {

    private val root = FrameLayout(a)
    override val view: View get() = root

    private val timeV = label(150f, Color.WHITE, "sans-serif-thin", -0.04f)
    private val dateV = label(15f, Color.argb(210, 255, 255, 255), "sans-serif-light", 0.34f)
    private val wx1 = label(24f, Color.WHITE, "sans-serif-light")
    private val wx2 = label(10f, Color.argb(165, 255, 255, 255), "sans-serif", 0.2f)
    private val customize = a.customizeButton()

    private val capV = label(9f, Color.argb(150, 255, 255, 255), "sans-serif-medium", 0.3f)
    private val cardBox = LinearLayout(a)
    private val cardImg = ImageView(a)
    private val cardTitle = label(12f, Color.WHITE, "sans-serif-medium")
    private val cardSub = label(10f, Color.argb(170, 255, 255, 255))

    private val hsv = HorizontalScrollView(a)
    private val row = LinearLayout(a)
    private val hint = label(8f, Color.argb(100, 255, 255, 255), "sans-serif", 0.3f)

    private var apps: List<AppEntry> = emptyList()
    private var current: AppEntry? = null
    private var startKey: String? = null
    private var shown: TvFeed.Item? = null
    private val cells = ArrayList<View>()
    private val bindRunnable = Runnable { current?.let { showFeed(it) } }

    private fun cellW() = u(84)

    init {
        root.clipChildren = false

        root.addView(timeV, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(u(50), u(56), 0, 0) })
        root.addView(dateV, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(u(54), u(238), 0, 0) })

        val wx = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        wx.addView(wx1)
        wx.addView(wx2, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(3), 0, 0) })
        wx.addView(customize, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(10), 0, 0) })
        root.addView(wx, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(34), u(50), 0) })

        // "continue" card, only when the focused app has something to continue
        cardBox.orientation = LinearLayout.VERTICAL
        cardBox.background = rounded(Color.argb(95, 0, 0, 0), u(14), u(1), Color.argb(34, 255, 255, 255))
        cardBox.setPadding(u(10), u(10), u(10), u(10))
        cardBox.visibility = View.GONE
        cardImg.scaleType = ImageView.ScaleType.CENTER_CROP
        cardImg.clipToOutline = true
        cardImg.background = rounded(Color.argb(40, 255, 255, 255), u(8))
        cardBox.addView(cardImg, LinearLayout.LayoutParams(u(152), u(86)))
        cardBox.addView(cardTitle.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(u(152), -2).apply { setMargins(0, u(8), 0, 0) })
        cardBox.addView(cardSub.apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(u(152), -2).apply { setMargins(0, u(2), 0, 0) })
        capV.visibility = View.GONE
        root.addView(capV, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(152), u(50), 0) })
        root.addView(cardBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(170), u(50), 0) })

        // the round icon row
        hsv.isHorizontalScrollBarEnabled = false
        hsv.overScrollMode = View.OVER_SCROLL_NEVER
        hsv.clipChildren = false
        hsv.clipToPadding = false
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.clipToPadding = false
        val side = (a.resources.displayMetrics.widthPixels - cellW()) / 2
        row.setPadding(side, u(34), side, u(34))
        hsv.addView(row, ViewGroup.LayoutParams(-2, -2))
        hsv.setOnScrollChangeListener { _, _, _, _, _ -> applyScale() }
        root.addView(hsv, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(0, 0, 0, u(48)) })

        hint.text = "◀ ▶  BROWSE     ·     OK  OPEN     ·     HOLD OK  OPTIONS"
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, u(18)) })
        refreshHeader()
        NowPlaying.onChange = { current?.let { showFeed(it) } }
    }

    // colour of the glow follows the time of day
    private fun palette(hour: Int): IntArray = when (hour) {
        in 5..8 -> intArrayOf(Color.rgb(255, 170, 64), Color.rgb(255, 110, 130), Color.rgb(64, 160, 255))      // morning
        in 9..16 -> intArrayOf(Color.rgb(0, 150, 255), Color.rgb(0, 229, 200), Color.rgb(100, 110, 255))      // day
        in 17..20 -> intArrayOf(Color.rgb(255, 112, 67), Color.rgb(214, 60, 140), Color.rgb(110, 60, 200))    // evening
        else -> intArrayOf(Color.rgb(0, 170, 140), Color.rgb(30, 90, 210), Color.rgb(90, 45, 170))            // night
    }

    private fun refreshHeader() {
        timeV.text = a.nowStr(a.clockPattern())
        dateV.text = a.nowStr("EEEE  ·  d MMMM").uppercase(Locale.getDefault())
        wx1.text = a.wxMain()
        wx2.text = a.wxSub()
        a.bg.setColors(palette(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)))
    }

    override fun bind(apps: List<AppEntry>, focusKey: String?) {
        this.apps = apps
        startKey = focusKey
        row.removeAllViews()
        cells.clear()
        for (e in apps) {
            val c = cell(e)
            cells.add(c)
            row.addView(c, LinearLayout.LayoutParams(cellW(), -2))
        }
        refreshHeader()
        // put the starting app in the middle straight away
        hsv.post {
            val k = startKey
            val idx = apps.indexOfFirst { it.key == k }.coerceAtLeast(0)
            cells.getOrNull(idx)?.let { hsv.scrollTo(it.left + it.width / 2 - hsv.width / 2, 0) }
            applyScale()
        }
    }

    override fun currentKey(): String? = current?.key ?: startKey

    override fun refocus() {
        val cell = cells.firstOrNull { it.tag == (current?.key ?: startKey) } ?: cells.firstOrNull()
        cell?.post { cell.requestFocus() }
    }

    override fun onTick() = refreshHeader()
    override fun onWeather() = refreshHeader()
    override fun onFeed() { current?.let { showFeed(it) } }
    override fun destroy() { a.handler.removeCallbacks(bindRunnable); NowPlaying.onChange = null }

    private fun applyScale() {
        val w = hsv.width
        if (w == 0) return
        val center = hsv.scrollX + w / 2f
        val cw = cellW().toFloat()
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            val d = abs((c.left + c.width / 2f) - center) / cw
            val f = (1f - d).coerceIn(0f, 1f)
            val s = 1f + 0.62f * f
            c.scaleX = s
            c.scaleY = s
            c.alpha = 0.5f + 0.5f * f
        }
    }

    private fun cell(e: AppEntry): View {
        val size = u(46)
        val disc = FrameLayout(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(if (IconStyles.hasOwnDisc(a.store.iconStyle)) 0 else 38, 255, 255, 255))
            }
        }
        // icons that bring their own round plate fill the whole disc
        val pad = if (IconStyles.hasOwnDisc(a.store.iconStyle)) 0 else (size * 0.17f).toInt()
        disc.addView(ImageView(a).apply { setImageDrawable(e.icon) }, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
        val name = label(9f, Color.WHITE, "sans-serif-medium", 0.04f).apply {
            text = e.label
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            alpha = 0f
        }
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            tag = e.key
        }
        cell.addView(disc, LinearLayout.LayoutParams(size, size))
        cell.addView(name, LinearLayout.LayoutParams(cellW() - u(6), -2).apply { setMargins(0, u(5), 0, 0) })
        cell.setOnClickListener { a.openApp(e) }
        cell.setOnLongClickListener { a.showAppMenu(e, "all"); true }
        cell.setOnFocusChangeListener { v, has ->
            disc.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                val own = IconStyles.hasOwnDisc(a.store.iconStyle)
                setColor(Color.argb(if (own) 0 else if (has) 70 else 38, 255, 255, 255))
                if (has) setStroke(maxOf(2, u(2.5f)), Color.WHITE)
            }
            name.animate().alpha(if (has) 1f else 0f).setDuration(140).start()
            if (has) {
                current = e
                a.lastKey = e.key
                hsv.smoothScrollTo(v.left + v.width / 2 - hsv.width / 2, 0)
                a.handler.removeCallbacks(bindRunnable)
                a.handler.postDelayed(bindRunnable, 280)
            }
        }
        return cell
    }

    private fun showFeed(e: AppEntry) {
        val np = NowPlaying.infoFor(e.pkg)
        if (np != null) {
            // now-playing card: OK = play/pause, LEFT / RIGHT = previous / next
            shown = null
            capV.text = (if (np.playing) "NOW PLAYING" else "PAUSED") + "  ·  " + e.label.uppercase(Locale.getDefault()) + "  ·  HOLD OK TO STOP"
            capV.visibility = View.VISIBLE
            cardBox.visibility = View.VISIBLE
            cardTitle.text = (if (np.playing) "▶  " else "❚❚  ") + np.title
            cardSub.text = np.artist.ifEmpty { np.album }
            cardImg.layoutParams.height = u(140)      // album covers are square
            cardImg.requestLayout()
            if (np.art != null) cardImg.setImageBitmap(np.art) else cardImg.setImageDrawable(null)
            cardBox.isFocusable = true
            cardBox.isClickable = true
            cardBox.setOnClickListener { NowPlaying.playPause(e.pkg) }
            cardBox.isLongClickable = true
            cardBox.setOnLongClickListener { NowPlaying.stopPlayback(e.pkg); true }
            cardBox.setOnKeyListener { _, k, ev ->
                if (ev.action != android.view.KeyEvent.ACTION_DOWN) false
                else when (k) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { NowPlaying.prev(e.pkg); true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { NowPlaying.next(e.pkg); true }
                    else -> false
                }
            }
            cardBox.setOnFocusChangeListener { v, has ->
                v.background = rounded(Color.argb(95, 0, 0, 0), u(14), if (has) u(3) else u(1), if (has) a.accent else Color.argb(34, 255, 255, 255))
            }
            return
        }
        cardImg.layoutParams.height = u(86)
        cardBox.isFocusable = false
        cardBox.setOnKeyListener(null)
        cardBox.setOnLongClickListener(null)
        cardBox.isLongClickable = false
        cardBox.setOnFocusChangeListener(null)
        val item = a.feed[e.pkg]?.firstOrNull()
        shown = item
        if (item == null) {
            cardBox.visibility = View.GONE
            capV.visibility = View.GONE
            return
        }
        capV.text = item.kind + "  ·  " + e.label.uppercase(Locale.getDefault())
        capV.visibility = View.VISIBLE
        cardBox.visibility = View.VISIBLE
        cardImg.layoutParams.height = if (item.square) u(140) else u(86)
        cardImg.requestLayout()
        cardTitle.text = item.title
        cardSub.text = if (item.progress >= 0f) "${(item.progress * 100).toInt()}% watched" else item.desc
        cardImg.setImageDrawable(null)
        Images.load(a, item.art, 420) { bmp -> if (shown === item && bmp != null) cardImg.setImageBitmap(bmp) }
        cardBox.setOnClickListener { a.playItem(item, e) }
    }
}
