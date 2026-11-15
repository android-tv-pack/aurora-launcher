package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class IptvTest {

    private val own = """
        #EXTM3U
        #EXTINF:0 tvg-rec="0",First Channel UHD
        #EXTGRP:news
        http://host.example/iptv/SECRETKEY/19241/index.m3u8
        #EXTINF:0 tvg-rec="3",Film, the Channel
        #EXTGRP:cinema
        http://host.example/iptv/SECRETKEY/77/index.m3u8
        #EXTINF:-1,No Number
        http://host.example/live/stream.m3u8
    """.trimIndent()

    private val other = """
        #EXTM3U x-tvg-url="http://guide.example/epg.xml.gz,http://second.example/x.xml"
        #EXTINF:0 group-title="news" tvg-id="146" tvg-logo="http://guide.example/img/146.png" tvg-rec="0",Первый канал UHD
        http://localhost/iptv/00000000000000/19241/index.m3u8
        #EXTINF:0 group-title="x" tvg-id="900" tvg-logo="http://guide.example/img/900.png",no number
        http://localhost/else.m3u8
    """.trimIndent()

    @Test fun aPlaylistIsRead() {
        val p = Iptv.parseM3u(own)
        assertEquals(listOf("First Channel UHD", "Film, the Channel", "No Number"), p.channels.map { it.name })
        assertEquals(listOf("news", "cinema", ""), p.channels.map { it.group })
        assertEquals(listOf("19241", "77", ""), p.channels.map { it.sid })
        assertEquals("", p.guideUrl)
        assertEquals(listOf("news", "cinema"), Iptv.groups(p.channels))
    }

    @Test fun attributesInTheChannelLineAreRead() {
        val p = Iptv.parseM3u(other)
        assertEquals("http://guide.example/epg.xml.gz", p.guideUrl)
        assertEquals("146", p.channels[0].tvgId)
        assertEquals("http://guide.example/img/146.png", p.channels[0].logo)
        assertEquals("news", p.channels[0].group)
    }

    @Test fun logosAndGuideComeFromTheSecondPlaylistByNumberThenByName() {
        val p = Iptv.merge(Iptv.parseM3u(own), Iptv.parseM3u(other))
        assertEquals("146", p.channels[0].tvgId)                       // by its number, though the names differ
        assertEquals("http://guide.example/img/146.png", p.channels[0].logo)
        assertEquals("", p.channels[1].logo)                           // unknown to the second playlist
        assertEquals("900", p.channels[2].tvgId)                       // by name
        assertEquals("http://guide.example/epg.xml.gz", p.guideUrl)
    }

    @Test fun theNumberIsTakenFromTheAddressOnly() {
        assertEquals("19241", Iptv.streamId("http://h.example/iptv/KEY/19241/index.m3u8"))
        assertEquals("55", Iptv.streamId("http://h.example/live/user/pass/55.ts?token=9"))
        assertEquals("", Iptv.streamId("http://h.example:8080/stream.m3u8"))
        assertEquals("", Iptv.streamId("http://h.example/2024/"))      // too close to the host to be a channel
    }

    @Test fun guideTimesAreRead() {
        assertEquals(0L, Iptv.xmltvTime("19700101000000 +0000"))
        assertEquals(1_000L * 1794084900L, Iptv.xmltvTime("20261107235500 +0300"))
        assertEquals(Iptv.xmltvTime("20261107205500 +0000"), Iptv.xmltvTime("20261107235500 +0300"))
        assertEquals(Iptv.xmltvTime("20240229120000 +0000") + 86_400_000L, Iptv.xmltvTime("20240301120000"))
        assertEquals(0L, Iptv.xmltvTime("soon"))
    }

    @Test fun aProgrammeIsRead() {
        val (id, p) = Iptv.progOf("<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"8900\">\n\t<title lang=\"ru\">Tom &amp; Jerry (1976)</title>\n</programme>")!!
        assertEquals("8900", id)
        assertEquals("Tom & Jerry (1976)", p.title)
        assertEquals(107 * 60_000L, p.stop - p.start)
        assertNull(Iptv.progOf("<programme start=\"x\" stop=\"y\" channel=\"1\"><title>t</title></programme>"))
    }

    @Test fun nowAndNext() {
        val l = listOf(Iptv.Prog(0, 100, "a"), Iptv.Prog(100, 200, "b"), Iptv.Prog(200, 300, "c"))
        assertEquals("b" to "c", Iptv.nowNext(l, 150).let { it.first?.title to it.second?.title })
        assertEquals(null to "a", Iptv.nowNext(l, -5).let { it.first?.title to it.second?.title })
        assertEquals("c" to null, Iptv.nowNext(l, 250).let { it.first?.title to it.second?.title })
        assertEquals("Now: b, until 200  ·  Next: c", Iptv.line(l[1], l[2]) { it.toString() })
        assertEquals("Next: a, 0", Iptv.line(null, l[0]) { it.toString() })
        assertEquals("", Iptv.line(null, null) { "" })
    }

    @Test fun theHomeShowsOneGroup() {
        val ch = Iptv.parseM3u(own).channels
        assertEquals(listOf("Film, the Channel"), Iptv.shown(ch, "cinema").map { it.name })
        assertEquals(listOf("First Channel UHD"), Iptv.shown(ch, "gone").map { it.name })      // an unknown group: the first one
    }

    private val xml = "<?xml version=\"1.0\"?><!DOCTYPE tv SYSTEM \"x\">\n<tv>\n<channel id=\"8900\">\n\t<display-name lang=\"ru\">4Ever Cinema HD</display-name>\n\t<icon src=\"i\" />\n</channel>" +
        "<channel id=\"146\"><display-name>One</display-name></channel>\n" +
        "<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"8900\">\n\t<title lang=\"ru\">A</title>\n</programme>\n" +
        "<programme start=\"20261107231300 +0300\" stop=\"20261108010000 +0300\" channel=\"146\"><title>B</title><desc>long &lt;text&gt;</desc></programme>" +
        "<programme start=\"20261108010000 +0300\" stop=\"20261108020000 +0300\" channel=\"8900\">\n\t<title lang=\"ru\">C</title>\n</programme>\n</tv>"

    @Test fun aGuideIsWalkedWithoutHoldingIt() {
        val chans = ArrayList<String>()
        val progs = ArrayList<String>()
        // tiny reads: every tag is cut across two reads somewhere
        val slow = object : java.io.InputStream() {
            val b = xml.toByteArray(); var i = 0
            override fun read(): Int = if (i < b.size) b[i++].toInt() and 0xff else -1
            override fun read(buf: ByteArray, off: Int, len: Int): Int { if (i >= b.size) return -1; val n = minOf(7, len, b.size - i); System.arraycopy(b, i, buf, off, n); i += n; return n }
        }
        Iptv.scanGuide(slow, { Iptv.attrOf(it, "channel") == "8900" }, { chans.add(it) }, { progs.add(it) })
        assertEquals(listOf("8900" to listOf("4Ever Cinema HD"), "146" to listOf("One")), chans.map { Iptv.channelOf(it)!! })
        assertEquals(listOf("A", "C"), progs.map { Iptv.progOf(it)!!.second.title })
    }

    /** With IPTV_TEST_DIR set to a folder holding playlist.m3u, extra.m3u and epg.xml.gz, the real files are read. */
    @Test fun realFilesIfGiven() {
        val dir = System.getenv("IPTV_TEST_DIR")
        assumeTrue(dir != null && File(dir, "playlist.m3u").exists())
        var p = Iptv.parseM3u(File(dir, "playlist.m3u").readText())
        if (File(dir, "extra.m3u").exists()) p = Iptv.merge(p, Iptv.parseM3u(File(dir, "extra.m3u").readText()))
        println("channels ${p.channels.size}, logos ${p.channels.count { it.logo.isNotEmpty() }}, guide ids ${p.channels.count { it.tvgId.isNotEmpty() }}, groups ${Iptv.groups(p.channels).size}, guide ${p.guideUrl.isNotEmpty()}")
        assertTrue(p.channels.isNotEmpty())
        val g = File(dir, "epg.xml.gz")
        if (!g.exists()) return
        val ids = p.channels.map { it.tvgId }.filter { it.isNotEmpty() }.toHashSet()
        val out = HashMap<String, ArrayList<Iptv.Prog>>()
        val now = System.currentTimeMillis()
        var chans = 0
        val t0 = System.currentTimeMillis()
        GZIPInputStream(g.inputStream(), 1 shl 16).use { s ->
            Iptv.scanGuide(s, { Iptv.attrOf(it, "channel") in ids }, { chans++ }, { b ->
                Iptv.progOf(b)?.let { (id, pr) -> if (pr.stop > now && pr.start < now + 86_400_000L) out.getOrPut(id) { ArrayList() }.add(pr) }
            })
        }
        println("guide: $chans channel blocks, ${out.size} channels with programmes, ${out.values.sumOf { it.size }} programmes in 24h, ${System.currentTimeMillis() - t0} ms")
        for (c in Iptv.shown(p.channels, "").take(4)) {
            val (cur, next) = Iptv.nowNext(out[c.tvgId]?.sortedBy { it.start }, now)
            println("  ${c.name}: " + Iptv.line(cur, next) { java.text.SimpleDateFormat("HH:mm").format(java.util.Date(it)) })
        }
        assertTrue(chans > 0)
    }
}
