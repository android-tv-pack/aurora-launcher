package app.hermes.aurora

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider

/**
 * A small live picture of the home screen, for Customize > Look: it draws the views of [root] (all but [skip])
 * scaled down to its own width, a few times a second, so a change of style, theme or icons is seen at once.
 */
internal class MirrorView(ctx: Context, private val root: ViewGroup, private val skip: () -> List<View?>) : View(ctx) {

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, v.height * 0.07f) }
        }
        clipToOutline = true
    }

    override fun onDraw(c: Canvas) {
        if (root.width == 0 || width == 0) return
        val mark = c.save()
        try {
            val s = width.toFloat() / root.width
            c.scale(s, s)
            val no = skip()
            for (i in 0 until root.childCount) {
                val ch = root.getChildAt(i)
                if (ch in no || ch.visibility != VISIBLE) continue
                ch.draw(c)
            }
        } catch (_: Exception) {
        } finally {
            c.restoreToCount(mark)
        }
        if (isAttachedToWindow) postInvalidateDelayed(150)
    }
}
