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
 * Network is used only for the weather (Open-Meteo, no key) and the artwork apps publish.
 *
 *  - two home styles, Cinema and Drift (see CinemaStyle, DriftStyle), over an animated scene or the theme's colours
 *  - hold OK on an app: pin / reorder / hide / app info / uninstall; hold OK on a title: pin
 *  - MENU or INFO key (or the on-screen "Customize"): style, theme, icons, visualizer, night dimming, weather ...
 */
class MainActivity : Activity() {

    private class Row(val title: String, val value: () -> String, val shown: () -> Boolean = { true }, val act: (Int) -> Unit)

    internal lateinit var store: Store
    private lateinit var rootView: FrameLayout
    internal lateinit var bg: AuroraView
    private var weatherError: String? = null
    private var fetching = false

    internal val handler = Handler(Looper.getMainLooper())
    internal var feed: Map<String, List<TvFeed.Item>> = emptyMap()

    // ---- the home style on screen (Cinema or Drift)
    private lateinit var host: FrameLayout
    internal lateinit var viz: VisualizerView
    private var currentStyle: HomeStyle? = null
    internal var lastKey: String? = null
    /** the black veil of the night dimming, above everything else */
    private lateinit var dim: View
    private var nightOn = false
    private val dimAgain = Runnable { if (nightOn) dim.animate().alpha(1f).setDuration(1500).start() }
    /** a full-screen picture hides the scene and the theme's colours: they stop drawing meanwhile */
    private var covered = false

    private var panel: FrameLayout? = null
    private var refreshPanel: (() -> Unit)? = null
    private var focusPanel: (() -> Unit)? = null
    private var panelRow: View? = null
    private var renderedRecents = ""

    private var apps: List<AppEntry> = emptyList()
    private var appByPkg: Map<String, AppEntry> = emptyMap()
    private var loading = false
    private var dirty = false
    /** the installed launcher entries as of the last load: apps installed while Aurora was in the background are noticed by comparing */
    @Volatile private var appsSig = ""

    // all sizes are relative to a 540-unit-high screen so the layout looks the same at any density
    private var unit = 1f
    internal fun u(n: Float): Int = (n * unit).toInt()
    internal fun u(n: Int): Int = u(n.toFloat())

