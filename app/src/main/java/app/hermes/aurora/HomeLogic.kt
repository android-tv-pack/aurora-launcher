package app.hermes.aurora

/**
 * The parts of the home screen that are plain logic (no Android classes), so they run in JVM unit tests:
 * style migration, the lanes built from what apps publish, what the centre of Drift holds,
 * the night-dimming window, pinned titles and cutting long titles at a word.
 */
internal object HomeLogic {

    // ---------------------------------------------------------------- styles

    const val CINEMA = 1
    const val DRIFT = 2
    val STYLE_NAMES = arrayOf("Cinema", "Drift")

    /** A style stored by an older version (0 Classic, 1 Cinema, 2 Horizon, 3 Command) as one of today's two. */
    fun migrateStyle(old: Int): Int = if (old <= 1) CINEMA else DRIFT

    /**
     * A visualizer number stored before six of them were removed (0 Off, 1 Flow, 2 Orb, 3 Stardust, 4 Silk, 5 Pixels,
     * 6 Wave, 7 Desert, 8 Cyberpunk, 9 Tron Legacy, 10 Matrix, 11 Blade Runner, 12 Interstellar) as today's
     * (0 Off, 1 Flow, 2 Pixels, 3 Wave, 4 Desert, 5 Cyberpunk, 6 Blade Runner). A removed music visualizer becomes
     * Flow, a removed scene becomes Blade Runner; the screensaver's "rotate" (-1) stays.
     */
    fun migrateVisualizer(old: Int): Int = when (old) {
        -1 -> -1
        0 -> 0
        1, 2, 3, 4 -> 1
        5 -> 2
        6 -> 3
        7 -> 4
        8 -> 5
        9, 10, 11, 12 -> 6
        else -> 0
    }

    // ---------------------------------------------------------------- lanes

    class Lane(
        val title: String,
        val items: List<TvFeed.Item>,
        /** items of several apps: the cards carry their app's badge */
        val crossApp: Boolean
    ) {
        /** changes when the lane would look different */
        fun signature(): String = title + "#" + items.joinToString("|") { it.pkg + "/" + it.title + "/" + it.progress }
    }

    const val TITLE_RESUME = "Pick up where you left off · all apps"
    const val TITLE_SPARSE = "From your apps"
    const val TITLE_PINNED = "Pinned"

    /**
     * The lane across all apps: what the viewer was in the middle of comes first (newest engagement first),
     * then the apps take turns with their own suggestions so that no single app fills the lane.
     * [appOrder] is the order of the user's apps (package names); apps outside it are left out.
     */
    fun crossLane(feed: Map<String, List<TvFeed.Item>>, appOrder: List<String>, max: Int = 18, perApp: Int = 3): List<TvFeed.Item> {
        val pkgs = appOrder.distinct().filter { !feed[it].isNullOrEmpty() }
        val resume = pkgs.flatMap { p -> feed.getValue(p).filter { it.resume } }.sortedByDescending { it.engaged }
        val out = ArrayList<TvFeed.Item>(resume.take(max))
        val rest = pkgs.map { p -> feed.getValue(p).filter { !it.resume }.take(perApp) }
        var round = 0
        while (out.size < max) {
            var added = false
            for (l in rest) {
                if (round < l.size && out.size < max) { out.add(l[round]); added = true }
            }
            if (!added) break
            round++
        }
        return out
    }

    /** The lane's name says what is really in it: "pick up" only when something can be picked up. */
    fun crossTitle(items: List<TvFeed.Item>): String = if (items.any { it.resume }) TITLE_RESUME else TITLE_SPARSE

    /** Every lane of the home, top to bottom: across all apps, the pinned titles, then one lane per app. */
    fun lanes(
        feed: Map<String, List<TvFeed.Item>>, appOrder: List<String>, labelOf: (String) -> String?,
        pins: List<Pin> = emptyList(), perLane: Int = 12
    ): List<Lane> {
        val out = ArrayList<Lane>()
        val pkgs = appOrder.distinct().filter { !feed[it].isNullOrEmpty() && labelOf(it) != null }
        val cross = crossLane(feed, pkgs)
        if (cross.isNotEmpty()) out.add(Lane(crossTitle(cross), cross, true))
        val pinned = pins.filter { labelOf(it.pkg) != null }
        if (pinned.isNotEmpty()) out.add(Lane(TITLE_PINNED, pinned.map { it.toItem() }, true))
        // with a single publishing app the lane above already is that app's lane
        val only = if (pkgs.size == 1) feed.getValue(pkgs[0]).size else 0
        if (pkgs.size > 1 || cross.size < only) {
            for (p in pkgs) out.add(Lane(labelOf(p)!!, feed.getValue(p).take(perLane), false))
        }
        return out
    }

