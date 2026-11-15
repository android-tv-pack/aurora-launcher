package app.hermes.aurora

import android.app.Activity
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

    /** a setting of Customize; [steps] = Left and Right step through its values (otherwise OK acts) */
    private class Row(val title: String, val desc: String, val value: () -> String, val shown: () -> Boolean = { true },
                      val steps: Boolean = true,
                      /** for the tab with the scene picture: what this setting shows there, as (caption, visualizer mode) */
                      val scene: (() -> Pair<String, Int>)? = null,
                      val act: (Int) -> Unit)
    /** a tab of Customize: its settings on the left; on the right what it is for, figures, and for Look a live picture */
    private class Tab(val name: String, val blurb: String,
                      /** the picture in the card: 0 none, 1 the home screen, 2 the visualizer or screensaver scene */
                      val preview: Int = 0,
                      val stats: () -> List<Pair<String, String>> = { emptyList() }, val rows: List<Row>)

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
        AdBlock.ensure(this, store)
        if (Tmdb.takeDroppedKey(this, store)) dirty = true
        store.dropPlaylist()
        if (Iptv.takeDropped(this, store)) syncHomeGuard()
        Iptv.refresh(this, store, false) { refreshPanel?.invoke(); if (Iptv.stamp(store) != tvStamp) loadFeed() }
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

    @Deprecated("the platform's own result callback; Aurora has no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == AdBlock.REQUEST) {
            AdBlock.onPermission(this, store, resultCode == RESULT_OK)
            for (t in longArrayOf(300, 2000)) handler.postDelayed({ refreshPanel?.invoke() }, t)
        }
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
        sheet("Settings backup", "Kept on this device. Restoring replaces the settings you have now. Your TMDB key and watched channels are not part of it.")
            .item("Back up now", { backupLabel().let { if (it == "Not yet") "" else "Last: $it" } }) {
                try { backupFile().parentFile?.mkdirs(); backupFile().writeText(store.exportJson()); Toast.makeText(this, "Backed up", Toast.LENGTH_SHORT).show() }
                catch (e: Exception) { Toast.makeText(this, "Backup failed", Toast.LENGTH_SHORT).show() }
                refreshPanel?.invoke()
            }
            .item("Restore from the backup") {
                val f = backupFile()
                val n = if (f.exists()) store.importJson(f.readText()) else -1
                if (n < 0) Toast.makeText(this, "No backup found", Toast.LENGTH_SHORT).show()
                else { Toast.makeText(this, "Restored $n settings", Toast.LENGTH_SHORT).show(); recreate() }
            }
            .open()
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
                askForPreviews()
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
        // TV channels: a new programme has started on one of them
        if (Iptv.on(store) && Iptv.stamp(store) != tvStamp) loadFeed()
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

    internal fun rounded(fill: Int, radius: Int, strokeW: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
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
    internal fun musicLanes(): List<HomeLogic.Lane> = HomeLogic.musicLanes(feed, pkgOrder(), first = musicApp()?.pkg.orEmpty()) { appByPkg[it]?.label }

    private fun isAudioApp(pkg: String): Boolean = try {
        android.os.Build.VERSION.SDK_INT >= 26 && packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_AUDIO
    } catch (_: Exception) { false }

    /** the user's music app: the chosen one, else the first app that offers covers, else the first that calls itself an audio app */
    internal fun musicApp(): AppEntry? {
        appByPkg[store.musicApp]?.let { return it }
        val shown = apps.filter { it.pkg != packageName && !it.key.endsWith("/googletv") && it.key !in store.hidden }
        return shown.firstOrNull { e -> feed[e.pkg].orEmpty().any { it.square && !it.channel } } ?: shown.firstOrNull { isAudioApp(it.pkg) }
    }

    private fun askMusicApp() {
        val list = manageable().sortedBy { !isAudioApp(it.pkg) }
        val sh = sheet("Music app", "Its playlists come first in the music panel, and it is the app that panel offers to open when nothing plays. " +
            "Automatic takes the first app that offers playlists.")
        sh.item("Automatic", { if (store.musicApp.isEmpty() || appByPkg[store.musicApp] == null) "\u2713" else "" }) { store.musicApp = ""; refreshPanel?.invoke(); currentStyle?.onFeed() }
        for (e in list) sh.item(e.label, { if (store.musicApp == e.pkg) "\u2713" else "" }) { store.musicApp = e.pkg; refreshPanel?.invoke(); currentStyle?.onFeed() }
        sh.open()
    }

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

    /** Aurora's own dialog (see Sheet); when it closes the focus goes back to where it was in Customize */
    private fun sheet(title: String, message: String? = null) = Sheet(this, title, message)
    private fun Sheet.open() = show { focusPanel?.invoke() }

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

        val sh = sheet(e.label)
        for (i in labels.indices) sh.item(labels[i], danger = labels[i] == "Uninstall", act = acts[i])
        sh.open()
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
        val sh = sheet(item.title)
        for (i in labels.indices) sh.item(labels[i], act = acts[i])
        sh.open()
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
        // the same service notes the channel the IPTV app shows (TV channels), also with the Home button left alone
        val want = store.homeButton || Iptv.on(store)
        if (want != HomeGuardService.isEnabled(this)) HomeGuardService.setEnabled(this, want)
    }

    private fun settingsTabs(): List<Tab> {
        val nightNames = arrayOf("Off", "Auto (23:00 to 07:00)", "Custom hours")
        fun hour(h: Int) = String.format(Locale.US, "%02d:00", h)
        fun num(n: Int) = String.format(Locale.US, "%,d", n)
        val saverNames = arrayOf("Off", "2 min", "5 min", "10 min", "20 min")
        val saverScenes = intArrayOf(6, 5, 4, -1)             // Blade Runner, Cyberpunk, Desert, Rotate
        val rotNames = arrayOf("Off", "Every track", "Every 5 min", "Every 15 min")
        val vizScene = { ("MUSIC VISUALIZER  ·  " + VisualizerView.MODES[store.visualizer].uppercase(Locale.US)) to store.visualizer }
        val saverScene = {
            if (store.saverMode == -1) "SCREENSAVER  ·  ROTATES THROUGH ITS SCENES" to saverScenes[0]
            else ("SCREENSAVER  ·  " + VisualizerView.MODES[store.saverMode].uppercase(Locale.US)) to store.saverMode
        }
        return listOf(
            Tab("Look", "How the home screen looks. The picture shows your home as it is now and follows every change.", preview = 1, rows = listOf(
                Row("Home style", "The layout of the home screen", { HomeLogic.STYLE_NAMES[store.style - 1] }) {
                    store.style = if (store.style == HomeLogic.CINEMA) HomeLogic.DRIFT else HomeLogic.CINEMA
                    render(null)
                },
                Row("Theme", "The glow colours and the colour of highlights", { Themes.ALL[store.theme].name }) { d ->
                    store.theme = (store.theme + d + Themes.ALL.size) % Themes.ALL.size
                    applyTheme()
                },
                Row("Icon style", "How the icons of your apps are drawn", { IconStyles.NAMES[store.iconStyle] }) { d ->
                    store.iconStyle = (store.iconStyle + d + IconStyles.NAMES.size) % IconStyles.NAMES.size
                    reload(null)
                },
                Row("Animated background", "Colours that drift slowly behind the home", { onOff(store.animated) }) {
                    store.animated = !store.animated
                    bg.animated = store.animated
                }
            )),
            Tab("Home", "What is on the home screen: your apps, what they offer to watch, and the weather.",
                stats = { listOf("APPS ON THE HOME" to num(apps.count { it.key !in store.hidden.toSet() }), "HIDDEN" to num(store.hidden.size)) }, rows = listOf(
                Row("Titles from your apps", "Films, series and music your apps offer for the home screen",
                    { if (!TvFeed.allowed(this)) "Not allowed yet" else onOff(store.preview) }) {
                    if (!TvFeed.allowed(this)) {
                        // READ_TV_LISTINGS can't be granted from a prompt on the TV, only with adb
                        Toast.makeText(this, "Needs a one-time adb grant: the README has the command", Toast.LENGTH_LONG).show()
                    } else {
                        store.preview = !store.preview
                        loadFeed()
                    }
                },
                Row("Recently used apps first", "The apps you opened last move to the front", { onOff(store.showRecent) }) {
                    store.showRecent = !store.showRecent
                    render(null)
                },
                Row("Hidden apps", "Apps you keep off the home screen", { num(store.hidden.size) }, steps = false) { askHidden() },
                Row("Weather", "Temperature and sky in the corner of the home", { onOff(store.showWeather) }) {
                    store.showWeather = !store.showWeather
                    updateWeather()
                    if (store.showWeather) refreshWeather(false)
                },
                Row("Location", "The place the weather is for; Auto finds it by itself",
                    { if (store.city.isBlank()) "Auto" + (if (store.place.isNotEmpty()) " · ${store.place}" else "") else store.city },
                    { store.showWeather }, steps = false) { askCity() },
                Row("Temperature", "Celsius or Fahrenheit", { if (store.fahrenheit) "°F" else "°C" }, { store.showWeather }) {
                    store.fahrenheit = !store.fahrenheit
                    updateWeather()
                }
            )),
            Tab("Music & screensaver", "The picture shows the setting you are on, moving as it will on the home screen. " +
                "With music playing it follows the music.", preview = 2, rows = listOf(
                Row("Music app", "Your player: first in the music panel, and opened from it",
                    { (if (appByPkg[store.musicApp] == null) "Automatic  ·  " else "") + (musicApp()?.label ?: "none found") }, steps = false) { askMusicApp() },
                Row("Music visualizer", "A scene that moves with the music on the home screen", { vizLabel() }, scene = vizScene) { d ->
                    store.visualizer = (store.visualizer + d + VisualizerView.MODES.size) % VisualizerView.MODES.size
                    viz.evaluate()
                    onVisualizerChanged()
                },
                Row("Visualizer rotation", "Changes the scene by itself", { rotNames[store.vizRotate] }, { store.visualizer != 0 }, scene = vizScene) { d ->
                    store.vizRotate = (store.vizRotate + d + 4) % 4
                    viz.evaluate()
                },
                Row("Screensaver", "Starts when the home screen has been left alone this long", { saverNames[store.saver] }, scene = saverScene) { d ->
                    store.saver = (store.saver + d + 5) % 5
                    lastInteraction = android.os.SystemClock.elapsedRealtime()
                },
                Row("Screensaver scene", "What the screensaver shows",
                    { if (store.saverMode == -1) "Rotate" else VisualizerView.MODES[store.saverMode] }, { store.saver != 0 }, scene = saverScene) { d ->
                    val i = saverScenes.indexOf(store.saverMode).coerceAtLeast(0)
                    store.saverMode = saverScenes[(i + d + saverScenes.size) % saverScenes.size]
                },
                Row("Night dimming", "Darkens the home screen during the night", { nightNames[store.nightMode] }) { d ->
                    store.nightMode = (store.nightMode + d + 3) % 3
                    updateNight(true)
                },
                Row("Dim from", "The hour the dimming starts", { hour(store.nightFrom) }, { store.nightMode == HomeLogic.NIGHT_CUSTOM }) { d ->
                    store.nightFrom = (store.nightFrom + d + 24) % 24
                    updateNight(true)
                },
                Row("Dim until", "The hour the dimming ends", { hour(store.nightUntil) }, { store.nightMode == HomeLogic.NIGHT_CUSTOM }) { d ->
                    store.nightUntil = (store.nightUntil + d + 24) % 24
                    updateNight(true)
                }
            )),
            Tab("Content", "Extras for what your apps offer. Extra artwork adds full-screen pictures, title logos, ratings and descriptions from TMDB, with your own free key. " +
                "TV channels shows the five channels you watched last, with what is on now and next.",
                stats = {
                    val out = ArrayList<Pair<String, String>>()
                    if (Tmdb.on(store)) Tmdb.matched(this).let { (found, all) -> out += "TITLES WITH EXTRA ARTWORK" to "${num(found)} of ${num(all)} looked up" }
                    if (Iptv.on(store)) {
                        out += "PROGRAMME GUIDE" to (if (Iptv.guideTime == 0L) Iptv.label(store)
                            else "Read " + SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(Iptv.guideTime)) + "  ·  " + num(Iptv.guideChannels) + " channels")
                        out += "WATCHED LAST" to (Iptv.decode(store.iptvRecent).joinToString("\n") { it.name }.ifEmpty { "Nothing yet: watch a channel for half a minute" })
                    }
                    out
                }, rows = listOf(
                Row("Extra artwork (TMDB)", "Sharper pictures, logos, ratings and descriptions for films and series", { Tmdb.label(store) }, steps = false) {
                    if (store.tmdbKey.isEmpty() || Tmdb.keyRefused) askTmdbKey()
                    else { store.tmdb = !store.tmdb; currentStyle?.onFeed(); render(null) }
                },
                Row("TMDB key", "Your own free key from themoviedb.org", { if (store.tmdbKey.isEmpty()) "Not set" else "Set" }, steps = false) { askTmdbKey() },
                Row("TV channels", "The channels you watched last, from your programme guide", { Iptv.label(store) }, steps = false) { askIptv() },
                Row("Channels open in", "Your IPTV app; only its screen is read, only for the channel name", { iptvApp()?.label ?: "" }, { Iptv.on(store) }) { d ->
                    val hidden = store.hidden.toSet()
                    val l = apps.filter { it.key !in hidden && !it.key.endsWith("/googletv") && it.pkg != packageName }.distinctBy { it.pkg }
                    if (l.isNotEmpty()) {
                        store.iptvApp = l[(l.indexOfFirst { it.pkg == iptvApp()?.pkg } + d + l.size) % l.size].pkg
                        loadFeed()
                    }
                }
            )),
            Tab("Ad blocker", "Refuses to look up the addresses of known ad and tracking servers, for every app on this device. " +
                "It filters name lookups only, on the device itself; nothing is sent anywhere. Android asks for permission once. " +
                "Ads that come from the same servers as the video itself (YouTube's) are not affected.",
                stats = {
                    listOf("STATUS" to (if (store.adblock == 0) "Off" else if (AdBlock.running) "Running" else AdBlock.label(this, store)),
                        "BLOCK LIST" to (if (AdBlock.domains > 0) num(AdBlock.domains) + " addresses" else "Read when switched on"),
                        "BLOCKED SINCE IT STARTED" to (if (AdBlock.running) num(AdBlock.blocked.get()) + " lookups" else "\u2014"))
                }, rows = listOf(
                Row("Ad blocker", "Blocks ads and trackers by their addresses", { onOff(store.adblock != 0) }) { d ->
                    AdBlock.choose(this, store, (store.adblock + d + 2) % 2)
                }
            )),
            Tab("System", "How Aurora works with the device, and how the device is doing.",
                stats = {
                    val out = ArrayList<Pair<String, String>>()
                    var free = 0L; var total = 0L; var low = false
                    try { val st = android.os.StatFs(android.os.Environment.getDataDirectory().path); free = st.availableBytes; total = st.totalBytes } catch (_: Exception) {}
                    val mi = android.app.ActivityManager.MemoryInfo()
                    try { (getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi); low = mi.lowMemory } catch (_: Exception) {}
                    out += "HEALTH" to HomeLogic.health(free, total, low)
                    if (total > 0) out += "STORAGE" to HomeLogic.size(free) + " free of " + HomeLogic.size(total)
                    if (mi.totalMem > 0) out += "MEMORY" to HomeLogic.size(mi.availMem) + " free of " + HomeLogic.size(mi.totalMem)
                    out += "NETWORK" to networkLine()
                    out += "RUNNING FOR" to HomeLogic.uptime(android.os.SystemClock.elapsedRealtime()) + "  ·  " + android.os.Build.MODEL + ", Android " + android.os.Build.VERSION.RELEASE
                    out
                }, rows = listOf(
                Row("Home button opens Aurora", "The Home key of the remote comes here, not to the built-in home", { homeLabel() }) {
                    if (!HomeGuardService.canSwitch(this) && !HomeGuardService.isEnabled(this)) {
                        Toast.makeText(this, "Needs a one-time adb grant: the README has the command", Toast.LENGTH_LONG).show()
                    } else {
                        store.homeButton = !store.homeButton
                        syncHomeGuard()
                    }
                },
                Row("Pause music when another app opens", "Music does not play on under a film", { onOff(store.pauseOnSwitch) }) {
                    store.pauseOnSwitch = !store.pauseOnSwitch
                },
                Row("Apps", "Open, update or remove the apps on this device", { num(manageable().size) }, steps = false) { askApps() },
                Row("Clear cache", "Free the space taken by temporary files", { HomeLogic.size(ownCache()) }, steps = false) { askCache() },
                Row("Settings backup", "Save these settings, or bring saved ones back", { backupLabel() }, steps = false) { askBackup() }
            ))
        )
    }

    /** "Wi-Fi  ·  192.168.1.20", "Ethernet", "Not connected" */
    private fun networkLine(): String = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(n)
        if (n == null || caps == null) "Not connected" else {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "Connected"
                else -> "Connected"
            }
            val ip = cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            kind + (if (ip != null) "  ·  $ip" else "")
        }
    } catch (_: Exception) { "Unknown" }

    /** the apps of the home screen that can be managed: all but Aurora itself and the entry for the built-in home */
    private fun manageable(): List<AppEntry> =
        apps.filter { it.pkg != packageName && !it.key.endsWith("/googletv") }.distinctBy { it.pkg }.sortedBy { it.label.lowercase(Locale.getDefault()) }

    private fun askApps() {
        val list = manageable()
        if (list.isEmpty()) return
        val sh = sheet("Apps", "Choose an app to open it, look for an update, see its storage, or remove it.")
        for (e in list) {
            val version = try { packageManager.getPackageInfo(e.pkg, 0).versionName.orEmpty() } catch (_: Exception) { "" }
            sh.item(e.label, { version }) { askApp(e) }
        }
        sh.open()
    }

    /** the store an app came from, as (package, name), or null for an app installed from a file */
    private fun storeOf(pkg: String): Pair<String, String>? = try {
        val from = if (android.os.Build.VERSION.SDK_INT >= 30) packageManager.getInstallSourceInfo(pkg).installingPackageName
                   else @Suppress("DEPRECATION") packageManager.getInstallerPackageName(pkg)
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        if (from == null || packageManager.resolveActivity(page.setPackage(from), 0) == null) null
        else from to packageManager.getApplicationLabel(packageManager.getApplicationInfo(from, 0)).toString()
    } catch (_: Exception) { null }

    private fun askApp(e: AppEntry) {
        val shop = storeOf(e.pkg)
        val sh = sheet(e.label, if (shop == null) "Installed from a file, so there is no store to update it from: install a newer file over it." else null)
        sh.item("Open") { openApp(e) }
        if (shop != null) sh.item("Look for an update", { shop.second }) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${e.pkg}")).setPackage(shop.first))
        }
        sh.item("Storage and cache", { "Android's page" }) { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))) }
        if (!e.system) sh.item("Uninstall", danger = true) { launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.pkg}"))) }
        sh.open()
    }

    /** what Aurora itself keeps and can fetch again: pictures in memory, answers from TMDB, the programme guide, temporary files */
    private fun ownCache(): Long {
        fun dir(f: java.io.File?): Long = f?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val guide = filesDir.listFiles { x -> x.name.startsWith("tv-") }?.sumOf { it.length() } ?: 0L
        val tmdb = java.io.File(filesDir.parentFile, "shared_prefs/tmdb.xml").length()
        return Images.kept() + dir(cacheDir) + dir(externalCacheDir) + guide + tmdb
    }

    private fun askCache() {
        sheet("Clear cache", "Temporary files are fetched again when they are needed, so nothing is lost. " +
            "Android lets an app clear only its own; for every app at once, Android's storage page has \"Cached data\".")
            .item("Aurora's own", { HomeLogic.size(ownCache()) }) {
                Images.forget()
                Tmdb.forget(this)
                Iptv.forget(this)
                try { cacheDir.deleteRecursively(); externalCacheDir?.deleteRecursively() } catch (_: Exception) {}
                Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show()
                refreshPanel?.invoke()
                currentStyle?.onFeed()
                Iptv.refresh(this, store, false) { refreshPanel?.invoke(); if (Iptv.stamp(store) != tvStamp) loadFeed() }
            }
            .item("Every app", { "Android's storage page" }) { launch(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            .open()
    }

    /** shows a visualizer mode in the picture of Customize (0 = back to normal) */
    private fun previewViz(mode: Int) {
        viz.preview = mode
        viz.covered = if (mode > 0) false else covered
    }

    /** Customize: a full-screen page, tabs along the top, the tab's settings on the left and what they are for on the right. */
    internal fun openSettings() {
        if (panel != null) return
        val tabs = settingsTabs()
        var cur = store.settingsTab.coerceIn(0, tabs.size - 1)
        fun white(a: Int) = Color.argb(a, 255, 255, 255)
        fun label(t: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_PX, u(size).toFloat())
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        fun caps(t: String, color: Int) = label(t, 9.5f, color, true).apply { letterSpacing = 0.22f }

        val p = FrameLayout(this)
        p.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(14, 19, 38), Color.rgb(6, 8, 16)))
        p.isClickable = true
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(u(48), u(30), u(48), u(16))
            clipToPadding = false; clipChildren = false          // (the focus ring of the first tab reaches into the margin)
        }

        // ---- header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Customize", 25f, Color.WHITE).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) })
        if (isBeta) head.addView(caps("BETA", accent).apply {      // a beta build says so (the version name ends in "-beta")
            background = rounded(Color.TRANSPARENT, u(9), u(1), accent)
            setPadding(u(8), u(2.5f), u(7), u(2.5f))
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(14); topMargin = u(3) })
        page.addView(head)

        // ---- tabs
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabViews = ArrayList<TextView>()
        page.addView(tabBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(14); leftMargin = -u(4) })

        // ---- body: settings left, card right
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(list, ViewGroup.LayoutParams(-1, -2)) }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(white(10), u(16), u(1), white(22))
            setPadding(u(20), u(18), u(20), u(18))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(scroll, LinearLayout.LayoutParams(u(450), -1))
        body.addView(side, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = u(26) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = u(16) })

        // ---- footer
        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        foot.addView(label("\u25C0 \u25B6  change      OK  select      BACK  close", 10f, white(110)), LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(label("Aurora " + BuildConfig.VERSION_NAME.replace("-", " ") + "  ·  free, no ads, no account", 10f, white(110)))
        page.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(10) })
        p.addView(page, ViewGroup.LayoutParams(-1, -1))

        val rowViews = ArrayList<Triple<Row, View, TextView>>()
        var statsBox: LinearLayout? = null
        var caption: TextView? = null
        var sceneRow: Row? = null
        /** the scene picture follows the setting in focus */
        fun applyScene() {
            if (tabs[cur].preview != 2) return
            val (name, mode) = (sceneRow?.scene ?: tabs[cur].rows.firstNotNullOf { it.scene })()
            previewViz(mode)
            caption?.text = name
        }
        fun refresh() {
            for ((r, line, v) in rowViews) {
                v.text = if (r.steps) "\u2039   ${r.value()}   \u203A" else r.value()
                line.visibility = if (r.shown()) View.VISIBLE else View.GONE
            }
            statsBox?.let { box ->
                box.removeAllViews()
                for ((k, v) in tabs[cur].stats()) {
                    box.addView(caps(k, white(120)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(11) })
                    box.addView(label(v, 14f, Color.WHITE).apply { setLineSpacing(u(2).toFloat(), 1f) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(2) })
                }
            }
            applyScene()
        }
        fun styleTabs() {
            for ((i, t) in tabViews.withIndex()) {
                val has = t.isFocused
                t.setTextColor(if (i == cur || has) Color.WHITE else white(140))
                t.background = when {
                    has -> rounded(white(46), u(15), u(2), accent)
                    i == cur -> rounded(white(26), u(15))
                    else -> null
                }
            }
        }
        fun visibleRows() = rowViews.filter { it.second.visibility == View.VISIBLE }.map { it.second }

        fun show(i: Int) {
            cur = i
            store.settingsTab = i
            val tab = tabs[i]
            list.removeAllViews(); rowViews.clear(); panelRow = null
            sceneRow = null; caption = null
            previewViz(0)
            scroll.scrollTo(0, 0)
            for (r in tab.rows) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = true
                    isClickable = true
                    setPadding(u(16), u(7.5f), u(16), u(8))
                    background = rounded(white(10), u(11))
                }
                val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                names.addView(label(r.title, 14.5f, Color.WHITE))
                names.addView(label(r.desc, 10.5f, white(120)).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                line.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
                val value = label("", 13.5f, white(210)).apply { gravity = Gravity.END; maxLines = 1 }
                line.addView(value, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = u(12) })
                rowViews += Triple(r, line, value)

                line.setOnFocusChangeListener { v, has ->
                    v.background = if (has) rounded(white(40), u(11), u(2), accent) else rounded(white(10), u(11))
                    value.setTextColor(if (has) accent else white(210))
                    if (has) panelRow = v
                    if (has && r.scene != null) { sceneRow = r; applyScene() }
                }
                line.setOnClickListener { r.act(1); refresh() }
                line.setOnKeyListener { v, kc, ev ->
                    val down = ev.action == KeyEvent.ACTION_DOWN
                    when (kc) {
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (down && r.steps) { r.act(if (kc == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1); refresh() }
                            true
                        }
                        // above the first setting are the tabs; below the last there is nothing
                        KeyEvent.KEYCODE_DPAD_UP -> if (visibleRows().firstOrNull() === v) { if (down) tabViews[cur].requestFocus(); true } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> visibleRows().lastOrNull() === v
                        else -> false
                    }
                }
                list.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = u(5) })
            }

            side.removeAllViews()
            side.addView(caps(tab.name.uppercase(Locale.US), accent))
            val w = u(864 - 450 - 26 - 40)      // the card's inner width
            if (tab.preview == 1) {
                side.addView(MirrorView(this, rootView) { listOf(panel, dim) }, LinearLayout.LayoutParams(w, w * 9 / 16).apply { topMargin = u(12) })
            } else if (tab.preview == 2) {
                // only the visualizer, on black
                val mirror = MirrorView(this, rootView) { (0 until rootView.childCount).map { rootView.getChildAt(it) }.filter { it !== viz } }
                mirror.setBackgroundColor(Color.BLACK)
                side.addView(mirror, LinearLayout.LayoutParams(w, w * 9 / 16).apply { topMargin = u(12) })
                caption = caps("", white(150))
                side.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u(9) })
            }
            side.addView(label(tab.blurb, 12f, white(170)).apply { setLineSpacing(u(3).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = u(10) })
            statsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            side.addView(statsBox)
            refresh()
            styleTabs()
        }

        for ((i, tab) in tabs.withIndex()) {
            val t = label(tab.name, 13.5f, white(140), true).apply {
                isFocusable = true
                isClickable = true
                setPadding(u(15), u(6), u(15), u(6.5f))
            }
            t.setOnFocusChangeListener { _, has -> if (has && cur != i) show(i) else styleTabs() }
            t.setOnKeyListener { _, kc, ev ->
                val down = ev.action == KeyEvent.ACTION_DOWN
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (down) visibleRows().firstOrNull()?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    KeyEvent.KEYCODE_DPAD_LEFT -> i == 0
                    KeyEvent.KEYCODE_DPAD_RIGHT -> i == tabs.size - 1
                    else -> false
                }
            }
            tabViews += t
            tabBar.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = u(4) })
        }

        refreshPanel = { refresh() }
        focusPanel = { (panelRow ?: visibleRows().firstOrNull() ?: tabViews[cur]).requestFocus() }
        rootView.addView(p, rootView.indexOfChild(dim), ViewGroup.LayoutParams(-1, -1))      // (under the night veil)
        panel = p
        host.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        show(cur)
        focusPanel?.invoke()
        // figures change in the background (lookups blocked, the guide being read)
        handler.postDelayed(panelTick, 2000)
    }

    private val panelTick = object : Runnable {
        override fun run() {
            if (panel == null) return
            refreshPanel?.invoke()
            handler.postDelayed(this, 2000)
        }
    }

    private fun closeSettings() {
        handler.removeCallbacks(panelTick)
        previewViz(0)
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
        val sh = sheet("Weather location", "The town the weather is for. Leave it empty and Aurora finds the place by itself.")
        val input = sh.input(store.city, "Town, for example Lisbon", InputType.TYPE_CLASS_TEXT)
        sh.button("Cancel")
            .button("Auto-detect") { applyCity("") }
            .button("Save", primary = true) { applyCity(input.text.toString()) }
            .open()
    }

    /** recently watched TV channels need the link of a programme guide; typed here, or dropped as a file (see Iptv.takeDropped) */
    private fun askIptv() {
        val sh = sheet("TV channels", "Shows the five channels you watched last, with their logos and what is on now and next, taken from your programme guide (XMLTV). " +
            "To know what you watch, Aurora reads the channel name your IPTV app shows on screen. It looks only at that app, only for channel names, and nothing leaves this device. " +
            "A channel opens your IPTV app.")
        val url = sh.input(store.iptvGuide, "Programme guide link (.xml or .xml.gz)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        sh.button("Cancel")
        if (store.iptv) sh.button("Turn off") { store.iptv = false; syncHomeGuard(); refreshPanel?.invoke(); loadFeed() }
        sh.button("Save", primary = true) {
            val v = url.text.toString().trim()
            val changed = v != store.iptvGuide
            store.iptvGuide = v
            store.iptv = v.isNotEmpty()
            if (changed) Iptv.forget(this)
            syncHomeGuard()
            refreshPanel?.invoke()
            loadFeed()
            Iptv.refresh(this, store, changed) { refreshPanel?.invoke(); if (Iptv.stamp(store) != tvStamp) loadFeed() }
        }.open()
    }

    /** TMDB needs the user's own free key; typed here, or dropped as a file (see Tmdb.takeDroppedKey) */
    private fun askTmdbKey() {
        val sh = sheet("Extra artwork from TMDB", "Clean backdrops, title logos and short descriptions for films and series from any app. " +
            "It needs your own free API key from themoviedb.org (Settings > API). The titles on your home screen are sent to TMDB to find them.\n\n" +
            "This product uses the TMDB API but is not endorsed or certified by TMDB.")
        val input = sh.input(store.tmdbKey, "TMDB API key", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        sh.button("Cancel")
        if (store.tmdb) sh.button("Turn off") { store.tmdb = false; refreshPanel?.invoke(); render(null) }
        sh.button("Save", primary = true) {
            val k = input.text.toString().trim()
            store.tmdbKey = k
            store.tmdb = k.isNotEmpty()
            Tmdb.keyRefused = false
            refreshPanel?.invoke()
            if (k.isNotEmpty()) Tmdb.check(k) { ok ->
                Toast.makeText(this, when (ok) { true -> "TMDB artwork is on"; false -> "TMDB did not accept this key"; else -> "Could not reach TMDB to check the key" }, Toast.LENGTH_LONG).show()
                refreshPanel?.invoke()
                if (ok == true) render(null)
            }
        }.open()
    }

    private fun askHidden() {
        val hidden = store.hidden.toSet()
        val list = apps.filter { it.key in hidden }
        if (list.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show()
            return
        }
        val keep = BooleanArray(list.size) { true }
        val sh = sheet("Hidden apps", "Press OK on an app to bring it back to the home screen.")
        for ((i, e) in list.withIndex()) sh.item(e.label, { if (keep[i]) "Hidden" else "Back on the home" }, close = false) { keep[i] = !keep[i] }
        sh.button("Cancel")
            .button("Done", primary = true) {
                val restore = list.filterIndexed { i, _ -> !keep[i] }.map { it.key }.toSet()
                store.hidden = store.hidden.filter { it !in restore }
                render(null)
                refreshPanel?.invoke()
            }
            .open()
    }

    // ---------------------------------------------------------------- what the apps publish

    private fun loadFeed() {
        if (!store.preview || !TvFeed.allowed(this)) {
            feed = withChannels(emptyMap())
            currentStyle?.onFeed()
            return
        }
        Thread {
            val f = try { TvFeed.load(this) } catch (e: Exception) { emptyMap() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                feed = withChannels(f)
                android.util.Log.i("AuroraFeed", "apps with published titles: " +
                        (if (feed.isEmpty()) "none" else feed.entries.joinToString { "${it.key}=${it.value.size}" }))
                currentStyle?.onFeed()
            }
        }.start()
    }

    /**
     * Android TV apps publish their default previews when the home screen tells them to, once after they are
     * installed (the system's own launcher does this; as the home screen, Aurora has to as well, or a new app
     * shows nothing until it decides to publish by itself). Every installed app that listens is told once.
     */
    private fun askForPreviews() {
        try {
            val action = "android.media.tv.action.INITIALIZE_PROGRAMS"
            val found = packageManager.queryBroadcastReceivers(Intent(action), 0).mapNotNull { it.activityInfo }
                .filter { it.packageName != packageName }
            val (ask, told) = HomeLogic.previewsToAsk(found.map { it.packageName }, store.previewsAsked)
            for (r in found) if (r.packageName in ask) {
                sendBroadcast(Intent(action).setClassName(r.packageName, r.name).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }
            if (told != store.previewsAsked) store.previewsAsked = told
            if (ask.isNotEmpty()) {
                android.util.Log.i("AuroraFeed", "asked for default previews: " + ask.joinToString())
                // publishing takes the app a moment
                for (t in longArrayOf(15_000, 60_000)) handler.postDelayed({ if (!isDestroyed) loadFeed() }, t)
            }
        } catch (e: Exception) {
            android.util.Log.w("AuroraFeed", "asking for previews failed: $e")
        }
    }

    /** the app TV channels open in: the one chosen in Customize, else the first that looks like an IPTV player */
    internal fun iptvApp(): AppEntry? {
        val hidden = store.hidden.toSet()
        val visible = apps.filter { it.key !in hidden && !it.key.endsWith("/googletv") && it.pkg != packageName }
        visible.firstOrNull { it.pkg == store.iptvApp }?.let { return it }
        val hint = Regex("ott|iptv|tivimate|televizo|smarters|kodi|vlc", RegexOption.IGNORE_CASE)
        return visible.firstOrNull { hint.containsMatchIn(it.pkg) || hint.containsMatchIn(it.label) } ?: visible.firstOrNull()
    }

    /** what the apps publish, plus the TV channels of the user's playlist under the app they open in */
    private fun withChannels(f: Map<String, List<TvFeed.Item>>): Map<String, List<TvFeed.Item>> {
        tvStamp = Iptv.stamp(store)
        val app = iptvApp() ?: return f
        // the screen watcher runs outside this activity: it needs the app by name
        if (Iptv.on(store) && store.iptvApp != app.pkg) store.iptvApp = app.pkg
        val ch = Iptv.items(store, app.pkg)
        if (ch.isEmpty()) return f
        val out = LinkedHashMap(f)
        out[app.pkg] = ch + f[app.pkg].orEmpty()
        return out
    }
    private var tvStamp = ""

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
