package app.hermes.aurora

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * TV channels on the home (optional, off by default). IPTV apps publish nothing to the TV home, so Aurora reads
 * the user's own playlist (M3U) instead and shows the channels of one group with their logos and, when a
 * programme guide (XMLTV) is known, what is on now and next. Pressing a channel opens the IPTV app the user
 * chose: such apps accept no link to a single channel.
 *
 * Nothing here knows a particular provider or app. Logos and the guide come from the playlist itself or, when
 * it has none, from an optional second playlist that carries them (matched by the channel's number in its
 * address, else by its name).
 *
 * The playlist link contains the user's subscription key: it stays in Aurora's private settings, is left out of
 * the settings backup, is never logged, and the channels' stream addresses are not kept at all.
 */
internal object Iptv {

    // ---------------------------------------------------------------- plain logic (unit-tested)

    class Channel(val name: String, val group: String, val sid: String, var logo: String, var tvgId: String)
    class Playlist(val channels: List<Channel>, val guideUrl: String)
    class Prog(val start: Long, val stop: Long, val title: String)

    private val attr = Regex("""([A-Za-z][A-Za-z0-9-]*)="([^"]*)"""")

    /** the channel's number in its stream address (".../iptv/KEY/19241/index.m3u8" -> "19241"), "" when it has none */
    fun streamId(url: String): String {
        val path = url.substringBefore('?').trimEnd('/')
        val parts = path.split('/')
        for (i in parts.indices.reversed()) {
            val p = parts[i].substringBefore('.')
            if (i >= 4 && p.length in 1..9 && p.all { it.isDigit() }) return p
        }
        return ""
    }

    fun parseM3u(text: String): Playlist {
        val out = ArrayList<Channel>()
        var guide = ""
        var name: String? = null
        var group = ""
        var logo = ""
        var id = ""
        for (raw in text.lineSequence()) {
            val l = raw.trim().trimStart('\uFEFF')
            when {
                l.startsWith("#EXTM3U") -> {
                    val a = attr.findAll(l).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    guide = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"] ?: "").split(',').first().trim()
                }
                l.startsWith("#EXTINF") -> {
                    val a = attr.findAll(l).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    // the name follows the first comma behind the attributes (a name may contain commas itself)
                    val from = attr.findAll(l).lastOrNull()?.range?.last ?: 0
                    val comma = l.indexOf(',', from)
                    name = (if (comma >= 0) l.substring(comma + 1) else a["tvg-name"] ?: "").trim()
                    group = a["group-title"] ?: ""
                    logo = a["tvg-logo"] ?: ""
                    id = a["tvg-id"] ?: ""
                }
                l.startsWith("#EXTGRP:") -> if (group.isEmpty()) group = l.substring(8).trim()
                l.isEmpty() || l.startsWith("#") -> {}
                else -> {
                    val n = name
                    if (!n.isNullOrEmpty()) out.add(Channel(n, group, streamId(l), logo, id))
                    name = null; group = ""; logo = ""; id = ""
                }
            }
        }
        return Playlist(out, guide)
    }

    /** logos, guide ids and the guide's address that [own] lacks, taken from [other]: by channel number, else by name */
    fun merge(own: Playlist, other: Playlist?): Playlist {
        if (other == null) return own
        val bySid = HashMap<String, Channel>()
        val byName = HashMap<String, Channel>()
        for (c in other.channels) {
            if (c.sid.isNotEmpty()) bySid.putIfAbsent(c.sid, c)
            byName.putIfAbsent(Tmdb.norm(c.name), c)
        }
        for (c in own.channels) {
            if (c.logo.isNotEmpty() && c.tvgId.isNotEmpty()) continue
            val o = (if (c.sid.isNotEmpty()) bySid[c.sid] else null) ?: byName[Tmdb.norm(c.name)] ?: continue
            if (c.logo.isEmpty()) c.logo = o.logo
            if (c.tvgId.isEmpty()) c.tvgId = o.tvgId
        }
        return Playlist(own.channels, own.guideUrl.ifEmpty { other.guideUrl })
    }

    /** the groups in the order the playlist names them */
    fun groups(channels: List<Channel>): List<String> = channels.map { it.group }.filter { it.isNotEmpty() }.distinct()

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

    /** the channels the home shows: those of [group] (the first group when it is unknown), at most [max] */
    fun shown(channels: List<Channel>, group: String, max: Int = 24): List<Channel> {
        val gs = groups(channels)
        if (gs.isEmpty()) return channels.take(max)
        val g = if (group in gs) group else gs.first()
        return channels.filter { it.group == g }.take(max)
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

    private val displayName = Regex("""<display-name[^>]*>([^<]*)</display-name>""")

    /** the names a guide knows a channel by: (id, names) of a "<channel ...>" block */
    fun channelOf(block: String): Pair<String, List<String>>? {
        val end = block.indexOf('>')
        if (end < 0) return null
        val id = attrOf(block.substring(0, end), "id")
        if (id.isEmpty()) return null
        return id to displayName.findAll(block).map { unescape(it.groupValues[1].trim()) }.toList()
    }

    // ---------------------------------------------------------------- the service

    private const val LIST_MS = 24L * 3600 * 1000
    private const val GUIDE_MS = 12L * 3600 * 1000
    private const val WINDOW_MS = 24L * 3600 * 1000
    /** Android TV's program type "channel" */
    const val TYPE_CHANNEL = 6
    const val KIND = "TV CHANNEL"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var guideUrl = ""
    @Volatile private var guide: Map<String, List<Prog>> = emptyMap()
    @Volatile private var listAt = 0L
    @Volatile private var guideAt = 0L
    @Volatile private var busy = false
    @Volatile private var loaded = false
    @Volatile var error = ""
        private set

    fun on(store: Store) = store.iptv && store.iptvUrl.isNotEmpty()

    fun label(store: Store): String = when {
        !on(store) -> "Off"
        channels.isNotEmpty() -> String.format(Locale.US, "%,d channels", channels.size) + (if (busy) "  ·  updating" else "")
        busy -> "Loading"
        error.isNotEmpty() -> error
        else -> "On"
    }

    fun groupNames(): List<String> = groups(channels)
    fun groupLabel(store: Store): String = groupNames().let { g -> if (store.iptvGroup in g) store.iptvGroup else g.firstOrNull() ?: "" }

    /** an `iptv-playlist.txt` dropped into the app's files folder: line 1 the playlist link, line 2 (optional) a playlist with logos and guide */
    fun takeDropped(ctx: Context, store: Store): Boolean {
        val f = File(ctx.getExternalFilesDir(null) ?: return false, "iptv-playlist.txt")
        if (!f.exists()) return false
        val lines = try { f.readLines().map { it.trim() }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
        f.delete()
        if (lines.isEmpty()) return false
        store.iptvUrl = lines[0]
        store.iptvExtra = lines.getOrElse(1) { "" }
        store.iptv = true
        forget(ctx)
        return true
    }

    fun forget(ctx: Context) {
        channels = emptyList(); guide = emptyMap(); guideUrl = ""; listAt = 0; guideAt = 0; loaded = true; error = ""
        File(ctx.filesDir, "iptv-channels.json").delete()
        File(ctx.filesDir, "iptv-guide.json").delete()
    }

    private fun http(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Aurora")
        var code = c.responseCode
        var conn = c
        // a redirect from http to https (or back) is not followed by itself
        var hops = 0
        while (code in 301..308 && hops++ < 4) {
            val to = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(URL(conn.url, to).toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Aurora")
            code = conn.responseCode
        }
        if (code != 200) throw java.io.IOException("HTTP $code")
        return conn.inputStream
    }

    private fun text(url: String, max: Int = 24 * 1024 * 1024): String {
        http(url).use { s ->
            val out = java.io.ByteArrayOutputStream()
            val b = ByteArray(1 shl 15)
            while (true) {
                val n = s.read(b)
                if (n < 0) break
                out.write(b, 0, n)
                if (out.size() > max) throw java.io.IOException("too large")
            }
            return out.toString("UTF-8")
        }
    }

    // ---- what was read is kept on the box (never the stream addresses)

    private fun saveChannels(ctx: Context) {
        val a = JSONArray()
        for (c in channels) a.put(JSONArray().put(c.name).put(c.group).put(c.logo).put(c.tvgId))
        File(ctx.filesDir, "iptv-channels.json").writeText(JSONObject().put("t", listAt).put("g", guideUrl).put("c", a).toString())
    }

    private fun saveGuide(ctx: Context) {
        val o = JSONObject()
        for ((id, l) in guide) { val a = JSONArray(); for (p in l) a.put(JSONArray().put(p.start).put(p.stop).put(p.title)); o.put(id, a) }
        File(ctx.filesDir, "iptv-guide.json").writeText(JSONObject().put("t", guideAt).put("d", o).toString())
    }

    private fun loadSaved(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val o = JSONObject(File(ctx.filesDir, "iptv-channels.json").readText())
            val a = o.getJSONArray("c")
            val l = ArrayList<Channel>(a.length())
            for (i in 0 until a.length()) { val c = a.getJSONArray(i); l.add(Channel(c.getString(0), c.getString(1), "", c.getString(2), c.getString(3))) }
            channels = l; guideUrl = o.optString("g"); listAt = o.optLong("t")
        } catch (_: Exception) {}
        try {
            val o = JSONObject(File(ctx.filesDir, "iptv-guide.json").readText())
            val d = o.getJSONObject("d")
            val m = HashMap<String, List<Prog>>()
            for (id in d.keys()) { val a = d.getJSONArray(id); val l = ArrayList<Prog>(a.length()); for (i in 0 until a.length()) { val p = a.getJSONArray(i); l.add(Prog(p.getLong(0), p.getLong(1), p.getString(2))) }; m[id] = l }
            guide = m; guideAt = o.optLong("t")
        } catch (_: Exception) {}
    }

    /**
     * Reads the playlist (once a day) and the guide (twice a day) when they are due; [changed] is called on the
     * main thread whenever there is something new to show. Runs off the main thread, one at a time.
     */
    fun refresh(ctx: Context, store: Store, force: Boolean, changed: () -> Unit) {
        if (!on(store) || busy) return
        busy = true
        val app = ctx.applicationContext
        val url = store.iptvUrl
        val extra = store.iptvExtra
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val had = channels.size
                loadSaved(app)
                if (channels.size != had) main.post(changed)
                val now = System.currentTimeMillis()
                if (force || channels.isEmpty() || now - listAt > LIST_MS) {
                    try {
                        var p = parseM3u(text(url))
                        if (p.channels.isEmpty()) throw java.io.IOException("no channels")
                        if (extra.isNotEmpty() && p.channels.any { it.logo.isEmpty() || it.tvgId.isEmpty() }) {
                            try { p = merge(p, parseM3u(text(extra))) } catch (_: Exception) {}
                        }
                        channels = p.channels; guideUrl = p.guideUrl; listAt = now; error = ""
                        saveChannels(app)
                        android.util.Log.i("AuroraTv", "playlist: ${p.channels.size} channels, ${p.channels.count { it.logo.isNotEmpty() }} logos, guide ${if (p.guideUrl.isEmpty()) "none" else "known"}")
                        main.post(changed)
                    } catch (e: Exception) {
                        if (channels.isEmpty()) error = "Could not load the playlist"
                        android.util.Log.w("AuroraTv", "playlist failed: ${e.javaClass.simpleName}")
                        main.post(changed)
                    }
                }
                val g = guideUrl
                if (g.isNotEmpty() && channels.isNotEmpty() && (force || guide.isEmpty() || now - guideAt > GUIDE_MS)) {
                    try {
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        val ids = HashSet<String>()
                        val names = HashMap<String, ArrayList<Channel>>()       // channels the playlist gives no guide id: found by name
                        for (c in channels) if (c.tvgId.isNotEmpty()) ids.add(c.tvgId) else names.getOrPut(Tmdb.norm(c.name)) { ArrayList() }.add(c)
                        val out = HashMap<String, ArrayList<Prog>>()
                        val until = now + WINDOW_MS
                        var named = 0
                        val raw = http(g)
                        val input = if (g.substringBefore('?').endsWith(".gz", true)) GZIPInputStream(raw, 1 shl 16) else raw
                        input.use { s ->
                            scanGuide(s, { head -> attrOf(head, "channel") in ids }, { block ->
                                if (names.isNotEmpty()) channelOf(block)?.let { (id, ns) ->
                                    for (n in ns) names[Tmdb.norm(n)]?.let { l -> for (c in l) if (c.tvgId.isEmpty()) { c.tvgId = id; named++ }; ids.add(id) }
                                }
                            }, { block ->
                                progOf(block)?.let { (id, p) -> if (p.stop > now && p.start < until) out.getOrPut(id) { ArrayList() }.add(p) }
                            })
                        }
                        for (l in out.values) l.sortBy { it.start }
                        guide = out; guideAt = now
                        saveGuide(app)
                        if (named > 0) saveChannels(app)
                        android.util.Log.i("AuroraTv", "guide: ${out.size} channels, ${out.values.sumOf { it.size }} programmes, ${(android.os.SystemClock.elapsedRealtime() - t0) / 1000}s")
                        main.post(changed)
                    } catch (e: Exception) {
                        android.util.Log.w("AuroraTv", "guide failed: ${e.javaClass.simpleName}")
                    }
                }
            } finally {
                busy = false
                main.post(changed)
            }
        }, "aurora-tv").start()
    }

    private val clock = SimpleDateFormat("HH:mm", Locale.US)

    /** the channels of the chosen group as items of the app [pkg] */
    fun items(store: Store, pkg: String): List<TvFeed.Item> {
        if (!on(store)) return emptyList()
        val now = System.currentTimeMillis()
        return shown(channels, store.iptvGroup).filter { it.logo.isNotEmpty() || it.name.isNotEmpty() }.map { c ->
            val (cur, next) = nowNext(guide[c.tvgId], now)
            val desc = line(cur, next) { synchronized(clock) { clock.format(Date(it)) } }
            TvFeed.Item(pkg, c.name, desc, c.logo, false, true, KIND, null, -1f, type = TYPE_CHANNEL)
        }
    }

    /** changes when what is on now changes (so the home is rebuilt only then) */
    fun stamp(store: Store): String {
        if (!on(store)) return ""
        val now = System.currentTimeMillis()
        return store.iptvGroup + "|" + channels.size + "|" + shown(channels, store.iptvGroup).joinToString("|") { nowNext(guide[it.tvgId], now).first?.start?.toString() ?: "" }
    }
}
