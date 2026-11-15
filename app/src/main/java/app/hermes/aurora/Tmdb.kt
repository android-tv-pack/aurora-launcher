package app.hermes.aurora

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Optional extra artwork from TMDB (themoviedb.org), off by default. With it on, a film or series that any app
 * publishes is looked up by its title, and the home shows TMDB's clean backdrop, the title's logo, a line with
 * year, genres and rating, and a short description. Nothing here knows about particular apps: what is looked up
 * is decided by what an item says about itself (its title, its kind, its year).
 *
 * It needs the user's own free TMDB API key (Customize, or a file `tmdb-key.txt` dropped into Aurora's files
 * folder). The key stays in Aurora's private settings and is left out of the settings backup. The titles on the
 * home screen are sent to TMDB to find them. Anything that is not found keeps what the app published.
 *
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
internal object Tmdb {

    /** what TMDB adds to a title; every part may be missing */
    class Extra(val backdrop: String?, val logo: String?, val overview: String, val meta: String)

    // ---------------------------------------------------------------- plain logic (unit-tested)

    class Hit(val id: Int, val tv: Boolean, val names: List<String>, val year: Int, val popularity: Double)
    class Img(val path: String, val lang: String?, val width: Int, val vote: Double)

    /** kinds an item can state about itself (Android TV's program types) */
    const val KIND_UNKNOWN = -1
    const val KIND_MOVIE = 0
    const val KIND_SERIES = 1
    const val KIND_SEASON = 2
    const val KIND_EPISODE = 3

    /** Only films and series are looked up: clips, channels, music and games are not, and neither are covers. */
    fun lookable(kind: Int, square: Boolean): Boolean = !square && kind in KIND_UNKNOWN..KIND_EPISODE

    private val tail = Regex(
        """(?i)\s*[-–—:·|,(\[]?\s*(s\d{1,2}\s*[:.·]?\s*e\d{1,3}|season\s+\d+|сезон\s+\d+|\d+\s+сезон|episode\s+\d+|серия\s+\d+|\d+\s+серия|ep\.?\s*\d+).*$"""
    )
    private val yearTail = Regex("""\s*[(\[]\s*(19|20)\d{2}\s*[)\]]\s*$""")

    /** the title without what apps add behind it: "(2019)", "S1E2", "Season 2", "3 серия" ... */
    fun cleanTitle(t: String): String {
        var s = t.trim()
        s = tail.replace(s, "")
        s = yearTail.replace(s, "")
        return s.trim().trim('-', '–', '—', ':', '·', '|', ',').trim()
    }

    /** for comparing titles: letters and digits only, lower case ("Spider-Man: No Way Home" = "spider man no way home") */
    fun norm(t: String): String {
        val b = StringBuilder()
        var gap = false
        for (c in t.lowercase(Locale.ROOT).replace('ё', 'е')) {
            if (c.isLetterOrDigit()) { if (gap && b.isNotEmpty()) b.append(' '); b.append(c); gap = false } else gap = true
        }
        return b.toString()
    }

    /**
     * The search result that IS this title, or null. The name must be equal (not merely similar), so a video that
     * only mentions a film does not get that film's artwork. Of several equal names: the stated kind and year
     * decide, then the best known one.
     */
    fun pick(hits: List<Hit>, title: String, kind: Int, year: Int): Hit? {
        val want = norm(cleanTitle(title))
        if (want.isEmpty()) return null
        var c = hits.filter { h -> h.names.any { norm(it) == want } }
        if (kind == KIND_MOVIE) c = c.filter { !it.tv }
        if (kind in KIND_SERIES..KIND_EPISODE) c = c.filter { it.tv }
        if (year > 0) {
            val near = c.filter { it.year > 0 && kotlin.math.abs(it.year - year) <= 1 }
            // a series runs for years: its stated year may be that of an episode
            if (near.isNotEmpty()) c = near else if (kind == KIND_MOVIE) return null
        }
        return c.maxByOrNull { it.popularity }
    }

