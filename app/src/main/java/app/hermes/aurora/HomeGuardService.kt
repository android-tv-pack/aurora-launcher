package app.hermes.aurora

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes Aurora the Home screen on a box whose firmware insists on Google TV's launcher.
 * Android TV won't let another app replace it (its Home filter has the top priority), so instead this
 * accessibility service
 *   - takes the Home button before the system sees it and opens Aurora,
 *   - sends you back to Aurora when Google TV's home shows up by itself (after boot, after closing an app),
 *   - HOLD Home (0.7 s) opens the real Google TV home, as does the "Google TV" tile.
 * It only watches window changes and the Home key; it reads no screen content.
 */
class HomeGuardService : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var longFired = false
    private val redirects = ArrayDeque<Long>()
    private var cooldownUntil = 0L
    private val longPress = Runnable { longFired = true; openGoogleTv(this) }

    override fun onServiceConnected() {
        instance = this
        // just (re)started, e.g. after boot: if the system is showing Google TV, bring Aurora up
        // (the service can connect after Google TV's screen is already up, so right after boot don't wait for an event)
        val justBooted = android.os.SystemClock.elapsedRealtime() < 3 * 60 * 1000
        h.postDelayed({ if (enabled() && !gtvMode && (front == GTV || justBooted)) goAurora() }, 2000)
    }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    private fun enabled() = Store(this).homeButton

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("tv.settings.intelligence")) return
        val changed = pkg != front
        front = pkg
        if (changed) pauseMusicFor(pkg)
        if (pkg == GTV) {
            if (!enabled() || gtvMode) return
            goAurora()
        } else {
            gtvMode = false      // left Google TV: the next time it appears uninvited it gets redirected again
        }
    }

    /** A real app came to the front (not Aurora, Google TV, settings, dialogs): stop the music that is still playing behind it. */
    private fun pauseMusicFor(pkg: String) {
        if (pkg == packageName || pkg == GTV || !Store(this).pauseOnSwitch) return
        if (IGNORE.any { pkg.contains(it) }) return
        if (packageManager.getLeanbackLaunchIntentForPackage(pkg) == null && packageManager.getLaunchIntentForPackage(pkg) == null) return
        NowPlaying.pauseOthers(this, pkg)
    }

    override fun onKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode != KeyEvent.KEYCODE_HOME || !enabled()) return false
        when (ev.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ev.repeatCount == 0) {
                    longFired = false
                    h.removeCallbacks(longPress)
                    h.postDelayed(longPress, 700)
                } else if (!longFired && (ev.repeatCount >= 1 || ev.isLongPress)) {
                    // held: the key auto-repeat tells us as well (what remotes and adb send)
                    h.removeCallbacks(longPress)
                    longPress.run()
                }
            }
            KeyEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (!longFired) { gtvMode = false; goAurora(force = true) }
            }
        }
        return true
    }

    private fun goAurora(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            // safety net: if Aurora can't stay up, don't fight the system forever
            if (now < cooldownUntil) return
            while (redirects.isNotEmpty() && now - redirects.first() > 8000) redirects.removeFirst()
            redirects.addLast(now)
            if (redirects.size > 4) { cooldownUntil = now + 30000; redirects.clear(); return }
        }
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (_: Exception) {}
    }

    companion object {
        const val GTV = "com.google.android.apps.tv.launcherx"
        /** windows that are not "another app": system UI, settings, installers, voice search, the remote-control helper */
        private val IGNORE = listOf("systemui", "inputmethod", "tv.settings", "permissioncontroller", "packageinstaller", "katniss",
            "googlequicksearchbox", "com.google.android.gms", "com.pult.tv", "setupwraith", "tvlauncher", "android.tv.provision")
        @Volatile var instance: HomeGuardService? = null
        @Volatile var front: String = ""
        /** true while the user deliberately sits in the Google TV home */
        @Volatile var gtvMode = false

        fun openGoogleTv(ctx: Context) {
            gtvMode = true
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setComponent(ComponentName(GTV, "$GTV.home.HomeActivity"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { gtvMode = false }
        }

        private fun component(ctx: Context) = ComponentName(ctx, HomeGuardService::class.java).flattenToString()

        fun isEnabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: return false
            return s.split(':').contains(component(ctx)) &&
                Settings.Secure.getInt(ctx.contentResolver, "accessibility_enabled", 0) == 1
        }

        fun canSwitch(ctx: Context) =
            ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

        /** Turns the service on or off. Needs a one-time adb grant of WRITE_SECURE_SETTINGS. Returns false when not allowed. */
        fun setEnabled(ctx: Context, on: Boolean): Boolean {
            if (!canSwitch(ctx)) return false
            val me = component(ctx)
            val cur = (Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "")
                .split(':').filter { it.isNotBlank() && it != me }
            val next = if (on) cur + me else cur
            Settings.Secure.putString(ctx.contentResolver, "enabled_accessibility_services", next.joinToString(":"))
            if (on) Settings.Secure.putInt(ctx.contentResolver, "accessibility_enabled", 1)
            return true
        }
    }
}
