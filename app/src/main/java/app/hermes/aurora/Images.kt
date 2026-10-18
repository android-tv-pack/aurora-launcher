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
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    /** Calls back on the main thread; bmp is null when the image can't be loaded. */
    fun load(ctx: Context, url: String, maxWidth: Int, cb: (Bitmap?) -> Unit) {
        cache.get(url)?.let { cb(it); return }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = try { fetch(app, url, maxWidth) } catch (e: Throwable) { null }
            if (bmp != null) cache.put(url, bmp)
            main.post { cb(bmp) }
        }
    }

    private fun fetch(ctx: Context, url: String, maxWidth: Int): Bitmap? {
        val bytes = readAll(ctx, url) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val d = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, d)
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
