package app.hermes.aurora

import android.content.Context

/** All launcher settings and cached data, in one SharedPreferences file. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("aurora", Context.MODE_PRIVATE)

    private fun list(k: String): List<String> =
        (p.getString(k, "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun putList(k: String, v: List<String>) {
        p.edit().putString(k, v.joinToString("\n")).apply()
    }

    var theme: Int
        get() = p.getInt("theme", 0).coerceIn(0, Themes.ALL.size - 1)
        set(v) { p.edit().putInt("theme", v).apply() }

    var animated: Boolean
        get() = p.getBoolean("animated", true)
        set(v) { p.edit().putBoolean("animated", v).apply() }

    /** 0 = small, 1 = medium, 2 = large */
    var tileSize: Int
        get() = p.getInt("tileSize", 1).coerceIn(0, 2)
        set(v) { p.edit().putInt("tileSize", v).apply() }

    /** 0 = Classic, 1 = Cinema, 2 = Horizon, 3 = Command */
    /** the Home button (and boot) open Aurora instead of Google TV; see HomeGuardService */
    var homeButton: Boolean
        get() = p.getBoolean("homeButton", true)
        set(v) { p.edit().putBoolean("homeButton", v).apply() }

    /** music visualiser: 0 Off, 1 Flow, 2 Pixels, 3 Wave, 4 Desert, 5 Cyberpunk, 6 Blade Runner (see VisualizerView.MODES) */
    var visualizer: Int
        get() = p.getInt("visualizer", 0).coerceIn(0, 6)
        set(v) { p.edit().putInt("visualizer", v).apply() }

    init {
        // settings written when there were twelve visualizers: the six that remain keep their choice
        if (p.getInt("vizV", 0) < 2) {
            val ed = p.edit().putInt("vizV", 2)
            if (p.contains("visualizer")) ed.putInt("visualizer", HomeLogic.migrateVisualizer(p.getInt("visualizer", 0)))
            if (p.contains("saverMode")) ed.putInt("saverMode", HomeLogic.migrateVisualizer(p.getInt("saverMode", 12)))
            ed.apply()
        }
        // settings written by a version with the three old styles: Cinema stays Cinema, Horizon and Command -> Drift
        if (p.getInt("styleV", 0) < 2) {
            val ed = p.edit().putInt("styleV", 2)
            if (p.contains("style")) ed.putInt("style", HomeLogic.migrateStyle(p.getInt("style", 1)))
            ed.apply()
        }
    }

    /** 1 = Cinema, 2 = Drift (see HomeLogic) */
    var style: Int
        get() = p.getInt("style", HomeLogic.CINEMA).coerceIn(HomeLogic.CINEMA, HomeLogic.DRIFT)
        set(v) { p.edit().putInt("style", v.coerceIn(HomeLogic.CINEMA, HomeLogic.DRIFT)).apply() }

    /** night dimming: 0 Off, 1 Auto (23:00 to 07:00), 2 the hours below */
    var nightMode: Int
        get() = p.getInt("nightMode", HomeLogic.NIGHT_AUTO).coerceIn(0, 2)
        set(v) { p.edit().putInt("nightMode", v).apply() }

    /** custom night hours, 0..23: dim from [nightFrom] until [nightUntil] (may cross midnight) */
    var nightFrom: Int
        get() = p.getInt("nightFrom", HomeLogic.AUTO_FROM).coerceIn(0, 23)
        set(v) { p.edit().putInt("nightFrom", v).apply() }

    var nightUntil: Int
        get() = p.getInt("nightUntil", HomeLogic.AUTO_UNTIL).coerceIn(0, 23)
        set(v) { p.edit().putInt("nightUntil", v).apply() }

    /** titles pinned from the lanes (hold OK on a card); a plain string, so it travels with the settings backup */
    internal var pins: List<HomeLogic.Pin>
        get() = HomeLogic.decodePins(p.getString("pins", "") ?: "")
        set(v) { p.edit().putString("pins", HomeLogic.encodePins(v)).apply() }

    /** pause a playing music/video app when another app comes to the front */
    /** the ad blocker: 0 = off, 1 = on */
    var adblock: Int
        get() = p.getInt("adblock", 0).coerceIn(0, 1)
        set(v) { p.edit().putInt("adblock", v).apply() }

    /** extra artwork from TMDB: off unless the user turns it on and gives a key */
    var tmdb: Boolean
        get() = p.getBoolean("tmdb", false)
        set(v) { p.edit().putBoolean("tmdb", v).apply() }
    /** the user's own TMDB API key; kept out of the settings backup */
    var tmdbKey: String
        get() = p.getString("tmdbKey", "") ?: ""
        set(v) { p.edit().putString("tmdbKey", v.trim()).apply() }

    /** recently watched TV channels: off unless the user gives a programme guide */
    var iptv: Boolean
        get() = p.getBoolean("iptv", false)
        set(v) { p.edit().putBoolean("iptv", v).apply() }
    /** the link of the programme guide (XMLTV) */
    var iptvGuide: String
        get() = p.getString("iptvGuide", "") ?: ""
        set(v) { p.edit().putString("iptvGuide", v.trim()).apply() }
    /** the package of the IPTV app: its screen is watched for channel names, and channels open it */
    var iptvApp: String
        get() = p.getString("iptvApp", "") ?: ""
        set(v) { p.edit().putString("iptvApp", v).apply() }
    /** the channels watched last, newest first (see Iptv.encode); kept out of the settings backup */
    var iptvRecent: List<String>
        get() = list("iptvRecent")
        set(v) { putList("iptvRecent", v) }
    /** an earlier version kept a playlist link (with the subscription key in it): it is not needed any more */
    fun dropPlaylist() { p.edit().remove("iptvUrl").remove("iptvExtra").remove("iptvGroup").apply() }

    /** the apps that were already asked to publish their default previews (see MainActivity.askForPreviews) */
    var previewsAsked: List<String>
        get() = list("previewsAsked")
        set(v) { putList("previewsAsked", v.distinct()) }

    /** the package of the user's music app, "" = chosen automatically */
    var musicApp: String
        get() = p.getString("musicApp", "") ?: ""
        set(v) { p.edit().putString("musicApp", v).apply() }

    /** when Aurora last looked for an update of itself, and the release it found then ("" = none) */
    var updateChecked: Long
        get() = p.getLong("updateChecked", 0L)
        set(v) { p.edit().putLong("updateChecked", v).apply() }
    var updateSeen: String
        get() = p.getString("updateSeen", "") ?: ""
        set(v) { p.edit().putString("updateSeen", v).apply() }

    /** the tab Customize was left on */
    var settingsTab: Int
        get() = p.getInt("settingsTab", 0)
        set(v) { p.edit().putInt("settingsTab", v).apply() }

    var pauseOnSwitch: Boolean
        get() = p.getBoolean("pauseOnSwitch", true)
        set(v) { p.edit().putBoolean("pauseOnSwitch", v).apply() }

    /** visualiser auto-rotate: 0 Off, 1 every new track, 2 every 5 min, 3 every 15 min */
    var vizRotate: Int
        get() = p.getInt("vizRotate", 0).coerceIn(0, 3)
        set(v) { p.edit().putInt("vizRotate", v).apply() }

    /** screensaver after idling on the home screen: 0 Off, 1 = 2 min, 2 = 5 min, 3 = 10 min, 4 = 20 min */
    var saver: Int
        get() = p.getInt("saver", 2).coerceIn(0, 4)
        set(v) { p.edit().putInt("saver", v).apply() }

    /** screensaver scene: -1 = rotate through the scenes, else a visualiser mode 7..12 */
    var saverMode: Int
        get() = p.getInt("saverMode", 6).let { if (it == -1) -1 else it.coerceIn(4, 6) }
        set(v) { p.edit().putInt("saverMode", v).apply() }

    // ---- backup / restore of the settings (not the weather cache)
    private val skip = setOf("lat", "lon", "geoFor", "place", "wTemp", "wHi", "wLo", "wCode", "wDay", "wTime", "recents", "askedTv", "adblock", "tmdbKey", "iptvRecent", "previewsAsked", "updateChecked", "updateSeen")
    private var backupListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun exportJson(): String {
        val o = org.json.JSONObject()
        for ((k, v) in p.all) {
            if (k in skip) continue
            val a = org.json.JSONArray()
            when (v) {
                is Boolean -> { a.put("b"); a.put(v) }
                is Int -> { a.put("i"); a.put(v) }
                is Long -> { a.put("l"); a.put(v) }
                is Float -> { a.put("f"); a.put(v.toDouble()) }
                is String -> { a.put("s"); a.put(v) }
                else -> continue
            }
            o.put(k, a)
        }
        return org.json.JSONObject().put("aurora", 1).put("saved", System.currentTimeMillis()).put("prefs", o).toString(1)
    }

    /** returns how many settings were restored, or -1 when the file is not an Aurora backup */
    fun importJson(text: String): Int {
        val root = try { org.json.JSONObject(text) } catch (e: Exception) { return -1 }
        val o = root.optJSONObject("prefs") ?: return -1
        val ed = p.edit()
        var n = 0
        for (k in o.keys()) {
            if (k in skip) continue
            val a = o.optJSONArray(k) ?: continue
            when (a.optString(0)) {
                "b" -> ed.putBoolean(k, a.getBoolean(1))
                "i" -> ed.putInt(k, a.getInt(1))
                "l" -> ed.putLong(k, a.getLong(1))
                "f" -> ed.putFloat(k, a.getDouble(1).toFloat())
                "s" -> ed.putString(k, a.getString(1))
                else -> continue
            }
            n++
        }
        // a backup made before the two new styles holds one of the old style numbers
        // ... and a backup made when there were twelve visualizers holds the old numbers
        if (!o.has("vizV")) {
            ed.putInt("vizV", 2)
            o.optJSONArray("visualizer")?.let { ed.putInt("visualizer", HomeLogic.migrateVisualizer(it.optInt(1, 0))) }
            o.optJSONArray("saverMode")?.let { ed.putInt("saverMode", HomeLogic.migrateVisualizer(it.optInt(1, 12))) }
        }
        if (!o.has("styleV")) {
            ed.putInt("styleV", 2)
            o.optJSONArray("style")?.let { ed.putInt("style", HomeLogic.migrateStyle(it.optInt(1, 1))) }
        }
        ed.apply()
        return n
    }

    /** writes the backup file a few seconds after the last settings change */
    fun autoBackup(file: java.io.File, h: android.os.Handler) {
        val job = Runnable { try { file.parentFile?.mkdirs(); file.writeText(exportJson()) } catch (_: Exception) {} }
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key !in skip) { h.removeCallbacks(job); h.postDelayed(job, 4000) }
        }
        backupListener = l
        p.registerOnSharedPreferenceChangeListener(l)
    }

    /** an `aurora-restore.json` dropped into the app's files folder (adb push) is imported once at start */
    fun restoreIfRequested(dir: java.io.File?): Int {
        val f = java.io.File(dir ?: return 0, "aurora-restore.json")
        if (!f.exists()) return 0
        val n = importJson(f.readText())
        f.delete()
        return n
    }

    var carousel: Boolean
        get() = p.getBoolean("carousel", true)
        set(v) { p.edit().putBoolean("carousel", v).apply() }

    /** true = square app icons only, false = wide banner tiles */
    var iconsOnly: Boolean
        get() = p.getBoolean("iconsOnly", false)
        set(v) { p.edit().putBoolean("iconsOnly", v).apply() }

    /** fullscreen "what to watch next" backdrop behind a focused video app */
    var preview: Boolean
        get() = p.getBoolean("preview", true)
        set(v) { p.edit().putBoolean("preview", v).apply() }

    var askedTv: Boolean
        get() = p.getBoolean("askedTv", false)
        set(v) { p.edit().putBoolean("askedTv", v).apply() }

    var showWeather: Boolean
        get() = p.getBoolean("showWeather", true)
        set(v) { p.edit().putBoolean("showWeather", v).apply() }

    var city: String
        get() = p.getString("city", "") ?: ""   // empty = detect automatically
        set(v) { p.edit().putString("city", v).apply() }

    var fahrenheit: Boolean
        get() = p.getBoolean("fahrenheit", false)
        set(v) { p.edit().putBoolean("fahrenheit", v).apply() }

    var showRecent: Boolean
        get() = p.getBoolean("showRecent", true)
        set(v) { p.edit().putBoolean("showRecent", v).apply() }

    var favs: List<String>
        get() = list("favs")
        set(v) { putList("favs", v) }

    /**
     * Hidden apps, stored as "package/activity". Per activity on purpose: one package can have
     * several launcher entries (e.g. Kino and Media Player), and hiding one must not hide the other.
     * This lives in SharedPreferences, which survives normal app updates (`adb install -r`);
     * only `pm clear` / uninstall wipes it.
     */
    var hidden: List<String>
        get() = list("hidden")
        set(v) { putList("hidden", v.distinct()) }

    var recents: List<String>
        get() = list("recents")
        set(v) { putList("recents", v) }

    fun pushRecent(key: String) {
        putList("recents", (listOf(key) + recents.filter { it != key }).take(12))
    }

    // ---- weather cache (temperatures stored in Celsius)
    var lat: String
        get() = p.getString("lat", "") ?: ""
        set(v) { p.edit().putString("lat", v).apply() }

    var lon: String
        get() = p.getString("lon", "") ?: ""
        set(v) { p.edit().putString("lon", v).apply() }

    /** The city name the stored lat/lon were looked up for. */
    var geoFor: String
        get() = p.getString("geoFor", "") ?: ""
        set(v) { p.edit().putString("geoFor", v).apply() }

    var place: String
        get() = p.getString("place", "") ?: ""
        set(v) { p.edit().putString("place", v).apply() }

    var wTemp: Float
        get() = p.getFloat("wTemp", 0f)
        set(v) { p.edit().putFloat("wTemp", v).apply() }

    var wHi: Float
        get() = p.getFloat("wHi", 0f)
        set(v) { p.edit().putFloat("wHi", v).apply() }

    var wLo: Float
        get() = p.getFloat("wLo", 0f)
        set(v) { p.edit().putFloat("wLo", v).apply() }

    var wCode: Int
        get() = p.getInt("wCode", 0)
        set(v) { p.edit().putInt("wCode", v).apply() }

    var wDay: Boolean
        get() = p.getBoolean("wDay", true)
        set(v) { p.edit().putBoolean("wDay", v).apply() }

    var wTime: Long
        get() = p.getLong("wTime", 0L)
        set(v) { p.edit().putLong("wTime", v).apply() }
}
