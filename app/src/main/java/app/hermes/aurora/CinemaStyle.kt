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
    /** with extra artwork on: the title's logo (in place of the title text) and a line with year, genres, rating */
    private val logo = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE }
    private val meta = label(12f, Color.argb(225, 255, 255, 255), "sans-serif-medium", 0.04f).apply { maxLines = 1; visibility = View.GONE }
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
            intArrayOf(Color.argb(215, 0, 0, 0), Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)))
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
        hero.addView(logo, LinearLayout.LayoutParams(u(300), u(78)).apply { setMargins(0, u(12), 0, u(10)) })
        hero.addView(title, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(8), 0, u(6)) })
        hero.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, u(6)) })
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
        clearExtra()
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
        a.setBackdropCovers(false)      // a colour wash lets the scene show through
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
    override fun destroy() { a.setBackdropCovers(false); a.handler.removeCallbacks(bindRunnable); a.handler.removeCallbacks(npTick); NowPlaying.onChange = null }

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
        cell.setOnLongClickListener { a.showAppMenu(e); true }
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

    /** back to what the app itself published: title as text, no logo, no extra line */
    private fun clearExtra() {
        logo.visibility = View.GONE
        logo.setImageDrawable(null)
        title.visibility = View.VISIBLE
        meta.visibility = View.GONE
    }

    /** TMDB's logo, line and description for the item on screen (its backdrop is handled where the picture loads) */
    private fun applyExtra(item: TvFeed.Item, x: Tmdb.Extra) {
        if (x.meta.isNotEmpty()) { meta.text = x.meta; meta.visibility = View.VISIBLE }
        if (x.overview.isNotEmpty()) { desc.text = x.overview; desc.visibility = View.VISIBLE }
        val l = x.logo ?: return
        Images.load(a, l, 600) { bmp ->
            if (shown !== item || bmp == null) return@load
            logo.setImageBitmap(bmp)
            logo.visibility = View.VISIBLE
            title.visibility = View.GONE
        }
    }

    private fun showPlain(e: AppEntry) {
        shown = null
        clearExtra()
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
        clearExtra()
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
        resumeBtn.setOnClickListener { a.openItem(item, e) }
        openBtn.text = "Open " + e.label
        openBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.VISIBLE

        // extra artwork (optional): what is already known paints at once; otherwise the app's own picture shows
        // first and TMDB's replaces it when the answer arrives
        val known = Tmdb.known(a, a.store, item)
        if (known != null) applyExtra(item, known)
        else Tmdb.lookup(a, a.store, item) { x ->
            if (shown !== item || x == null) return@lookup
            applyExtra(item, x)
            x.backdrop?.let { loadArt(item, it, null) }
        }
        loadArt(item, known?.backdrop ?: item.art, if (known?.backdrop != null) item.art else null)
    }

    /** the item's picture: full screen for a title, a cover card for music. [fallback] is tried when [url] does not load. */
    private fun loadArt(item: TvFeed.Item, url: String, fallback: String?) {
        if (BuildConfig.DEBUG) android.util.Log.d("AuroraArt", "show ${item.pkg}: $url")
        // a title's picture fills the screen: decode it at the screen's full width, never smaller
        Images.load(a, url, if (item.square) 640 else a.resources.displayMetrics.widthPixels.coerceAtLeast(1280)) { bmp ->
            if (shown !== item) return@load
            if (bmp == null) { if (fallback != null) loadArt(item, fallback, null); return@load }
            if (item.square) {
                // a playlist / album cover: cover card + the cover's colours as a soft wash
                cover.setImageBitmap(bmp)
                cover.visibility = View.VISIBLE
                // the visualiser is the backdrop while it runs: a colour wash on top would dim it
                if (a.viz.live) { hideBackdrop(); lastAmbient = null } else setAmbient(bmp)
            } else if (a.viz.hearing) hideBackdrop() else setBackdrop(bmp)     // the artwork wins over a scene; only music playing takes the screen back
        }
    }

    private fun setBackdrop(bmp: android.graphics.Bitmap) {
        if (bmp === lastBackdrop && backdrop.visibility == View.VISIBLE) return
        lastBackdrop = bmp
        a.setBackdropCovers(true)       // the scene stops drawing behind the picture
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
        a.setBackdropCovers(false)
        lastBackdrop = null
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
        f.setOnClickListener { a.openItem(item, e) }
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
