package app.jarvis

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val t = Times(5, 13, 21)

    @Test fun searchRanksByLongestSharedPiece() {
        val apps = listOf("Voice Recorder", "Voice Assistant", "Camera", "Calendar", "Gallery")
        assertEquals("Voice Recorder", rankApps("recorde", apps).first())
        assertEquals("slip in the middle", "Voice Recorder", rankApps("recordr", apps).first())
        assertEquals("shorter name wins a tie", listOf("Voice Recorder", "Voice Assistant"), rankApps("voice", apps))
        assertEquals("any case", "Voice Recorder", rankApps("RECORDER", apps).first())
        assertEquals("nothing shared", emptyList<String>(), rankApps("xyz", apps))
        assertEquals("a stray pair of letters is no match", emptyList<String>(), rankApps("reco", listOf("Acode", "Coffy", "Connect")))
        assertEquals(listOf("Recorder"), rankApps("reco", listOf("Acode", "Recorder")))
        assertEquals("nothing typed lists all, sorted", listOf("Calendar", "Camera", "Gallery", "Voice Assistant", "Voice Recorder"), rankApps(" ", apps))
    }

    @Test fun phases() {
        assertEquals(Phase.MORNING, phaseAt(7, false, false, t))
        assertEquals(Phase.AWAKE, phaseAt(7, true, false, t))
        assertEquals(Phase.EVENING, phaseAt(1, true, false, t))
        assertEquals(Phase.AWAKE, phaseAt(1, true, true, t))
        assertEquals(Phase.MORNING, phaseAt(5, false, false, t))
        assertEquals(Phase.AWAKE, phaseAt(14, false, false, t))
        assertEquals(Phase.EVENING, phaseAt(22, true, false, t))
        assertEquals(Phase.AWAKE, phaseAt(22, true, true, t))
    }

    private val p = MorningPolicy(
        alwaysAllowed = setOf("dialer"), allowedPackages = setOf("com.example.cards"),
        allowedPrefixes = listOf("org.chromium.webapk."), allowedUrls = setOf("https://cards.example/card"),
        browsers = setOf("chrome"), whatsappLimitMs = 300_000,
    )

    @Test fun morningRules() {
        assertEquals(Verdict.ALLOW, morningVerdict("dialer", null, 0, p))
        assertEquals(Verdict.ALLOW, morningVerdict("com.whatsapp", null, 299_999, p))
        assertEquals(Verdict.KICK_LIMIT, morningVerdict("com.whatsapp", null, 300_000, p))
        assertEquals(Verdict.ALLOW, morningVerdict("org.chromium.webapk.abc", null, 0, p))
        assertEquals(Verdict.ALLOW, morningVerdict("chrome", "cards.example/card/3", 0, p))
        assertEquals(Verdict.KICK, morningVerdict("chrome", "cards.example/other", 0, p))
        assertEquals(Verdict.KICK, morningVerdict("chrome", "youtube.com", 0, p))
        assertEquals(Verdict.KICK, morningVerdict("chrome", null, 0, p))
        assertEquals(Verdict.KICK, morningVerdict("instagram", null, 0, p))
    }

    @Test fun shortsUrls() {
        assertEquals("aB_1-", shortsId("m.youtube.com/shorts/aB_1-?feature=share"))
        assertEquals("x", shortsId("https://www.youtube.com/shorts/x"))
        assertNull(shortsId("youtube.com/watch?v=x"))
        assertNull(shortsId("evil.com/youtube.com/shorts/x"))
    }

    @Test fun oneShortAndOneSwipeAreFineSecondSwipeWarns() {
        val s = ShortsTracker(swipesAllowed = 1, nagMs = 300_000)
        assertEquals(ShortsAction.NONE, s.onUrl("a", 1000))
        assertEquals(ShortsAction.NONE, s.onUrl("a", 2000))
        assertEquals(ShortsAction.NONE, s.onUrl("b", 3000))
        assertEquals(ShortsAction.WARN, s.onUrl("c", 4000))
        assertEquals(ShortsAction.NONE, s.onUrl("d", 5000))
    }

    @Test fun nagsOnlyAfterChoosingToContinue() {
        val s = ShortsTracker(1, 300_000)
        s.onSwipe(1000); s.onSwipe(2000)
        assertEquals(ShortsAction.NONE, s.tick(10_000))
        s.chooseToContinue(10_000)
        assertEquals(ShortsAction.NONE, s.tick(309_999))
        assertEquals(ShortsAction.NAG, s.tick(310_000))
        assertEquals(ShortsAction.NONE, s.tick(310_001))
    }

    @Test fun longGapStartsNewSession() {
        val s = ShortsTracker(1, 300_000)
        s.onSwipe(1000); assertEquals(ShortsAction.WARN, s.onSwipe(2000))
        assertEquals(ShortsAction.NONE, s.onSwipe(2000 + 3 * 60_000 + 1))
    }

    private val apps = listOf("WhatsApp", "Claude", "Word", "Instagram", "YouTube", "Çizim")

    @Test fun search() {
        assertEquals(listOf("Word"), matchApps("WOR", apps))
        assertEquals(listOf("Çizim"), matchApps("ciz", apps))
        assertNull(uniqueApp("a", apps))
        assertEquals("Claude", uniqueApp("cl", apps))
        assertNull(uniqueApp("xx", apps))
        assertEquals("Instagram", uniqueApp("İNST", apps))
    }

    @Test fun planLinesFindApps() {
    }

    @Test fun todoOrderAndRollover() {
        val l = listOf(Todo("a", true), Todo("b", false), Todo("c", true), Todo("d", false))
        assertEquals(listOf("b", "d", "a", "c"), ordered(l).map { it.text })
        assertEquals(listOf("b", "d", "yeni"), rollover(l, listOf("yeni", "B")).map { it.text })
    }
}

