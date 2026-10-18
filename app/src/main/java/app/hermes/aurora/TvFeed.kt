package app.hermes.aurora

import android.content.Context
import android.net.Uri

/**
 * "What to watch next" for each app, read from Android TV's TvProvider: the Watch Next row
 * ("continue watching") and the preview channels that apps publish to the home screen.
 * Needs the READ_TV_LISTINGS permission; only apps that actually publish content show up.
 */
object TvFeed {

    const val PERMISSION = "android.permission.READ_TV_LISTINGS"

    class Item(
        val pkg: String,
        val title: String,
        val desc: String,
        val art: String,
        val landscape: Boolean,
        /** album / playlist covers: 1:1 art, shown as a cover card instead of a full-screen backdrop */
        val square: Boolean,
        val kind: String,
        /** deep link into the app for this exact title (intent:// or a plain URI), may be null */
        val intentUri: String?,
        /** 0..1 how far the viewer got, or -1 when unknown */
        val progress: Float
    )

    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** package name -> items, best first. */
    fun load(ctx: Context): Map<String, List<Item>> {
        val out = LinkedHashMap<String, MutableList<Item>>()
        if (!allowed(ctx)) return out
        val seen = HashSet<String>()

        fun read(uri: String, order: String?, kindOf: (android.database.Cursor) -> String) {
            try {
                ctx.contentResolver.query(Uri.parse(uri), null, null, null, order)?.use { c ->
                    fun col(n: String) = c.getColumnIndex(n)
                    val iPkg = col("package_name")
                    val iTitle = col("title")
                    val iDesc = col("short_description")
                    val iPoster = col("poster_art_uri")
                    val iPosterR = col("poster_art_aspect_ratio")
                    val iThumb = col("thumbnail_uri")
                    val iThumbR = col("thumbnail_aspect_ratio")
                    val iIntent = col("intent_uri")
                    val iPos = col("last_playback_position_millis")
                    val iDur = col("duration_millis")
                    if (iPkg < 0 || iTitle < 0) return
                    while (c.moveToNext()) {
                        val pkg = c.getString(iPkg) ?: continue
                        val title = c.getString(iTitle)?.trim().orEmpty()
                        if (title.isEmpty() || !seen.add("$pkg|$title")) continue
                        val desc = if (iDesc >= 0) c.getString(iDesc)?.trim().orEmpty() else ""
                        val poster = if (iPoster >= 0) c.getString(iPoster) else null
                        val thumb = if (iThumb >= 0) c.getString(iThumb) else null
                        // aspect ratio codes: 0 = 16:9, 1 = 3:2, 2 = 4:3 (landscape); 3 = 1:1, 4+ = portrait
                        fun landscape(r: Int) = r in 0..2
                        val pr = if (iPosterR >= 0 && !c.isNull(iPosterR)) c.getInt(iPosterR) else 4
                        val tr = if (iThumbR >= 0 && !c.isNull(iThumbR)) c.getInt(iThumbR) else 0
                        // prefer a landscape picture: it can fill the screen
                        val squareArt = (!poster.isNullOrEmpty() && pr == 3) || (poster.isNullOrEmpty() && !thumb.isNullOrEmpty() && tr == 3)
                        val (art, land) = when {
                            squareArt && !poster.isNullOrEmpty() -> poster to false
                            squareArt -> thumb!! to false
                            !thumb.isNullOrEmpty() && landscape(tr) -> thumb to true
                            !poster.isNullOrEmpty() && landscape(pr) -> poster to true
                            !poster.isNullOrEmpty() -> poster to false
                            !thumb.isNullOrEmpty() -> thumb to landscape(tr)
                            else -> continue
                        }
                        val intentUri = if (iIntent >= 0) c.getString(iIntent) else null
                        val pos = if (iPos >= 0 && !c.isNull(iPos)) c.getLong(iPos) else 0L
                        val dur = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L
                        val progress = if (pos > 0 && dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else -1f
                        // music apps say what the item is in their deep link: .../playlist/ID, /album/ID, /artist/ID
                        val musicKind = intentUri?.let { Regex("/(playlist|album|artist|track|show|episode)/").find(it)?.groupValues?.get(1)?.uppercase() }
                        out.getOrPut(pkg) { ArrayList() }
                            .add(Item(pkg, title, desc, art, land, squareArt, musicKind ?: kindOf(c), intentUri, progress))
                    }
                }
            } catch (e: Exception) {
                // provider missing or access denied: simply no previews
            }
        }

        // 1) Watch Next: what the user was watching, most recent first
        read("content://android.media.tv/watch_next_program", "last_engagement_time_utc_millis DESC") { c ->
            val i = c.getColumnIndex("watch_next_type")
            when (if (i >= 0 && !c.isNull(i)) c.getInt(i) else 0) {
                0 -> "CONTINUE WATCHING"
                1 -> "UP NEXT"
                2 -> "NEW EPISODE"
                else -> "WATCHLIST"
            }
        }
        // 2) preview channels: the app's own recommendations, heaviest weight first
        read("content://android.media.tv/preview_program", "weight DESC") { "RECOMMENDED" }
        return out
    }
}
