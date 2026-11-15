package app.hermes.aurora

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The ad blocker. Ads and trackers are loaded from their own servers; when a box can't look up those servers'
 * addresses, the ads don't load. Aurora answers the box's address lookups (DNS) itself: a lookup of a name on the
 * blocklist gets "no such name", every other lookup goes to the network's usual DNS server unchanged.
 *
 * It uses Android's VPN interface for that, but only the lookups go through it (one private address is routed
 * into it, nothing else); videos, music and all other traffic never touch it. If Aurora's filter stops for any
 * reason, Android drops the VPN and the box simply uses its normal DNS again: it can't leave the box offline.
 *
 * Blocklist: StevenBlack/hosts (MIT licence), unified list, about 77,000 ad and tracker domains, in
 * assets/adblock_hosts.txt (one domain per line). A name is blocked when it or any domain above it is on the list.
 * Limits: ads served from the same servers as the content (YouTube's) can't be told apart by name; apps that
 * use their own encrypted DNS bypass the box's DNS.
 */
internal object AdBlock {
    val NAMES = arrayOf("Off", "On")
    const val REQUEST = 4711

    /** ad lookups answered with "no such name" since the filter started */
    val blocked = AtomicInteger(0)
    @Volatile var running = false
    @Volatile var domains = 0

    fun label(ctx: Context, store: Store): String = when {
        store.adblock == 0 -> "Off"
        running -> "On  ·  " + String.format(java.util.Locale.US, "%,d", blocked.get()) + " blocked"
        VpnService.prepare(ctx) != null -> "Allow it in the prompt"
        else -> "Starting"
    }

    /** Customize: 0 = off, 1 = on. Turning it on the first time shows Android's one-time VPN permission prompt. */
    fun choose(a: Activity, store: Store, i: Int) {
        store.adblock = i
        if (i == 0) { stop(a); return }
        val ask = VpnService.prepare(a)
        if (ask != null) {
            try { a.startActivityForResult(ask, REQUEST) } catch (_: Exception) {}
        } else start(a)
    }

    /** Aurora started: bring the filter back if it is switched on (after a reboot, an update ...) */
    fun ensure(ctx: Context, store: Store) {
        if (store.adblock == 1 && !running && VpnService.prepare(ctx) == null) start(ctx)
    }

    /** the answer of Android's permission prompt */
    fun onPermission(a: Activity, store: Store, ok: Boolean) {
        android.util.Log.i("AuroraAdBlock", "permission answer ok=$ok setting=${store.adblock}")
        if (ok && store.adblock == 1) start(a) else store.adblock = 0
    }

    fun start(ctx: Context) {
        android.util.Log.i("AuroraAdBlock", "start requested")
        try { ctx.startService(Intent(ctx, AdBlockService::class.java)) } catch (e: Exception) { android.util.Log.w("AuroraAdBlock", "start failed: $e") }
    }

    fun stop(ctx: Context) {
        try { ctx.startService(Intent(ctx, AdBlockService::class.java).setAction(AdBlockService.STOP)) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- the list

    @Volatile private var list: HashSet<String>? = null

    fun load(ctx: Context): HashSet<String> {
        list?.let { return it }
        val set = HashSet<String>(90_000)
        ctx.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
            for (l in lines) { val d = l.trim(); if (d.isNotEmpty() && !d.startsWith("#")) set.add(d) }
        }
        list = set
        domains = set.size
        return set
    }

    /** is [name] or a domain above it on the list? ("ads.example.com" is caught by "example.com" too) */
    fun isBlocked(set: Set<String>, name: String): Boolean {
        var n = name.lowercase(java.util.Locale.ROOT).trimEnd('.')
        while (true) {
            if (n in set) return true
            val dot = n.indexOf('.')
            if (dot < 0 || dot == n.length - 1) return false
            n = n.substring(dot + 1)
            if (n.indexOf('.') < 0) return false       // never block a whole top-level domain
        }
    }

    // ---------------------------------------------------------------- DNS and IP packets (plain logic, unit-tested)

    /** the name asked for in a DNS query, or null if it is not a simple query */
    fun qname(dns: ByteArray, off: Int, len: Int): Pair<String, Int>? {
        if (len < 17) return null
        if (dns[off + 2].toInt() and 0x80 != 0) return null          // a response, not a query
        val qd = ((dns[off + 4].toInt() and 0xff) shl 8) or (dns[off + 5].toInt() and 0xff)
        if (qd != 1) return null
        var p = off + 12
        val sb = StringBuilder()
        while (true) {
            if (p >= off + len) return null
            val l = dns[p].toInt() and 0xff
            if (l == 0) { p++; break }
            if (l and 0xc0 != 0) return null
            if (p + 1 + l > off + len) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 1..l) sb.append((dns[p + i].toInt() and 0xff).toChar())
            p += l + 1
        }
        if (p + 4 > off + len) return null
        return sb.toString() to (p + 4 - off)       // the name, and the length of header + question
    }

    /** "no such name" for the query, keeping its header and question */
    fun nxdomain(dns: ByteArray, off: Int, questionEnd: Int): ByteArray {
        val r = dns.copyOfRange(off, off + questionEnd)
        r[2] = (0x80 or (r[2].toInt() and 0x01)).toByte()                // response, keep "recursion desired"
        r[3] = 0x83.toByte()                                            // recursion available, NXDOMAIN
        for (i in 6..11) r[i] = 0                                       // no answers, authority or extra records
        return r
    }

    /** wraps a DNS answer into an IPv4/UDP packet going back to whoever asked in [query] (an IPv4/UDP packet) */
    fun reply(query: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val p = ByteArray(total)
        p[0] = 0x45; p[8] = 64; p[9] = 17
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        System.arraycopy(query, 16, p, 12, 4)       // source = the address that was asked
        System.arraycopy(query, 12, p, 16, 4)       // destination = the asker
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((p[i].toInt() and 0xff) shl 8) or (p[i + 1].toInt() and 0xff)
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        val cs = sum.inv() and 0xffff
        p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        // UDP: ports swapped, checksum 0 (allowed over IPv4)
        p[20] = query[ihl + 2]; p[21] = query[ihl + 3]
        p[22] = query[ihl]; p[23] = query[ihl + 1]
        val ul = 8 + dns.size
        p[24] = (ul shr 8).toByte(); p[25] = ul.toByte()
        System.arraycopy(dns, 0, p, 28, dns.size)
        return p
    }
}

/** Runs the filter; see [AdBlock]. */
class AdBlockService : VpnService() {
    companion object {
        const val STOP = "app.hermes.aurora.adblock.STOP"
        private const val ME = "10.215.173.1"
        /** the made-up DNS server the box is told to use: the only address routed into the filter */
        private const val DNS = "10.215.173.53"
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var thread: Thread? = null
    private val pool = Executors.newFixedThreadPool(6)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("AuroraAdBlock", "service command ${intent?.action}")
        if (intent?.action == STOP) { shutdown(); stopSelf(); return START_NOT_STICKY }
        if (thread == null) {
            val t = Thread({ run() }, "aurora-adblock")
            thread = t
            t.start()
        }
        return START_STICKY
    }

    override fun onRevoke() { shutdown(); stopSelf() }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    private fun shutdown() {
        AdBlock.running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        thread?.interrupt()
        thread = null
    }

    /** the DNS servers of the real network (read before the filter takes over) */
    private fun upstream(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        val out = ArrayList<InetAddress>()
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                cm.getLinkProperties(n)?.dnsServers?.filterIsInstance<Inet4Address>()?.forEach { if (it !in out) out.add(it) }
            }
        } catch (_: Exception) {}
        // last resort when the network names none
        for (h in listOf("1.1.1.1", "8.8.8.8")) InetAddress.getByName(h).let { if (it !in out) out.add(it) }
        return out
    }

    private fun run() {
        var mine: ParcelFileDescriptor? = null
        try {
            val set = AdBlock.load(this)
            val servers = upstream()
            val pfd = Builder()
                .setSession("Aurora ad blocker")
                .addAddress(ME, 32)
                .addDnsServer(DNS)
                .addRoute(DNS, 32)
                .addDisallowedApplication(packageName)       // Aurora's own lookups (and the forwarding below) go direct
                .setBlocking(true)
                .establish()
            if (pfd == null) { android.util.Log.w("AuroraAdBlock", "establish() gave nothing (not allowed?)"); return }
            mine = pfd
            tun = pfd
            AdBlock.blocked.set(0)
            AdBlock.running = true
            android.util.Log.i("AuroraAdBlock", "on: ${set.size} domains, upstream $servers")
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (!Thread.interrupted()) {
                val n = input.read(buf)
                if (n <= 0) continue
                if (buf[0].toInt() shr 4 != 4 || buf[9].toInt() != 17) continue      // IPv4 UDP only
                val ihl = (buf[0].toInt() and 0x0f) * 4
                if (n < ihl + 8) continue
                val dport = ((buf[ihl + 2].toInt() and 0xff) shl 8) or (buf[ihl + 3].toInt() and 0xff)
                if (dport != 53) continue
                val pkt = buf.copyOf(n)
                val dOff = ihl + 8
                val q = AdBlock.qname(pkt, dOff, n - dOff)
                if (q != null && AdBlock.isBlocked(set, q.first)) {
                    AdBlock.blocked.incrementAndGet()
                    val r = AdBlock.reply(pkt, ihl, AdBlock.nxdomain(pkt, dOff, q.second))
                    synchronized(output) { output.write(r) }
                    continue
                }
                pool.execute { forward(pkt, ihl, dOff, n, servers, output) }
            }
        } catch (e: Exception) {
            android.util.Log.w("AuroraAdBlock", "stopped: $e")
        } finally {
            // the filter is gone: drop the VPN, so the box goes back to its normal DNS at once
            // (unless a newer start has already replaced it)
            if (tun === mine) { shutdown(); stopSelf() } else try { mine?.close() } catch (_: Exception) {}
        }
    }

    private fun forward(pkt: ByteArray, ihl: Int, dOff: Int, n: Int, servers: List<InetAddress>, out: FileOutputStream) {
        for (s in servers) {
            try {
                DatagramSocket().use { sock ->
                    protect(sock)
                    sock.soTimeout = 2500
                    sock.send(DatagramPacket(pkt, dOff, n - dOff, s, 53))
                    val r = ByteArray(4096)
                    val dp = DatagramPacket(r, r.size)
                    sock.receive(dp)
                    val ans = AdBlock.reply(pkt, ihl, r.copyOf(dp.length))
                    synchronized(out) { out.write(ans) }
                }
                return
            } catch (_: Exception) {
                // try the next server
            }
        }
    }
}
