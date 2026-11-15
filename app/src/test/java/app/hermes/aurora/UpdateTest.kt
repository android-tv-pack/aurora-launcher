package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    private fun r(tag: String, pre: Boolean = tag.contains("beta"), apk: String = "https://x/$tag.apk") = Update.Release(tag, pre, apk)

    @Test fun versionsCompareInReleaseOrder() {
        assertTrue(Update.newer("v3.20", "3.19"))
        assertTrue(Update.newer("v3.19", "3.19-beta"))            // the release follows its beta
        assertTrue(Update.newer("3.20-beta", "3.19"))
        assertTrue(Update.newer("3.19-beta2", "3.19-beta"))
        assertTrue(Update.newer("3.19.1", "3.19"))
        assertTrue(Update.newer("v3.100", "v3.20"))               // numbers, not text
        assertFalse(Update.newer("3.19-beta", "3.19-beta"))
        assertFalse(Update.newer("3.17", "3.19-beta"))
        assertFalse(Update.newer("nightly", "3.19"))
        assertFalse(Update.newer("3.20", "what"))
    }

    @Test fun aReleasedBuildIsOfferedOnlyReleases() {
        val list = listOf(r("v3.21-beta"), r("v3.20"), r("v3.19"), r("v3.17"))
        assertEquals("v3.20", Update.pick(list, "3.19")?.tag)
        assertNull(Update.pick(list, "3.20"))
    }

    @Test fun aBetaBuildIsOfferedBetasAndReleases() {
        val list = listOf(r("v3.17"), r("v3.19-beta"), r("v3.20-beta"), r("v3.19"))
        assertEquals("v3.20-beta", Update.pick(list, "3.19-beta")?.tag)
        assertEquals("v3.19", Update.pick(listOf(r("v3.17"), r("v3.19-beta"), r("v3.19")), "3.19-beta")?.tag)
        assertNull(Update.pick(listOf(r("v3.17"), r("v3.19-beta")), "3.19-beta"))
    }

    @Test fun aReleaseWithoutAnApkIsNotOffered() {
        assertEquals("v3.20", Update.pick(listOf(r("v3.21", apk = ""), r("v3.20")), "3.19")?.tag)
        assertEquals("3.20 beta", r("v3.20-beta").name)
    }
}
