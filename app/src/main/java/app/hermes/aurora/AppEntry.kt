package app.hermes.aurora

import android.content.Intent
import android.graphics.drawable.Drawable

/** One launchable app. Shared by the Classic screen and the Cinema / Horizon / Command styles. */
internal class AppEntry(
    val key: String,
    val pkg: String,
    val label: String,
    val intent: Intent,
    val banner: Drawable?,
    val icon: Drawable,
    val system: Boolean
)