    internal val accent: Int get() = Themes.ALL[store.theme].accent
    private val isBeta = BuildConfig.VERSION_NAME.contains("beta")

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
        bg.showGrid = false
        rootView.addView(bg, ViewGroup.LayoutParams(-1, -1))
        viz = VisualizerView(this)
        rootView.addView(viz, ViewGroup.LayoutParams(-1, -1))
        host = FrameLayout(this).apply { clipChildren = false }
        rootView.addView(host, ViewGroup.LayoutParams(-1, -1))
        dim = View(this).apply {
            setBackgroundColor(Color.argb(NIGHT_VEIL, 0, 0, 0))
            alpha = 0f
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        rootView.addView(dim, ViewGroup.LayoutParams(-1, -1))
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
        if (BuildConfig.DEBUG) return      // a debug build leaves the receiver (and its ports) to the installed release
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
        updateNight(false)
        updateWeather()
        refreshWeather(false)
        loadFeed()
        if (dirty) reload(null)
        else if (apps.isNotEmpty() && panel == null && launcherSig() != appsSig) reload(null)
        else if (apps.isNotEmpty() && panel == null && renderedRecents != store.recents.joinToString("\n")) {
            // coming back from an app: the order of the apps follows what was used last
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
        currentStyle?.home()
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
        // Back closes what the home style has open (a Drift panel)
        if (keyCode == KeyEvent.KEYCODE_BACK && currentStyle?.onBack() == true) return true
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
            if (ev.action == KeyEvent.ACTION_DOWN) { brighten(); exitAmbient() }
            return true
        }
        if (ev.action == KeyEvent.ACTION_DOWN) {
            brighten()
            currentStyle?.onKey()
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
        bg.visibility = View.INVISIBLE
        viz.covered = false
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
        rootView.addView(v, rootView.indexOfChild(dim), ViewGroup.LayoutParams(-1, -1))      // (under the night veil)
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
        host.visibility = View.VISIBLE
        viz.covered = covered
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
            val sig = launcherSig()
            val list = try { queryApps() } catch (e: Exception) { emptyList() }
            appsSig = sig
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

    /** cheap fingerprint of every app that has a launcher entry */
    private fun launcherSig(): String = try {
        val pm = packageManager
        (pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0) +
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            .map { it.activityInfo.packageName + "/" + it.activityInfo.name }.sorted().joinToString("|")
    } catch (e: Exception) { "" }

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

    // ---------------------------------------------------------------- clock, weather, night dimming

    internal fun nowStr(pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    internal fun clockPattern(): String = if (DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"

    private fun updateClock() {
        currentStyle?.onTick()
        updateNight(true)
    }

    /** Night dimming: inside the night hours a black veil lies over the whole home, scenes included. */
    private fun updateNight(animate: Boolean) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val on = HomeLogic.isNight(store.nightMode, hour, store.nightFrom, store.nightUntil)
        if (on == nightOn) return
        nightOn = on
        handler.removeCallbacks(dimAgain)
        dim.animate().cancel()
        if (animate) dim.animate().alpha(if (on) 1f else 0f).setDuration(1200).start() else dim.alpha = if (on) 1f else 0f
    }

    /** any key press lifts the veil for a few seconds */
    private fun brighten() {
        if (!nightOn) return
        handler.removeCallbacks(dimAgain)
        dim.animate().cancel()
        dim.animate().alpha(0f).setDuration(180).start()
        handler.postDelayed(dimAgain, NIGHT_BRIGHT_MS)
    }

    // ---- weather text pieces, shared by the home styles
    private fun wxReady() = store.showWeather && store.wTime != 0L && store.lat.isNotEmpty()
    private fun tf(c: Float): String {
        val f = store.fahrenheit
        return "${(if (f) c * 9f / 5f + 32f else c).roundToInt()}°"
    }
    /** "15° Place" */
    internal fun wxShort(): String = if (wxReady()) "${tf(store.wTemp)} ${store.place}".trim() else ""
    /** "15°  clear" */
    internal fun wxNow(): String =
        if (wxReady()) "${tf(store.wTemp)}  ${Weather.label(store.wCode).lowercase(Locale.getDefault())}" else ""
    internal fun wxPlace(): String = if (wxReady()) store.place else ""
    /** Cinema header: "☀  15°   Clear" */
    internal fun wxMain(): String = when {
        !store.showWeather -> ""
        !wxReady() -> weatherError ?: "Loading weather…"
        else -> "${Weather.emoji(store.wCode, store.wDay)}  ${tf(store.wTemp)}   ${Weather.label(store.wCode)}"
    }
    /** Cinema header: "Lisbon    H 18°   L 9°" */
    internal fun wxSub(): String =
        if (wxReady()) "${store.place}    H ${tf(store.wHi)}   L ${tf(store.wLo)}" else ""

    /** Cinema's on-screen way into Customize for remotes that have no MENU key. */
    internal fun customizeButton(): TextView = pill("\u2699  Customize") { if (panel == null) openSettings() else closeSettings() }.apply {
        // nothing is above it: swallow UP so focus can't fall off the screen
        setOnKeyListener { _, k, e -> k == KeyEvent.KEYCODE_DPAD_UP && e.action == KeyEvent.ACTION_DOWN }
    }

    private fun updateWeather() {
        currentStyle?.onWeather()
    }

    // ---- opening things
    internal fun openApp(e: AppEntry) {
        if (e.key.endsWith("/googletv")) { HomeGuardService.openGoogleTv(this); return }
        store.pushRecent(e.key)
        try { startActivity(e.intent) } catch (ex: Exception) {
            Toast.makeText(this, "Can't open ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

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

    // ---------------------------------------------------------------- the home screen

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

    /** the user's app for a package (the first of its entries, in the user's order) */
    internal fun appFor(pkg: String): AppEntry? = appByPkg[pkg]

    private fun pkgOrder(): List<String> = appByPkg.keys.toList()

    /** every lane of the home: across all apps, the pinned titles, one per app (see HomeLogic.lanes) */
    internal fun lanes(): List<HomeLogic.Lane> = HomeLogic.lanes(feed, pkgOrder(), { appByPkg[it]?.label }, store.pins)

    /** the shelves of the music apps: playlists, albums, artists */
    internal fun musicLanes(): List<HomeLogic.Lane> = HomeLogic.musicLanes(feed, pkgOrder()) { appByPkg[it]?.label }

    /** the pinned titles as one lane, or null when nothing is pinned */
    internal fun pinLane(): HomeLogic.Lane? {
        val pins = store.pins.filter { appByPkg.containsKey(it.pkg) }
        return if (pins.isEmpty()) null else HomeLogic.Lane(HomeLogic.TITLE_PINNED, pins.map { it.toItem() }, true)
    }

    /** may the home take the focus now? (not while Customize or the screensaver is up) */
    internal fun homeMayFocus(): Boolean = panel == null && !ambientOn

    /** A full-screen picture covers the scene and the theme's colours: they stop drawing until it goes away. */
    internal fun setBackdropCovers(on: Boolean) {
        if (covered == on) return
        covered = on
        if (ambientOn) return
        viz.covered = on
        bg.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    private fun applyChrome() {
        bg.setColors(Themes.ALL[store.theme].colors)
        bg.visibility = if (covered) View.INVISIBLE else View.VISIBLE
    }

    /** Builds the home screen in the chosen style. [focusKey] is the app that should hold the focus afterwards, if any. */
    private fun render(focusKey: String?) {
        val key = focusKey ?: currentStyle?.currentKey()
        renderedRecents = store.recents.joinToString("\n")
        currentStyle?.destroy()
        host.removeAllViews()
        val ordered = orderedApps()
        val byPkg = LinkedHashMap<String, AppEntry>()
        for (e in ordered) byPkg.putIfAbsent(e.pkg, e)
        appByPkg = byPkg
        applyChrome()
        val st: HomeStyle = if (store.style == HomeLogic.DRIFT) DriftStyle(this) else CinemaStyle(this)
        currentStyle = st
        host.addView(st.view, ViewGroup.LayoutParams(-1, -1))
        st.bind(ordered, key)
        if (homeMayFocus()) st.refocus()
    }

    private fun refocusHome() {
        currentStyle?.refocus()
    }

    // ---------------------------------------------------------------- options (hold OK)

    private fun dialogBuilder(): AlertDialog.Builder =
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setOnDismissListener { focusPanel?.invoke() }

    /** Options of an app. Pinned apps come first everywhere and are the apps of Drift's pins panel. */
    internal fun showAppMenu(e: AppEntry) {
        val favs = store.favs
        val idx = favs.indexOf(e.key)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()

        if (idx >= 0) {
            labels += "Unpin"
            acts += {
                store.favs = favs - e.key
                render(e.key)
            }
            if (idx > 0) {
                labels += "Move earlier"
                acts += {
                    val l = favs.toMutableList()
                    l[idx] = l[idx - 1]; l[idx - 1] = e.key
                    store.favs = l
                    render(e.key)
                }
            }
            if (idx < favs.size - 1) {
                labels += "Move later"
                acts += {
                    val l = favs.toMutableList()
                    l[idx] = l[idx + 1]; l[idx + 1] = e.key
                    store.favs = l
                    render(e.key)
                }
            }
        } else {
            labels += "Pin"
            acts += {
                store.favs = favs + e.key
                Toast.makeText(this, "${e.label} pinned: it now comes first", Toast.LENGTH_SHORT).show()
                render(e.key)
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

    /** Options of a title in a lane: pin it (it then has its own lane, and sits in Drift's pins panel) or unpin it. */
    internal fun showItemMenu(item: TvFeed.Item) {
        val pin = HomeLogic.pinOf(item)
        val pins = store.pins
        val pinned = HomeLogic.isPinned(pins, pin.id)
        val app = appFor(item.pkg)
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        labels += if (pinned) "Unpin" else "Pin"
        acts += {
            store.pins = HomeLogic.togglePin(pins, pin)
            Toast.makeText(this, if (pinned) "Unpinned" else "Pinned", Toast.LENGTH_SHORT).show()
            currentStyle?.onFeed()
        }
        labels += "Play"
        acts += { openItem(item, app) }
        if (app != null) {
            labels += "Open ${app.label}"
            acts += { openApp(app) }
        }
        dialogBuilder()
            .setTitle(item.title)
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

    /** the visualiser appeared or went away: styles that paint their own backdrop adapt */
    internal fun onVisualizerChanged() {
        currentStyle?.onFeed()
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
        if (BuildConfig.DEBUG) return      // debug builds have no Home-button service
        if (!HomeGuardService.canSwitch(this)) return
        val want = store.homeButton
        if (want != HomeGuardService.isEnabled(this)) HomeGuardService.setEnabled(this, want)
    }

    internal fun openSettings() {
        if (panel != null) return
        val sizes = arrayOf("Small", "Medium", "Large")
        val nightNames = arrayOf("Off", "Auto (23:00 to 07:00)", "Custom hours")
        fun hour(h: Int) = String.format(Locale.US, "%02d:00", h)
        val saverNames = arrayOf("Off", "2 min", "5 min", "10 min", "20 min")
        val saverScenes = intArrayOf(6, 5, 4, -1)             // Blade Runner, Cyberpunk, Desert, Rotate
        val rotNames = arrayOf("Off", "Every track", "Every 5 min", "Every 15 min")
        val rows = listOf(
            Row("Home style", { HomeLogic.STYLE_NAMES[store.style - 1] }) {
                store.style = if (store.style == HomeLogic.CINEMA) HomeLogic.DRIFT else HomeLogic.CINEMA
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
            Row("Night dimming", { nightNames[store.nightMode] }) { d ->
                store.nightMode = (store.nightMode + d + 3) % 3
                updateNight(true)
            },
            Row("Dim from", { hour(store.nightFrom) }, { store.nightMode == HomeLogic.NIGHT_CUSTOM }) { d ->
                store.nightFrom = (store.nightFrom + d + 24) % 24
                updateNight(true)
            },
            Row("Dim until", { hour(store.nightUntil) }, { store.nightMode == HomeLogic.NIGHT_CUSTOM }) { d ->
                store.nightUntil = (store.nightUntil + d + 24) % 24
                updateNight(true)
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
            Row("Titles from your apps", { if (!TvFeed.allowed(this)) "Not allowed yet" else onOff(store.preview) }) {
                if (!TvFeed.allowed(this)) {
                    // READ_TV_LISTINGS can't be granted from a prompt on the TV, only with adb
                    Toast.makeText(this, "Needs a one-time adb grant: the README has the command", Toast.LENGTH_LONG).show()
                } else {
                    store.preview = !store.preview
                    loadFeed()
                }
            },
            Row("Recently used apps first", { onOff(store.showRecent) }) {
                store.showRecent = !store.showRecent
                render(null)
            },
            Row("Home button opens Aurora", { homeLabel() }) {
                if (!HomeGuardService.canSwitch(this) && !HomeGuardService.isEnabled(this)) {
                    Toast.makeText(this, "Needs a one-time adb grant: the README has the command", Toast.LENGTH_LONG).show()
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
            setPadding(u(20), u(14), u(20), u(10))
        }
        col.addView(TextView(this).apply {
            // a beta build says so (the version name ends in "-beta")
            text = if (isBeta) "CUSTOMIZE   ·   BETA" else "CUSTOMIZE"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(12).toFloat())
            setTextColor(accent)
            letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(u(6), 0, 0, u(8))
        })

        val valueViews = ArrayList<Pair<Row, TextView>>()
        fun refresh() {
            for ((r, v) in valueViews) {
                v.text = "‹  ${r.value()}  ›"
                (v.parent as View).visibility = if (r.shown()) View.VISIBLE else View.GONE
            }
        }
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
                setPadding(u(12), u(1.8f), u(12), u(1.8f))
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
            text = "Aurora " + BuildConfig.VERSION_NAME.replace("-", " ") + "  ·  free, no ads, no account"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(10).toFloat())
            setTextColor(Color.argb(110, 255, 255, 255))
            setPadding(u(6), u(10), 0, 0)
        })
        refresh()

        // the list scrolls when it is taller than the screen
        val sheet = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        sheet.addView(col, ViewGroup.LayoutParams(-1, -2))
        p.addView(sheet, FrameLayout.LayoutParams(u(400), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            setMargins(0, u(12), u(30), u(12))
        })
        rootView.addView(p, rootView.indexOfChild(dim), ViewGroup.LayoutParams(-1, -1))      // (under the night veil)
        panel = p
        host.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        first?.requestFocus()
    }

    private fun closeSettings() {
        panel?.let { rootView.removeView(it) }
        panel = null
        refreshPanel = null
        focusPanel = null
        panelRow = null
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

    // ---------------------------------------------------------------- what the apps publish

    private fun loadFeed() {
        if (!store.preview || !TvFeed.allowed(this)) {
            feed = emptyMap()
            currentStyle?.onFeed()
            return
        }
        Thread {
            val f = try { TvFeed.load(this) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                feed = f
                android.util.Log.i("AuroraFeed", "apps with published titles: " +
                        (if (f.isEmpty()) "none" else f.entries.joinToString { "${it.key}=${it.value.size}" }))
                currentStyle?.onFeed()
            }
        }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 7) {
            refreshPanel?.invoke()
            loadFeed()
        }
    }

    private companion object {
        /** how dark the night veil is: 0..255 (about 40 %) */
        const val NIGHT_VEIL = 102
        /** how long a key press keeps the home bright at night */
        const val NIGHT_BRIGHT_MS = 8000L
    }
}
