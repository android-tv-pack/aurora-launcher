package app.hermes.aurora

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Recently watched TV channels on the home (optional, off by default).
 *
 * IPTV apps publish nothing to the TV home and say nothing about what they play. They do show the channel's name
 * on screen when a channel is chosen, and that is what this uses: while the IPTV app the user chose is in front,
 * Aurora's accessibility service looks at the text on its screen, and a text that is the name of exactly one
 * channel of the programme guide, staying for half a minute, is noted as watched. The last five are shown under
 * that app with the channel's logo and what is on now and next, both from the guide (XMLTV) whose link the user
 * gives. Pressing one opens the app (such apps take no link to a single channel).
 *
 * Nothing here knows a particular app or provider. Only the chosen app's screen is looked at, only for channel
 * names, and nothing leaves the device.
 */
internal object Iptv {

    // ---------------------------------------------------------------- plain logic (unit-tested)

    class Prog(val start: Long, val stop: Long, val title: String)
    /** a channel of the guide: its id, its logo and the names it is known by */
    class Chan(val id: String, val icon: String, val names: List<String>)
    /** a channel that was watched: the guide's id and the name the app showed */
    class Recent(val id: String, val name: String)

    /** XMLTV time "20261107235500 +0300" as milliseconds, 0 when it is not one */
    fun xmltvTime(s: String): Long {
        val t = s.trim()
        if (t.length < 14) return 0
        if (!t.substring(0, 14).all { it.isDigit() }) return 0
        fun n(a: Int, b: Int): Int = t.substring(a, b).toInt()
        val y = n(0, 4); val mo = n(4, 6); val d = n(6, 8); val h = n(8, 10); val mi = n(10, 12); val se = n(12, 14)
        var off = 0
        val z = t.substring(14).trim()
        if (z.length >= 5 && (z[0] == '+' || z[0] == '-')) {
            val hh = z.substring(1, 3).toIntOrNull() ?: 0
            val mm = z.substring(3, 5).toIntOrNull() ?: 0
            off = (hh * 60 + mm) * (if (z[0] == '-') -1 else 1)
        }
        // days since 1970-01-01 (civil-from-days, no calendar object: this runs for every programme of the guide)
        val yy = if (mo <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (mo + (if (mo > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146097L + doe - 719468L
        return ((days * 86400L + h * 3600L + mi * 60L + se) - off * 60L) * 1000L
    }

    fun unescape(s: String): String =
        if (s.indexOf('&') < 0) s else s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    private val titleTag = Regex("""<title[^>]*>([^<]*)</title>""")
    private val displayName = Regex("""<display-name[^>]*>([^<]*)</display-name>""")
    private val iconTag = Regex("""<icon[^>]*\ssrc="([^"]*)"""")

    /** value of attribute [name] in an opening tag */
    fun attrOf(tag: String, name: String): String {
        val i = tag.indexOf("$name=\"")
        if (i < 0) return ""
        val from = i + name.length + 2
        val to = tag.indexOf('"', from)
        return if (to < 0) "" else tag.substring(from, to)
    }

    /** a "<programme ...>...</programme>" block of a guide as (channel id, programme), or null */
    fun progOf(block: String): Pair<String, Prog>? {
        val end = block.indexOf('>')
        if (end < 0) return null
        val head = block.substring(0, end)
        val ch = attrOf(head, "channel")
        val start = xmltvTime(attrOf(head, "start"))
        val stop = xmltvTime(attrOf(head, "stop"))
        val title = titleTag.find(block)?.groupValues?.get(1)?.trim().orEmpty()
        if (ch.isEmpty() || start == 0L || stop <= start || title.isEmpty()) return null
        return ch to Prog(start, stop, unescape(title))
    }

    /** a "<channel ...>...</channel>" block of a guide, or null */
    fun chanOf(block: String): Chan? {
        val end = block.indexOf('>')
        if (end < 0) return null
        val id = attrOf(block.substring(0, end), "id")
        if (id.isEmpty()) return null
        val names = displayName.findAll(block).map { unescape(it.groupValues[1].trim()) }.filter { it.isNotEmpty() }.toList()
        return Chan(id, iconTag.find(block)?.groupValues?.get(1).orEmpty(), names)
    }

    /** what is on at [now] and what follows */
    fun nowNext(list: List<Prog>?, now: Long): Pair<Prog?, Prog?> {
        if (list == null) return null to null
        val cur = list.firstOrNull { it.start <= now && now < it.stop }
        val next = list.filter { it.start >= (cur?.stop ?: now) }.minByOrNull { it.start }
        return cur to next
    }

    /** "Now: Film (1976), until 21:30  ·  Next: News" */
    fun line(cur: Prog?, next: Prog?, clock: (Long) -> String): String = listOfNotNull(
        cur?.let { "Now: " + it.title + ", until " + clock(it.stop) },
        next?.let { "Next: " + it.title + (if (cur == null) ", " + clock(it.start) else "") }
    ).joinToString("  ·  ")

    /** for comparing a text on screen with the guide's channel names; "" for what can't be a name (numbers, clock times) */
    fun nameKey(text: String): String {
        val t = text.trim()
        if (t.length < 2 || t.length > 60 || t.none { it.isLetter() }) return ""
        return Tmdb.norm(t)
    }

    /**
     * The channels whose names are on screen: (id, the text as shown). One name on screen is the channel that
     * plays (the app's info bar); several are a channel list, which says nothing about what plays.
     */
    fun onScreen(texts: List<String>, idOf: (String) -> String?): List<Recent> {
        val out = LinkedHashMap<String, Recent>()
        for (t in texts) {
            val k = nameKey(t)
            if (k.isEmpty()) continue
            val id = idOf(k) ?: continue
            out.putIfAbsent(id, Recent(id, t.trim()))
        }
        return out.values.toList()
    }

    /** how long a channel must stay on before it counts as watched (zapping through channels does not) */
    const val WATCHED_MS = 30_000L
    const val KEEP = 5

    /** Notes what the screen showed, look by look, and says when a channel has been watched. */
    class Watch {
        private var cand: Recent? = null
        private var since = 0L
        private var told = false

        /** [seen] = the channels named on screen at [now] (see onScreen); returns a channel once, when it counts as watched */
        fun look(seen: List<Recent>, now: Long): Recent? {
            if (seen.size == 1 && seen[0].id != cand?.id) { cand = seen[0]; since = now; told = false }
            val c = cand
            if (c != null && !told && now - since >= WATCHED_MS) { told = true; return c }
            return null
        }

        /** the app left the screen */
        fun reset() { cand = null; told = false }
    }

    /** the list of watched channels with [r] first, each channel once, at most [KEEP] */
    fun pushed(list: List<Recent>, r: Recent): List<Recent> = (listOf(r) + list.filter { it.id != r.id }).take(KEEP)

    fun encode(list: List<Recent>): List<String> = list.map { it.id + "\t" + it.name.replace('\t', ' ').replace('\n', ' ') }
    fun decode(lines: List<String>): List<Recent> = lines.mapNotNull { l ->
        val i = l.indexOf('\t')
        if (i <= 0 || i == l.length - 1) null else Recent(l.substring(0, i), l.substring(i + 1))
    }

    /**
     * Walks a guide (XMLTV) of any size without holding it: every "<channel ...>...</channel>" block is given to
     * [onChannel]; of the programmes only those whose opening tag [wanted] accepts are read to their end and
     * given to [onProgramme].
     */
    fun scanGuide(input: InputStream, wanted: (String) -> Boolean, onChannel: (String) -> Unit, onProgramme: (String) -> Unit) {
        val buf = ByteArray(1 shl 16)
        val cap = ByteArray(1 shl 15)
        var capLen = 0
        var state = 0           // 0 look for '<', 1 tag name, 2 programme's opening tag, 3 block to its end
        var endPat = ByteArray(0)
        var endAt = 0
        var isProg = false
        val nameBuf = ByteArray(12)
        var nameLen = 0
        val pEnd = "</programme>".toByteArray()
        val cEnd = "</channel>".toByteArray()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            var i = 0
            while (i < n) {
                val b = buf[i]
                when (state) {
                    0 -> {
                        // the fast path: most of a guide is skipped here
                        while (i < n && buf[i] != '<'.code.toByte()) i++
                        if (i < n) { state = 1; nameLen = 0 }
                    }
                    1 -> {
                        val c = b.toInt()
                        if (c == ' '.code || c == '>'.code || c == '\t'.code || c == '\n'.code || c == '\r'.code) {
                            val name = String(nameBuf, 0, nameLen, Charsets.US_ASCII)
                            if (name == "programme" || name == "channel") {
                                isProg = name == "programme"
                                cap[0] = '<'.code.toByte()
                                System.arraycopy(nameBuf, 0, cap, 1, nameLen)
                                capLen = nameLen + 1
                                cap[capLen++] = b
                                endPat = if (isProg) pEnd else cEnd
                                endAt = 0
                                state = if (isProg && c != '>'.code) 2 else 3
                            } else state = 0
                        } else if (c == '<'.code) { nameLen = 0 }
                        else if (nameLen < nameBuf.size) nameBuf[nameLen++] = b
                        else state = 0
                    }
                    2 -> {
                        if (capLen < cap.size) cap[capLen++] = b else state = 0
                        if (state == 2 && b == '>'.code.toByte()) {
                            state = if (wanted(String(cap, 0, capLen, Charsets.US_ASCII))) 3 else 0
                        }
                    }
                    else -> {
                        if (capLen < cap.size) cap[capLen++] = b else { state = 0; i++; continue }
                        endAt = if (b == endPat[endAt]) endAt + 1 else if (b == endPat[0]) 1 else 0
                        if (endAt == endPat.size) {
                            val s = String(cap, 0, capLen, Charsets.UTF_8)
                            if (isProg) onProgramme(s) else onChannel(s)
                            state = 0
                        }
                    }
                }
                i++
            }
        }
    }

    // ---------------------------------------------------------------- the service

    private const val GUIDE_MS = 12L * 3600 * 1000
    private const val WINDOW_MS = 30L * 3600 * 1000
    /** Android TV's program type "channel" */
    const val TYPE_CHANNEL = 6
    const val KIND = "TV CHANNEL"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** the guide's channels: name key -> id, id -> logo */
    @Volatile private var nameToId: Map<String, String> = emptyMap()
    @Volatile private var icons: Map<String, String> = emptyMap()
    /** the programmes of the watched channels only (the whole guide stays in a file) */
    @Volatile private var progs: Map<String, List<Prog>> = emptyMap()
    @Volatile private var progsFor = ""
    @Volatile private var guideAt = 0L
    @Volatile private var busy = false
    @Volatile private var indexed = false
    @Volatile var error = ""
        private set

    fun on(store: Store) = store.iptv && store.iptvGuide.isNotEmpty()

    /** when the guide was read last (0 = not yet) and how many of its channels have a logo, for Customize */
    val guideTime: Long get() = guideAt
    val guideChannels: Int get() = icons.size

    fun label(store: Store): String = when {
        !on(store) -> "Off"
        busy && nameToId.isEmpty() -> "Loading the guide"
        nameToId.isNotEmpty() -> "On" + (if (busy) "  ·  updating the guide" else "")
        error.isNotEmpty() -> error
        else -> "On"
    }

    /** an `iptv-guide.txt` dropped into the app's files folder: the link of the programme guide (XMLTV) */
    fun takeDropped(ctx: Context, store: Store): Boolean {
        val f = File(ctx.getExternalFilesDir(null) ?: return false, "iptv-guide.txt")
        if (!f.exists()) return false
        val url = try { f.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() } } catch (_: Exception) { null }
        f.delete()
        if (url.isNullOrEmpty()) return false
        store.iptvGuide = url
        store.iptv = true
        forget(ctx)
        return true
    }

    private fun namesFile(ctx: Context) = File(ctx.filesDir, "tv-channels.tsv")
    private fun guideFile(ctx: Context) = File(ctx.filesDir, "tv-guide.tsv")

    fun forget(ctx: Context) {
        nameToId = emptyMap(); icons = emptyMap(); progs = emptyMap(); progsFor = ""; guideAt = 0; indexed = true; error = ""
        namesFile(ctx).delete(); guideFile(ctx).delete()
        // files of the earlier playlist-based version
        File(ctx.filesDir, "iptv-channels.json").delete(); File(ctx.filesDir, "iptv-guide.json").delete()
    }

    private fun http(url: String): InputStream {
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Aurora")
            val code = conn.responseCode
            // a redirect from http to https (or back) is not followed by itself
            if (code in 301..308 && hops++ < 4) {
                val to = conn.getHeaderField("Location") ?: throw java.io.IOException("HTTP $code")
                val next = URL(conn.url, to)
                conn.disconnect()
                conn = next.openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw java.io.IOException("HTTP $code")
            return conn.inputStream
        }
    }

    /** the guide's channels from the file: first line the time it was read, then "id, logo, names" per line */
    private fun loadIndex(ctx: Context) {
        if (indexed) return
        indexed = true
        try {
            val n = HashMap<String, String>()
            val ic = HashMap<String, String>()
            namesFile(ctx).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val f = l.split('\t')
                    if (f[0] == "#" && f.size > 1) { guideAt = f[1].toLongOrNull() ?: 0; continue }
                    if (f.size < 3) continue
                    if (f[1].isNotEmpty()) ic[f[0]] = f[1]
                    for (i in 2 until f.size) nameKey(f[i]).let { if (it.isNotEmpty()) n.putIfAbsent(it, f[0]) }
                }
            }
            nameToId = n; icons = ic
        } catch (_: Exception) {}
    }

    /** the programmes of [ids] from the guide file */
    private fun loadProgs(ctx: Context, ids: Set<String>) {
        val out = HashMap<String, ArrayList<Prog>>()
        try {
            guideFile(ctx).bufferedReader().useLines { lines ->
                for (l in lines) {
                    val a = l.indexOf('\t')
                    if (a <= 0 || l.substring(0, a) !in ids) continue
                    val f = l.split('\t', limit = 4)
                    if (f.size == 4) out.getOrPut(f[0]) { ArrayList() }.add(Prog(f[1].toLongOrNull() ?: continue, f[2].toLongOrNull() ?: continue, f[3]))
                }
            }
        } catch (_: Exception) {}
        for (l in out.values) l.sortBy { it.start }
        progs = out
        progsFor = ids.sorted().joinToString(",")
    }

    /** the id of the guide's channel that is called [key] (see nameKey), for the screen watcher */
    fun idOf(ctx: Context, key: String): String? {
        if (!indexed) loadIndex(ctx.applicationContext)
        return nameToId[key]
    }

    /**
     * Reads the guide when it is due (twice a day) into two files, then the programmes of the watched channels
     * into memory. [changed] is called on the main thread when there is something new to show. One at a time,
     * off the main thread.
     */
    fun refresh(ctx: Context, store: Store, force: Boolean, changed: () -> Unit) {
        if (!on(store) || busy) return
        busy = true
        val app = ctx.applicationContext
        val url = store.iptvGuide
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                loadIndex(app)
                val now = System.currentTimeMillis()
                if (force || nameToId.isEmpty() || !guideFile(app).exists() || now - guideAt > GUIDE_MS) {
                    val tmpN = File(app.filesDir, "tv-channels.tmp")
                    val tmpG = File(app.filesDir, "tv-guide.tmp")
                    try {
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        var chans = 0
                        var count = 0
                        val until = now + WINDOW_MS
                        val raw = http(url)
                        val input = if (url.substringBefore('?').endsWith(".gz", true)) GZIPInputStream(raw, 1 shl 16) else raw
                        tmpN.bufferedWriter().use { wn ->
                            wn.write("#\t$now\n")
                            tmpG.bufferedWriter().use { wg ->
                                input.use { s ->
                                    scanGuide(s, { true }, { block ->
                                        chanOf(block)?.let { c ->
                                            wn.write(c.id + "\t" + c.icon + "\t" + c.names.joinToString("\t") { it.replace('\t', ' ') } + "\n"); chans++
                                        }
                                    }, { block ->
                                        progOf(block)?.let { (id, p) ->
                                            if (p.stop > now && p.start < until) { wg.write(id + "\t" + p.start + "\t" + p.stop + "\t" + p.title.replace('\t', ' ').replace('\n', ' ') + "\n"); count++ }
                                        }
                                    })
                                }
                            }
                        }
                        if (chans == 0) throw java.io.IOException("no channels in the guide")
                        tmpN.renameTo(namesFile(app)); tmpG.renameTo(guideFile(app))
                        indexed = false; loadIndex(app); progsFor = ""; error = ""
                        android.util.Log.i("AuroraTv", "guide: $chans channels, $count programmes, ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000}s")
                    } catch (e: Exception) {
                        tmpN.delete(); tmpG.delete()
                        if (nameToId.isEmpty()) error = "Could not load the guide"
                        android.util.Log.w("AuroraTv", "guide failed: ${e.javaClass.simpleName}")
                    }
                }
                val ids = decode(store.iptvRecent).map { it.id }.toSet()
                if (ids.sorted().joinToString(",") != progsFor) loadProgs(app, ids)
            } finally {
                busy = false
                main.post(changed)
            }
        }, "aurora-tv").start()
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    /** the watched channels, newest first, as items of the app [pkg] */
    fun items(store: Store, pkg: String): List<TvFeed.Item> {
        if (!on(store)) return emptyList()
        val now = System.currentTimeMillis()
        return decode(store.iptvRecent).map { c ->
            val (cur, next) = nowNext(progs[c.id], now)
            val desc = line(cur, next) { synchronized(clock) { clock.format(Date(it)) } }
            TvFeed.Item(pkg, c.name, desc, icons[c.id].orEmpty(), false, true, KIND, null, -1f, type = TYPE_CHANNEL)
        }
    }

    /** changes when the watched channels or what is on now on them changes (the home is rebuilt only then) */
    fun stamp(store: Store): String {
        if (!on(store)) return ""
        val now = System.currentTimeMillis()
        return icons.size.toString() + "|" + progsFor + "|" + decode(store.iptvRecent).joinToString("|") { it.id + ":" + (nowNext(progs[it.id], now).first?.start ?: 0) }
    }

    /** the watcher noted a channel: it goes first in the list */
    fun watched(store: Store, r: Recent) {
        store.iptvRecent = encode(pushed(decode(store.iptvRecent), r))
        android.util.Log.i("AuroraTv", "watched: a channel was noted (${decode(store.iptvRecent).size} kept)")
    }
}