class DayTest {
    @Test fun spotifyIsNeverKicked() {
        val p = MorningPolicy(NEVER_BLOCKED, emptySet(), emptyList(), emptySet(), emptySet(), whatsappLimitMs = 0)
        assertEquals(Verdict.ALLOW, morningVerdict("com.spotify.music", null, 999_999, p))
    }

    @Test fun moods() {
        assertEquals(DayMood.PROUD, dayMood(4, 4, 0))
        assertEquals(DayMood.MIXED, dayMood(3, 4, 0))
        assertEquals(DayMood.MIXED, dayMood(2, 4, 0))
        assertEquals(DayMood.DISAPPOINTED, dayMood(4, 4, 2))
        assertEquals(DayMood.DISAPPOINTED, dayMood(1, 4, 0))
        assertEquals(DayMood.DISAPPOINTED, dayMood(0, 0, 0))
    }

    @Test fun theDayLeavesItsNoteWithoutClosing() {
        assertEquals(DayMood.PROUD, inferredMood(4, 4, 0, null))
        assertEquals("the user's own word wins", DayMood.MIXED, inferredMood(4, 4, 0, DayMood.MIXED))
        assertEquals(DayMood.PROUD, inferredMood(0, 0, 0, DayMood.PROUD))
        assertNull("nothing to go on: no note", inferredMood(0, 0, 0, null))
        assertEquals(DayMood.DISAPPOINTED, inferredMood(0, 0, 1, null))
    }

    @Test fun moodWords() {
        assertEquals(DayMood.PROUD, moodWord("It went well"))
        assertEquals(DayMood.MIXED, moodWord("mixed"))
        assertEquals(DayMood.MIXED, moodWord("not bad"))
        assertEquals(DayMood.DISAPPOINTED, moodWord("Bad"))
        assertEquals(DayMood.DISAPPOINTED, moodWord("it didn't go well"))
        assertNull(moodWord("gym"))
    }

    @Test fun everyPhaseHasButtonsWithAnswers() {
        Phase.values().forEach { ph ->
            val c = offlineChoices(ph, "x")
            assertTrue(c.size >= 2)
            assertTrue(c.all { it.label.isNotBlank() && it.reply.isNotBlank() })
        }
    }
}

class SuggestionTest {
    private val wall = "stare at the wall"

    @Test fun bookIsOfferedWithItsName() {
        val line = suggestionLine(emptyList(), "Dune", emptyList(), "", { 0 })
        assertEquals("If you are sitting, you could read Dune.", line)
    }

    @Test fun todoComesFirstWhenPicked() {
        val line = suggestionLine(listOf("Go to the gym"), "Dune", listOf("Sketch something"), wall, { 0 })
        assertTrue(line, line.startsWith("\"Go to the gym\" is still on your list"))
        assertTrue(line, line.endsWith("Worst case: stare at the wall."))
    }

    @Test fun ideasAndBookTakeTurnsWithTodos() {
        val line = suggestionLine(listOf("Go to the gym"), "", listOf("Sketch something"), "", { n -> n - 1 })
        assertEquals("Sketch something.", line)
    }

    @Test fun lastResortAloneStillSaysSomething() {
        assertEquals("Worst case: stare at the wall.", suggestionLine(emptyList(), "", emptyList(), wall, { 0 }))
        assertEquals("I have no suggestion, sir, but I would not suggest this either.", suggestionLine(emptyList(), "", emptyList(), "", { 0 }))
    }
}

class LogicalDayTest {
    private fun at(s: String) = java.time.LocalDateTime.parse(s)

    @Test fun afterMidnightIsStillTheDayBefore() {
        assertEquals("2026-10-30", logicalDay(at("2026-10-31T01:08:00"), 5))
        assertEquals("2026-10-30", logicalDay(at("2026-10-31T04:59:00"), 5))
        assertEquals("2026-10-31", logicalDay(at("2026-10-31T05:00:00"), 5))
        assertEquals("2026-10-31", logicalDay(at("2026-10-31T23:30:00"), 5))
    }

    @Test
    fun everySwipeIsCountedEvenBeforeTheWarning() {
        var counted = 0
        val t = ShortsTracker(1, 300_000, onSwiped = { counted++ })
        t.onUrl("a", 1_000)
        assertEquals(0, counted)
        t.onUrl("a", 2_000)
        assertEquals(0, counted)
        t.onUrl("b", 3_000)
        assertEquals(1, counted)
        t.onUrl("c", 4_000)
        t.onUrl("d", 5_000)
        assertEquals(3, counted)
        t.onSwipe(6_000)
        assertEquals(4, counted)
    }
}