    /** The shelves of the music apps: per app, what it publishes with a square cover (playlists, albums, artists). */
    fun musicLanes(
        feed: Map<String, List<TvFeed.Item>>, appOrder: List<String>, perLane: Int = 12, labelOf: (String) -> String?
    ): List<Lane> = appOrder.distinct().mapNotNull { p ->
        val covers = feed[p].orEmpty().filter { it.square && !it.channel }
        val name = labelOf(p)
        if (covers.isEmpty() || name == null) null else Lane(name, covers.take(perLane), false)
    }

    // ---------------------------------------------------------------- default previews of newly installed apps

    /**
     * Android TV expects the home screen to tell an app once, after it is installed, to publish its default
     * previews. [able] are the installed apps that listen for that, [asked] those already told:
     * the apps to tell now, and the new list of told apps (an app that was removed is told again when it returns).
     */
    fun previewsToAsk(able: List<String>, asked: List<String>): Pair<List<String>, List<String>> {
        val now = able.distinct()
        return now.filter { it !in asked } to now
    }

    // ---------------------------------------------------------------- the centre of Drift

    /** The app that was opened last among the apps on the home ([keys], in the home's order); the first app when none was. */
    fun lastUsed(recents: List<String>, keys: List<String>): String? =
        recents.firstOrNull { it in keys } ?: keys.firstOrNull()

    /** the newest thing of the app [pkg] to continue, or null when that app has published none */
    fun resumeFor(feed: Map<String, List<TvFeed.Item>>, pkg: String): TvFeed.Item? =
        feed[pkg].orEmpty().filter { it.resume }.maxByOrNull { it.engaged }

    /** how long the buttons of something that was paused stay in the centre */
    const val PAUSED_KEEP_MS = 5 * 60_000L
    /** between two tracks an app reports neither "playing" nor "paused" for a moment */
    const val GONE_GRACE_MS = 1_500L

    /**
     * Whose playback the centre shows: [pkg] (null = none, the centre shows the last-used app), when that playback
     * was first seen paused and when it was first seen gone (0 = it is not).
     */
    class Held(val pkg: String? = null, val pausedAt: Long = 0L, val goneAt: Long = 0L)

    /**
     * The next state of the centre. [playing] and [paused] are the apps that play or are paused right now, in the
     * home's order; [was] is what the centre held until now.
     * Something that plays always takes the centre. Something paused only keeps it: its buttons stay when they were
     * already there (pausing from the home must not take the Play button away), for PAUSED_KEEP_MS. An app that was
     * paused before the home came up never takes the centre.
     */
    fun nextHeld(playing: List<String>, paused: List<String>, was: Held, now: Long): Held {
        val p = was.pkg
        return when {
            p != null && p in playing -> Held(p)
            playing.isNotEmpty() -> Held(playing.first())
            p == null -> Held()
            p in paused -> {
                val since = if (was.pausedAt == 0L) now else was.pausedAt
                if (now - since < PAUSED_KEEP_MS) Held(p, since) else Held()
            }
            else -> {
                val since = if (was.goneAt == 0L) now else was.goneAt
                if (now - since < GONE_GRACE_MS) Held(p, was.pausedAt, since) else Held()
            }
        }
    }

    /** "38 min left", "1 h 5 min left", or "" when the length is unknown */
    fun leftText(leftMs: Long): String {
        if (leftMs <= 0) return ""
        val min = ((leftMs + 59_999) / 60_000).toInt()
        return if (min < 60) "$min min left" else "${min / 60} h ${min % 60} min left"
    }

    // ---------------------------------------------------------------- night dimming

