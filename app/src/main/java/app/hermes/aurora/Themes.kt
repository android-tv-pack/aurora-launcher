package app.hermes.aurora

import android.graphics.Color

/** Colour themes: three drifting glow colours + the accent used for focus rings and titles. */
object Themes {
    class Theme(val name: String, val colors: IntArray, val accent: Int)

    val ALL: List<Theme> = listOf(
        Theme("Aurora", intArrayOf(Color.rgb(0, 229, 255), Color.rgb(124, 77, 255), Color.rgb(255, 64, 129)), Color.rgb(0, 229, 255)),
        Theme("Ocean", intArrayOf(Color.rgb(0, 122, 255), Color.rgb(0, 229, 200), Color.rgb(63, 81, 181)), Color.rgb(64, 196, 255)),
        Theme("Sunset", intArrayOf(Color.rgb(255, 112, 67), Color.rgb(255, 64, 129), Color.rgb(156, 39, 176)), Color.rgb(255, 171, 64)),
        Theme("Emerald", intArrayOf(Color.rgb(0, 200, 83), Color.rgb(0, 229, 255), Color.rgb(29, 233, 182)), Color.rgb(105, 240, 174)),
        Theme("Mono", intArrayOf(Color.rgb(120, 144, 156), Color.rgb(96, 125, 139), Color.rgb(69, 90, 100)), Color.rgb(224, 224, 224))
    )
}
