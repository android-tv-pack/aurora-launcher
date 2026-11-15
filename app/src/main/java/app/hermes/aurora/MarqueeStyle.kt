package app.hermes.aurora

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * MARQUEE: the thing you were watching is the screen. Full-bleed artwork of the focused title, a rail of
 * apps with their names on the left, a hero with the title and its buttons, and lanes of what the apps
 * publish: first one lane across all apps, then one per app.
 *
 * Keys: the rail moves UP / DOWN, RIGHT goes to the hero buttons, DOWN from the buttons to the lanes,
 * LEFT from the first button or card goes back to the rail.
 */
internal class MarqueeStyle(a: MainActivity) : HomeStyle(a), LaneList.Host {

    private val root = FrameLayout(a)
    override val view: View get() = root

    private val backdrop = ImageView(a)
    private val scrim = View(a)
    /** album / playlist cover, shown as a card on the right */
    private val cover = ImageView(a)
    private val clock = label(24f, Color.WHITE, "sans-serif-light")
    private val sub = label(10.5f, Color.argb(215, 255, 255, 255), "sans-serif", 0.04f)

    private val rail = FrameLayout(a)
    private val railScroll = ScrollView(a)
    private val railCol = LinearLayout(a)
    private val railRows = ArrayList<View>()
    private val customize = railRow(null)

