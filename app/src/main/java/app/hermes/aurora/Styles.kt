package app.hermes.aurora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A complete home-screen layout: Marquee or Drift. */
internal abstract class HomeStyle(protected val a: MainActivity) {
    abstract val view: View
    abstract fun bind(apps: List<AppEntry>, focusKey: String?)
    /** put the focus back where it was (after Customize, the screensaver, a dialog) */
    abstract fun refocus()
    /** the Home key was pressed while the home screen is showing: back to its resting state */
    open fun home() = refocus()
    /** the app whose entry holds the focus, if any (kept across a rebuild of the screen) */
    abstract fun currentKey(): String?
    open fun onTick() {}
    open fun onWeather() {}
    /** what the apps publish, the pins or the visualizer changed */
    open fun onFeed() {}
    /** any key was pressed on the home screen */
    open fun onKey() {}
    /** Back: true when the style had something to close */
    open fun onBack(): Boolean = false
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

    /** a title that may be long and in any script: wraps to [lines] lines, then ends at a whole word */
    protected fun titleLabel(size: Float, lines: Int, color: Int = Color.WHITE, face: String = "sans-serif-medium") =
        TitleView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
            setTextColor(color)
            typeface = Typeface.create(face, Typeface.NORMAL)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

    protected fun matchParent() = FrameLayout.LayoutParams(-1, -1)

    /** a pill button; the primary one is white, the others glass. The focus ring is the theme's accent colour. */
    protected fun actionButton(primary: Boolean): TextView = TextView(a).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12f).toFloat())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.rgb(6, 18, 26) else Color.WHITE)
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(u(18), u(8), u(18), u(8))
        isFocusable = true
        isClickable = true
        val normal = rounded(if (primary) Color.WHITE else Color.argb(120, 24, 26, 36), u(18),
            if (primary) 0 else u(1), Color.argb(80, 255, 255, 255))
        val focus = rounded(if (primary) Color.WHITE else Color.argb(200, 44, 48, 64), u(18), u(3), a.accent)
        background = normal
        setOnFocusChangeListener { v, has ->
            v.background = if (has) focus else normal
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
        }
    }

    /** an app as icon with its name underneath (the name is always shown); OK opens, hold OK = options */
    protected fun appCell(e: AppEntry, width: Int): View {
        val icon = ImageView(a).apply { setImageDrawable(e.icon) }
        val name = titleLabel(10.5f, 2, Color.argb(235, 255, 255, 255)).apply {
            setTitle(e.label)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val cell = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            tag = e.key
            setPadding(u(6), u(9), u(6), u(7))
        }
        cell.addView(icon, LinearLayout.LayoutParams(u(38), u(38)))
        cell.addView(name, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, u(6), 0, 0) })
        cell.layoutParams = LinearLayout.LayoutParams(width, -2)
        cell.setOnClickListener { a.openApp(e) }
        cell.setOnLongClickListener { a.showAppMenu(e); true }
        cell.setOnFocusChangeListener { v, has ->
            v.background = if (has) rounded(Color.argb(70, 255, 255, 255), u(12), u(3), a.accent) else null
        }
        return cell
    }

    /** is [v] this view or inside it? */
    protected fun View.holds(v: View?): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === this) return true
            p = p.parent as? View
        }
        return false
    }
}

/** A text view for titles: when the text does not fit its lines, it ends after a whole word ("Long title…"). */
internal class TitleView(ctx: Context) : TextView(ctx) {
    private var full = ""
    private var fitting = false

    fun setTitle(t: String) {
        full = t
        text = t
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (fitting || full.isEmpty()) return
        val l = layout ?: return
        val last = l.lineCount - 1
        if (last < 0 || l.getEllipsisCount(last) <= 0) return
        val shown = text.toString()
        val cut = HomeLogic.cutAtWord(shown.removeSuffix("…"), l.getLineStart(last) + l.getEllipsisStart(last))
        if (cut == shown) return
        fitting = true
        text = cut
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        fitting = false
    }
}

/**
 * Lanes of cards, one under the other: what the apps publish. LEFT / RIGHT walk a lane, UP / DOWN change lanes;
 * the focused lane slides to the top and the next one peeks in below it.
 * Cards are only built, and their pictures only loaded, for the lanes on screen; the others are released again.
 */
internal class LaneList(private val a: MainActivity, cardU: Float, sideU: Float, private val host: Host) {

    interface Host {
        fun onItemFocus(item: TvFeed.Item) {}
        /** UP was pressed in the first lane */
        fun onLaneExitUp() {}
        /** LEFT was pressed on the first card of a lane */
        fun onLaneExitLeft() {}
    }

