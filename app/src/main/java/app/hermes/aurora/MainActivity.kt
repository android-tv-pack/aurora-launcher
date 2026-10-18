package app.hermes.aurora

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Aurora: a modern, dark, free TV launcher. No accounts, no premium tier, no ads.
 * Network is used only for the weather widget (Open-Meteo, no key) and can be switched off.
 *
 *  - animated themed background, big clock, date, network name, weather
 *  - FAVORITES, RECENT and ALL APPS rows
 *  - hold OK on an app: pin / reorder / hide / app info / uninstall
 *  - MENU key (or "Customize"): theme, animation, tile size, weather, city, units, recents, hidden apps
 */
class MainActivity : Activity() {

    private class Row(val title: String, val value: () -> String, val act: (Int) -> Unit)

    internal lateinit var store: Store
    private lateinit var rootView: FrameLayout
    internal lateinit var bg: AuroraView
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout

    private var clock: TextView? = null
    private var dateView: TextView? = null
    private var weatherMain: TextView? = null
    private var weatherSub: TextView? = null
    private var weatherError: String? = null
    private var fetching = false

    // ---- "what to watch next" backdrop
    internal val handler = Handler(Looper.getMainLooper())
    internal var feed: Map<String, List<TvFeed.Item>> = emptyMap()
    private lateinit var backdrop: ImageView
    private lateinit var backdropInfo: LinearLayout
    private lateinit var infoKind: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoDesc: TextView
    private var shownPkg: String? = null
    private var shownIndex = 0
    private var shownUrl: String? = null
    private val showRunnable = Runnable { showFor(pendingEntry) }
    private var pendingEntry: AppEntry? = null
    private val cycleRunnable = object : Runnable {
        override fun run() {
            val pkg = shownPkg ?: return
            val items = feed[pkg] ?: return
            if (items.size > 1) {
                shownIndex = (shownIndex + 1) % items.size
                showItem(items[shownIndex], true)
                handler.postDelayed(this, 9000)
            }
        }
    }

    // ---- selectable home styles (0 Classic, 1 Cinema, 2 Horizon, 3 Command)
    private lateinit var host: FrameLayout
    internal lateinit var viz: VisualizerView
    private var currentStyle: HomeStyle? = null
    internal var lastKey: String? = null

    private var panel: FrameLayout? = null
    private var refreshPanel: (() -> Unit)? = null
    private var focusPanel: (() -> Unit)? = null
    private var panelRow: View? = null
    private var renderedRecents = ""

    private var apps: List<AppEntry> = emptyList()
    private val tiles = LinkedHashMap<String, View>()
    private var loading = false
    private var dirty = false

    // all sizes are relative to a 540-unit-high screen so the layout looks the same at any density
    private var unit = 1f
    internal fun u(n: Float): Int = (n * unit).toInt()
    internal fun u(n: Int): Int = u(n.toFloat())

