package app.hermes.aurora

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Aurora's own dialog, in the look of Customize: a dark card in the middle of the screen with a title, a few
 * words of explanation, and then a text field, a list of choices and buttons, whichever are asked for.
 * Back closes it without doing anything.
 */
internal class Sheet(private val a: MainActivity, private val title: String, private val message: String? = null) {

    private class Item(val label: String, val value: (() -> String)?, val danger: Boolean, val close: Boolean, val act: () -> Unit)
    private class Button(val label: String, val primary: Boolean, val act: () -> Unit)

    private val items = ArrayList<Item>()
    private val buttons = ArrayList<Button>()
    private var field: EditText? = null

    /** a choice; [value] is shown at its right and read again after OK when the sheet stays open ([close] = false) */
    fun item(label: String, value: (() -> String)? = null, danger: Boolean = false, close: Boolean = true, act: () -> Unit): Sheet {
        items += Item(label, value, danger, close, act); return this
    }

    /** a button at the bottom; the [primary] one is also what "Done" on the keyboard presses */
    fun button(label: String, primary: Boolean = false, act: () -> Unit = {}): Sheet {
        buttons += Button(label, primary, act); return this
    }

    /** a text field; read its text in a button's action */
    fun input(text: String, hint: String, type: Int): EditText {
        val e = EditText(a).apply {
            setText(text)
            this.hint = hint
            inputType = type
            setSingleLine(true)
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextSize(TypedValue.COMPLEX_UNIT_PX, a.u(14).toFloat())
            setTextColor(Color.WHITE)
            setHintTextColor(white(90))
            setPadding(a.u(14), a.u(9), a.u(14), a.u(9))
        }
        field = e
        return e
    }

    private fun white(alpha: Int) = Color.argb(alpha, 255, 255, 255)
    private val ink = Color.rgb(8, 11, 22)

    private fun label(t: String, size: Float, color: Int, medium: Boolean = false) = TextView(a).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_PX, a.u(size).toFloat())
        setTextColor(color)
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun show(onDismiss: () -> Unit) {
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
        val accent = a.accent
        val root = FrameLayout(a).apply { setBackgroundColor(Color.argb(175, 0, 0, 0)) }
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = a.rounded(Color.rgb(16, 21, 40), a.u(18), a.u(1), white(34))
            setPadding(a.u(22), a.u(20), a.u(22), a.u(18))
        }
        card.addView(label(title, 18f, Color.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        message?.let {
            card.addView(label(it, 12f, white(170)).apply { setLineSpacing(a.u(3).toFloat(), 1f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.u(8) })
        }

        var first: View? = null
        field?.let { e ->
            fun paint(has: Boolean) { e.background = a.rounded(white(if (has) 30 else 14), a.u(11), a.u(if (has) 2 else 1), if (has) accent else white(40)) }
            paint(false)
            e.setOnFocusChangeListener { _, has -> paint(has) }
            // the keyboard would cover the buttons: it comes up when OK is pressed on the field, not by itself
            e.showSoftInputOnFocus = false
            e.setOnClickListener {
                (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(e, 0)
            }
            e.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { buttons.firstOrNull { it.primary }?.let { b -> d.dismiss(); b.act() }; true } else false
            }
            card.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.u(14) })
            first = e
        }

        if (items.isNotEmpty()) {
            val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            for (it in items) {
                val tint = if (it.danger) Color.rgb(255, 138, 128) else Color.WHITE
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(a.u(15), a.u(9.5f), a.u(15), a.u(10))
                    background = a.rounded(white(10), a.u(11))
                }
                line.addView(label(it.label, 14f, tint).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                val value = label(it.value?.invoke().orEmpty(), 13f, white(190))
                line.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.u(12) })
                line.setOnFocusChangeListener { v, has ->
                    v.background = if (has) a.rounded(white(40), a.u(11), a.u(2), if (it.danger) tint else accent) else a.rounded(white(10), a.u(11))
                    value.setTextColor(if (has) accent else white(190))
                }
                line.setOnClickListener { _ ->
                    if (it.close) d.dismiss()
                    it.act()
                    if (!it.close) value.text = it.value?.invoke().orEmpty()
                }
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = a.u(5) })
                if (first == null) first = line
            }
            // a long list scrolls inside the card
            val scroll = object : ScrollView(a) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(a.u(300), MeasureSpec.AT_MOST))
            }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
            scroll.addView(list, ViewGroup.LayoutParams(-1, -2))
            card.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.u(14) })
        }

        if (buttons.isNotEmpty()) {
            val bar = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            for (b in buttons) {
                val t = label(b.label, 13f, Color.WHITE, true).apply {
                    isFocusable = true
                    isClickable = true
                    setPadding(a.u(17), a.u(7), a.u(17), a.u(7.5f))
                }
                fun paint(has: Boolean) {
                    t.setTextColor(if (has) ink else if (b.primary) accent else white(215))
                    t.background = when {
                        has -> a.rounded(accent, a.u(16))
                        b.primary -> a.rounded(Color.TRANSPARENT, a.u(16), a.u(1), accent)
                        else -> a.rounded(white(18), a.u(16))
                    }
                }
                paint(false)
                t.setOnFocusChangeListener { _, has -> paint(has) }
                t.setOnClickListener { d.dismiss(); b.act() }
                // Down from the text field goes to the button that saves
                if (b.primary) { t.id = View.generateViewId(); field?.nextFocusDownId = t.id }
                bar.addView(t, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = a.u(8) })
                if (first == null) first = t
            }
            card.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = a.u(if (items.isEmpty()) 16 else 10) })
        }

        root.addView(card, FrameLayout.LayoutParams(a.u(440), -2, Gravity.CENTER))
        d.setContentView(root, ViewGroup.LayoutParams(-1, -1))
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        d.setOnDismissListener { onDismiss() }
        d.show()
        first?.requestFocus()
    }
}
