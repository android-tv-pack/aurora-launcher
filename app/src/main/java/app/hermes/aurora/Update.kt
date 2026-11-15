package app.hermes.aurora

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aurora's own updates, from the releases of its public repository.
 *
 * A released build follows the releases; a beta build follows the betas too. The newer build is downloaded and
 * handed to Android's installer, which asks before it installs and takes only a build signed like this one.
 */
internal object Update {

    const val REPO = "android-tv-pack/aurora-launcher"
    const val ACTION_STATUS = "app.hermes.aurora.UPDATE_STATUS"
    private const val DAY_MS = 24L * 3600 * 1000

    class Release(val tag: String, val pre: Boolean, val apk: String) {
        /** "3.20" or "3.20 beta" */
        val name: String get() = tag.removePrefix("v").replace("-", " ")
    }

    // ---------------------------------------------------------------- plain logic (unit-tested)

    /**
     * "v3.19", "3.19.2", "3.20-beta", "3.20-beta2" as numbers that compare in release order
     * (a beta comes before the release it leads to), or null when it is not a version.
     */
    fun version(s: String): LongArray? {
        val m = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:-beta(\d*))?$""").find(s.trim()) ?: return null
        val g = m.groupValues
        val beta = s.contains("-beta")
        return longArrayOf(g[1].toLong(), g[2].toLong(), g[3].toLongOrNull() ?: 0, if (beta) 0 else 1, g[4].toLongOrNull() ?: 0)
    }

    /** is version [a] newer than [b]? (false when either is not a version) */
    fun newer(a: String, b: String): Boolean {
        val x = version(a) ?: return false
        val y = version(b) ?: return false
        for (i in x.indices) if (x[i] != y[i]) return x[i] > y[i]
        return false
    }

    /** the release to offer to a build named [current]: the newest one that is newer; betas only to a beta build */
    fun pick(releases: List<Release>, current: String): Release? {
        val betas = current.contains("beta")
        return releases.filter { it.apk.isNotEmpty() && (betas || !it.pre) && newer(it.tag, current) }
            .sortedWith { p, q -> if (newer(p.tag, q.tag)) -1 else if (newer(q.tag, p.tag)) 1 else 0 }
            .firstOrNull()
    }

    // ---------------------------------------------------------------- the service

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile var available: Release? = null
        private set
    @Volatile private var checking = false
    @Volatile private var failed = false
    @Volatile private var checkedOnce = false
    /** -1 = nothing is being fetched, else the percent fetched */
    @Volatile var progress = -1
        private set
    @Volatile private var note = ""

    fun label(): String = when {
        progress in 0..99 -> "Downloading  $progress%"
        progress >= 100 -> "Waiting for Android's installer"
        note.isNotEmpty() -> note
        checking -> "Checking"
        available != null -> available!!.name + " is available"
        failed -> "Could not check"
        checkedOnce -> "Up to date"
        else -> "Check now"
    }

    private fun releases(json: String): List<Release> {
        val a = JSONArray(json)
        val out = ArrayList<Release>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            if (o.optBoolean("draft")) continue
            val assets = o.optJSONArray("assets")
            var apk = ""
            if (assets != null) for (k in 0 until assets.length()) {
                val url = assets.getJSONObject(k).optString("browser_download_url")
                if (url.endsWith(".apk", true)) { apk = url; break }
            }
            out += Release(o.optString("tag_name"), o.optBoolean("prerelease"), apk)
        }
        return out
    }

    /** Looks for a newer release: when asked ([force]) or once a day. [done] runs on the main thread afterwards. */
    fun check(store: Store, force: Boolean, done: () -> Unit) {
        if (checking || progress >= 0) return
        if (!force && System.currentTimeMillis() - store.updateChecked < DAY_MS && checkedOnce) return
        if (!force && System.currentTimeMillis() - store.updateChecked < DAY_MS && store.updateSeen.isEmpty()) { checkedOnce = true; return }
        checking = true; note = ""
        Thread({
            try {
                val c = URL("https://api.github.com/repos/$REPO/releases?per_page=20").openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "Aurora-Launcher")
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val list = releases(c.inputStream.bufferedReader().use { it.readText() })
                available = pick(list, BuildConfig.VERSION_NAME)
                failed = false; checkedOnce = true
                store.updateChecked = System.currentTimeMillis()
                store.updateSeen = available?.tag.orEmpty()
            } catch (e: Exception) {
                failed = true
                android.util.Log.w("AuroraUpdate", "check failed: ${e.javaClass.simpleName}")
            } finally {
                checking = false
                main.post(done)
            }
        }, "aurora-update").start()
    }

    /** may Aurora hand a build to Android's installer? (the user allows that once in Android's settings) */
    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Fetches [r] and hands it to Android's installer, which asks the user. [changed] runs on the main thread as it goes. */
    fun install(ctx: Context, r: Release, changed: () -> Unit) {
        if (progress >= 0) return
        progress = 0; note = ""
        val app = ctx.applicationContext
        Thread({
            var session: PackageInstaller.Session? = null
            try {
                var c = URL(r.apk).openConnection() as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Aurora-Launcher")
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val total = c.contentLength.toLong()
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (total > 0) params.setSize(total)
                val id = pi.createSession(params)
                session = pi.openSession(id)
                var got = 0L
                var shown = 0
                c.inputStream.use { input ->
                    session.openWrite("aurora", 0, if (total > 0) total else -1).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val pct = if (total > 0) (got * 99 / total).toInt() else 0
                            if (pct != shown) { shown = pct; progress = pct; main.post(changed) }
                        }
                        session.fsync(out)
                    }
                }
                progress = 100
                main.post(changed)
                // the installer answers to a receiver, not to the home screen: the screen stays as it is while Android asks
                val back = Intent(app, UpdateReceiver::class.java).setAction(ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(app, 7, back, flags).intentSender)
                session.close()
            } catch (e: Exception) {
                try { session?.abandon() } catch (_: Exception) {}
                progress = -1
                note = "Download failed"
                android.util.Log.w("AuroraUpdate", "install failed: $e")
                main.post(changed)
            }
        }, "aurora-update").start()
    }

    /** called on the main thread when the state shown by [label] has changed */
    @Volatile var onChange: (() -> Unit)? = null

    /**
     * Android's installer reports back (see ACTION_STATUS): it wants to ask the user, it is done, or it refused.
     * Returns the screen to show when it wants to ask.
     */
    fun onStatus(i: Intent): Intent? {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            return i.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        progress = -1
        note = when (status) {
            PackageInstaller.STATUS_SUCCESS -> ""
            PackageInstaller.STATUS_FAILURE_ABORTED -> ""                 // the user said no
            else -> "Android did not install it"
        }
        android.util.Log.i("AuroraUpdate", "installer status $status ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}")
        return null
    }
}

/** Android's installer reports here on Aurora's update (see Update.install). */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Update.ACTION_STATUS) return
        val ask = Update.onStatus(i)
        if (ask != null) {
            try { ctx.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { android.util.Log.w("AuroraUpdate", "can't show the installer: $e") }
        }
        Update.onChange?.invoke()
    }
}