    internal val accent: Int get() = Themes.ALL[store.theme].accent

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateClock()
            refreshWeather(false)
        }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            reload(null)
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        store.restoreIfRequested(getExternalFilesDir(null))
        store.autoBackup(java.io.File(getExternalFilesDir(null), "aurora-backup.json"), handler)
        syncHomeGuard()
        startAirPlay()
        unit = resources.displayMetrics.heightPixels / 540f

        rootView = FrameLayout(this)
        bg = AuroraView(this)
        bg.setColors(Themes.ALL[store.theme].colors)
        bg.animated = store.animated
        rootView.addView(bg, ViewGroup.LayoutParams(-1, -1))
        viz = VisualizerView(this)
        rootView.addView(viz, ViewGroup.LayoutParams(-1, -1))
        buildBackdrop()

        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, u(34))
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -1))
        rootView.addView(scroll, ViewGroup.LayoutParams(-1, -1))
        host = FrameLayout(this).apply { clipChildren = false; visibility = View.GONE }
        rootView.addView(host, ViewGroup.LayoutParams(-1, -1))
        setContentView(rootView)

        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        reload(null)
    }

    /** The AirPlay receiver is built in: opening Aurora (and boot, via its BootReceiver) makes the TV visible to iPhones/Macs. */
    private fun startAirPlay() {
        val p = getSharedPreferences(io.github.jqssun.airplay.Prefs.NAME, MODE_PRIVATE)
        if (!p.getBoolean(io.github.jqssun.airplay.Prefs.AUTO_START, io.github.jqssun.airplay.Prefs.DEF_AUTO_START)) return
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                Intent(this, io.github.jqssun.airplay.service.AirPlayService::class.java)
                    .setAction(io.github.jqssun.airplay.service.AirPlayService.ACTION_START_SERVER)
            )
        } catch (e: Exception) {
            android.util.Log.w("Aurora", "AirPlay start failed", e)
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        })
        NowPlaying.start(this)
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(saverTick)
        handler.postDelayed(saverTick, 10_000)
        viz.start()
        updateClock()
        updateWeather()
        refreshWeather(false)
        loadFeed()
        if (dirty) reload(null)
        else if (apps.isNotEmpty() && panel == null && renderedRecents != store.recents.joinToString("\n")) {
            // coming back from an app: show the updated RECENT row
            render(null)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
        handler.removeCallbacks(saverTick)
        if (ambientOn) exitAmbient()
        viz.stop()
        NowPlaying.stop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("ambient", false)) { enterAmbient(); return }      // (for testing: am start --ez ambient true)
        if (ambientOn) exitAmbient()
        if (panel != null) closeSettings()
        if (store.style == 0) {
            scroll.smoothScrollTo(0, 0)
            firstTile()?.requestFocus()
        } else {
            currentStyle?.refocus()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // MENU is missing on many TV remotes, so a few other keys open Customize too.
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_TV_CONTENTS_MENU || keyCode == KeyEvent.KEYCODE_PROG_YELLOW) {
            if (panel == null) openSettings() else closeSettings()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != null) {
            closeSettings()
            return true
        }
        // as the Home screen, Back has nowhere to go (it would only show Google TV for a moment)
        if (keyCode == KeyEvent.KEYCODE_BACK && store.homeButton && HomeGuardService.isEnabled(this)) return true
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- screensaver

    private var lastInteraction = android.os.SystemClock.elapsedRealtime()
    internal var ambientOn = false
    private var ambientView: FrameLayout? = null
    private var ambClock: TextView? = null
    private var ambTrack: TextView? = null

    private val saverTick = object : Runnable {
        override fun run() { checkSaver(); handler.postDelayed(this, 10_000) }
    }
    private val ambientTick = object : Runnable {
        override fun run() { if (!ambientOn) return; updateAmbientText(); handler.postDelayed(this, 1000) }
    }

    override fun onUserInteraction() { lastInteraction = android.os.SystemClock.elapsedRealtime() }

    /** while the screensaver runs, the first key press only wakes it */
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ambientOn) {
            if (ev.action == KeyEvent.ACTION_DOWN) exitAmbient()
            return true
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun checkSaver() {
        val mins = intArrayOf(0, 2, 5, 10, 20)[store.saver]
        android.util.Log.d("AuroraSaver", "tick mins=$mins ambient=$ambientOn panel=${panel != null} focus=${window.decorView.hasWindowFocus()} idle=${(android.os.SystemClock.elapsedRealtime() - lastInteraction) / 1000}s")
        if (mins == 0 || ambientOn || panel != null || !window.decorView.hasWindowFocus()) return
        if (android.os.SystemClock.elapsedRealtime() - lastInteraction >= mins * 60_000L) enterAmbient()
    }

    internal fun enterAmbient() {
        if (ambientOn) return
        ambientOn = true
        host.visibility = View.INVISIBLE
        scroll.visibility = View.INVISIBLE
        bg.visibility = View.INVISIBLE
        val clock = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(34).toFloat())
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setTextColor(Color.argb(190, 255, 255, 255))
        }
        val track = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11).toFloat())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            setTextColor(Color.argb(170, 255, 255, 255))
            maxLines = 1
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(clock); addView(track) }
        val v = FrameLayout(this)
        v.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(u(44), 0, 0, u(32)) })
        rootView.addView(v, ViewGroup.LayoutParams(-1, -1))
        ambientView = v; ambClock = clock; ambTrack = track
        updateAmbientText()
        handler.removeCallbacks(ambientTick)
        handler.postDelayed(ambientTick, 1000)
        viz.ambient = if (store.saverMode == -1) -2 else store.saverMode
    }

    internal fun exitAmbient() {
        if (!ambientOn) return
        ambientOn = false
        handler.removeCallbacks(ambientTick)
        viz.ambient = -1
        ambientView?.let { rootView.removeView(it) }
        ambientView = null; ambClock = null; ambTrack = null
        host.visibility = if (store.style == 0) View.GONE else View.VISIBLE
        scroll.visibility = if (store.style == 0) View.VISIBLE else View.GONE
        applyChrome()
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        currentStyle?.refocus()
    }

    private fun updateAmbientText() {
        ambClock?.text = nowStr(clockPattern())
        val line = NowPlaying.playingLine()
        ambTrack?.text = if (line.isEmpty()) nowStr("EEEE, d MMMM").uppercase(Locale.getDefault()) else "\u266A  " + line.uppercase(Locale.getDefault())
    }

    // ---------------------------------------------------------------- settings backup

    private fun backupFile() = java.io.File(getExternalFilesDir(null), "aurora-backup.json")

    private fun backupLabel(): String {
        val f = backupFile()
        if (!f.exists()) return "Not yet"
        return java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(java.util.Date(f.lastModified()))
    }

    private fun askBackup() {
        dialogBuilder()
            .setTitle("Settings backup")
            .setItems(arrayOf("Back up now", "Restore from the backup")) { _, i ->
                if (i == 0) {
                    try { backupFile().parentFile?.mkdirs(); backupFile().writeText(store.exportJson()); Toast.makeText(this, "Backed up", Toast.LENGTH_SHORT).show() }
                    catch (e: Exception) { Toast.makeText(this, "Backup failed", Toast.LENGTH_SHORT).show() }
                    refreshPanel?.invoke()
                } else {
                    val f = backupFile()
                    val n = if (f.exists()) store.importJson(f.readText()) else -1
                    if (n < 0) Toast.makeText(this, "No backup found", Toast.LENGTH_SHORT).show()
                    else { Toast.makeText(this, "Restored $n settings", Toast.LENGTH_SHORT).show(); recreate() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- data

    private fun reload(focusId: String?) {
        if (loading) return
        loading = true
        Thread {
            val list = try { queryApps() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                loading = false
                if (isDestroyed) return@runOnUiThread
                dirty = false
                apps = list
                render(focusId)
            }
        }.start()
    }

    /** The stock Google TV home has no app-list entry of its own, so give it a tile: Aurora is the Home app, this is the way to its recommendations. */
    private fun googleTvEntry(): AppEntry? = try {
        val pkg = "com.google.android.apps.tv.launcherx"
        val comp = ComponentName(pkg, "$pkg.home.HomeActivity")
        packageManager.getActivityInfo(comp, 0)
        val ai = packageManager.getApplicationInfo(pkg, 0)
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(comp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        AppEntry("$pkg/googletv", pkg, "Google TV", i, ai.loadBanner(packageManager),
            IconStyles.apply(resources, ai.loadIcon(packageManager), store.iconStyle, accent), true)
    } catch (e: Exception) { null }

    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val tvIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val phoneIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val tv = pm.queryIntentActivities(tvIntent, 0)
        val tvPkgs = tv.map { it.activityInfo.packageName }.toSet()
        val phone = pm.queryIntentActivities(phoneIntent, 0).filter { it.activityInfo.packageName !in tvPkgs }

        fun entry(ri: ResolveInfo, category: String): AppEntry? {
            val ai = ri.activityInfo
            if (ai.packageName == packageName && ai.name == MainActivity::class.java.name) return null   // not itself; the built-in AirPlay screen still gets a tile
            val i = Intent(Intent.ACTION_MAIN).addCategory(category)
                .setClassName(ai.packageName, ai.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val banner = if (category == Intent.CATEGORY_LEANBACK_LAUNCHER)
                (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)) else null
            val system = (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            return AppEntry(
                "${ai.packageName}/${ai.name}", ai.packageName, ri.loadLabel(pm).toString(),
                i, banner, IconStyles.apply(resources, ri.loadIcon(pm), store.iconStyle, accent), system
            )
        }

        val all = tv.mapNotNull { entry(it, Intent.CATEGORY_LEANBACK_LAUNCHER) } +
                phone.mapNotNull { entry(it, Intent.CATEGORY_LAUNCHER) } +
                listOfNotNull(googleTvEntry())
        return all.distinctBy { it.key }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    // ---------------------------------------------------------------- clock, network, weather

    internal fun netLabel(): String {
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                else -> "Online"
            }
        } catch (e: Exception) {
            ""
        }
    }

    internal fun nowStr(pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    internal fun clockPattern(): String = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"

    private fun updateClock() {
        currentStyle?.onTick()
        val c = clock ?: return
        val d = dateView ?: return
        c.text = nowStr(clockPattern())
        val date = nowStr("EEEE, d MMMM")
        val net = netLabel()
        d.text = if (net.isEmpty()) date else "$date   ·   $net"
    }

    // ---- weather text pieces, shared by every home style
    private fun wxReady() = store.showWeather && store.wTime != 0L && store.lat.isNotEmpty()
    private fun tf(c: Float): String {
        val f = store.fahrenheit
        return "${(if (f) c * 9f / 5f + 32f else c).roundToInt()}°"
    }
    internal fun wxMain(): String = when {
        !store.showWeather -> ""
        !wxReady() -> weatherError ?: "Loading weather…"
        else -> "${Weather.emoji(store.wCode, store.wDay)}  ${tf(store.wTemp)}   ${Weather.label(store.wCode)}"
    }
    internal fun wxSub(): String =
        if (wxReady()) "${store.place}    H ${tf(store.wHi)}   L ${tf(store.wLo)}" else ""
    internal fun wxTemp(): String =
        if (wxReady()) "${Weather.emoji(store.wCode, store.wDay)} ${tf(store.wTemp)}" else "…"
    internal fun wxDetail(): String =
        if (wxReady()) "H ${tf(store.wHi)} · L ${tf(store.wLo)} · ${Weather.label(store.wCode).uppercase(Locale.getDefault())}" else ""

    private fun updateWeather() {
        currentStyle?.onWeather()
        val w1 = weatherMain ?: return
        val w2 = weatherSub ?: return
        if (!store.showWeather) {
            w1.visibility = View.GONE
            w2.visibility = View.GONE
            return
        }
        w1.visibility = View.VISIBLE
        w2.visibility = View.VISIBLE
        w1.text = wxMain()
        w2.text = wxSub()
    }

    // ---- system facts for the Command HUD
    internal fun ipAddress(): String = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress ?: ""
    } catch (e: Exception) { "" }

    internal fun storageFree(): String = try {
        val st = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        "${(st.availableBytes / 1_000_000_000L)} GB"
    } catch (e: Exception) { "—" }

    internal fun ramUsedPct(): Int = try {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        (100 - mi.availMem * 100 / mi.totalMem).toInt().coerceIn(0, 100)
    } catch (e: Exception) { 0 }

    // ---- opening things
    internal fun openApp(e: AppEntry) {
        if (e.key.endsWith("/googletv")) { HomeGuardService.openGoogleTv(this); return }
        store.pushRecent(e.key)
        try { startActivity(e.intent) } catch (ex: Exception) {
            Toast.makeText(this, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Play opens the item in its app (background playback from the home screen was tried and removed). */
    internal fun playItem(item: TvFeed.Item, fallback: AppEntry?) = openItem(item, fallback)

    /** Opens the exact title when the app gave a deep link, otherwise just the app. */
    internal fun openItem(item: TvFeed.Item, fallback: AppEntry?) {
        val uri = item.intentUri
        if (!uri.isNullOrEmpty()) {
            try {
                val i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
                i.selector = null
                val comp = i.component
                // only ever open things inside the app that published the item
                if (comp != null && comp.packageName != item.pkg) throw SecurityException("foreign component")
                if (comp == null) i.`package` = item.pkg
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                (fallback ?: apps.firstOrNull { it.pkg == item.pkg })?.let { store.pushRecent(it.key) }
                startActivity(i)
                return
            } catch (e: Exception) {
                // fall through to opening the app
            }
        }
        (fallback ?: apps.firstOrNull { it.pkg == item.pkg })?.let { openApp(it) }
    }

    private fun refreshWeather(force: Boolean) {
        if (!store.showWeather || fetching) return
        val fresh = System.currentTimeMillis() - store.wTime < 30 * 60 * 1000L &&
                store.lat.isNotEmpty() && store.geoFor == (store.city.trim().ifEmpty { Weather.AUTO })
        if (!force && fresh) return
        fetching = true
        Thread {
            val err = Weather.fetch(store)
            runOnUiThread {
                fetching = false
                weatherError = err
                if (!isDestroyed) updateWeather()
            }
        }.start()
    }

    // ---------------------------------------------------------------- shared UI helpers

    private fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

    internal fun pill(text: String, action: () -> Unit): TextView {
        val r = u(16)
        val normal = rounded(Color.argb(40, 255, 255, 255), r, u(1), Color.argb(50, 255, 255, 255))
        return TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(u(14), u(7), u(14), u(7))
            isFocusable = true
            isClickable = true
            background = normal
            setOnClickListener { action() }
            setOnFocusChangeListener { v, has ->
                v.background = if (has) rounded(Color.argb(80, 255, 255, 255), r, u(2), accent) else normal
                v.animate().scaleX(if (has) 1.07f else 1f).scaleY(if (has) 1.07f else 1f).setDuration(120).start()
            }
        }
    }

    private fun launch(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- main screen

    private fun header(): View {
        val box = FrameLayout(this).apply { setPadding(u(44), u(30), u(44), u(10)) }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clock = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(58).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
        }
        dateView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14).toFloat())
            setTextColor(Color.argb(170, 255, 255, 255))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = 0.06f
            setPadding(u(3), u(4), 0, 0)
        }
        left.addView(clock)
        left.addView(dateView)
        box.addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        weatherMain = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(24).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.END
        }
        weatherSub = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
            setTextColor(Color.argb(160, 255, 255, 255))
            letterSpacing = 0.05f
            gravity = Gravity.END
            setPadding(0, u(2), 0, u(8))
        }
        right.addView(weatherMain)
        right.addView(weatherSub)

        val pills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            clipChildren = false
        }
        val gap = LinearLayout.LayoutParams(-2, -2).apply { setMargins(u(6), 0, 0, 0) }
        pills.addView(pill("Customize") { openSettings() }, gap)
        pills.addView(pill("Wi-Fi") { launch(Intent(Settings.ACTION_WIFI_SETTINGS)) }, gap)
        pills.addView(pill("Settings") { launch(Intent(Settings.ACTION_SETTINGS)) }, gap)
        right.addView(pills)
        box.addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP))

        updateClock()
        updateWeather()
        return box
    }

    /** Favorites first, then recents, then everything else; one list for the non-Classic styles. */
    private fun orderedApps(): List<AppEntry> {
        val hidden = store.hidden.toSet()
        val visible = apps.filter { it.key !in hidden }
        val byKey = visible.associateBy { it.key }
        val out = LinkedHashMap<String, AppEntry>()
        store.favs.mapNotNull { byKey[it] }.forEach { out[it.key] = it }
        if (store.showRecent) store.recents.mapNotNull { byKey[it] }.forEach { out.putIfAbsent(it.key, it) }
        visible.forEach { out.putIfAbsent(it.key, it) }
        return out.values.toList()
    }

    private fun applyChrome() {
        bg.setColors(Themes.ALL[store.theme].colors)
        // Command draws its own background
        bg.visibility = if (store.style == 3) View.INVISIBLE else View.VISIBLE
        bg.showGrid = store.style != 2
    }

    private fun render(focusId: String?) {
        if (store.style == 0) renderClassic(focusId) else renderStyle(focusId)
    }

    private fun renderStyle(focusId: String?) {
        val key = focusId?.substringAfter(':') ?: currentStyle?.currentKey() ?: lastKey
        renderedRecents = store.recents.joinToString("\n")
        tiles.clear()
        hideBackdrop()                       // the Classic watch-next backdrop is not used here
        currentStyle?.destroy()
        scroll.visibility = View.GONE
        host.visibility = View.VISIBLE
        host.removeAllViews()
        applyChrome()
        val st: HomeStyle = when (store.style) {
            1 -> CinemaStyle(this)
            2 -> HorizonStyle(this)
            else -> CommandStyle(this)
        }
        currentStyle = st
        host.addView(st.view, ViewGroup.LayoutParams(-1, -1))
        st.bind(orderedApps(), key)
        if (store.style == 3) bg.visibility = View.INVISIBLE
        if (panel == null) st.refocus()
    }

    private fun refocusHome() {
        if (store.style == 0) firstTile()?.requestFocus() else currentStyle?.refocus()
    }

    private fun renderClassic(focusId: String?) {
        currentStyle?.destroy()
        currentStyle = null
        host.removeAllViews()
        host.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        applyChrome()
        tiles.clear()
        renderedRecents = store.recents.joinToString("\n")
        content.removeAllViews()
        content.addView(header())
        // spacer pushes the rows to the lower part of the screen (clock stays top-left)
        content.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val hidden = store.hidden.toSet()
        val visible = apps.filter { it.key !in hidden }
        val byKey = visible.associateBy { it.key }
        val favs = store.favs.mapNotNull { byKey[it] }
        var index = 0
        if (favs.isNotEmpty()) content.addView(section("FAVORITES", "fav", favs, index++))
        if (store.showRecent) {
            val rec = store.recents.mapNotNull { byKey[it] }
            if (rec.isNotEmpty()) content.addView(section("RECENT", "rec", rec, index++))
        }
        content.addView(section("ALL APPS", "all", visible, index))

        if (panel == null) {
            val target = (focusId?.let { tiles[it] }) ?: firstTile()
            target?.post { target.requestFocus() }
        }
    }

    private fun firstTile(): View? =
        tiles.entries.firstOrNull { it.key.startsWith("fav:") }?.value
            ?: tiles.entries.firstOrNull { it.key.startsWith("all:") }?.value

    private fun section(title: String, id: String, list: List<AppEntry>, index: Int): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        wrap.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11).toFloat())
            setTextColor(accent)
            alpha = 0.85f
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(u(48), u(14), 0, u(2))
        })

        val hsv = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
        }
        val car = store.carousel
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            // carousel: side padding lets the first and last tile reach the centre of the screen
            val side = if (car) (resources.displayMetrics.widthPixels - cellWidth()) / 2 else u(36)
            setPadding(side, if (car) u(26) else u(6), side, if (car) u(16) else u(2))
        }
        val favSet = store.favs.toSet()
        for (e in list) {
            val t = tile(e, id, index, wrap, hsv, e.key in favSet)
            tiles["$id:${e.key}"] = t
            row.addView(t)
        }
        hsv.addView(row, ViewGroup.LayoutParams(-2, -2))
        wrap.addView(hsv, ViewGroup.LayoutParams(-1, -2))
        if (car) {
            hsv.setOnScrollChangeListener { _, _, _, _, _ -> applyCarousel(hsv, row) }
            hsv.post { applyCarousel(hsv, row) }
        }
        return wrap
    }

    private fun tileScale(): Float = floatArrayOf(0.85f, 1f, 1.2f)[store.tileSize]

    /** Icons-only tiles are square; banner tiles are wide (16:9). */
    private fun cardWidth(): Int = if (store.iconsOnly) u(92f * tileScale()) else u(152f * tileScale())
    private fun cardHeight(): Int = if (store.iconsOnly) u(92f * tileScale()) else u(86f * tileScale())

    /** Width of one tile cell including its padding and margins. */
    private fun cellWidth(): Int = cardWidth() + u(18) + u(4)

    /**
     * Coverflow: tiles are scaled, faded, tilted and pulled together according to how far
     * they are from the centre of the row. Runs on every scroll frame, so it animates for free.
     */
    private fun applyCarousel(hsv: HorizontalScrollView, row: LinearLayout) {
        val w = hsv.width
        if (w == 0) return
        val center = hsv.scrollX + w / 2f
        val cw = cellWidth().toFloat()
        val lift = u(3).toFloat()
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            val d = ((c.left + c.width / 2f) - center) / cw          // signed distance in tiles
            val a = minOf(abs(d), 3f)
            val s = maxOf(0.62f, 1.12f - 0.2f * a)
            c.scaleX = s
            c.scaleY = s
            c.alpha = maxOf(0.3f, 1f - 0.3f * a)
            c.rotationY = -d.coerceIn(-1.6f, 1.6f) * 26f
            c.translationX = -d.coerceIn(-2f, 2f) * cw * 0.16f
            c.translationZ = (3f - a) * lift
        }
    }

    private fun tile(
        e: AppEntry, sectionId: String, sectionIndex: Int,
        sectionView: View, hsv: HorizontalScrollView, pinned: Boolean
    ): View {
        val scale = tileScale()
        val car = store.carousel
        val cardW = cardWidth()
        val cardH = cardHeight()
        val icons = store.iconsOnly
        val r = u(12)

        val card = FrameLayout(this).apply {
            if (!icons) {
                background = rounded(Color.argb(40, 255, 255, 255), r)
                foreground = rounded(Color.TRANSPARENT, r, u(1), Color.argb(36, 255, 255, 255))
                clipToOutline = true
            }
        }
        if (icons) {
            // icons only: just the app icon - no tile background, no banner art, no text
            val pad = (cardW * 0.06f).toInt()
            card.addView(ImageView(this).apply {
                setImageDrawable(e.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(-1, -1).also { it.setMargins(pad, pad, pad, pad) })
        } else if (e.banner != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(e.banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(u(8), 0, u(8), 0)
            }
            inner.addView(ImageView(this).apply { setImageDrawable(e.icon) }, LinearLayout.LayoutParams(u(46), u(46)))
            inner.addView(TextView(this).apply {
                text = e.label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
                setTextColor(Color.argb(225, 255, 255, 255))
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, u(6), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            card.addView(inner, FrameLayout.LayoutParams(-1, -1))
        }
        if (pinned && sectionId != "fav") {
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
            }
            card.addView(View(this).apply { background = dot },
                FrameLayout.LayoutParams(u(7), u(7), Gravity.END or Gravity.TOP).also {
                    it.setMargins(0, u(7), u(7), 0)
                })
        }

        val label = TextView(this).apply {
            text = e.label
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0f
            setPadding(0, u(7), 0, 0)
        }

        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            setPadding(u(9), u(9), u(9), u(5))
            layoutParams = LinearLayout.LayoutParams(-2, -2).also { it.setMargins(u(2), 0, u(2), 0) }
            tag = e.key
            if (car) cameraDistance = resources.displayMetrics.density * 9000f   // soft perspective
        }
        cell.addView(card, LinearLayout.LayoutParams(cardW, cardH))
        cell.addView(label, LinearLayout.LayoutParams(cardW, -2))

        cell.setOnClickListener { openApp(e) }
        cell.setOnLongClickListener { showAppMenu(e, sectionId); true }
        cell.setOnFocusChangeListener { v, has ->
            if (!car) {   // in the carousel, scale and depth come from the scroll position instead
                v.animate().scaleX(if (has) 1.1f else 1f).scaleY(if (has) 1.1f else 1f).setDuration(140).start()
                v.translationZ = if (has) u(8).toFloat() else 0f
            }
            if (icons) {
                // no square anywhere: a soft round glow in the accent colour fades in behind the icon
                v.background = if (has) {
                    GradientDrawable().apply {
                        gradientType = GradientDrawable.RADIAL_GRADIENT
                        // radius = half the tile width, so the glow fades out before it reaches the
                        // tile's edges (otherwise the edges cut it into a visible rectangle)
                        gradientRadius = (cardW + u(18)) / 2f
                        setGradientCenter(0.5f, (u(9) + cardH / 2f) / (u(9) + cardH + u(30)))
                        colors = intArrayOf(
                            Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)),
                            Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent))
                        )
                    }
                } else null
            } else {
                v.background = if (has) rounded(Color.argb(38, Color.red(accent), Color.green(accent), Color.blue(accent)), u(18)) else null
                card.background = rounded(Color.argb(if (has) 78 else 40, 255, 255, 255), r)
                card.foreground = if (has) rounded(Color.TRANSPARENT, r, u(3), accent)
                else rounded(Color.TRANSPARENT, r, u(1), Color.argb(36, 255, 255, 255))
            }
            label.animate().alpha(if (has) 1f else 0f).setDuration(140).start()
            if (has) onTileFocused(e)
            if (has) {
                hsv.smoothScrollTo(maxOf(0, v.left + v.width / 2 - hsv.width / 2), 0)
                if (sectionIndex > 0) scroll.smoothScrollTo(0, maxOf(0, sectionView.top - u(110)))
                else scroll.smoothScrollTo(0, 0)
            }
        }
        return cell
    }

    // ---------------------------------------------------------------- app options (hold OK)

    private fun dialogBuilder(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setOnDismissListener { focusPanel?.invoke() }

    internal fun showAppMenu(e: AppEntry, sectionId: String) {
        val favs = store.favs
        val idx = favs.indexOf(e.key)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()

        if (idx >= 0) {
            labels += "Unpin from favorites"
            acts += {
                store.favs = favs - e.key
                render("all:${e.key}")
            }
            if (idx > 0) {
                labels += "Move left"
                acts += {
                    val l = favs.toMutableList()
                    l[idx] = l[idx - 1]; l[idx - 1] = e.key
                    store.favs = l
                    render("fav:${e.key}")
                }
            }
            if (idx < favs.size - 1) {
                labels += "Move right"
                acts += {
                    val l = favs.toMutableList()
                    l[idx] = l[idx + 1]; l[idx + 1] = e.key
                    store.favs = l
                    render("fav:${e.key}")
                }
            }
        } else {
            labels += "Pin to favorites"
            acts += {
                store.favs = favs + e.key
                render("$sectionId:${e.key}")
            }
        }
        labels += "Hide from launcher"
        acts += {
            store.hidden = store.hidden + e.key
            Toast.makeText(this, "${e.label} hidden. Restore it in Customize > Hidden apps", Toast.LENGTH_LONG).show()
            render(null)
        }
        labels += "App info"
        acts += {
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
        }
        if (!e.system) {
            labels += "Uninstall"
            acts += { launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}"))) }
        }

        dialogBuilder()
            .setTitle(e.label)
            .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
            .show()
    }

    // ---------------------------------------------------------------- customize panel (MENU)

    private fun applyTheme() {
        bg.setColors(Themes.ALL[store.theme].colors)
        render(null)
        // Neon and Duotone icons are drawn in the theme colour
        if (IconStyles.usesAccent(store.iconStyle)) reload(null)
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    /** The on-screen way into Customize for remotes that have no MENU key. */
    internal fun customizeButton(): TextView = pill("\u2699  Customize") { if (panel == null) openSettings() else closeSettings() }.apply {
        // nothing is above it: swallow UP so focus can't fall off the screen
        setOnKeyListener { _, k, e -> k == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN }
    }

    /** the visualiser appeared or went away: styles that paint their own backdrop adapt */
    internal fun onVisualizerChanged() {
        currentStyle?.onFeed()
        currentStyle?.onWeather()      // (Command repaints on this)
    }

    private fun vizLabel(): String = when {
        store.visualizer == 0 -> "Off"
        !VisualizerView.allowed(this) && !VisualizerView.isScene(store.visualizer) -> "Needs setup"
        else -> VisualizerView.MODES[store.visualizer]
    }

    private fun homeLabel(): String = when {
        !store.homeButton -> "Off"
        HomeGuardService.isEnabled(this) -> "On"
        else -> "Needs setup"
    }

    /** keeps the accessibility service in line with the setting (it is switched off when an app is force-stopped) */
    private fun syncHomeGuard() {
        if (!HomeGuardService.canSwitch(this)) return
        val want = store.homeButton
        if (want != HomeGuardService.isEnabled(this)) HomeGuardService.setEnabled(this, want)
    }

    internal fun openSettings() {
        if (panel != null) return
        val sizes = arrayOf("Small", "Medium", "Large")
        val styleNames = arrayOf("Cinema", "Horizon", "Command")
        val saverNames = arrayOf("Off", "2 min", "5 min", "10 min", "20 min")
        val saverScenes = intArrayOf(12, 11, 9, 10, 8, 7, -1)             // Interstellar, Blade Runner, Tron, Matrix, Cyberpunk, Desert, Rotate
        val rotNames = arrayOf("Off", "Every track", "Every 5 min", "Every 15 min")
        val rows = listOf(
            Row("Home style", { styleNames[store.style - 1] }) { d ->
                store.style = (store.style - 1 + d + 3) % 3 + 1
                render(null)
            },
            Row("Theme", { Themes.ALL[store.theme].name }) { d ->
                store.theme = (store.theme + d + Themes.ALL.size) % Themes.ALL.size
                applyTheme()
            },
            Row("Icon style", { IconStyles.NAMES[store.iconStyle] }) { d ->
                store.iconStyle = (store.iconStyle + d + IconStyles.NAMES.size) % IconStyles.NAMES.size
                reload(null)
            },
            Row("Music visualizer", { vizLabel() }) { d ->
                store.visualizer = (store.visualizer + d + VisualizerView.MODES.size) % VisualizerView.MODES.size
                viz.evaluate()
                onVisualizerChanged()
            },
            Row("Visualizer rotation", { rotNames[store.vizRotate] }) { d ->
                store.vizRotate = (store.vizRotate + d + 4) % 4
                viz.evaluate()
            },
            Row("Screensaver", { saverNames[store.saver] }) { d ->
                store.saver = (store.saver + d + 5) % 5
                lastInteraction = android.os.SystemClock.elapsedRealtime()
            },
            Row("Screensaver scene", { if (store.saverMode == -1) "Rotate" else VisualizerView.MODES[store.saverMode] }) { d ->
                val i = saverScenes.indexOf(store.saverMode).coerceAtLeast(0)
                store.saverMode = saverScenes[(i + d + saverScenes.size) % saverScenes.size]
            },
            Row("Animated background", { onOff(store.animated) }) {
                store.animated = !store.animated
                bg.animated = store.animated
            },
            Row("Weather", { onOff(store.showWeather) }) {
                store.showWeather = !store.showWeather
                updateWeather()
                if (store.showWeather) refreshWeather(false)
            },
            Row("Location", { if (store.city.isBlank()) "Auto" + (if (store.place.isNotEmpty()) " · ${store.place}" else "") else store.city }) { askCity() },
            Row("Temperature", { if (store.fahrenheit) "°F" else "°C" }) {
                store.fahrenheit = !store.fahrenheit
                updateWeather()
            },
            Row("Watch-next backdrop", { if (!TvFeed.allowed(this)) "Not allowed yet" else onOff(store.preview) }) {
                if (!TvFeed.allowed(this)) {
                    // READ_TV_LISTINGS can't be granted from a prompt on the TV, only with adb
                    Toast.makeText(this, "Needs a one-time adb grant: ask your assistant to run it", Toast.LENGTH_LONG).show()
                } else {
                    store.preview = !store.preview
                    if (!store.preview) hideBackdrop() else loadFeed()
                }
            },
            Row("Recent apps row", { onOff(store.showRecent) }) {
                store.showRecent = !store.showRecent
                render(null)
            },
            Row("Home button opens Aurora", { homeLabel() }) {
                if (!HomeGuardService.canSwitch(this) && !HomeGuardService.isEnabled(this)) {
                    Toast.makeText(this, "Needs a one-time adb grant: ask your assistant to run it", Toast.LENGTH_LONG).show()
                } else {
                    store.homeButton = !store.homeButton
                    syncHomeGuard()
                }
            },
            Row("Pause music when another app opens", { onOff(store.pauseOnSwitch) }) {
                store.pauseOnSwitch = !store.pauseOnSwitch
            },
            Row("Settings backup", { backupLabel() }) { askBackup() },
            Row("Hidden apps", { "${store.hidden.size}" }) { askHidden() }
        )

        val p = FrameLayout(this)
        p.setBackgroundColor(Color.argb(150, 0, 0, 0))
        p.isClickable = true

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(238, 10, 14, 28), u(18), u(1), Color.argb(60, 255, 255, 255))
            setPadding(u(20), u(18), u(20), u(14))
        }
        col.addView(TextView(this).apply {
            text = "CUSTOMIZE"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
            setTextColor(accent)
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(u(6), 0, 0, u(8))
        })

        val valueViews = ArrayList<Pair<Row, TextView>>()
        fun refresh() { for ((r, v) in valueViews) v.text = "‹  ${r.value()}  ›" }
        refreshPanel = { refresh() }
        focusPanel = { (panelRow ?: col.getChildAt(1))?.requestFocus() }

        var first: View? = null
        for (r in rows) {
            val rr = u(10)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(u(12), u(2.6f), u(12), u(2.6f))
            }
            line.addView(TextView(this).apply {
                text = r.title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14).toFloat())
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14).toFloat())
                setTextColor(Color.argb(200, 255, 255, 255))
                gravity = Gravity.END
            }
            line.addView(value, LinearLayout.LayoutParams(-2, -2))
            valueViews += r to value

            line.setOnFocusChangeListener { v, has ->
                v.background = if (has) rounded(Color.argb(46, 255, 255, 255), rr, u(2), accent) else null
                if (has) panelRow = v
            }
            line.setOnClickListener { r.act(1); refresh() }
            line.setOnKeyListener { _, kc, ev ->
                if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        r.act(if (kc == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        refresh()
                    }
                    true
                } else false
            }
            col.addView(line, LinearLayout.LayoutParams(-1, -2))
            if (first == null) first = line
        }
        col.addView(TextView(this).apply {
            text = "Aurora 3.16  ·  free, no ads, no account"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(10).toFloat())
            setTextColor(Color.argb(110, 255, 255, 255))
            setPadding(u(6), u(10), 0, 0)
        })
        refresh()

        p.addView(col, FrameLayout.LayoutParams(u(400), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, 0, u(30), 0)
        })
        rootView.addView(p, ViewGroup.LayoutParams(-1, -1))
        panel = p
        scroll.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        host.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        first?.requestFocus()
    }

    private fun closeSettings() {
        panel?.let { rootView.removeView(it) }
        panel = null
        refreshPanel = null
        focusPanel = null
        panelRow = null
        scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        host.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        refocusHome()
    }

    /** Empty city = detect the location automatically. */
    private fun applyCity(c: String) {
        store.city = c.trim()
        store.lat = ""
        store.geoFor = ""
        store.wTime = 0L
        weatherError = null
        updateWeather()
        refreshWeather(true)
        refreshPanel?.invoke()
    }

    private fun askCity() {
        val input = EditText(this).apply {
            setText(store.city)
            hint = "Leave empty to detect automatically"
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val holder = FrameLayout(this).apply { setPadding(u(24), u(8), u(24), 0); addView(input) }
        dialogBuilder()
            .setTitle("Weather location")
            .setView(holder)
            .setPositiveButton("Save") { _, _ -> applyCity(input.text.toString()) }
            .setNeutralButton("Auto-detect") { _, _ -> applyCity("") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askHidden() {
        val hidden = store.hidden.toSet()
        val list = apps.filter { it.key in hidden }
        if (list.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
            return
        }
        val checked = BooleanArray(list.size) { true }
        dialogBuilder()
            .setTitle("Hidden apps (untick to restore)")
            .setMultiChoiceItems(list.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                val restore = list.filterIndexed { i, _ -> !checked[i] }.map { it.key }.toSet()
                store.hidden = store.hidden.filter { it !in restore }
                render(null)
                refreshPanel?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- "what to watch next" backdrop

    private fun buildBackdrop() {
        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            visibility = View.GONE
        }
        rootView.addView(backdrop, ViewGroup.LayoutParams(-1, -1))

        // darkening: strong on the left (text) and at the bottom (app rows), clear top-right
        val left = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(225, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.argb(30, 0, 0, 0)))
        val bottom = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(150, 0, 0, 0), Color.argb(0, 0, 0, 0)))
        val scrim = View(this).apply {
            background = LayerDrawable(arrayOf(left, bottom))
            alpha = 0f
            visibility = View.GONE
            tag = "scrim"
        }
        rootView.addView(scrim, ViewGroup.LayoutParams(-1, -1))

        infoKind = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(11).toFloat())
            setTextColor(accent)
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        infoTitle = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(34).toFloat())
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, u(4), 0, u(4))
        }
        infoDesc = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(14).toFloat())
            setTextColor(Color.argb(210, 255, 255, 255))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(u(2).toFloat(), 1f)
        }
        backdropInfo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0f
            visibility = View.GONE
            addView(infoKind)
            addView(infoTitle)
            addView(infoDesc)
        }
        rootView.addView(backdropInfo, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.52f).toInt(), -2, Gravity.TOP or Gravity.START
        ).apply { setMargins(u(48), u(150), 0, 0) })

        // (added before the scroll view, so the rows and header stay on top of it)
    }

    private fun scrimView(): View? = rootView.findViewWithTag("scrim")

    private fun loadFeed() {
        if (!store.preview || !TvFeed.allowed(this)) {
            feed = emptyMap()
            return
        }
        Thread {
            val f = try { TvFeed.load(this) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                feed = f
                android.util.Log.i("AuroraFeed", "apps with watch-next content: " +
                        (if (f.isEmpty()) "none" else f.entries.joinToString { "${it.key}=${it.value.size}" }))
                // refresh what is shown for the currently focused app
                currentStyle?.onFeed()
                val cur = currentFocusedEntry()
                if (cur != null) onTileFocused(cur)
            }
        }.start()
    }

    private fun currentFocusedEntry(): AppEntry? {
        val key = (window.currentFocus?.tag as? String) ?: return null
        return apps.firstOrNull { it.key == key }
    }

    /** Debounced: moving quickly across the row doesn't load a picture for every tile it passes. */
    private fun onTileFocused(e: AppEntry) {
        lastKey = e.key
        if (store.style != 0) return
        pendingEntry = e
        handler.removeCallbacks(showRunnable)
        handler.postDelayed(showRunnable, 350)
    }

    private fun showFor(e: AppEntry?) {
        if (e == null || panel != null) return
        val items = if (store.preview) feed[e.pkg] else null
        if (items.isNullOrEmpty()) {
            hideBackdrop()
            return
        }
        if (shownPkg != e.pkg) {
            shownPkg = e.pkg
            shownIndex = 0
        }
        handler.removeCallbacks(cycleRunnable)
        showItem(items[shownIndex.coerceIn(0, items.size - 1)], false)
        if (items.size > 1) handler.postDelayed(cycleRunnable, 9000)
    }

    private fun showItem(item: TvFeed.Item, fade: Boolean) {
        val url = item.art
        shownUrl = url
        Images.load(this, url, 1280) { bmp ->
            if (isDestroyed || shownUrl != url || bmp == null) return@load
            val scrim = scrimView()
            // judge the picture by its real size: apps often report the wrong aspect ratio
            val wide = bmp.width >= bmp.height * 1.25f
            backdrop.scaleType = if (wide) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_END
            backdrop.setPadding(0, 0, if (wide) 0 else u(70), 0)
            infoKind.text = item.kind + "  ·  " + (apps.firstOrNull { it.pkg == item.pkg }?.label?.uppercase(Locale.getDefault()) ?: "")
            infoKind.setTextColor(accent)
            infoTitle.text = item.title
            infoDesc.text = item.desc
            infoDesc.visibility = if (item.desc.isEmpty()) View.GONE else View.VISIBLE

            val wasHidden = backdrop.visibility != View.VISIBLE || backdrop.alpha < 0.05f
            backdrop.visibility = View.VISIBLE
            scrim?.visibility = View.VISIBLE
            backdropInfo.visibility = View.VISIBLE
            if (wasHidden || !fade) {
                backdrop.setImageBitmap(bmp)
                backdrop.animate().alpha(1f).setDuration(380).start()
                scrim?.animate()?.alpha(1f)?.setDuration(380)?.start()
                backdropInfo.animate().alpha(1f).setDuration(380).start()
            } else {
                // dissolve from one picture to the next
                backdrop.animate().alpha(0f).setDuration(220).withEndAction {
                    backdrop.setImageBitmap(bmp)
                    backdrop.animate().alpha(1f).setDuration(320).start()
                }.start()
            }
            bg.visibility = View.INVISIBLE   // stop the background animation while it is covered
        }
    }

    private fun hideBackdrop() {
        handler.removeCallbacks(cycleRunnable)
        shownPkg = null
        shownUrl = null
        if (!::backdrop.isInitialized || backdrop.visibility != View.VISIBLE) {
            bg.visibility = View.VISIBLE
            return
        }
        bg.visibility = View.VISIBLE
        backdrop.animate().alpha(0f).setDuration(260).withEndAction { backdrop.visibility = View.GONE }.start()
        scrimView()?.let { v -> v.animate().alpha(0f).setDuration(260).withEndAction { v.visibility = View.GONE }.start() }
        backdropInfo.animate().alpha(0f).setDuration(200).withEndAction { backdropInfo.visibility = View.GONE }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7) {
            refreshPanel?.invoke()
            loadFeed()
        }
    }
}