    /** the backdrop to show: one without lettering first, wide enough for the screen, then the best rated */
    fun pickBackdrop(imgs: List<Img>): String? =
        imgs.sortedWith(compareByDescending<Img> { it.lang == null }.thenByDescending { it.width >= 1920 }.thenByDescending { it.vote })
            .firstOrNull()?.path

    /** the title's logo: in the first of [langs] that has one, else one without a language; never an SVG */
    fun pickLogo(imgs: List<Img>, langs: List<String>): String? {
        val ok = imgs.filter { !it.path.endsWith(".svg", true) }
        fun rank(i: Img) = if (i.lang == null) langs.size else langs.indexOf(i.lang).let { if (it < 0) Int.MAX_VALUE else it }
        return ok.filter { rank(it) != Int.MAX_VALUE }.sortedWith(compareBy<Img> { rank(it) }.thenByDescending { it.vote }).firstOrNull()?.path
    }

    /**
     * An app may name a title in another language than the box is set to (a Russian service on an English box).
     * [names] are a candidate's names in every language TMDB has (language to name; "" for a name without one):
     * the language in which it is called [title], or null when it is not this title.
     */
    fun matchLang(names: List<Pair<String, String>>, title: String): String? {
        val want = norm(cleanTitle(title))
        if (want.isEmpty()) return null
        return names.firstOrNull { norm(it.second) == want }?.first
    }

    /** the candidates worth a closer look when no name was equal: the first few of the stated kind (and, for a film, year) */
    fun candidates(hits: List<Hit>, kind: Int, year: Int, max: Int = 3): List<Hit> = hits.filter { h ->
        (kind != KIND_MOVIE || !h.tv) && (kind !in KIND_SERIES..KIND_EPISODE || h.tv) &&
            (kind != KIND_MOVIE || year <= 0 || h.year <= 0 || kotlin.math.abs(h.year - year) <= 1)
    }.take(max)

    /** "2002  ·  Crime, Drama  ·  ★ 8.6" from what is known */
    fun metaLine(year: Int, genres: List<String>, rating: Double, votes: Int): String = listOfNotNull(
        if (year > 0) year.toString() else null,
        genres.take(2).joinToString(", ").ifEmpty { null },
        if (rating > 0 && votes >= 20) "★ " + String.format(Locale.US, "%.1f", rating) else null
    ).joinToString("  ·  ")

    fun cacheKey(title: String, kind: Int, lang: String) = "2|" + norm(cleanTitle(title)) + "|" + (if (kind == KIND_MOVIE) "m" else if (kind in KIND_SERIES..KIND_EPISODE) "t" else "x") + "|" + lang

    // ---------------------------------------------------------------- the service

    private const val API = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val FOUND_MS = 30L * 24 * 3600 * 1000
    private const val MISSING_MS = 3L * 24 * 3600 * 1000