    val view = ScrollView(a)
    private val col = LinearLayout(a)
    private val rows = ArrayList<Row>()
    private var sig = ""
    private val cardH = a.u(cardU)
    private val side = a.u(sideU)
    private val gap = a.u(5)
    private val pad = a.u(8)

    /** the lane that holds (or last held) the focus */
    var current = 0
        private set
    val count: Int get() = rows.size
    /** height of one lane with its title */
    val laneHeight: Int get() = a.u(18) + cardH + pad * 2

    init {
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isFocusable = false
        view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        col.orientation = LinearLayout.VERTICAL
        view.addView(col, ViewGroup.LayoutParams(-1, -2))
    }

    private inner class Card(val item: TvFeed.Item, val row: Row, val pos: Int) {
        val box = FrameLayout(a)
        val img = ImageView(a)
        /** 0 no picture, 1 loading, 2 shown */
        var state = 0
    }

    private inner class Row(val lane: HomeLogic.Lane, val index: Int) {
        val box = LinearLayout(a)
        val hsv = HorizontalScrollView(a)
        val strip = LinearLayout(a)
        val cards = ArrayList<Card>()
        var built = false
        var last = 0

        init {
            box.orientation = LinearLayout.VERTICAL
            box.addView(TextView(a).apply {
                text = lane.title.uppercase(java.util.Locale.getDefault())
                setTextSize(TypedValue.COMPLEX_UNIT_PX, a.u(9.5f).toFloat())
                setTextColor(Color.argb(205, 255, 255, 255))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.22f
                includeFontPadding = false
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
                setPadding(side, 0, side, 0)
            }, LinearLayout.LayoutParams(-1, a.u(18)))
            hsv.isHorizontalScrollBarEnabled = false
            hsv.overScrollMode = View.OVER_SCROLL_NEVER
            hsv.clipChildren = false
            hsv.clipToPadding = false
            strip.orientation = LinearLayout.HORIZONTAL
            strip.clipChildren = false
            strip.clipToPadding = false
            strip.setPadding(side - gap, pad, side, pad)
            hsv.addView(strip, ViewGroup.LayoutParams(-2, -1))
            box.addView(hsv, LinearLayout.LayoutParams(-1, cardH + pad * 2))
        }

        fun build() {
            if (built) return
            built = true
            for ((i, item) in lane.items.withIndex()) {
                val c = card(item, this, i)
                cards.add(c)
                val w = if (item.square) cardH else cardH * 16 / 9
                strip.addView(c.box, LinearLayout.LayoutParams(w, cardH).apply { setMargins(gap, 0, gap, 0) })
            }
        }

        /** pictures for the cards up to [upTo] */
        fun load(upTo: Int) {
            build()
            for (i in 0..minOf(upTo, cards.size - 1)) {
                val c = cards[i]
                if (c.state != 0) continue
                c.state = 1
                val w = if (c.item.square) cardH else cardH * 16 / 9
                Images.load(a, c.item.art, w) { bmp -> if (c.state == 1 && bmp != null) show(c, bmp, w) else if (c.state == 1) c.state = 0 }
            }
        }

        fun unload() {
            for (c in cards) {
                if (c.state != 0) { c.img.setImageDrawable(null); c.state = 0 }
            }
        }
    }

    private fun show(c: Card, bmp: Bitmap, w: Int) {
        c.state = 2
        if (!c.item.square && bmp.height > bmp.width * 1.15f) {
            // an upright poster in a wide card: keep its top (faces, the title), not its middle
            val s = w.toFloat() / bmp.width
            c.img.scaleType = ImageView.ScaleType.MATRIX
            c.img.imageMatrix = Matrix().apply { setScale(s, s) }
        } else {
            c.img.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        c.img.setImageBitmap(bmp)
    }

    private fun ring(focused: Boolean, r: Int) = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = r.toFloat()
        if (focused) setStroke(a.u(3), a.accent) else setStroke(maxOf(1, a.u(1)), Color.argb(46, 255, 255, 255))
    }

