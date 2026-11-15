package app.hermes.aurora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTest {

    private fun hit(id: Int, tv: Boolean, year: Int, pop: Double, vararg names: String) = Tmdb.Hit(id, tv, names.toList(), year, pop)

    @Test fun whatAppsAddBehindATitleIsRemoved() {
        assertEquals("The Wire", Tmdb.cleanTitle("The Wire - S1E2"))
        assertEquals("The Wire", Tmdb.cleanTitle("The Wire: Season 2"))
        assertEquals("The Wire", Tmdb.cleanTitle("The Wire (2002)"))
        assertEquals("Прослушка", Tmdb.cleanTitle("Прослушка · 3 сезон 4 серия"))
        assertEquals("Каникулы в Провансе", Tmdb.cleanTitle("Каникулы в Провансе"))
        // a title that merely contains such words keeps them
        assertEquals("Open Season", Tmdb.cleanTitle("Open Season"))
        assertEquals("1917", Tmdb.cleanTitle("1917"))
    }

    @Test fun titlesAreComparedByLettersAndDigits() {
        assertEquals("spider man no way home", Tmdb.norm("Spider-Man: No Way Home"))
        assertEquals(Tmdb.norm("Ёлки"), Tmdb.norm("елки"))
    }

    @Test fun onlyFilmsAndSeriesAreLookedUp() {
        assertTrue(Tmdb.lookable(Tmdb.KIND_MOVIE, false))
        assertTrue(Tmdb.lookable(Tmdb.KIND_EPISODE, false))
        assertTrue(Tmdb.lookable(Tmdb.KIND_UNKNOWN, false))
        assertFalse(Tmdb.lookable(4, false))        // a clip
        assertFalse(Tmdb.lookable(10, false))       // a playlist
        assertFalse(Tmdb.lookable(Tmdb.KIND_UNKNOWN, true))   // a cover
    }

    @Test fun theNameMustBeEqualNotSimilar() {
        val hits = listOf(hit(1, false, 1999, 90.0, "The Matrix", "Матрица"), hit(2, false, 2003, 60.0, "The Matrix Reloaded"))
        assertEquals(1, Tmdb.pick(hits, "Матрица", Tmdb.KIND_UNKNOWN, 0)?.id)
        assertEquals(1, Tmdb.pick(hits, "the matrix (1999)", Tmdb.KIND_UNKNOWN, 0)?.id)
        assertNull(Tmdb.pick(hits, "The Matrix explained in 10 minutes", Tmdb.KIND_UNKNOWN, 0))
        assertNull(Tmdb.pick(hits, "Matrix", Tmdb.KIND_UNKNOWN, 0))
    }

    @Test fun kindAndYearDecideBetweenEqualNames() {
        val hits = listOf(hit(1, false, 1990, 50.0, "It"), hit(2, false, 2017, 80.0, "It"), hit(3, true, 2017, 99.0, "It"))
        assertEquals(3, Tmdb.pick(hits, "It", Tmdb.KIND_UNKNOWN, 0)?.id)         // the best known
        assertEquals(2, Tmdb.pick(hits, "It", Tmdb.KIND_MOVIE, 0)?.id)
        assertEquals(1, Tmdb.pick(hits, "It", Tmdb.KIND_MOVIE, 1990)?.id)
        // a film of a year TMDB does not have under this name is not guessed
        assertNull(Tmdb.pick(hits, "It", Tmdb.KIND_MOVIE, 2005))
        // a series keeps its match when the stated year is an episode's
        assertEquals(3, Tmdb.pick(hits, "It", Tmdb.KIND_EPISODE, 2021)?.id)
    }

    @Test fun theBackdropWithoutLetteringWins() {
        val imgs = listOf(Tmdb.Img("/en.jpg", "en", 3840, 9.0), Tmdb.Img("/small.jpg", null, 1280, 9.5), Tmdb.Img("/clean.jpg", null, 1920, 5.0))
        assertEquals("/clean.jpg", Tmdb.pickBackdrop(imgs))
        assertNull(Tmdb.pickBackdrop(emptyList()))
    }

    @Test fun theLogoFollowsTheLanguage() {
        val imgs = listOf(Tmdb.Img("/en.png", "en", 500, 5.0), Tmdb.Img("/ru.png", "ru", 500, 1.0), Tmdb.Img("/de.png", "de", 500, 9.0), Tmdb.Img("/ru.svg", "ru", 500, 9.0))
        assertEquals("/ru.png", Tmdb.pickLogo(imgs, "ru"))
        assertEquals("/en.png", Tmdb.pickLogo(imgs, "ka"))
        assertNull(Tmdb.pickLogo(listOf(Tmdb.Img("/de.png", "de", 500, 9.0)), "en"))
    }

    @Test fun theLineSaysOnlyWhatIsKnown() {
        assertEquals("2002  ·  Crime, Drama  ·  ★ 8.6", Tmdb.metaLine(2002, listOf("Crime", "Drama", "Mystery"), 8.56, 2000))
        assertEquals("2002", Tmdb.metaLine(2002, emptyList(), 9.0, 3))      // too few votes to mean anything
        assertEquals("", Tmdb.metaLine(0, emptyList(), 0.0, 0))
    }
}