    // (lazy: the plain logic above must load without Android, in unit tests)
    private val pool by lazy { Executors.newFixedThreadPool(2) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val mem = HashMap<String, Extra?>()
    private val waiting = HashMap<String, ArrayList<(Extra?) -> Unit>>()
    /** TMDB refused the key */
    @Volatile var keyRefused = false

    fun on(store: Store) = store.tmdb && store.tmdbKey.isNotEmpty()

    fun label(store: Store): String = when {
        store.tmdbKey.isEmpty() -> "Off"
        !store.tmdb -> "Off"
        keyRefused -> "Key not accepted"
        else -> "On"
    }

    private fun lang(): String = Locale.getDefault().language.ifEmpty { "en" }

    /** a `tmdb-key.txt` dropped into the app's files folder is taken once at start and removed */
    fun takeDroppedKey(ctx: Context, store: Store): Boolean {
        val f = java.io.File(ctx.getExternalFilesDir(null) ?: return false, "tmdb-key.txt")
        if (!f.exists()) return false
        val k = try { f.readText().trim() } catch (_: Exception) { "" }
        f.delete()
        if (k.isEmpty()) return false
        store.tmdbKey = k
        store.tmdb = true
        keyRefused = false
        return true
    }

    private fun get(key: String, path: String, query: String): JSONObject? {
        val bearer = key.length > 40          // TMDB's long "read access token" goes in a header, the short key in the address
        val url = API + path + "?" + query + (if (bearer) "" else "&api_key=" + URLEncoder.encode(key, "UTF-8"))
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
            val code = c.responseCode
            if (code == 401) { keyRefused = true; return null }
            if (code != 200) throw java.io.IOException("HTTP $code")
            keyRefused = false
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** does TMDB accept [key]? null = could not ask (no network) */
    fun check(key: String, cb: (Boolean?) -> Unit) {
        pool.execute {
            val r: Boolean? = try { get(key, "/configuration", "x=1") != null } catch (_: Exception) { null }
            main.post { cb(if (r == false && !keyRefused) null else r) }
        }
    }

    private fun year(s: String?): Int = s?.take(4)?.toIntOrNull() ?: 0

    private fun hits(a: JSONArray?, forceTv: Boolean?): List<Hit> {
        val out = ArrayList<Hit>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val type = o.optString("media_type", "")
            val tv = forceTv ?: when (type) { "tv" -> true; "movie" -> false; else -> continue }
            val names = listOf(o.optString("title"), o.optString("name"), o.optString("original_title"), o.optString("original_name")).filter { it.isNotEmpty() }
            out.add(Hit(o.optInt("id"), tv, names, year(o.optString(if (tv) "first_air_date" else "release_date")), o.optDouble("popularity", 0.0)))
        }
        return out
    }

    private fun imgs(a: JSONArray?): List<Img> {
        val out = ArrayList<Img>()
        if (a == null) return out
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val p = o.optString("file_path")
            if (p.isEmpty()) continue
            out.add(Img(p, if (o.isNull("iso_639_1")) null else o.optString("iso_639_1").ifEmpty { null }, o.optInt("width"), o.optDouble("vote_average", 0.0)))
        }
        return out
    }

