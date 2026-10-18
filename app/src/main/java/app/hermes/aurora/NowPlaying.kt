package app.hermes.aurora

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/** Android only lets an app list media sessions if it holds a notification listener; this is that listener. It does not read notifications. */
class MediaListener : NotificationListenerService()

/**
 * What every music/video app is playing right now: title, artist, cover, progress and the
 * transport controls. Read from the apps' own media sessions, so it works for any player.
 */
internal object NowPlaying {

    class Info(
        val pkg: String, val title: String, val artist: String, val album: String,
        val art: Bitmap?, val playing: Boolean, val duration: Long,
        private val pos: Long, private val stamp: Long, private val speed: Float,
        val canPrev: Boolean, val canNext: Boolean
    ) {
        fun position(): Long {
            val p = if (playing) pos + ((SystemClock.elapsedRealtime() - stamp) * speed).toLong() else pos
            return if (duration > 0) p.coerceIn(0, duration) else p
        }
        /** 0..1, or -1 when the length is unknown (live streams, radio) */
        fun fraction(): Float = if (duration > 0) position().toFloat() / duration else -1f
        fun subtitle(): String = listOf(artist, album).filter { it.isNotEmpty() }.joinToString("  ·  ")
    }

    private val main = Handler(Looper.getMainLooper())
    private val ctrls = LinkedHashMap<String, MediaController>()
    private val callbacks = HashMap<String, MediaController.Callback>()
    private val uriArt = HashMap<String, Pair<String, Bitmap>>()   // pkg -> (uri, loaded cover)
    private var appCtx: Context? = null
    private var mgr: MediaSessionManager? = null
    private var sessionListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var comp: ComponentName? = null
    private var lastPoll = 0L

    /** the active home style sets this to repaint when something changes */
    var onChange: (() -> Unit)? = null

