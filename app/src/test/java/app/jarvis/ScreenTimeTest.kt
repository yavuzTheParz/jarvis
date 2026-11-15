package app.jarvis

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeTest {
    private val min = 60_000L

    @Test fun sumsForegroundSpans() {
        val e = listOf(
            UsageEvent("a", 10 * min, true), UsageEvent("a", 20 * min, false),
            UsageEvent("b", 30 * min, true), UsageEvent("b", 35 * min, false),
        )
        assertEquals(15 * min, screenTimeMs(e, 0, 60 * min))
    }

    @Test fun clipsToDayAndNow() {
        val e = listOf(
            UsageEvent("a", 5 * min, false), // already open when the day began
            UsageEvent("b", 50 * min, true), // still open
        )
        assertEquals(15 * min, screenTimeMs(e, 0, 60 * min))
    }

    @Test fun skipsIgnoredAndRepeatedStarts() {
        val e = listOf(
            UsageEvent("home", 0, true), UsageEvent("home", 30 * min, false),
            UsageEvent("a", 10 * min, true), UsageEvent("a", 12 * min, true), UsageEvent("a", 20 * min, false),
        )
        assertEquals(10 * min, screenTimeMs(e, 0, 60 * min, setOf("home")))
    }

    @Test fun pauseThenStopCountsOnce() {
        val e = listOf(
            UsageEvent("a", 10 * min, true), UsageEvent("a", 20 * min, false), UsageEvent("a", 21 * min, false),
        )
        assertEquals(10 * min, screenTimeMs(e, 0, 60 * min))
    }

    @Test fun screenOffEndsTheOpenApp() {
        val e = listOf(
            UsageEvent("a", 10 * min, true), UsageEvent(SCREEN_OFF, 15 * min, false), UsageEvent("a", 40 * min, false),
        )
        assertEquals(5 * min, screenTimeMs(e, 0, 60 * min))
    }

    @Test fun labels() {
        assertEquals("0 DK", screenTimeLabel(30_000))
        assertEquals("42 DK", screenTimeLabel(42 * min))
        assertEquals("2 SA 05 DK", screenTimeLabel(125 * min))
    }

    @Test fun splitsIntoHours() {
        val hour = 60 * min
        val e = listOf(UsageEvent("a", 50 * min, true), UsageEvent("a", hour + 20 * min, false), UsageEvent("b", 3 * hour, true))
        val h = screenTimeByHour(e, 0, 3 * hour + 5 * min)
        assertEquals(10 * min, h[0]); assertEquals(20 * min, h[1]); assertEquals(0L, h[2]); assertEquals(5 * min, h[3])
        assertEquals(35 * min, screenTimeMs(e, 0, 3 * hour + 5 * min))
    }

    @Test fun splitsIntoAppsLongestFirst() {
        val e = listOf(
            UsageEvent("a", 5 * min, false), // already open at midnight
            UsageEvent("b", 10 * min, true), UsageEvent("b", 40 * min, false),
            UsageEvent("home", 40 * min, true), UsageEvent("home", 45 * min, false),
            UsageEvent("c", 50 * min, true), // still open
        )
        val apps = screenTimeByApp(e, 0, 60 * min, setOf("home"))
        assertEquals(listOf("b" to 30 * min, "c" to 10 * min, "a" to 5 * min), apps)
        assertEquals("the bars add up to the total", screenTimeMs(e, 0, 60 * min, setOf("home")), apps.sumOf { it.second })
    }

    @Test fun readoutPiecesAndLevels() {
        assertEquals(listOf("2" to true, "H" to false, " " to false, "05" to true, "M" to false), screenPieces(125 * min))
        assertEquals(listOf("40" to true, "M" to false), screenPieces(40 * min))
        assertEquals(0, screenLevel(119)); assertEquals(1, screenLevel(120)); assertEquals(2, screenLevel(240))
    }
}