    private val kicker = label(10.5f, a.accent, "sans-serif-medium", 0.28f)
    private val title = titleLabel(46f, 2, Color.WHITE, "sans-serif").apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 0.95f)
    }
    private val desc = label(13f, Color.argb(225, 255, 255, 255)).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(u(2).toFloat(), 1f)
        includeFontPadding = true
    }
    private val track = FrameLayout(a)
    private val fill = View(a)
    private val trackW = u(280)
    private val prevBtn = actionButton(false).apply { text = "⏮" }
    private val mainBtn = actionButton(true)
    private val nextBtn = actionButton(false).apply { text = "⏭" }
    private val stopBtn = actionButton(false).apply { text = "⏹  Stop" }
    private val openBtn = actionButton(false)
    private val buttons = listOf(prevBtn, mainBtn, nextBtn, stopBtn, openBtn)

    private val lanes = LaneList(a, 100f, 34f, this)
    private val lanesBox = FrameLayout(a)

    private var apps: List<AppEntry> = emptyList()
    private var startKey: String? = null
    /** the app whose rail entry has (or last had) the focus */
    private var railApp: AppEntry? = null
    /** the app the hero is about: its rail entry is marked */
    private var heroPkg: String? = null
    private var shown: TvFeed.Item? = null
    private var lastBackdrop: Bitmap? = null
    private var lastAmbient: Bitmap? = null
    private var lastFocus: View? = null
    /** false until the first key press: until then the screen may still settle on its opening state */
    private var touched = false
    private var destroyed = false

    private val showRail = Runnable { railApp?.let { showApp(it) } }
    private val loadArt = Runnable { shown?.let { loadArtFor(it) } }
    private val focusWatch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (now != null && root.holds(now)) lastFocus = now
    }

    init {
        root.clipChildren = false
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.alpha = 0f
        backdrop.visibility = View.GONE
        root.addView(backdrop, matchParent())

        // the dark fade every text sits on: strong on the left (hero) and at the bottom (lanes), a band at the top (clock)
        val left = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(240, 0, 0, 0), Color.argb(190, 0, 0, 0), Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        val bottom = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(250, 0, 0, 0), Color.argb(215, 0, 0, 0), Color.argb(60, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        scrim.background = LayerDrawable(arrayOf(left, bottom))
        // drawn once into a texture: two full-screen gradients on every frame are too much for a weak GPU
        scrim.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        root.addView(scrim, matchParent())

        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = rounded(Color.argb(40, 255, 255, 255), u(16))
        cover.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        cover.clipToOutline = true
        cover.elevation = u(14).toFloat()
        cover.visibility = View.GONE
        root.addView(cover, FrameLayout.LayoutParams(u(190), u(190), Gravity.TOP or Gravity.END).apply { setMargins(0, u(110), u(96), 0) })

        // lanes, in the lower part
        lanesBox.addView(lanes.view, matchParent())
        lanesBox.visibility = View.GONE
        root.addView(lanesBox, FrameLayout.LayoutParams(-1, u(196), Gravity.BOTTOM).apply { setMargins(u(RAIL), 0, 0, 0) })

        // hero: grows upwards from a fixed bottom edge, so the buttons never move
        val hero = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; gravity = Gravity.BOTTOM }
        hero.addView(kicker)
        hero.addView(title, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(6), 0, u(6)) })
        hero.addView(desc, LinearLayout.LayoutParams(-1, -2))
        track.background = rounded(Color.argb(70, 255, 255, 255), u(2))
        fill.background = rounded(a.accent, u(2))
        track.addView(fill, FrameLayout.LayoutParams(0, -1))
        hero.addView(track, LinearLayout.LayoutParams(trackW, maxOf(3, u(3))).apply { setMargins(0, u(12), 0, 0) })
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for ((i, b) in buttons.withIndex()) {
            row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(if (i == 0) 0 else u(10), 0, 0, 0) })
            b.setOnKeyListener { v, k, ev -> buttonKey(v, k, ev) }
        }
        hero.addView(row, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(14), 0, 0) })
        root.addView(hero, FrameLayout.LayoutParams(u(520), u(290), Gravity.TOP or Gravity.START).apply {
            setMargins(u(RAIL + 34), u(42), 0, 0)
        })

        // clock, date and weather, top right
        val head = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            // its own dark plate: the corner of a picture can be anything, also white
            background = rounded(Color.argb(120, 0, 0, 0), u(12))
            setPadding(u(12), u(7), u(12), u(8))
        }
        head.addView(clock, LinearLayout.LayoutParams(-2, -2))
        head.addView(sub, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(3), 0, 0) })
        root.addView(head, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(16), u(22), 0) })

        // the rail of apps
        rail.background = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(Color.argb(205, 7, 8, 14)) },
            GradientDrawable().apply { setColor(Color.argb(40, 255, 255, 255)) }
        )).apply { setLayerGravity(1, Gravity.END); setLayerWidth(1, maxOf(1, u(1))) }
        railScroll.isVerticalScrollBarEnabled = false
        railScroll.overScrollMode = View.OVER_SCROLL_NEVER
        railCol.orientation = LinearLayout.VERTICAL
        railCol.setPadding(0, u(14), 0, u(14))
        railScroll.addView(railCol, FrameLayout.LayoutParams(-1, -2))
        rail.addView(railScroll, FrameLayout.LayoutParams(-1, -1).apply { setMargins(0, 0, 0, u(46)) })
        rail.addView(customize, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(u(8), 0, u(8), u(9)) })
        root.addView(rail, FrameLayout.LayoutParams(u(RAIL), -1, Gravity.START))

        refreshHeader()
        NowPlaying.onChange = { if (!destroyed) refreshHero() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatch)
    }

    // ---------------------------------------------------------------- rail

    /** one entry of the rail: icon and name (always shown). With null it is the Customize entry at the bottom. */
    private fun railRow(e: AppEntry?): View {
        val icon: View = if (e != null) ImageView(a).apply { setImageDrawable(e.icon) }
        else label(15f, Color.WHITE).apply { text = "⚙"; gravity = Gravity.CENTER }
        val name = titleLabel(11.5f, 2, Color.argb(220, 255, 255, 255)).apply { setTitle(e?.label ?: "Customize") }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            tag = e?.key ?: "customize"
            setPadding(u(8), u(5), u(6), u(5))
            minimumHeight = u(34)
        }
        row.addView(icon, LinearLayout.LayoutParams(u(24), u(24)))
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u(9), 0, 0, 0) })
        if (e != null) {
            row.setOnClickListener { a.openApp(e) }
            row.setOnLongClickListener { a.showAppMenu(e); true }
        } else {
            row.setOnClickListener { a.openSettings() }
        }
        row.setOnFocusChangeListener { v, has ->
            if (has && e != null) {
                railApp = e
                a.lastKey = e.key
                // debounced: sweeping along the rail doesn't load a picture for every app passed
                a.handler.removeCallbacks(showRail)
                a.handler.postDelayed(showRail, 240)
            }
            if (has) v.post { railScroll.smoothScrollTo(0, (v.top + v.height / 2 - railScroll.height / 2).coerceAtLeast(0)) }
            paintRail()
        }
        row.setOnKeyListener { v, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) railStep(v, if (k == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        if (e != null) { a.handler.removeCallbacks(showRail); showApp(e) }
                        mainBtn.requestFocus()
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
        return row
    }

    /** the rail is a ring: past the last app comes Customize, past Customize the first app */
    private fun railStep(from: View, d: Int) {
        val all = railRows + customize
        val i = all.indexOf(from)
        if (i < 0 || all.isEmpty()) return
        all[(i + d + all.size) % all.size].requestFocus()
    }

    private fun paintRail() {
        val inRail = railRows.any { it.isFocused } || customize.isFocused
        var first = true
        for (r in railRows + customize) {
            val key = r.tag as? String
            val marked = heroPkg != null && apps.firstOrNull { it.key == key }?.pkg == heroPkg
            if (marked && first && !inRail) {
                // the app of the title on screen stays in view while the focus is elsewhere
                first = false
                r.post { railScroll.smoothScrollTo(0, (r.top + r.height / 2 - railScroll.height / 2).coerceAtLeast(0)) }
            }
            r.background = when {
                r.isFocused -> rounded(Color.argb(90, 255, 255, 255), u(10), u(3), a.accent)
                marked -> rounded(Color.argb(40, 255, 255, 255), u(10), maxOf(1, u(1)), Color.argb(70, 255, 255, 255))
                else -> null
            }
        }
    }

    private fun focusRail() {
        val key = apps.firstOrNull { it.pkg == heroPkg }?.key ?: railApp?.key ?: startKey
        (railRows.firstOrNull { it.tag == key } ?: railRows.firstOrNull() ?: customize).requestFocus()
    }

    // ---------------------------------------------------------------- keys of the hero buttons

    private fun buttonKey(v: View, k: Int, ev: KeyEvent): Boolean {
        if (k != KeyEvent.KEYCODE_DPAD_LEFT && k != KeyEvent.KEYCODE_DPAD_RIGHT &&
            k != KeyEvent.KEYCODE_DPAD_UP && k != KeyEvent.KEYCODE_DPAD_DOWN) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        val vis = buttons.filter { it.visibility == View.VISIBLE }
        val i = vis.indexOf(v)
        when (k) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (i <= 0) focusRail() else vis[i - 1].requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (i < vis.size - 1) vis[i + 1].requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (lanesBox.visibility == View.VISIBLE) lanes.focus()
        }
        return true
    }

    override fun onLaneExitUp() { mainBtn.requestFocus() }
    override fun onLaneExitLeft() = focusRail()
    override fun onItemFocus(item: TvFeed.Item) {
        a.handler.removeCallbacks(showRail)
        showItem(item)
    }

    // ---------------------------------------------------------------- data

    override fun bind(apps: List<AppEntry>, focusKey: String?) {
        this.apps = apps
        startKey = focusKey
        railCol.removeAllViews()
        railRows.clear()
        for (e in apps) {
            val r = railRow(e)
            railRows.add(r)
            railCol.addView(r, LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(8), u(1), u(8), u(1)) })
        }
        refreshHeader()
        setLanes()
        root.post { if (!destroyed && !root.hasFocus() && a.homeMayFocus()) opening() }
    }

    private fun setLanes() {
        val list = a.lanes()
        lanes.setLanes(list)
        lanesBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** the opening state: the first title of the lane across all apps, focus on its button; without any title, the rail */
    private fun opening() {
        val key = startKey
        val row = if (key != null) railRows.firstOrNull { it.tag == key } else null
        val first = lanes.firstItem()
        when {
            row != null -> row.requestFocus()                 // rebuilt after an app option: stay on that app
            first != null -> { showItem(first); mainBtn.requestFocus() }
            else -> (railRows.firstOrNull() ?: customize).requestFocus()
        }
        startKey = null
    }

    override fun currentKey(): String? =
        if (railRows.any { it.isFocused }) railApp?.key else startKey

    override fun refocus() {
        if (root.hasFocus()) return
        val v = lastFocus
        if (v != null && v.isAttachedToWindow && v.isShown && v.isFocusable) v.requestFocus() else opening()
    }

    override fun home() {
        touched = false
        startKey = null
        opening()
    }

    override fun onKey() { touched = true }
    override fun onTick() = refreshHeader()
    override fun onWeather() = refreshHeader()

    override fun onFeed() {
        setLanes()
        if (!touched && !lanes.view.hasFocus() && a.homeMayFocus()) opening() else refreshHero()
    }

    override fun destroy() {
        destroyed = true
        a.handler.removeCallbacks(showRail)
        a.handler.removeCallbacks(loadArt)
        a.handler.removeCallbacks(npTick)
        NowPlaying.onChange = null
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatch)
        lanes.release()
        a.setBackdropCovers(false)
    }

    private fun refreshHeader() {
        clock.text = a.nowStr(a.clockPattern())
        val wx = a.wxShort()
        sub.text = a.nowStr("EEE d MMM") + (if (wx.isEmpty()) "" else "  ·  $wx")
    }

    // ---------------------------------------------------------------- hero

    /** repaint the hero for what holds the focus now (something started or stopped playing, new data) */
    private fun refreshHero() {
        val item = shown
        when {
            railRows.any { it.isFocused } -> railApp?.let { showApp(it) }
            item != null && a.appFor(item.pkg)?.let { NowPlaying.infoFor(it.pkg) } == null -> {}
            else -> apps.firstOrNull { it.pkg == heroPkg }?.let { showApp(it) }
        }
    }

    private fun showApp(e: AppEntry) {
        val np = NowPlaying.infoFor(e.pkg)
        if (np != null) { showNowPlaying(e, np); return }
        val item = a.feed[e.pkg]?.firstOrNull()
        if (item == null) showPlain(e) else showItem(item)
    }

    private fun transport(on: Boolean) {
        val had = buttons.firstOrNull { it.isFocused }
        prevBtn.visibility = if (on) View.VISIBLE else View.GONE
        nextBtn.visibility = if (on) View.VISIBLE else View.GONE
        stopBtn.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) a.handler.removeCallbacks(npTick)
        // the focused button went away: the main one takes over, the focus must never get lost
        if (had != null && had.visibility != View.VISIBLE) mainBtn.requestFocus()
    }

    private fun setProgress(f: Float) {
        if (f < 0f) { track.visibility = View.GONE; return }
        track.visibility = View.VISIBLE
        (fill.layoutParams as FrameLayout.LayoutParams).width = (trackW * f).toInt().coerceAtLeast(maxOf(3, u(3)))
        fill.requestLayout()
    }

    private fun setText(kick: String, t: String, d: String) {
        kicker.text = kick
        kicker.setTextColor(a.accent)
        if (title.text.toString() != t) title.setTitle(t)
        desc.text = d
        desc.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
    }

    /** an app that publishes nothing: its name and an Open button over the scene or the theme's colours */
    private fun showPlain(e: AppEntry) {
        shown = null
        heroPkg = e.pkg
        transport(false)
        cover.visibility = View.GONE
        setText("APP", e.label, "")
        setProgress(-1f)
        mainBtn.text = "▶  Open"
        mainBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.GONE
        if (openBtn.isFocused) mainBtn.requestFocus()
        hideBackdrop()
        paintRail()
    }

    private fun showItem(item: TvFeed.Item) {
        val e = a.appFor(item.pkg)
        val same = shown === item
        shown = item
        heroPkg = item.pkg
        transport(false)
        if (!same) cover.visibility = View.GONE
        val app = e?.label ?: ""
        val kind = if (item.resume) "CONTINUE" else item.kind
        val left = HomeLogic.leftText(item.leftMs)
        setText(
            kind + (if (app.isEmpty()) "" else "  ·  " + app.uppercase(Locale.getDefault())), item.title,
            listOf(item.desc, left).filter { it.isNotEmpty() }.joinToString(if (item.desc.endsWith(".")) " " else ". ")
        )
        setProgress(if (item.progress > 0f) item.progress else -1f)
        mainBtn.text = if (item.resume) "▶  Resume" else "▶  Play"
        mainBtn.setOnClickListener { a.openItem(item, e) }
        if (e != null) {
            openBtn.text = "Open " + e.label
            openBtn.setOnClickListener { a.openApp(e) }
            openBtn.visibility = View.VISIBLE
        } else {
            openBtn.visibility = View.GONE
        }
        paintRail()
        if (!same) {
            // debounced: walking along a lane doesn't fetch a full-screen picture for every card passed
            a.handler.removeCallbacks(loadArt)
            a.handler.postDelayed(loadArt, 200)
        }
    }

    private fun loadArtFor(item: TvFeed.Item) {
        Images.load(a, item.art, if (item.square) 480 else BACKDROP_PX) { bmp ->
            if (destroyed || shown !== item || bmp == null) return@load
            // judged by the picture itself as well: apps do not always say that a cover is square
            if (item.square || kotlin.math.abs(bmp.width - bmp.height) < bmp.width * 0.12f) {
                // a playlist / album cover: a cover card, and its colours as a soft wash (unless a visualizer is the backdrop)
                cover.setImageBitmap(bmp)
                cover.visibility = View.VISIBLE
                if (a.viz.live) hideBackdrop() else setAmbient(bmp)
            } else setBackdrop(bmp)
        }
    }

    private val npTick = object : Runnable {
        override fun run() {
            val np = heroPkg?.let { NowPlaying.infoFor(it) } ?: return
            setProgress(np.fraction())
            if (np.playing) a.handler.postDelayed(this, 1000)
        }
    }

    private fun showNowPlaying(e: AppEntry, np: NowPlaying.Info) {
        shown = null
        heroPkg = e.pkg
        a.handler.removeCallbacks(loadArt)
        setText((if (np.playing) "NOW PLAYING" else "PAUSED") + "  ·  " + e.label.uppercase(Locale.getDefault()), np.title, np.subtitle())
        setProgress(np.fraction())
        a.handler.removeCallbacks(npTick)
        if (np.playing) a.handler.postDelayed(npTick, 1000)
        mainBtn.text = if (np.playing) "❚❚  Pause" else "▶  Play"
        mainBtn.setOnClickListener { NowPlaying.playPause(e.pkg) }
        prevBtn.visibility = View.VISIBLE
        nextBtn.visibility = View.VISIBLE
        stopBtn.visibility = View.VISIBLE
        prevBtn.setOnClickListener { NowPlaying.prev(e.pkg) }
        nextBtn.setOnClickListener { NowPlaying.next(e.pkg) }
        stopBtn.setOnClickListener { NowPlaying.stopPlayback(e.pkg) }
        openBtn.text = "Open " + e.label
        openBtn.setOnClickListener { a.openApp(e) }
        openBtn.visibility = View.VISIBLE
        val art = np.art
        if (art != null) {
            cover.setImageBitmap(art)
            cover.visibility = View.VISIBLE
            a.viz.evaluate()
            if (a.viz.live) hideBackdrop() else setAmbient(art)
        } else {
            cover.visibility = View.GONE
            hideBackdrop()
        }
        paintRail()
    }

    // ---------------------------------------------------------------- backdrop

    /** a soft wash of a cover's colours behind everything (the cover shrunk to a few pixels, then stretched) */
    private fun setAmbient(art: Bitmap) {
        if (art === lastAmbient && backdrop.visibility == View.VISIBLE) return
        lastAmbient = art
        lastBackdrop = null
        var tiny = art
        while (tiny.width > 8) tiny = Bitmap.createScaledBitmap(tiny, tiny.width / 2, maxOf(1, tiny.height / 2), true)
        backdrop.scaleType = ImageView.ScaleType.CENTER_CROP
        backdrop.setPadding(0, 0, 0, 0)
        backdrop.setImageBitmap(tiny)
        backdrop.visibility = View.VISIBLE
        backdrop.animate().alpha(0.62f).setDuration(380).start()
        a.setBackdropCovers(false)
    }

    private fun setBackdrop(bmp: Bitmap) {
        if (bmp === lastBackdrop && backdrop.visibility == View.VISIBLE) return
        lastBackdrop = bmp
        lastAmbient = null
        // judge the picture by its real shape: apps often report the wrong aspect ratio
        val wide = bmp.width >= bmp.height * 1.25f
        val apply = Runnable {
            backdrop.scaleType = if (wide) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_END
            backdrop.setPadding(0, 0, if (wide) 0 else u(70), 0)
            backdrop.setImageBitmap(bmp)
        }
        val wasHidden = backdrop.visibility != View.VISIBLE || backdrop.alpha < 0.05f
        backdrop.visibility = View.VISIBLE
        if (wasHidden) {
            apply.run()
            backdrop.animate().alpha(1f).setDuration(380).withEndAction { if (lastBackdrop === bmp) a.setBackdropCovers(wide) }.start()
        } else {
            a.setBackdropCovers(false)
            backdrop.animate().alpha(0f).setDuration(160).withEndAction {
                apply.run()
                backdrop.animate().alpha(1f).setDuration(300).withEndAction { if (lastBackdrop === bmp) a.setBackdropCovers(wide) }.start()
            }.start()
        }
    }

    private fun hideBackdrop() {
        lastBackdrop = null
        lastAmbient = null
        a.setBackdropCovers(false)
        if (backdrop.visibility != View.VISIBLE) return
        backdrop.animate().alpha(0f).setDuration(240).withEndAction {
            if (lastBackdrop == null && lastAmbient == null) backdrop.visibility = View.GONE
        }.start()
    }

    private companion object {
        /** width of the rail of apps, in screen units */
        const val RAIL = 150
        /** a full-screen picture is decoded no wider than this (about 3.7 MB each), which is what apps publish anyway */
        const val BACKDROP_PX = 1280
    }
}