    private fun card(item: TvFeed.Item, row: Row, pos: Int): Card {
        val c = Card(item, row, pos)
        val r = a.u(10)
        val f = c.box
        f.background = GradientDrawable().apply { setColor(Color.argb(150, 30, 32, 44)); cornerRadius = r.toFloat() }
        f.foreground = ring(false, r)
        f.clipToOutline = true
        f.isFocusable = true
        f.isClickable = true
        f.addView(c.img, FrameLayout.LayoutParams(-1, -1))
        // the dark band the title sits on
        f.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        }, FrameLayout.LayoutParams(-1, cardH * 62 / 100, Gravity.BOTTOM))
        val hasBar = item.progress >= 0f
        f.addView(TitleView(a).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.u(10.5f).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTitle(item.title)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(a.u(8), 0, a.u(8), if (hasBar) a.u(11) else a.u(6)) })
        if (hasBar) {
            val bar = FrameLayout(a).apply { setBackgroundColor(Color.argb(80, 255, 255, 255)) }
            val w = (if (item.square) cardH else cardH * 16 / 9) - a.u(16)
            bar.addView(View(a).apply { setBackgroundColor(Color.WHITE) },
                FrameLayout.LayoutParams((w * item.progress).toInt().coerceAtLeast(2), -1))
            f.addView(bar, FrameLayout.LayoutParams(-1, maxOf(2, a.u(2)), Gravity.BOTTOM).apply { setMargins(a.u(8), 0, a.u(8), a.u(6)) })
        }
        if (row.lane.crossApp) {
            // which app it comes from
            a.appFor(item.pkg)?.let { e ->
                f.addView(ImageView(a).apply {
                    setImageDrawable(e.icon.constantState?.newDrawable(a.resources)?.mutate() ?: e.icon)
                    background = GradientDrawable().apply { setColor(Color.argb(150, 0, 0, 0)); cornerRadius = a.u(6).toFloat() }
                    setPadding(a.u(2), a.u(2), a.u(2), a.u(2))
                }, FrameLayout.LayoutParams(a.u(22), a.u(22), Gravity.TOP or Gravity.END).apply { setMargins(0, a.u(6), a.u(6), 0) })
            }
        }
        f.setOnClickListener { a.openItem(item, null) }
        f.setOnLongClickListener { a.showItemMenu(item); true }
        f.setOnFocusChangeListener { v, has ->
            v.foreground = ring(has, r)
            v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(130).start()
            if (has) focused(c)
        }
        f.setOnKeyListener { _, k, ev ->
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (ev.action == KeyEvent.ACTION_DOWN) { if (pos == 0) host.onLaneExitLeft() else row.cards[pos - 1].box.requestFocus() }; true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (ev.action == KeyEvent.ACTION_DOWN && pos < row.cards.size - 1) row.cards[pos + 1].box.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (ev.action == KeyEvent.ACTION_DOWN) { if (row.index == 0) host.onLaneExitUp() else focus(row.index - 1) }; true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (ev.action == KeyEvent.ACTION_DOWN && row.index < rows.size - 1) focus(row.index + 1); true }
                else -> false
            }
        }
        return c
    }

    private fun focused(c: Card) {
        val row = c.row
        current = row.index
        row.last = c.pos
        window(row.index)
        row.load(c.pos + 7)
        view.post {
            row.hsv.smoothScrollTo((c.box.left - side).coerceAtLeast(0), 0)
            view.smoothScrollTo(0, row.box.top)
        }
        host.onItemFocus(c.item)
    }

    /** lanes around lane [k] are kept ready, the rest give their pictures back */
    private fun window(k: Int) {
        for (r in rows) {
            if (r.index in (k - 1)..(k + 2)) r.load(maxOf(r.last + 7, 7)) else r.unload()
        }
    }

    /** Shows these lanes. Returns false when they are what is already shown. Keeps the focus in the same lane. */
    fun setLanes(lanes: List<HomeLogic.Lane>): Boolean {
        val s = lanes.joinToString("\n") { it.signature() }
        if (s == sig) return false
        sig = s
        val had = view.hasFocus()
        val keepLane = current
        val keepPos = rows.getOrNull(current)?.last ?: 0
        col.removeAllViews()
        rows.clear()
        for ((i, l) in lanes.withIndex()) {
            val r = Row(l, i)
            rows.add(r)
            col.addView(r.box, LinearLayout.LayoutParams(-1, -2))
        }
        // room below the last lane, so that it can slide to the top as well
        col.setPadding(0, 0, 0, laneHeight * 3)
        current = keepLane.coerceIn(0, maxOf(0, rows.size - 1))
        rows.getOrNull(current)?.last = keepPos
        view.scrollTo(0, 0)
        window(current)
        if (had) { if (rows.isEmpty()) host.onLaneExitUp() else focus(current) }
        else if (rows.isNotEmpty()) view.post { view.scrollTo(0, rows[current].box.top) }
        return true
    }

    /** moves the focus to lane [index] (to the card last used there). False when there is no such lane. */
    fun focus(index: Int = current): Boolean {
        val row = rows.getOrNull(index) ?: return false
        row.build()
        val c = row.cards.getOrNull(row.last.coerceIn(0, maxOf(0, row.cards.size - 1))) ?: return false
        c.box.requestFocus()
        return true
    }

    fun firstItem(): TvFeed.Item? = rows.firstOrNull()?.lane?.items?.firstOrNull()

    fun release() {
        for (r in rows) r.unload()
    }
}
