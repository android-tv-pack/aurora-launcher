package app.hermes.aurora

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * DRIFT: almost nothing on screen. The scene (or the theme's colours) fills it; a large thin clock, the date,
 * the weather in a corner and, in the centre, one pill with the app that was opened last (OK opens it, or continues
 * what that app has to continue). While something plays, the pill gives its place to what plays: cover, title and
 * the previous / play-pause / next buttons.
 *
 * From this resting screen each direction slides a panel in: UP apps, LEFT music, DOWN watch, RIGHT pins.
 * A panel leaves again on Back, or by itself after half a minute without a key press.
 */
internal class DriftStyle(a: MainActivity) : HomeStyle(a) {

    private val root = FrameLayout(a)
    override val view: View get() = root

    private val clock = label(128f, Color.WHITE, "sans-serif-thin").apply { maxLines = 1 }
    private val date = label(13f, Color.argb(240, 255, 255, 255), "sans-serif", 0.34f)
    private val wx1 = label(15f, Color.WHITE, "sans-serif")
    private val wx2 = label(11.5f, Color.argb(215, 255, 255, 255), "sans-serif")
    private val wxBox = LinearLayout(a)

    private val pill = LinearLayout(a)
    private val pillTitle = titleLabel(14f, 1)
    private val pillSub = label(10.5f, Color.argb(215, 255, 255, 255))
    private val pillIcon = ImageView(a)
    private val hints = ArrayList<View>()

    // what plays, in the pill's place: cover, title, artist and the transport buttons
    private val ctrCard = LinearLayout(a)
    private val ctrCover = ImageView(a)
    private val ctrCap = label(9f, a.accent, "sans-serif-medium", 0.26f).apply { maxLines = 1 }
    private val ctrTitle = titleLabel(14f, 1)
    private val ctrBy = label(10.5f, Color.argb(215, 255, 255, 255)).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = true
    }
    private val ctrTrack = FrameLayout(a)
    private val ctrFill = View(a)
    private val ctrPrev = actionButton(false)
    private val ctrMain = actionButton(true)
    private val ctrNext = actionButton(false)
    private val ctrButtons = listOf(ctrPrev, ctrMain, ctrNext)
    /** whose playback the centre shows (see HomeLogic.nextHeld) */
    private var held = HomeLogic.Held()
    private var ctrArt: Any? = null

    private val shade = View(a)
    /** the resting screen's own content (clock, date, pill, weather): it leaves while a panel is open, so nothing shows through the panel */
    private val rest = ArrayList<View>()
    private val sheetHost = FrameLayout(a)

    private var apps: List<AppEntry> = emptyList()
    private var panel = NONE
    private var sheet: View? = null
    private var sheetFocus: (() -> Unit)? = null
    private var sheetLanes: LaneList? = null
    private var npBox: LinearLayout? = null
    private val npBars = ArrayList<Triple<String, View, Int>>()
    private var destroyed = false

    private val fadeHints = Runnable { for (h in hints) h.animate().alpha(0f).setDuration(900).start() }
    private val idle = object : Runnable {
        override fun run() {
            if (panel == NONE) return
            // a dialog (app options) is open on top: wait for it
            if (!a.hasWindowFocus()) a.handler.postDelayed(this, PANEL_IDLE_MS) else close()
        }
    }
    private val npTick = object : Runnable {
        override fun run() {
            if (panel != LEFT) return
            var playing = false
            for ((pkg, fill, w) in npBars) {
                val np = NowPlaying.infoFor(pkg) ?: continue
                val f = np.fraction()
                if (f >= 0f) { fill.layoutParams.width = (w * f).toInt().coerceAtLeast(2); fill.requestLayout() }
                if (np.playing) playing = true
            }
            if (playing) a.handler.postDelayed(this, 1000)
        }
    }

    /** the centre looks again: a pause that has lasted long enough, or a track change that took a moment */
    private val ctrLater = Runnable { refreshCentre() }
    private val ctrTick = object : Runnable {
        override fun run() {
            if (destroyed || panel != NONE) return
            val i = held.pkg?.let { NowPlaying.infoFor(it) } ?: return
            paintProgress(i)
            if (i.playing) a.handler.postDelayed(this, 1000)
        }
    }

    init {
        // the dark band behind the clock and the pill: darkest in the middle, lighter towards the edges
        root.addView(View(a).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = u(430).toFloat()
                setGradientCenter(0.5f, 0.42f)
                colors = intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(105, 0, 0, 0), Color.argb(50, 0, 0, 0))
            }
        }, matchParent())

        val shadow = Color.argb(170, 0, 0, 0)
        clock.setShadowLayer(u(12).toFloat(), 0f, u(2).toFloat(), shadow)
        date.setShadowLayer(u(6).toFloat(), 0f, u(1).toFloat(), shadow)
        val center = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        // (explicit sizes: a vertical LinearLayout would otherwise squeeze a child to the width of its siblings)
        center.addView(clock, LinearLayout.LayoutParams(-2, -2))
        center.addView(date, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(10), 0, 0) })

        // the one pill
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.isFocusable = true
        pill.isClickable = true
        pill.setPadding(u(11), u(8), u(24), u(8))
        pill.addView(pillIcon, LinearLayout.LayoutParams(u(30), u(30)))
        val txt = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        pillTitle.maxWidth = u(460)
        pillSub.maxLines = 1
        txt.addView(pillTitle, LinearLayout.LayoutParams(-2, -2))
        txt.addView(pillSub, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(1), 0, 0) })
        pill.addView(txt, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(12), 0, 0, 0) })
        pill.setOnFocusChangeListener { _, _ -> paintPill() }
        pill.setOnKeyListener { _, k, ev ->
            val dir = when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> UP
                KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
                else -> NONE
            }
            if (dir != NONE && ev.action == KeyEvent.ACTION_DOWN) open(dir)
            dir != NONE
        }
        paintPill()
        center.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(30), 0, 0) })

        // what plays
        ctrCard.orientation = LinearLayout.HORIZONTAL
        ctrCard.gravity = Gravity.CENTER_VERTICAL
        ctrCard.clipChildren = false
        ctrCard.clipToPadding = false
        ctrCard.background = rounded(Color.argb(185, 26, 22, 42), u(22), maxOf(1, u(1)), Color.argb(90, 255, 255, 255))
        ctrCard.setPadding(u(9), u(9), u(16), u(9))
        ctrCard.visibility = View.GONE
        ctrCover.scaleType = ImageView.ScaleType.CENTER_CROP
        ctrCover.background = rounded(Color.argb(50, 255, 255, 255), u(13))
        ctrCover.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        ctrCover.clipToOutline = true
        ctrCard.addView(ctrCover, LinearLayout.LayoutParams(u(54), u(54)))
        val what = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        what.addView(ctrCap, LinearLayout.LayoutParams(-1, -2))
        what.addView(ctrTitle, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(3), 0, 0) })
        what.addView(ctrBy, LinearLayout.LayoutParams(-1, -2))
        ctrTrack.background = rounded(Color.argb(70, 255, 255, 255), u(2))
        ctrFill.background = rounded(a.accent, u(2))
        ctrTrack.addView(ctrFill, FrameLayout.LayoutParams(2, -1))
        what.addView(ctrTrack, LinearLayout.LayoutParams(-1, maxOf(3, u(3))).apply { setMargins(0, u(5), 0, 0) })
        ctrCard.addView(what, LinearLayout.LayoutParams(u(CTR_TEXT), -2).apply { setMargins(u(12), 0, u(8), 0) })
        for (b in ctrButtons) {
            b.setPadding(u(14), u(9), u(14), u(9))
            b.setOnKeyListener { v, k, ev -> centreKey(v, k, ev) }
            ctrCard.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(8), 0, 0, 0) })
        }
        ctrPrev.text = "⏮"
        ctrNext.text = "⏭"
        ctrPrev.setOnClickListener { held.pkg?.let { NowPlaying.prev(it) } }
        ctrMain.setOnClickListener { held.pkg?.let { NowPlaying.playPause(it) } }
        ctrNext.setOnClickListener { held.pkg?.let { NowPlaying.next(it) } }
        center.addView(ctrCard, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(24), 0, 0) })
        root.addView(center, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, u(104), 0, 0) })
        rest.add(center)

        // weather, in the corner
        wxBox.orientation = LinearLayout.VERTICAL
        wxBox.gravity = Gravity.END
        wxBox.background = rounded(Color.argb(115, 0, 0, 0), u(12))
        wxBox.setPadding(u(12), u(8), u(12), u(8))
        wxBox.addView(wx1, LinearLayout.LayoutParams(-2, -2))
        wxBox.addView(wx2, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(2), 0, 0) })
        root.addView(wxBox, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, u(16), u(18), 0) })
        rest.add(wxBox)

        // the four directions
        addHint("▲  APPS", "all your apps, by name", Gravity.TOP or Gravity.CENTER_HORIZONTAL, Gravity.CENTER_HORIZONTAL, false)
        addHint("◀  MUSIC", "now playing, shelves", Gravity.START or Gravity.CENTER_VERTICAL, Gravity.START, false)
        addHint("PINS  ▶", "pinned apps and titles", Gravity.END or Gravity.CENTER_VERTICAL, Gravity.END, false)
        addHint("▼  WATCH", "from every app", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, Gravity.CENTER_HORIZONTAL, true)

        shade.setBackgroundColor(Color.argb(120, 0, 0, 0))
        shade.alpha = 0f
        root.addView(shade, matchParent())
        root.addView(sheetHost, matchParent())

        refreshHeader()
        NowPlaying.onChange = {
            if (!destroyed) {
                refreshCentre()
                if (panel == LEFT) fillNowPlaying(true)
            }
        }
    }

    private fun dirOf(k: Int) = when (k) {
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        else -> NONE
    }

    /**
     * Arrows on the transport buttons: Left and Right walk the buttons and, past the last one, open that side's
     * panel; Up and Down open theirs. A held key walks but never opens a panel.
     */
    private fun centreKey(v: View, k: Int, ev: KeyEvent): Boolean {
        val dir = dirOf(k)
        if (dir == NONE) return false
        if (ev.action != KeyEvent.ACTION_DOWN) return true
        val row = ctrButtons.filter { it.visibility == View.VISIBLE }
        val i = row.indexOf(v)
        when {
            dir == LEFT && i > 0 -> row[i - 1].requestFocus()
            dir == RIGHT && i >= 0 && i < row.size - 1 -> row[i + 1].requestFocus()
            ev.repeatCount == 0 -> open(dir)
        }
        return true
    }

    private fun addHint(head: String, caption: String, where: Int, align: Int, captionFirst: Boolean) {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = align
            background = rounded(Color.argb(115, 0, 0, 0), u(10))
            setPadding(u(11), u(6), u(11), u(6))
        }
        val h = label(10f, Color.WHITE, "sans-serif-medium", 0.3f).apply { text = head }
        val c = label(10f, Color.argb(205, 255, 255, 255)).apply { text = caption }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(3), 0, 0) }
        if (captionFirst) { box.addView(c, LinearLayout.LayoutParams(-2, -2)); box.addView(h, gap) } else { box.addView(h, LinearLayout.LayoutParams(-2, -2)); box.addView(c, gap) }
        root.addView(box, FrameLayout.LayoutParams(-2, -2, where).apply { setMargins(u(14), u(12), u(14), u(12)) })
        hints.add(box)
    }

    /** the hints show for a few seconds, then leave; any key brings them back */
    private fun showHints() {
        a.handler.removeCallbacks(fadeHints)
        for (h in hints) { h.animate().cancel(); h.alpha = 1f }
        a.handler.postDelayed(fadeHints, HINT_MS)
    }

    private fun hideHints() {
        a.handler.removeCallbacks(fadeHints)
        for (h in hints) { h.animate().cancel(); h.alpha = 0f }
    }

    private fun paintPill() {
        pill.background = if (pill.isFocused) rounded(Color.argb(205, 26, 22, 42), u(27), u(3), a.accent)
        else rounded(Color.argb(170, 26, 22, 42), u(27), maxOf(1, u(1)), Color.argb(90, 255, 255, 255))
    }

    // ---------------------------------------------------------------- the resting screen

    private fun refreshHeader() {
        clock.text = a.nowStr(a.clockPattern())
        date.text = a.nowStr("EEEE  ·  d MMMM").uppercase(Locale.getDefault())
        val w = a.wxNow()
        wxBox.visibility = if (w.isEmpty()) View.GONE else View.VISIBLE
        wx1.text = w
        wx2.text = a.wxPlace()
        wx2.visibility = if (wx2.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** the pill: the app that was opened last. When that app has something to continue, OK continues it. */
    private fun refreshPill() {
        val recents = a.store.recents
        val key = HomeLogic.lastUsed(recents, apps.map { it.key })
        val e = key?.let { k -> apps.firstOrNull { it.key == k } }
        if (e == null) {
            pillIcon.visibility = View.GONE
            pillTitle.setTitle("No apps yet")
            pillSub.text = "press UP for the list"
            pill.setOnClickListener { open(UP) }
            return
        }
        pillIcon.visibility = View.VISIBLE
        pillIcon.setImageDrawable(e.icon)
        // the app's name is always the headline; what it has to continue stands underneath
        pillTitle.setTitle(e.label)
        val item = HomeLogic.resumeFor(a.feed, e.pkg)
        if (item != null) {
            pillSub.text = listOf("OK to continue " + HomeLogic.cutAtWord(item.title, 36), HomeLogic.leftText(item.leftMs))
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
            pill.setOnClickListener { a.openItem(item, e) }
            return
        }
        pillSub.text = if (key in recents) "last used  ·  OK to open" else "OK to open"
        pill.setOnClickListener { a.openApp(e) }
    }

    /**
     * The centre: the buttons of what plays, otherwise the pill with the app that was opened last.
     * Which playback it is, and how long something paused keeps the buttons: HomeLogic.nextHeld.
     */
    private fun refreshCentre() {
        if (destroyed) return
        a.handler.removeCallbacks(ctrLater)
        a.handler.removeCallbacks(ctrTick)
        val infos = LinkedHashMap<String, NowPlaying.Info>()
        for (e in apps.distinctBy { it.pkg }) NowPlaying.infoFor(e.pkg)?.let { infos[e.pkg] = it }
        val now = android.os.SystemClock.uptimeMillis()
        held = HomeLogic.nextHeld(infos.filterValues { it.playing }.keys.toList(), infos.filterValues { !it.playing }.keys.toList(), held, now)
        val pkg = held.pkg
        val app = pkg?.let { p -> apps.firstOrNull { it.pkg == p } }
        if (pkg == null || app == null) {
            held = HomeLogic.Held()
            refreshPill()
            showCentre(false)
            return
        }
        val info = infos[pkg]
        if (info == null) {
            // between two tracks: what is shown stays, and the centre looks again in a moment
            a.handler.postDelayed(ctrLater, HomeLogic.GONE_GRACE_MS)
            return
        }
        paintPlayback(app, info)
        showCentre(true)
        if (info.playing) a.handler.postDelayed(ctrTick, 1000)
        else a.handler.postDelayed(ctrLater, (HomeLogic.PAUSED_KEEP_MS - (now - held.pausedAt)).coerceAtLeast(500) + 300)
    }

    private fun paintPlayback(app: AppEntry, info: NowPlaying.Info) {
        ctrCap.text = (if (info.playing) "NOW PLAYING" else "PAUSED") + "  ·  " + app.label.uppercase(Locale.getDefault())
        ctrTitle.setTitle(info.title)
        ctrBy.text = info.subtitle()
        ctrBy.visibility = if (ctrBy.text.isEmpty()) View.GONE else View.VISIBLE
        val art: Any = info.art ?: app.icon
        if (art !== ctrArt) {
            ctrArt = art
            if (info.art != null) { ctrCover.scaleType = ImageView.ScaleType.CENTER_CROP; ctrCover.setImageBitmap(info.art) }
            else { ctrCover.scaleType = ImageView.ScaleType.CENTER_INSIDE; ctrCover.setImageDrawable(app.icon) }
        }
        ctrMain.text = if (info.playing) "❚❚" else "▶"
        // a button that is about to leave must not hold the focus
        if ((ctrPrev.isFocused && !info.canPrev) || (ctrNext.isFocused && !info.canNext)) ctrMain.requestFocus()
        ctrPrev.visibility = if (info.canPrev) View.VISIBLE else View.GONE
        ctrNext.visibility = if (info.canNext) View.VISIBLE else View.GONE
        paintProgress(info)
    }

    private fun paintProgress(info: NowPlaying.Info) {
        val f = info.fraction()
        ctrTrack.visibility = if (f >= 0f) View.VISIBLE else View.INVISIBLE
        if (f >= 0f) {
            ctrFill.layoutParams.width = (u(CTR_TEXT) * f).toInt().coerceAtLeast(2)
            ctrFill.requestLayout()
        }
    }

    /** swaps the pill and the buttons; the focus follows when the centre had it */
    private fun showCentre(playback: Boolean) {
        if ((ctrCard.visibility == View.VISIBLE) == playback) return
        val hadFocus = pill.isFocused || ctrButtons.any { it.isFocused }
        (if (playback) ctrCard else pill).visibility = View.VISIBLE
        if (hadFocus) (if (playback) ctrMain else pill).requestFocus()
        (if (playback) pill else ctrCard).visibility = View.GONE
    }

    private fun focusCentre() {
        if (ctrCard.visibility == View.VISIBLE) ctrMain.requestFocus() else pill.requestFocus()
    }

    /** while a panel is open nothing in the centre can take the focus */
    private fun centreFocusable(on: Boolean) {
        pill.isFocusable = on
        for (b in ctrButtons) b.isFocusable = on
    }

    override fun bind(apps: List<AppEntry>, focusKey: String?) {
        this.apps = apps
        refreshHeader()
        refreshCentre()
        val again = if (android.os.SystemClock.uptimeMillis() - reopenAt < 3000) reopen else NONE
        reopen = NONE
        root.post {
            if (destroyed) return@post
            if (again != NONE) open(again, false)
            else if (a.homeMayFocus()) { focusCentre(); showHints() }
        }
    }

    override fun currentKey(): String? = null

    override fun refocus() {
        if (panel != NONE) sheetFocus?.invoke() else focusCentre()
    }

    override fun home() {
        if (panel != NONE) close(false)
        focusCentre()
        showHints()
    }

    override fun onTick() {
        refreshHeader()
        refreshCentre()
    }
    override fun onWeather() = refreshHeader()

    override fun onFeed() {
        if (ctrCard.visibility != View.VISIBLE) refreshPill()
        when (panel) {
            DOWN -> sheetLanes?.setLanes(a.lanes())
            LEFT -> sheetLanes?.setLanes(a.musicLanes())
            RIGHT -> { close(false); open(RIGHT, false) }       // a title was pinned or unpinned
        }
    }

    override fun onKey() {
        if (panel == NONE) showHints() else armIdle()
    }

    override fun onBack(): Boolean {
        if (panel == NONE) return false
        close()
        return true
    }

    override fun destroy() {
        destroyed = true
        // rebuilt while a panel is open (an app was pinned, hidden ...): the new screen opens it again
        reopen = panel
        reopenAt = android.os.SystemClock.uptimeMillis()
        a.handler.removeCallbacks(fadeHints)
        a.handler.removeCallbacks(idle)
        a.handler.removeCallbacks(npTick)
        a.handler.removeCallbacks(ctrLater)
        a.handler.removeCallbacks(ctrTick)
        sheetLanes?.release()
        NowPlaying.onChange = null
    }

    // ---------------------------------------------------------------- panels

    private fun armIdle() {
        a.handler.removeCallbacks(idle)
        a.handler.postDelayed(idle, PANEL_IDLE_MS)
    }

    private fun open(dir: Int, animate: Boolean = true) {
        if (panel != NONE || destroyed) return
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(240, 10, 12, 22), 0)
            isClickable = true
            // a focused control grows a little: it must not be cut at the edge of its row
            clipChildren = false
            clipToPadding = false
        }
        val lp = when (dir) {
            UP -> FrameLayout.LayoutParams(-1, u(396), Gravity.TOP)
            DOWN -> FrameLayout.LayoutParams(-1, u(420), Gravity.BOTTOM)
            LEFT -> FrameLayout.LayoutParams(u(SIDE), -1, Gravity.START)
            else -> FrameLayout.LayoutParams(u(SIDE), -1, Gravity.END)
        }
        panel = dir
        sheet = box
        when (dir) {
            UP -> appsSheet(box)
            DOWN -> watchSheet(box)
            LEFT -> musicSheet(box)
            else -> pinsSheet(box)
        }
        hideHints()
        sheetHost.addView(box, lp)
        centreFocusable(false)
        paintPill()
        if (animate) {
            when (dir) {
                UP -> box.translationY = -lp.height.toFloat()
                DOWN -> box.translationY = lp.height.toFloat()
                LEFT -> box.translationX = -lp.width.toFloat()
                else -> box.translationX = lp.width.toFloat()
            }
            box.animate().translationX(0f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
            shade.animate().alpha(1f).setDuration(240).start()
            for (v in rest) v.animate().alpha(0f).setDuration(180).start()
        } else {
            shade.alpha = 1f
            for (v in rest) { v.animate().cancel(); v.alpha = 0f }
        }
        box.post { if (sheet === box) sheetFocus?.invoke() }
        armIdle()
    }

    private fun close(animate: Boolean = true) {
        val box = sheet ?: return
        val dir = panel
        panel = NONE
        sheet = null
        sheetFocus = null
        sheetLanes?.release()
        sheetLanes = null
        npBox = null
        npBars.clear()
        a.handler.removeCallbacks(idle)
        a.handler.removeCallbacks(npTick)
        centreFocusable(true)
        refreshCentre()
        if (a.homeMayFocus()) focusCentre()
        paintPill()
        (box as? android.view.ViewGroup)?.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (animate) {
            val w = box.width.toFloat()
            val h = box.height.toFloat()
            val an = box.animate().setDuration(200)
            when (dir) {
                UP -> an.translationY(-h)
                DOWN -> an.translationY(h)
                LEFT -> an.translationX(-w)
                else -> an.translationX(w)
            }
            an.withEndAction { sheetHost.removeView(box) }.start()
            shade.animate().alpha(0f).setDuration(200).start()
            for (v in rest) v.animate().alpha(1f).setDuration(260).start()
        } else {
            sheetHost.removeView(box)
            shade.animate().cancel()
            for (v in rest) { v.animate().cancel(); v.alpha = 1f }
            shade.alpha = 0f
        }
        showHints()
    }

    private fun header(box: LinearLayout, title: String, caption: String, extra: View? = null) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(11f, a.accent, "sans-serif-medium", 0.3f).apply { text = title }, LinearLayout.LayoutParams(-2, -2))
        col.addView(label(10.5f, Color.argb(190, 255, 255, 255)).apply { text = caption; includeFontPadding = true },
            LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (extra != null) row.addView(extra)
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(30), u(16), u(30), u(8)) })
    }

    private fun note(box: LinearLayout, text: String) {
        box.addView(label(12.5f, Color.argb(225, 255, 255, 255)).apply {
            this.text = text
            includeFontPadding = true
            setLineSpacing(u(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(30), u(8), u(30), u(4)) })
    }

    /** a panel with nothing to walk through still gets one focused control, so the focus is never invisible */
    private fun closePill(box: LinearLayout, text: String, action: () -> Unit): View {
        val p = a.pill(text, action)
        box.addView(p, LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(30), u(10), u(30), u(10)) })
        return p
    }

    /** rows of app cells, [perRow] in a row */
    private fun grid(list: List<AppEntry>, perRow: Int, cellW: Int): Pair<LinearLayout, View?> {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        var first: View? = null
        var row: LinearLayout? = null
        for ((i, e) in list.withIndex()) {
            if (i % perRow == 0) {
                row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(2), 0, u(2)) })
            }
            val c = appCell(e, cellW)
            if (first == null) first = c
            row!!.addView(c)
        }
        return col to first
    }

    // ---- UP: apps

    private fun appsSheet(box: LinearLayout) {
        // the way into Customize for remotes without a MENU key
        val customize = a.pill("⚙  Customize") { close(false); a.openSettings() }
        header(box, "APPS", "OK opens  ·  hold OK for options  ·  Back closes", customize)
        val (g, first) = grid(apps, 8, u(112))
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        sv.addView(g, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
        box.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(u(30), 0, u(30), u(10)) })
        sheetFocus = { (first ?: customize).requestFocus() }
    }

    // ---- DOWN: watch

    private fun watchSheet(box: LinearLayout) {
        val lanes = a.lanes()
        header(box, "WATCH", if (lanes.isEmpty()) "Back closes" else "OK plays  ·  hold OK to pin  ·  Back closes")
        if (lanes.isEmpty()) {
            note(box, if (!TvFeed.allowed(a)) "Aurora needs a one-time permission to read what your apps publish. The README has the command."
                else if (!a.store.preview) "Titles from your apps are switched off in Customize."
                else "None of your apps has published anything to watch yet.")
            val p = closePill(box, "Close") { close() }
            sheetFocus = { p.requestFocus() }
            return
        }
        val list = LaneList(a, 100f, 30f, object : LaneList.Host {})
        list.setLanes(lanes)
        sheetLanes = list
        box.addView(list.view, LinearLayout.LayoutParams(-1, 0, 1f))
        sheetFocus = { list.focus() }
    }

    // ---- LEFT: music

    private fun musicSheet(box: LinearLayout) {
        header(box, "MUSIC", "Back closes")
        val np = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        npBox = np
        box.addView(np, LinearLayout.LayoutParams(-1, -2))
        val shelves = a.musicLanes()
        val list = LaneList(a, 84f, 30f, object : LaneList.Host {
            override fun onLaneExitUp() { np.findViewWithTag<View>(MAIN)?.requestFocus() }
        })
        list.setLanes(shelves)
        sheetLanes = list
        box.addView(list.view, LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, u(6), 0, 0) })
        val any = fillNowPlaying(false)
        var close: View? = null
        if (!any && shelves.isEmpty()) close = closePill(box, "Close") { close() }
        sheetFocus = {
            val main = np.findViewWithTag<View>(MAIN)
            if (main != null) main.requestFocus()
            else if (!list.focus()) {
                // what was playing has ended and there are no shelves: nothing is left to hold the focus
                if (close == null) close = closePill(box, "Close") { close() }
                close?.requestFocus()
            }
        }
    }

    /** the cards of everything that plays or is paused; returns false when there is none */
    private fun fillNowPlaying(keepFocus: Boolean): Boolean {
        val np = npBox ?: return false
        val had = if (keepFocus && np.hasFocus()) np.findFocus()?.tag as? String else null
        val lost = keepFocus && np.hasFocus()
        np.removeAllViews()
        npBars.clear()
        a.handler.removeCallbacks(npTick)
        var first = true
        var playing = false
        for (e in apps.distinctBy { it.pkg }) {
            val info = NowPlaying.infoFor(e.pkg) ?: continue
            np.addView(nowPlayingCard(e, info, first), LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(30), u(4), u(30), u(8)) })
            first = false
            if (info.playing) playing = true
        }
        var idleCard = false
        if (first) {
            // nothing plays: the user's music app waits here
            val m = a.musicApp()
            if (m != null) { np.addView(musicAppCard(m), LinearLayout.LayoutParams(-1, -2).apply { setMargins(u(30), u(4), u(30), u(8)) }); idleCard = true }
            if (m == null || !NowPlaying.enabled(a)) note(np, if (NowPlaying.enabled(a)) "Nothing is playing."
                else "Nothing is playing. Showing and controlling what plays needs a one-time setup: the README has the command.")
        }
        if (playing) a.handler.postDelayed(npTick, 1000)
        if (lost) {
            val v = had?.let { np.findViewWithTag<View>(it) } ?: np.findViewWithTag(MAIN)
            if (v != null) v.requestFocus() else sheetFocus?.invoke()
        }
        return !first || idleCard
    }

    /** nothing plays: the music app with a button that opens it */
    private fun musicAppCard(e: AppEntry): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(46, 255, 255, 255), u(14))
            setPadding(u(12), u(10), u(12), u(10))
            clipChildren = false
        }
        card.addView(ImageView(a).apply { setImageDrawable(e.icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(u(44), u(44)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(9f, a.accent, "sans-serif-medium", 0.26f).apply { text = "YOUR MUSIC APP"; maxLines = 1 })
        col.addView(titleLabel(13.5f, 1).apply { setTitle(e.label) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(2), 0, 0) })
        col.addView(label(10.5f, Color.argb(200, 255, 255, 255)).apply { text = "Nothing is playing"; includeFontPadding = true })
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u(12), 0, u(8), 0) })
        card.addView(actionButton(true).apply {
            text = "Open"
            tag = MAIN
            setPadding(u(14), u(6), u(14), u(6))
            setOnClickListener { a.openApp(e) }
        }, LinearLayout.LayoutParams(-2, -2))
        return card
    }

    private fun nowPlayingCard(e: AppEntry, info: NowPlaying.Info, first: Boolean): View {
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.argb(46, 255, 255, 255), u(14))
            setPadding(u(10), u(10), u(10), u(10))
            clipChildren = false
        }
        val cover = ImageView(a).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.argb(50, 255, 255, 255), u(10))
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            if (info.art != null) setImageBitmap(info.art) else { setImageDrawable(e.icon); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        }
        card.addView(cover, LinearLayout.LayoutParams(u(84), u(84)))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        col.addView(label(9f, a.accent, "sans-serif-medium", 0.26f).apply {
            text = (if (info.playing) "NOW PLAYING" else "PAUSED") + "  ·  " + e.label.uppercase(Locale.getDefault())
            maxLines = 1
        })
        col.addView(titleLabel(13.5f, 2).apply { setTitle(info.title) }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(3), 0, 0) })
        val by = info.subtitle()
        if (by.isNotEmpty()) col.addView(label(11f, Color.argb(210, 255, 255, 255)).apply {
            text = by; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = true
        })
        val barW = u(SIDE) - u(60) - u(20) - u(84) - u(12)
        val f = info.fraction()
        if (f >= 0f) {
            val track = FrameLayout(a).apply { background = rounded(Color.argb(70, 255, 255, 255), u(2)) }
            val fill = View(a).apply { background = rounded(a.accent, u(2)) }
            track.addView(fill, FrameLayout.LayoutParams((barW * f).toInt().coerceAtLeast(2), -1))
            col.addView(track, LinearLayout.LayoutParams(barW, maxOf(3, u(3))).apply { setMargins(0, u(6), 0, 0) })
            npBars.add(Triple(e.pkg, fill, barW))
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun small(text: String, tag: String, primary: Boolean, act: () -> Unit) {
            val b = actionButton(primary).apply {
                this.text = text
                this.tag = tag
                setPadding(u(11), u(6), u(11), u(6))
                setOnClickListener { act() }
            }
            row.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, u(7), 0) })
        }
        val t = if (first) "" else e.pkg + ":"
        small("⏮", t + "prev", false) { NowPlaying.prev(e.pkg) }
        small(if (info.playing) "❚❚" else "▶", if (first) MAIN else t + "main", true) { NowPlaying.playPause(e.pkg) }
        small("⏭", t + "next", false) { NowPlaying.next(e.pkg) }
        small("⏹", t + "stop", false) { NowPlaying.stopPlayback(e.pkg) }
        small("Open", t + "open", false) { a.openApp(e) }
        col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, u(9), 0, 0) })
        card.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(u(12), 0, 0, 0) })
        return card
    }

    // ---- RIGHT: pins

    private fun pinsSheet(box: LinearLayout) {
        val favs = a.store.favs
        val pinnedApps = favs.mapNotNull { k -> apps.firstOrNull { it.key == k } }
        val titles = a.pinLane()
        if (pinnedApps.isEmpty() && titles == null) {
            header(box, "PINS", "Back closes")
            note(box, "Nothing is pinned yet.\nHold OK on an app or on a title and choose Pin: it then waits for you here.")
            val p = closePill(box, "Browse apps") { close(false); open(UP) }
            sheetFocus = { p.requestFocus() }
            return
        }
        header(box, "PINS", "OK opens  ·  hold OK to unpin  ·  Back closes")
        var first: View? = null
        if (pinnedApps.isNotEmpty()) {
            val (g, f) = grid(pinnedApps, 4, u(96))
            first = f
            val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            sv.addView(g)
            // the titles follow right below the apps; without titles the apps may use the whole panel
            box.addView(sv, (if (titles == null) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, -2))
                .apply { setMargins(u(30), 0, u(30), u(8)) })
        }
        if (titles != null) {
            val list = LaneList(a, 84f, 30f, object : LaneList.Host {
                override fun onLaneExitUp() { first?.requestFocus() }
            })
            list.setLanes(listOf(titles))
            sheetLanes = list
            box.addView(list.view, LinearLayout.LayoutParams(-1, list.laneHeight))
            if (first == null) { sheetFocus = { list.focus() }; return }
        }
        sheetFocus = { first?.requestFocus() }
    }

    private companion object {
        const val NONE = 0
        const val UP = 1
        const val LEFT = 2
        const val DOWN = 3
        const val RIGHT = 4
        /** width of the side panels, in screen units */
        const val SIDE = 450
        /** width of the title and artist next to the cover of what plays, in screen units */
        const val CTR_TEXT = 270
        const val HINT_MS = 6000L
        const val PANEL_IDLE_MS = 30_000L
        const val MAIN = "main"
        /** a panel that was open when the screen had to be rebuilt */
        var reopen = NONE
        var reopenAt = 0L
    }
}
