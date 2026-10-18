package app.hermes.aurora

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Weather from Open-Meteo (free, no account or API key). */
object Weather {

    /** geoFor marker meaning "position came from the automatic IP lookup". */
    const val AUTO = "@auto"

    /** Returns null on success, otherwise a short message to show instead of the weather. */
    fun fetch(store: Store): String? {
        return try {
            val city = store.city.trim()
            if (city.isEmpty()) {
                // automatic: locate this network by its public IP (ipwho.is, free, no key)
                try {
                    val j = JSONObject(get("https://ipwho.is/"))
                    if (j.optBoolean("success", false)) {
                        store.lat = j.getDouble("latitude").toString()
                        store.lon = j.getDouble("longitude").toString()
                        store.place = j.optString("city", "").ifEmpty { j.optString("region", "Here") }
                        store.geoFor = AUTO
                    }
                } catch (e: Exception) {
                    // keep the last known position if there is one
                }
                if (store.lat.isEmpty() || store.geoFor != AUTO) return "Can't detect location (offline?)"
            } else if (store.lat.isEmpty() || store.geoFor != city) {
                val g = JSONObject(
                    get("https://geocoding-api.open-meteo.com/v1/search?name=" +
                            URLEncoder.encode(city, "UTF-8") + "&count=1&language=en&format=json")
                )
                val results = g.optJSONArray("results")
                if (results == null || results.length() == 0) return "City not found: $city"
                val o = results.getJSONObject(0)
                store.lat = o.getDouble("latitude").toString()
                store.lon = o.getDouble("longitude").toString()
                store.place = o.optString("name", city)
                store.geoFor = city
            }
            val w = JSONObject(
                get("https://api.open-meteo.com/v1/forecast?latitude=${store.lat}&longitude=${store.lon}" +
                        "&current=temperature_2m,weather_code,is_day" +
                        "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1")
            )
            val cur = w.getJSONObject("current")
            val daily = w.getJSONObject("daily")
            store.wTemp = cur.getDouble("temperature_2m").toFloat()
            store.wCode = cur.getInt("weather_code")
            store.wDay = cur.optInt("is_day", 1) == 1
            store.wHi = daily.getJSONArray("temperature_2m_max").getDouble(0).toFloat()
            store.wLo = daily.getJSONArray("temperature_2m_min").getDouble(0).toFloat()
            store.wTime = System.currentTimeMillis()
            null
        } catch (e: Exception) {
            "Weather unavailable (offline?)"
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun label(code: Int): String = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "—"
    }

    fun emoji(code: Int, day: Boolean): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1, 2 -> if (day) "⛅" else "☁️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}
