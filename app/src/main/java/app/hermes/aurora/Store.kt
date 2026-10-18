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

    /** 0 Original, 1 Round, 2 Mono, 3 Neon, 4 Duotone, 5 Glass: see IconStyles */
    var iconStyle: Int
        get() = p.getInt("iconStyle", 0).coerceIn(0, IconStyles.NAMES.size - 1)
        set(v) { p.edit().putInt("iconStyle", v).apply() }

    /** music visualiser: 0 Off, 1 Flow, 2 Orb, 3 Stardust, 4 Silk, 5 Pixels, 6 Wave, 7 Desert, 8 Cyberpunk, 9 Tron Legacy, 10 Matrix, 11 Blade Runner, 12 Interstellar (see VisualizerView.MODES) */
    var visualizer: Int
        get() = p.getInt("visualizer", 0).coerceIn(0, 12)
        set(v) { p.edit().putInt("visualizer", v).apply() }

    /** 1 = Cinema, 2 = Horizon, 3 = Command (0 was the old Classic screen, which was removed: it maps to Cinema) */
    var style: Int
        get() = p.getInt("style", 1).coerceIn(1, 3)
        set(v) { p.edit().putInt("style", v.coerceIn(1, 3)).apply() }

    /** pause a playing music/video app when another app comes to the front */
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
        get() = p.getInt("saverMode", 12).let { if (it == -1) -1 else it.coerceIn(7, 12) }
        set(v) { p.edit().putInt("saverMode", v).apply() }

    // ---- backup / restore of the settings (not the weather cache)
    private val skip = setOf("lat", "lon", "geoFor", "place", "wTemp", "wHi", "wLo", "wCode", "wDay", "wTime", "recents", "askedTv")
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