    /** every name TMDB has for a title: its translations (language to name) and its alternative titles ("" to name) */
    private fun allNames(d: JSONObject): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        d.optJSONObject("translations")?.optJSONArray("translations")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val data = o.optJSONObject("data") ?: continue
                val n = data.optString("title").ifEmpty { data.optString("name") }
                if (n.isNotEmpty()) out.add(o.optString("iso_639_1") to n)
            }
        }
        d.optJSONObject("alternative_titles")?.let { alt ->
            val a = alt.optJSONArray("titles") ?: alt.optJSONArray("results") ?: return@let
            for (i in 0 until a.length()) a.optJSONObject(i)?.optString("title")?.let { if (it.isNotEmpty()) out.add("" to it) }
        }
        return out
    }

    private fun fetch(key: String, title: String, kind: Int, year: Int): Extra? {
        val l = lang()
        val q = "query=" + URLEncoder.encode(cleanTitle(title), "UTF-8") + "&language=$l&include_adult=false"
        val (path, forceTv) = when (kind) {
            KIND_MOVIE -> "/search/movie" to false
            KIND_SERIES, KIND_SEASON, KIND_EPISODE -> "/search/tv" to true
            else -> "/search/multi" to null
        }
        val found = get(key, path, q) ?: throw java.io.IOException("refused")
        val all = hits(found.optJSONArray("results"), forceTv)
        fun details(h: Hit, more: String) = get(key, (if (h.tv) "/tv/" else "/movie/") + h.id,
            "language=$l&append_to_response=images$more&include_image_language=$l,en,null") ?: throw java.io.IOException("refused")
        var hit = pick(all, title, kind, year)
        var d: JSONObject? = null
        var via: String? = null         // the language the app named the title in, when it is not the box's
        if (hit != null) d = details(hit, "")
        else for (h in candidates(all, kind, year)) {
            // no equal name in the box's language: is one of the first results called this in another language?
            val dd = details(h, ",translations,alternative_titles")
            val lg = matchLang(allNames(dd), title) ?: continue
            hit = h; d = dd; via = lg.ifEmpty { null }
            break
        }
        if (hit == null || d == null) return null
        val im = d.optJSONObject("images")
        val back = pickBackdrop(imgs(im?.optJSONArray("backdrops"))) ?: d.optString("backdrop_path").ifEmpty { null }
        var logos = imgs(im?.optJSONArray("logos"))
        if (via != null && via != l && via != "en") {
            // the logo in the language the app uses for this title
            get(key, (if (hit.tv) "/tv/" else "/movie/") + hit.id + "/images", "include_image_language=$via,$l,en,null")
                ?.let { logos = imgs(it.optJSONArray("logos")) }
        }
        val logo = pickLogo(logos, listOfNotNull(via, l, "en").distinct())
        val genres = ArrayList<String>()
        d.optJSONArray("genres")?.let { g -> for (i in 0 until g.length()) g.optJSONObject(i)?.optString("name")?.let { if (it.isNotEmpty()) genres.add(it) } }
        val meta = metaLine(year(d.optString(if (hit.tv) "first_air_date" else "release_date")), genres, d.optDouble("vote_average", 0.0), d.optInt("vote_count"))
        return Extra(back?.let { IMG + "original" + it }, logo?.let { IMG + "w500" + it }, d.optString("overview").trim(), meta)
    }

    // ---- what was found is kept on the box, so a title is asked for once a month at most

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tmdb", Context.MODE_PRIVATE)

    private fun stored(ctx: Context, k: String): Pair<Boolean, Extra?> {
        val s = prefs(ctx).getString(k, null) ?: return false to null
        return try {
            val o = JSONObject(s)
            val none = o.optBoolean("none")
            if (System.currentTimeMillis() - o.optLong("t") > (if (none) MISSING_MS else FOUND_MS)) false to null
            else true to (if (none) null else Extra(o.optString("b").ifEmpty { null }, o.optString("l").ifEmpty { null }, o.optString("o"), o.optString("m")))
        } catch (_: Exception) { false to null }
    }

    private fun store(ctx: Context, k: String, x: Extra?) {
        val p = prefs(ctx)
        if (p.all.size > 400) p.edit().clear().apply()
        val o = JSONObject().put("t", System.currentTimeMillis())
        if (x == null) o.put("none", true) else o.put("b", x.backdrop ?: "").put("l", x.logo ?: "").put("o", x.overview).put("m", x.meta)
        p.edit().putString(k, o.toString()).apply()
    }

    fun forget(ctx: Context) { synchronized(mem) { mem.clear() }; prefs(ctx).edit().clear().apply() }

    /** what is already known about the item, without asking anyone (for a first paint without a flash) */
    fun known(ctx: Context, store: Store, item: TvFeed.Item): Extra? {
        if (!on(store) || !lookable(item.type, item.square)) return null
        val k = cacheKey(item.title, item.type, lang())
        synchronized(mem) { if (mem.containsKey(k)) return mem[k] }
        val (hit, x) = stored(ctx, k)
        if (hit) synchronized(mem) { mem[k] = x }
        return x
    }

    /** [cb] gets what TMDB has for the item (on the main thread), or null. Asked once per title; answers are kept. */
    fun lookup(ctx: Context, store: Store, item: TvFeed.Item, cb: (Extra?) -> Unit) {
        if (!on(store) || !lookable(item.type, item.square)) { cb(null); return }
        val k = cacheKey(item.title, item.type, lang())
        synchronized(mem) { if (mem.containsKey(k)) { cb(mem[k]); return } }
        val (hit, x) = stored(ctx, k)
        if (hit) { synchronized(mem) { mem[k] = x }; cb(x); return }
        synchronized(waiting) {
            val l = waiting[k]
            if (l != null) { l.add(cb); return }
            waiting[k] = arrayListOf(cb)
        }
        val key = store.tmdbKey
        val app = ctx.applicationContext
        pool.execute {
            var ok = true
            val r: Extra? = try { fetch(key, item.title, item.type, item.year) } catch (e: Exception) { ok = false; null }
            if (ok) { store(app, k, r); synchronized(mem) { mem[k] = r } }       // a failed request is asked again later
            android.util.Log.d("AuroraTmdb", "${item.title} -> ${if (!ok) "failed" else if (r == null) "not found" else r.meta}")
            main.post {
                val l = synchronized(waiting) { waiting.remove(k) } ?: return@post
                for (c in l) c(r)
            }
        }
    }
}