    const val NIGHT_OFF = 0
    const val NIGHT_AUTO = 1
    const val NIGHT_CUSTOM = 2
    const val AUTO_FROM = 23
    const val AUTO_UNTIL = 7

    /** Is [hour] (0..23) inside the window that starts at [from] and ends before [until]? The window may cross midnight. */
    fun inWindow(hour: Int, from: Int, until: Int): Boolean = when {
        from == until -> false
        from < until -> hour in from until until
        else -> hour >= from || hour < until
    }

    fun isNight(mode: Int, hour: Int, from: Int, until: Int): Boolean = when (mode) {
        NIGHT_AUTO -> inWindow(hour, AUTO_FROM, AUTO_UNTIL)
        NIGHT_CUSTOM -> inWindow(hour, from, until)
        else -> false
    }

    // ---------------------------------------------------------------- pinned titles

    class Pin(val pkg: String, val title: String, val art: String, val intentUri: String?, val square: Boolean) {
        val id: String get() = "$pkg|$title"
        fun toItem() = TvFeed.Item(pkg, title, "", art, !square, square, "PINNED", intentUri, -1f)
    }

    fun pinOf(i: TvFeed.Item) = Pin(i.pkg, i.title, i.art, i.intentUri, i.square)

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> b.append('\t'); 'n' -> b.append('\n'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    /** one pin per line, fields separated by tabs: package, title, picture, deep link, "1" for a square cover */
    fun encodePins(pins: List<Pin>): String = pins.joinToString("\n") { p ->
        listOf(p.pkg, p.title, p.art, p.intentUri ?: "", if (p.square) "1" else "0").joinToString("\t") { esc(it) }
    }

    fun decodePins(text: String): List<Pin> = text.split("\n").mapNotNull { line ->
        val f = line.split("\t").map { unesc(it) }
        if (f.size < 5 || f[0].isEmpty() || f[1].isEmpty()) null
        else Pin(f[0], f[1], f[2], f[3].ifEmpty { null }, f[4] == "1")
    }.distinctBy { it.id }

    fun isPinned(pins: List<Pin>, id: String) = pins.any { it.id == id }

    /** pins it when it is not pinned, unpins it when it is */
    fun togglePin(pins: List<Pin>, pin: Pin): List<Pin> =
        if (isPinned(pins, pin.id)) pins.filter { it.id != pin.id } else pins + pin

    // ---------------------------------------------------------------- titles

    /**
     * A title that does not fit is cut after its last whole word, never inside a word (unless one word alone is
     * too long) and never inside a character. [end] is how many characters fit.
     */
    fun cutAtWord(text: String, end: Int): String {
        if (end >= text.length) return text
        var e = end.coerceAtLeast(0)
        if (e > 0 && Character.isHighSurrogate(text[e - 1])) e--
        val head = text.substring(0, e)
        // the cut falls exactly between two words: keep the whole head
        val atBoundary = text[e].isWhitespace()
        val space = head.indexOfLast { it.isWhitespace() }
        val cut = if (atBoundary || space < head.length / 3) head else head.substring(0, space)
        return cut.trimEnd { it.isWhitespace() || it in ",.:;-–—·(" } + "…"
    }

    // ---------------------------------------------------------------- the device (Customize > System)

    /** "1.4 GB", "312 MB", "18 KB" */
    fun size(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1L shl 30).toDouble())
            b >= 1L shl 20 -> "${b shr 20} MB"
            else -> "${b shr 10} KB"
        }
    }

    /** "3 d 4 h", "5 h 12 min", "7 min" */
    fun uptime(ms: Long): String {
        val min = ms / 60_000
        return when {
            min >= 1440 -> "${min / 1440} d ${min % 1440 / 60} h"
            min >= 60 -> "${min / 60} h ${min % 60} min"
            else -> "$min min"
        }
    }

    /** one line on how the device is doing, from its free storage and whether Android reports low memory */
    fun health(freeBytes: Long, totalBytes: Long, lowMemory: Boolean): String = when {
        totalBytes > 0 && (freeBytes < totalBytes / 20 || freeBytes < 300L shl 20) -> "Storage is almost full"
        lowMemory -> "Memory is low"
        totalBytes > 0 && freeBytes < totalBytes / 10 -> "Storage is getting full"
        else -> "Good"
    }
}
