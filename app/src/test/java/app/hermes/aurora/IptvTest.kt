package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class IptvTest {

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

    @Test fun aChannelOfTheGuideIsRead() {
        val c = Iptv.chanOf("<channel id=\"8900\">\n\t<display-name lang=\"ru\">4Ever Cinema HD</display-name>\n\t<display-name lang=\"ru\">4ever Cinema HD UA</display-name>\n\t<icon src=\"http://g.example/img/8900.png\" />\n</channel>")!!
        assertEquals("8900", c.id)
        assertEquals(listOf("4Ever Cinema HD", "4ever Cinema HD UA"), c.names)
        assertEquals("http://g.example/img/8900.png", c.icon)
        assertNull(Iptv.chanOf("<channel><display-name>x</display-name></channel>"))
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

    private val guide = mapOf(Iptv.nameKey("Зоопарк HD") to "50", Iptv.nameKey("Зоопарк") to "50", Iptv.nameKey("Первый канал") to "146", Iptv.nameKey("2x2") to "9")
    private fun seen(vararg texts: String) = Iptv.onScreen(texts.toList()) { guide[it] }

    @Test fun whatCanNotBeANameIsNotCompared() {
        assertEquals("", Iptv.nameKey("6"))
        assertEquals("", Iptv.nameKey("14:25"))
        assertEquals("", Iptv.nameKey(" / 1920x1080".take(3)))
        assertEquals("2x2", Iptv.nameKey("2x2"))
        assertEquals(Iptv.nameKey("зоопарк hd"), Iptv.nameKey(" Зоопарк  HD "))
    }

    @Test fun theInfoBarNamesOneChannel() {
        // what an IPTV app's info bar shows: number, channel, times, programmes
        val s = seen("6", "Зоопарк HD", "14:25", "Количество имеет значение", "+44", "15:15", "Гениальные морские охотники", "50", "14:31")
        assertEquals(listOf("50"), s.map { it.id })
        assertEquals("Зоопарк HD", s[0].name)
        // the same channel under two of its names is still one channel
        assertEquals(1, seen("Зоопарк", "Зоопарк HD").size)
        // a channel list names many
        assertEquals(3, seen("Зоопарк HD", "Первый канал", "2x2", "Category").size)
        assertTrue(seen("EPG", "Category", "Actions").isEmpty())
    }

    @Test fun aChannelCountsAsWatchedAfterHalfAMinute() {
        val w = Iptv.Watch()
        val zoo = seen("Зоопарк HD"); val one = seen("Первый канал")
        assertNull(w.look(zoo, 0))
        assertNull(w.look(emptyList(), 10_000))                 // the bar has gone, the channel plays on
        assertEquals("50", w.look(emptyList(), Iptv.WATCHED_MS)?.id)
        assertNull(w.look(emptyList(), Iptv.WATCHED_MS + 5_000)) // told once
        assertNull(w.look(zoo, 100_000))                         // the bar shows the same channel again: nothing new
        // zapping: each next channel starts the count afresh, only the one stayed on counts
        assertNull(w.look(one, 200_000))
        assertNull(w.look(zoo, 210_000))
        assertNull(w.look(one, 220_000))
        assertNull(w.look(emptyList(), 249_000))
        assertEquals("146", w.look(emptyList(), 250_000)?.id)
    }

    @Test fun aChannelListSaysNothingAboutWhatPlays() {
        val w = Iptv.Watch()
        assertNull(w.look(seen("Зоопарк HD"), 0))
        assertNull(w.look(seen("Зоопарк HD", "Первый канал", "2x2"), 5_000))     // the list is open
        assertEquals("50", w.look(seen("Первый канал", "2x2"), 31_000)?.id)       // still the channel chosen before
    }

    @Test fun leavingTheAppForgetsAnUnfinishedCount() {
        val w = Iptv.Watch()
        assertNull(w.look(seen("Зоопарк HD"), 0))
        w.reset()
        assertNull(w.look(emptyList(), 60_000))
    }

    @Test fun theLastFiveAreKeptNewestFirst() {
        var l = emptyList<Iptv.Recent>()
        for (i in 1..7) l = Iptv.pushed(l, Iptv.Recent("$i", "Channel $i"))
        assertEquals(listOf("7", "6", "5", "4", "3"), l.map { it.id })
        l = Iptv.pushed(l, Iptv.Recent("4", "Channel 4 HD"))
        assertEquals(listOf("4", "7", "6", "5", "3"), l.map { it.id })
        assertEquals("Channel 4 HD", l[0].name)
        val back = Iptv.decode(Iptv.encode(l))
        assertEquals(l.map { it.id to it.name }, back.map { it.id to it.name })
        assertTrue(Iptv.decode(listOf("broken", "\tx", "y\t")).isEmpty())
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
        assertEquals(listOf("8900", "146"), chans.map { Iptv.chanOf(it)!!.id })
        assertEquals(listOf("A", "C"), progs.map { Iptv.progOf(it)!!.second.title })
    }

    /** With IPTV_TEST_GUIDE set to a guide file (.xml.gz), the real file is read and a name looked up. */
    @Test fun realGuideIfGiven() {
        val path = System.getenv("IPTV_TEST_GUIDE")
        assumeTrue(path != null && File(path).exists())
        val names = HashMap<String, String>()
        var icons = 0; var progs = 0
        val t0 = System.currentTimeMillis()
        GZIPInputStream(File(path).inputStream(), 1 shl 16).use { s ->
            Iptv.scanGuide(s, { true }, { b -> Iptv.chanOf(b)?.let { c -> if (c.icon.isNotEmpty()) icons++; for (n in c.names) Iptv.nameKey(n).let { if (it.isNotEmpty()) names.putIfAbsent(it, c.id) } } },
                { b -> if (Iptv.progOf(b) != null) progs++ })
        }
        println("guide: ${names.size} names, $icons logos, $progs programmes, ${System.currentTimeMillis() - t0} ms")
        System.getenv("IPTV_TEST_NAME")?.let { println("  '$it' -> channel ${names[Iptv.nameKey(it)]}") }
        assertTrue(names.isNotEmpty())
    }
}
