package app.hermes.aurora

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockTest {

    /** a DNS query for [name] (type A), with an EDNS record behind the question like real resolvers send */
    private fun query(name: String): ByteArray {
        val q = ArrayList<Byte>()
        q += listOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 1).map { it.toByte() }
        for (part in name.split('.')) { q += part.length.toByte(); q += part.toByteArray().toList() }
        q += listOf(0, 0, 1, 0, 1).map { it.toByte() }
        q += listOf(0, 0, 41, 4, 0, 0, 0, 0, 0, 0, 0).map { it.toByte() }
        return q.toByteArray()
    }

    private val list = setOf("doubleclick.net", "ads.adfox.ru", "mc.yandex.ru")

    @Test fun listedNamesAndEverythingBelowThemAreBlocked() {
        assertTrue(AdBlock.isBlocked(list, "doubleclick.net"))
        assertTrue(AdBlock.isBlocked(list, "googleads.g.doubleclick.net"))
        assertTrue(AdBlock.isBlocked(list, "ADS.adfox.ru."))
        assertFalse(AdBlock.isBlocked(list, "yandex.ru"))
        assertFalse(AdBlock.isBlocked(list, "kinopoisk.ru"))
        assertFalse(AdBlock.isBlocked(list, "notdoubleclick.net"))
        // a whole top-level domain is never blocked, even if the list had it
        assertFalse(AdBlock.isBlocked(setOf("ru"), "kinopoisk.ru"))
    }

    @Test fun theNameIsReadFromAQuery() {
        val q = query("ads.adfox.ru")
        val (name, end) = AdBlock.qname(q, 0, q.size)!!
        assertEquals("ads.adfox.ru", name)
        assertEquals(12 + 14 + 4, end)
    }

    @Test fun responsesAndBrokenPacketsAreLeftAlone() {
        val r = query("example.com"); r[2] = 0x81.toByte()
        assertNull(AdBlock.qname(r, 0, r.size))
        val q = query("example.com")
        assertNull(AdBlock.qname(q, 0, 20))
    }

    @Test fun aBlockedNameGetsNoSuchName() {
        val q = query("mc.yandex.ru")
        val (_, end) = AdBlock.qname(q, 0, q.size)!!
        val a = AdBlock.nxdomain(q, 0, end)
        assertEquals(end, a.size)                       // the extra record is dropped
        assertEquals(0x12, a[0].toInt()); assertEquals(0x34, a[1].toInt())
        assertEquals(0x81, a[2].toInt() and 0xff)       // response, recursion desired kept
        assertEquals(0x83, a[3].toInt() and 0xff)       // NXDOMAIN
        assertEquals(1, a[5].toInt())                   // the question stays
        for (i in 6..11) assertEquals(0, a[i].toInt())
    }

    @Test fun theAnswerGoesBackToTheAsker() {
        val dns = query("example.com")
        // IPv4 + UDP from 10.215.173.1:40000 to 10.215.173.53:53
        val ip = byteArrayOf(0x45, 0, 0, 0, 0, 0, 0, 0, 64, 17, 0, 0, 10, 215.toByte(), 173.toByte(), 1, 10, 215.toByte(), 173.toByte(), 53)
        val udp = byteArrayOf(0x9c.toByte(), 0x40, 0, 53, 0, 0, 0, 0)
        val pkt = ip + udp + dns
        val r = AdBlock.reply(pkt, 20, byteArrayOf(1, 2, 3))
        assertEquals(31, r.size)
        assertArrayEquals(byteArrayOf(10, 215.toByte(), 173.toByte(), 53), r.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 215.toByte(), 173.toByte(), 1), r.copyOfRange(16, 20))
        assertArrayEquals(byteArrayOf(0, 53, 0x9c.toByte(), 0x40), r.copyOfRange(20, 24))
        // the IPv4 header checksum adds up
        var sum = 0
        for (i in 0 until 20 step 2) sum += ((r[i].toInt() and 0xff) shl 8) or (r[i + 1].toInt() and 0xff)
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        assertEquals(0xffff, sum)
        assertArrayEquals(byteArrayOf(1, 2, 3), r.copyOfRange(28, 31))
    }
}