    fun enabled(ctx: Context): Boolean =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
            ?.contains(ctx.packageName + "/") == true

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (mgr != null) { onChange?.invoke(); return }
        if (!enabled(ctx)) return
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            val comp = ComponentName(ctx, MediaListener::class.java)
            this.comp = comp
            val l = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }
            m.addOnActiveSessionsChangedListener(l, comp, main)
            mgr = m
            sessionListener = l
            update(m.getActiveSessions(comp))
        } catch (e: SecurityException) {
            mgr = null
        }
    }

    fun stop() {
        try { sessionListener?.let { mgr?.removeOnActiveSessionsChangedListener(it) } } catch (_: Exception) {}
        for ((pkg, c) in ctrls) callbacks[pkg]?.let { try { c.unregisterCallback(it) } catch (_: Exception) {} }
        ctrls.clear(); callbacks.clear()
        sessionListener = null
        mgr = null
    }

    /** safety net: if an app we expect isn't known yet, ask the system again (at most every 1.5 s) */
    private fun poll() {
        val m = mgr ?: return
        val c = comp ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPoll < 1500) return
        lastPoll = now
        try { update(m.getActiveSessions(c), false) } catch (_: SecurityException) {}
    }

    private fun update(list: List<MediaController>, notify: Boolean = true) {
        val keep = HashSet<String>()
        for (c in list) {
            val pkg = c.packageName
            if (!keep.add(pkg)) continue          // first (= most recent) session per app wins
            val old = ctrls[pkg]
            if (old?.sessionToken == c.sessionToken) continue
            old?.let { o -> callbacks[pkg]?.let { try { o.unregisterCallback(it) } catch (_: Exception) {} } }
            ctrls[pkg] = c
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) { loadArt(pkg); onChange?.invoke() }
                override fun onPlaybackStateChanged(state: PlaybackState?) { onChange?.invoke() }
                override fun onSessionDestroyed() { ctrls.remove(pkg); callbacks.remove(pkg); onChange?.invoke() }
            }
            callbacks[pkg] = cb
            c.registerCallback(cb, main)
            loadArt(pkg)
        }
        for (pkg in ctrls.keys.toList()) if (pkg !in keep) {
            callbacks.remove(pkg)?.let { cb -> try { ctrls[pkg]?.unregisterCallback(cb) } catch (_: Exception) {} }
            ctrls.remove(pkg)
        }
        if (notify) onChange?.invoke()
    }

    /** some players only give a cover as a URI */
    private fun loadArt(pkg: String) {
        val ctx = appCtx ?: return
        val m = ctrls[pkg]?.metadata ?: return
        if (m.getBitmap(MediaMetadata.METADATA_KEY_ART) != null || m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null) return
        val uri = m.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: return
        if (uriArt[pkg]?.first == uri) return
        Images.load(ctx, uri, 900) { bmp -> if (bmp != null) { uriArt[pkg] = uri to bmp; onChange?.invoke() } }
    }

    fun controllerFor(pkg: String): MediaController? = ctrls[pkg]
    fun isPlaying(pkg: String): Boolean = ctrls[pkg]?.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }
    fun titleOf(pkg: String): String? = ctrls[pkg]?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)

    /** is any app playing right now? (used by the music visualiser) */
    fun anyPlaying(): Boolean = ctrls.values.any {
        val s = it.playbackState?.state
        s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
    }

    /** the cover of whatever is playing right now (the visualiser takes its colours from it) */
    fun playingArt(): Bitmap? {
        for (pkg in ctrls.keys.toList()) {
            val st = ctrls[pkg]?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING) infoFor(pkg)?.art?.let { return it }
        }
        return null
    }

    /** null when the app isn't playing or paused on something */
    fun infoFor(pkg: String): Info? {
        if (!ctrls.containsKey(pkg)) poll()
        val c = ctrls[pkg] ?: return null
        val st = c.playbackState ?: return null
        val playing = st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING
        if (!playing && st.state != PlaybackState.STATE_PAUSED) return null
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        if (title.isBlank()) return null
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = m.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: uriArt[pkg]?.second
        return Info(
            pkg, title, artist, m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "", art, playing,
            m.getLong(MediaMetadata.METADATA_KEY_DURATION), st.position, st.lastPositionUpdateTime, st.playbackSpeed,
            st.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L, st.actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        )
    }

    fun playPause(pkg: String) {
        val c = ctrls[pkg] ?: return
        val playing = c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING }
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }
    /** Stop: the app ends its playback; if it only pauses, fine, and if it ignores stop() we pause it after a moment */
    fun stopPlayback(pkg: String) {
        val c = ctrls[pkg] ?: return
        c.transportControls.stop()
        main.postDelayed({ if (isPlaying(pkg)) ctrls[pkg]?.transportControls?.pause() }, 800)
    }

    /** an id of the track that is playing right now ("" when nothing plays); used to rotate the visualiser per track */
    fun playingKey(): String {
        for ((pkg, c) in ctrls) {
            val s = c.playbackState?.state
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING)
                return pkg + "|" + (c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
        }
        return ""
    }

    /**
     * Pauses everything that is playing except [except] (and Aurora's own AirPlay). Works without Aurora being on screen:
     * it asks the system for the active sessions itself (the notification-listener grant is what allows that).
     */
    fun pauseOthers(ctx: Context, except: String): Int {
        var n = 0
        try {
            val m = ctx.getSystemService(MediaSessionManager::class.java)
            for (c in m.getActiveSessions(ComponentName(ctx, MediaListener::class.java))) {
                if (c.packageName == except || c.packageName == ctx.packageName) continue
                val s = c.playbackState?.state
                if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING) { c.transportControls.pause(); n++ }
            }
        } catch (_: SecurityException) {}
        return n
    }

    /** "Title  —  Artist" of whatever plays now, "" when nothing does */
    fun playingLine(): String {
        for (pkg in ctrls.keys.toList()) {
            val i = infoFor(pkg) ?: continue
            if (i.playing) return i.title + (if (i.artist.isNotEmpty()) "  —  " + i.artist else "")
        }
        return ""
    }

    fun next(pkg: String) { ctrls[pkg]?.transportControls?.skipToNext() }
    fun prev(pkg: String) { ctrls[pkg]?.transportControls?.skipToPrevious() }
}
