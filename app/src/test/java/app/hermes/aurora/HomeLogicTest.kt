package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private fun item(pkg: String, title: String, progress: Float = -1f, engaged: Long = 0, watchNext: Boolean = false, square: Boolean = false) =
        TvFeed.Item(pkg, title, "", "art://$title", !square, square, if (watchNext) "CONTINUE WATCHING" else "RECOMMENDED",
            "app://$pkg/$title", progress, engaged, watchNext, if (progress > 0) 60_000L else 0L)

    // ---- style migration

    @Test fun cinemaBecomesMarquee() {
        assertEquals(HomeLogic.MARQUEE, HomeLogic.migrateStyle(1))
        assertEquals(HomeLogic.MARQUEE, HomeLogic.migrateStyle(0))     // the long-gone Classic screen
    }

    @Test fun horizonAndCommandBecomeDrift() {
        assertEquals(HomeLogic.DRIFT, HomeLogic.migrateStyle(2))
        assertEquals(HomeLogic.DRIFT, HomeLogic.migrateStyle(3))
    }

    @Test fun styleNamesMatchTheIds() {
        assertEquals("Marquee", HomeLogic.STYLE_NAMES[HomeLogic.MARQUEE - 1])
        assertEquals("Drift", HomeLogic.STYLE_NAMES[HomeLogic.DRIFT - 1])
    }

    // ---- lanes

    @Test fun resumeItemsComeFirstNewestFirst() {
        val feed = mapOf(
            "a" to listOf(item("a", "a-old", 0.3f, 100, true), item("a", "a-rec")),
            "b" to listOf(item("b", "b-new", 0.5f, 900, true), item("b", "b-rec")),
            "c" to listOf(item("c", "c-rec"))
        )
        val lane = HomeLogic.crossLane(feed, listOf("a", "b", "c"))
        assertEquals(listOf("b-new", "a-old", "a-rec", "b-rec", "c-rec"), lane.map { it.title })
        assertEquals(HomeLogic.TITLE_RESUME, HomeLogic.crossTitle(lane))
    }

    @Test fun appsTakeTurnsAndNoAppFillsTheLane() {
        val feed = mapOf(
            "big" to (1..50).map { item("big", "big$it") },
            "small" to listOf(item("small", "s1"), item("small", "s2"))
        )
        val lane = HomeLogic.crossLane(feed, listOf("big", "small"), max = 18, perApp = 3)
        assertEquals(listOf("big1", "s1", "big2", "s2", "big3"), lane.map { it.title })
    }

    @Test fun laneIsCappedAndFollowsTheAppOrder() {
        val feed = mapOf("x" to (1..9).map { item("x", "x$it") }, "y" to (1..9).map { item("y", "y$it") })
        val lane = HomeLogic.crossLane(feed, listOf("y", "x"), max = 4, perApp = 3)
        assertEquals(listOf("y1", "x1", "y2", "x2"), lane.map { it.title })
    }

    @Test fun sparseDataGetsAnHonestTitle() {
        val feed = mapOf("a" to listOf(item("a", "only suggestions")))
        val lane = HomeLogic.crossLane(feed, listOf("a"))
        assertEquals(HomeLogic.TITLE_SPARSE, HomeLogic.crossTitle(lane))
        assertNull(HomeLogic.newestResume(feed, listOf("a")))
    }

    @Test fun progressAloneCountsAsResume() {
        val feed = mapOf("a" to listOf(item("a", "plain"), item("a", "half seen", 0.5f)))
        assertEquals("half seen", HomeLogic.newestResume(feed, listOf("a"))?.title)
    }

    @Test fun appsTheUserDoesNotHaveAreLeftOut() {
        val feed = mapOf("hidden" to listOf(item("hidden", "h", 0.5f, 5, true)), "shown" to listOf(item("shown", "s")))
        assertEquals(listOf("s"), HomeLogic.crossLane(feed, listOf("shown")).map { it.title })
        val lanes = HomeLogic.lanes(feed, listOf("shown", "hidden"), { if (it == "shown") "Shown" else null })
        assertTrue(lanes.all { l -> l.items.all { it.pkg == "shown" } })
    }

    @Test fun lanesAreCrossThenPinnedThenPerApp() {
        val feed = mapOf("a" to listOf(item("a", "a1"), item("a", "a2")), "b" to listOf(item("b", "b1")))
        val pins = listOf(HomeLogic.Pin("b", "b-pin", "art", null, false))
        val lanes = HomeLogic.lanes(feed, listOf("a", "b"), { it.uppercase() }, pins)
        assertEquals(listOf(HomeLogic.TITLE_SPARSE, HomeLogic.TITLE_PINNED, "A", "B"), lanes.map { it.title })
        assertEquals(listOf(true, true, false, false), lanes.map { it.crossApp })
        assertEquals(listOf("a1", "a2"), lanes[2].items.map { it.title })
    }

    @Test fun nothingPublishedMeansNoLanes() {
        assertTrue(HomeLogic.lanes(emptyMap(), listOf("a"), { "A" }).isEmpty())
        assertTrue(HomeLogic.crossLane(emptyMap(), emptyList()).isEmpty())
    }

    @Test fun oneAppOnlyIsNotShownTwice() {
        val feed = mapOf("a" to listOf(item("a", "a1"), item("a", "a2")))
        assertEquals(1, HomeLogic.lanes(feed, listOf("a"), { "A" }).size)
        // ... unless the app has more than fits the shared lane
        val many = mapOf("a" to (1..8).map { item("a", "a$it") })
        assertEquals(2, HomeLogic.lanes(many, listOf("a"), { "A" }).size)
    }

    @Test fun musicShelvesHoldOnlyCovers() {
        val feed = mapOf(
            "music" to listOf(item("music", "Playlist", square = true), item("music", "Album", square = true)),
            "video" to listOf(item("video", "Film"))
        )
        val shelves = HomeLogic.musicLanes(feed, listOf("video", "music")) { it.uppercase() }
        assertEquals(listOf("MUSIC"), shelves.map { it.title })
        assertEquals(listOf("Playlist", "Album"), shelves[0].items.map { it.title })
    }

    @Test fun timeLeftIsReadable() {
        assertEquals("38 min left", HomeLogic.leftText(38 * 60_000L))
        assertEquals("1 min left", HomeLogic.leftText(20_000L))
        assertEquals("1 h 5 min left", HomeLogic.leftText(65 * 60_000L))
        assertEquals("", HomeLogic.leftText(0))
    }

    // ---- night hours

    @Test fun autoWindowCrossesMidnight() {
        for (h in listOf(23, 0, 3, 6)) assertTrue("hour $h", HomeLogic.isNight(HomeLogic.NIGHT_AUTO, h, 0, 0))
        for (h in listOf(7, 12, 22)) assertFalse("hour $h", HomeLogic.isNight(HomeLogic.NIGHT_AUTO, h, 0, 0))
    }

    @Test fun customWindowWithinOneDay() {
        assertTrue(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 10, 10, 12))
        assertTrue(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 11, 10, 12))
        assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 12, 10, 12))
        assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 9, 10, 12))
    }

    @Test fun customWindowAcrossMidnight() {
        assertTrue(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 22, 22, 5))
        assertTrue(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 0, 22, 5))
        assertTrue(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 4, 22, 5))
        assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 5, 22, 5))
        assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, 21, 22, 5))
    }

    @Test fun offNeverDimsAndEqualHoursMeanNoWindow() {
        for (h in 0..23) {
            assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_OFF, h, 23, 7))
            assertFalse(HomeLogic.isNight(HomeLogic.NIGHT_CUSTOM, h, 8, 8))
        }
    }

    // ---- pins

    @Test fun pinsSurviveTheRoundTrip() {
        val pins = listOf(
            HomeLogic.Pin("ru.app", "Каникулы в Провансе", "https://img/1.jpg", "intent://x#Intent;end", false),
            HomeLogic.Pin("music.app", "Tab\there, line\nbreak and back\\slash", "content://cover/2", null, true)
        )
        val back = HomeLogic.decodePins(HomeLogic.encodePins(pins))
        assertEquals(2, back.size)
        for (i in pins.indices) {
            assertEquals(pins[i].pkg, back[i].pkg)
            assertEquals(pins[i].title, back[i].title)
            assertEquals(pins[i].art, back[i].art)
            assertEquals(pins[i].intentUri, back[i].intentUri)
            assertEquals(pins[i].square, back[i].square)
        }
    }

    @Test fun emptyAndBrokenPinTextGivesNoPins() {
        assertTrue(HomeLogic.decodePins("").isEmpty())
        assertTrue(HomeLogic.decodePins("garbage\n\nmore\tgarbage").isEmpty())
        assertEquals("", HomeLogic.encodePins(emptyList()))
    }

    @Test fun togglePinsAndUnpins() {
        val p = HomeLogic.Pin("a", "t", "art", null, false)
        val once = HomeLogic.togglePin(emptyList(), p)
        assertTrue(HomeLogic.isPinned(once, p.id))
        assertTrue(HomeLogic.togglePin(once, HomeLogic.Pin("a", "t", "other art", null, false)).isEmpty())
    }

    @Test fun pinFromAnItemOpensTheSameThing() {
        val i = item("a", "Title", 0.4f, 9, true)
        val back = HomeLogic.pinOf(i).toItem()
        assertEquals(i.pkg, back.pkg); assertEquals(i.title, back.title)
        assertEquals(i.art, back.art); assertEquals(i.intentUri, back.intentUri)
        assertFalse(back.resume)
    }

    // ---- titles

    @Test fun shortTitlesAreLeftAlone() {
        assertEquals("Чудо", HomeLogic.cutAtWord("Чудо", 10))
    }

    @Test fun longTitlesAreCutAtAWord() {
        assertEquals("Единая лига ВТБ…", HomeLogic.cutAtWord("Единая лига ВТБ: будет жарко", 19))
        assertEquals("Битва за пояс UFC в…", HomeLogic.cutAtWord("Битва за пояс UFC в тяжёлом весе", 20))
        assertEquals("Heavy haul…", HomeLogic.cutAtWord("Heavy haul transformer", 10))
    }

    @Test fun oneVeryLongWordIsCutInsideButNeverInsideACharacter() {
        assertEquals("Достопримеча…", HomeLogic.cutAtWord("Достопримечательности", 12))
        val emoji = "ab\uD83D\uDE00cd"
        assertEquals("ab…", HomeLogic.cutAtWord(emoji, 3))
    }

    @Test fun visualizersThatRemainKeepTheirChoice() {
        // old numbers: 1 Flow, 5 Pixels, 6 Wave, 7 Desert, 8 Cyberpunk, 11 Blade Runner
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), listOf(0, 1, 5, 6, 7, 8, 11).map { HomeLogic.migrateVisualizer(it) })
    }

    @Test fun removedVisualizersFallBack() {
        // Orb, Stardust, Silk -> Flow; Tron Legacy, Matrix, Interstellar -> Blade Runner; rotate stays; rubbish -> Off
        assertEquals(listOf(1, 1, 1), listOf(2, 3, 4).map { HomeLogic.migrateVisualizer(it) })
        assertEquals(listOf(6, 6, 6), listOf(9, 10, 12).map { HomeLogic.migrateVisualizer(it) })
        assertEquals(-1, HomeLogic.migrateVisualizer(-1))
        assertEquals(0, HomeLogic.migrateVisualizer(99))
    }
}
