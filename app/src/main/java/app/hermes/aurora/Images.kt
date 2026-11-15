package app.hermes.aurora

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny async image loader (http/https, content://, android.resource://) with a memory cache. */
object Images {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    // room for a few full-screen backdrops (a 1920 x 1080 picture is about 8 MB)
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** the same picture is kept once per size it is shown at (a card must not be reused as a full-screen backdrop) */
    private fun key(url: String, maxWidth: Int) = "$maxWidth|$url"

    /**
     * Calls back on the main thread; bmp is null when the image can't be loaded. The picture is decoded
     * off the main thread and is never wider than [maxWidth] pixels.
     */
    fun load(ctx: Context, url: String, maxWidth: Int, cb: (Bitmap?) -> Unit) {
        val k = key(url, maxWidth)
        cache.get(k)?.let { cb(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = cache.get(k) ?: try { fetch(app, url, maxWidth) } catch (e: Throwable) { null }
            if (bmp != null) cache.put(k, bmp)
            main.post { cb(bmp) }
        }
    }

    /**
     * Apps publish small pictures (a phone-sized 460 px still, a 640 px video frame) although their servers keep
     * the same picture much larger. For a full-screen backdrop ask for the large one first; the published address
     * stays the fallback. Known: Yandex/Kinopoisk CDNs take the size as the last path part, YouTube has
     * maxresdefault next to sd/hq/mqdefault, TMDB and its mirrors (used by many apps) take the width in the path.
     */
    internal fun sharper(url: String, maxWidth: Int): String? {
        if (maxWidth < 1280) return null
        val yandex = Regex("""^(https://[^/]*yandex\.net/.+)/\d+x\d*$""").find(url)
        if (yandex != null) return yandex.groupValues[1] + "/1920x1080"
        val yt = Regex("""^(https://i\d?\.ytimg\.com/vi(?:_webp)?/[^/]+/)(?:default|mqdefault|hqdefault|sddefault|hq720)(\.jpg|\.webp)(\?.*)?$""").find(url)
        if (yt != null) return yt.groupValues[1] + "maxresdefault" + yt.groupValues[2]
        // TMDB and its mirrors (Flux uses imagetmdb.com): "original" is the full picture, 1920 px for backdrops
        val tmdb = Regex("""^(https://[a-z0-9.-]*tmdb[a-z0-9.-]*/t/p/)w\d+(/.+)$""").find(url)
        if (tmdb != null) return tmdb.groupValues[1] + "original" + tmdb.groupValues[2]
        return null
    }

    private fun fetch(ctx: Context, url: String, maxWidth: Int): Bitmap? {
        val big = sharper(url, maxWidth)
        if (big != null) {
            val b = try { decode(ctx, big, maxWidth) } catch (e: Throwable) { null }
            // a YouTube video without a large frame answers with a small grey placeholder
            if (b != null && b.width >= 1000) return b
            b?.recycle()
        }
        return decode(ctx, url, maxWidth)
    }

    private fun decode(ctx: Context, url: String, maxWidth: Int): Bitmap? {
        val bytes = readAll(ctx, url) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (BuildConfig.DEBUG || maxWidth >= 1280) android.util.Log.d("AuroraArt", "${o.outWidth}x${o.outHeight} for $maxWidth: $url")
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d) ?: return null
        if (raw.width <= maxWidth) return raw
        // sampling only halves: bring it down to the size it is shown at
        val h = (raw.height.toLong() * maxWidth / raw.width).toInt().coerceAtLeast(1)
        val fit = Bitmap.createScaledBitmap(raw, maxWidth, h, true)
        if (fit !== raw) raw.recycle()
        return fit
    }

    private fun readAll(ctx: Context, url: String): ByteArray? {
        val uri = Uri.parse(url)
        val input = when (uri.scheme) {
            "http", "https" -> {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.instanceFollowRedirects = true
                c.inputStream
            }
            else -> ctx.contentResolver.openInputStream(uri)
        } ?: return null
        input.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
                if (total > 8 * 1024 * 1024) return null   // refuse absurdly large images
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
