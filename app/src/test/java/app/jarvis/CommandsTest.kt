package app.jarvis

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CommandsTest {
    private fun alarm(h: Int, m: Int = 0, tomorrow: Boolean = false) = Action.SetAlarm(h, m, tomorrow)

    @Test fun alarms() {
        assertEquals(alarm(8, 0, true), parseCommand("Set an alarm for 8 tomorrow"))
        assertEquals(alarm(8, 0, true), parseCommand("Jarvis, set an alarm for 8 tomorrow morning."))
        assertEquals(alarm(8, 30, true), parseCommand("alarm at 8:30 tomorrow"))
        assertEquals(alarm(8, 30, true), parseCommand("wake me up at 08.30 tomorrow"))
        assertEquals(alarm(8, 30, false), parseCommand("set an alarm for half past eight"))
        assertEquals(alarm(8), parseCommand("Set an alarm for eight"))
        assertEquals(alarm(6), parseCommand("set an alarm at six am"))
        assertEquals(alarm(5, 45), parseCommand("alarm for quarter to six"))
        assertEquals(alarm(21), parseCommand("set an alarm for 9 pm"))
        assertEquals(alarm(21), parseCommand("alarm at 9 in the evening"))
        assertEquals(alarm(0), parseCommand("alarm at 12 am"))
        assertNull("no time said", parseCommand("set an alarm"))
        assertNull("no such hour", parseCommand("set an alarm for 27 tomorrow"))
    }

    @Test fun tasks() {
        assertEquals(Action.AddTodo("buy milk"), parseCommand("Jarvis add buy milk"))
        assertEquals(Action.AddTodo("buy milk"), parseCommand("add buy milk to the list"))
        assertEquals(Action.AddTodo("write the report"), parseCommand("add a task: write the report"))
        assertEquals(Action.AddTodo("go for a run", "2026-11-01"), parseCommand("add go for a run tomorrow", LocalDate.of(2026, 10, 31)))
        assertEquals(Action.CompleteTodo("report"), parseCommand("mark the report as done"))
        assertEquals(Action.CompleteTodo("report"), parseCommand("the report is done"))
        assertEquals(Action.CompleteTodo("buying milk"), parseCommand("I finished buying milk."))
        assertEquals(Action.CompleteTodo("laundry"), parseCommand("tick off laundry"))
        assertEquals(Action.PlanTomorrow, parseCommand("Jarvis let's plan tomorrow"))
        assertEquals(Action.PlanTomorrow, parseCommand("plan for tomorrow"))
        assertNull("no closing of the day any more", parseCommand("Jarvis let's close the day"))
        assertEquals(Action.RemoveTodo("report"), parseCommand("Jarvis remove the report task"))
        assertEquals(Action.RemoveTodo("buy milk"), parseCommand("delete buy milk from the list"))
        assertNull("not a list command", parseCommand("take the trash out"))
    }

    private val saturday = LocalDate.of(2026, 10, 31)

    @Test fun days() {
        assertEquals(LocalDate.of(2026, 11, 2), dayNamed("on monday", saturday))
        assertEquals(LocalDate.of(2026, 11, 1), dayNamed("Sunday", saturday))
        assertEquals(LocalDate.of(2026, 11, 6), dayNamed("for friday", saturday))
        assertEquals("the same weekday means next week", LocalDate.of(2026, 11, 7), dayNamed("saturday", saturday))
        assertEquals(LocalDate.of(2026, 11, 1), dayNamed("tomorrow", saturday))
        assertNull(dayNamed("march", saturday))
    }

    @Test fun datedAndEditedTasksWithoutClaude() {
        assertEquals(Action.AddTodo("get a haircut", "2026-11-02"), parseCommand("add for monday: get a haircut", saturday))
        assertEquals(Action.AddTodo("get a haircut", "2026-11-02"), parseCommand("Jarvis add get a haircut on monday", saturday))
        assertEquals(Action.MoveTodo("report", "2026-11-04"), parseCommand("move the report to wednesday", saturday))
        assertEquals(Action.EditTodo("buy milk", "buy yogurt"), parseCommand("change buy milk to buy yogurt", saturday))
        assertEquals(Action.EditTodo("buy milk", "buy yogurt"), parseCommand("replace buy milk with buy yogurt", saturday))
    }

    @Test fun agendaSplit() {
        val a = mapOf("2026-10-30" to listOf("old"), "2026-10-31" to listOf("today"), "2026-11-01" to listOf("tomorrow"), "2026-11-02" to listOf("later"))
        val s = splitAgenda(a, saturday)
        assertEquals(listOf("old", "today"), s.dueNow)
        assertEquals(listOf("tomorrow"), s.tomorrow)
        assertEquals(mapOf("2026-11-02" to listOf("later")), s.later)
    }

    @Test fun routines() {
        val r = listOf(Routine("run", setOf(1, 3, 5)), Routine("weekend cleaning", setOf(6)))
        assertEquals(listOf("weekend cleaning"), routinesOn(r, saturday))
        assertEquals(listOf("run"), routinesOn(r, LocalDate.of(2026, 11, 2)))
    }

    @Test fun taper() {
        val t = Taper(2.0, 0.5, "2026-11-02")
        assertEquals(2.0, taperLimit(t, LocalDate.of(2026, 11, 2)), 0.0)
        assertEquals(2.0, taperLimit(t, LocalDate.of(2026, 11, 8)), 0.0)
        assertEquals(1.5, taperLimit(t, LocalDate.of(2026, 11, 9)), 0.0)
        assertEquals("never below zero", 0.0, taperLimit(t, LocalDate.of(2027, 1, 1)), 0.0)
        assertEquals(0.5, taperStep(2.0), 0.0)
        assertEquals(1.0, taperStep(4.0), 0.0)
        assertEquals("at least half a dose", 0.5, taperStep(1.0), 0.0)
    }

    @Test fun usualAmountIsTodaysDose() {
        val coffee = Habit("Coffee", null, Taper(2.0, 0.5, "2026-11-02", "tablespoon"))
        assertEquals(2.0, usualAmount(coffee, LocalDate.of(2026, 11, 2)), 0.0)
        assertEquals(1.5, usualAmount(coffee, LocalDate.of(2026, 11, 9)), 0.0)
        assertEquals(1.0, usualAmount(Habit("Workout", 3, null), LocalDate.of(2026, 11, 9)), 0.0)
    }

    @Test fun weeklyHabit() {
        val h = Habit("Workout", 3, null, mapOf("2026-10-26" to 1.0, "2026-11-01" to 1.0, "2026-10-25" to 1.0, "2026-10-28" to 0.0))
        assertEquals("Monday to Sunday, zero not counted", 2, weekCount(h, LocalDate.of(2026, 11, 1)))
        assertEquals(0, weekCount(h, LocalDate.of(2026, 11, 2)))
    }

    @Test fun calendarPage() {
        val november = monthCells(java.time.YearMonth.of(2026, 11))
        assertEquals("six weeks", 42, november.size)
        assertEquals("the 1st is a Sunday", LocalDate.of(2026, 11, 1), november[6])
        assertNull(november[5])
        assertEquals(LocalDate.of(2026, 11, 30), november[35])
        assertNull(november[36])
        val february = monthCells(java.time.YearMonth.of(2027, 2))
        assertEquals("the 1st is a Monday", LocalDate.of(2027, 2, 1), february[0])
        assertEquals(28, february.count { it != null })
    }

    @Test fun habitLoggedForAForgottenDay() {
        val wednesday = LocalDate.of(2026, 11, 4)
        assertEquals(wednesday, habitDay(null, wednesday))
        assertEquals(LocalDate.of(2026, 11, 3), habitDay("2026-11-03", wednesday))
        assertNull("days to come are turned away", habitDay("2026-11-05", wednesday))
        assertNull(habitDay("3 November", wednesday))
        // Tuesday's log counts in the week it belongs to, not the one it was written in.
        val h = Habit("Expense tracking", 3, null, mapOf("2026-11-03" to 1.0))
        assertEquals(1, weekCount(h, wednesday))
        assertEquals(0, weekCount(h, LocalDate.of(2026, 11, 2).minusDays(1)))
        // A forgotten coffee is that day's dose, not today's.
        val coffee = Habit("Coffee", null, Taper(2.0, 0.5, "2026-10-26"))
        assertEquals(2.0, usualAmount(coffee, LocalDate.of(2026, 11, 1)), 0.0)
        assertEquals(1.5, usualAmount(coffee, LocalDate.of(2026, 11, 3)), 0.0)
        val names = listOf("Workout", "Expense tracking")
        assertEquals(Action.LogHabit("Expense tracking", null, "2026-11-03"), parseHabit("I did my expense tracking yesterday", names, wednesday))
        assertEquals(Action.LogHabit("Workout", null, "2026-11-02"), parseHabit("I did a workout the day before yesterday", names, wednesday))
        assertEquals(Action.LogHabit("Workout", null, null), parseHabit("did my workout", names, wednesday))
    }

    @Test fun habitsWithoutClaude() {
        val names = listOf("Workout", "Coffee")
        assertEquals("no amount said", Action.LogHabit("Coffee", null), parseHabit("Jarvis I had coffee", names))
        assertEquals(Action.LogHabit("Coffee", 2.0), parseHabit("had two spoons of coffee", names))
        assertEquals(Action.LogHabit("Coffee", 2.0), parseHabit("I drank 2 coffees", names))
        assertEquals(Action.LogHabit("Coffee", 0.5), parseHabit("had half a coffee", names))
        assertEquals(Action.LogHabit("Coffee", 0.25), parseHabit("had a quarter spoon of coffee", names))
        assertEquals(Action.LogHabit("Coffee", 0.75), parseHabit("had three quarters of a spoon of coffee", names))
        assertEquals(Action.LogHabit("Coffee", 1.5), parseHabit("I had one and a half cups of coffee", names))
        assertEquals(Action.LogHabit("Workout", null), parseHabit("did my workout today", names))
        assertNull("not a habit", parseHabit("did the report", names))
        assertNull("not done", parseHabit("I need to have coffee", names))
    }

    private fun at(s: String) = clockMinute(s)!!
    private fun block(a: String, z: String, t: String) = Block(at(a), at(z), t)

    @Test fun typedPlans() {
        assertEquals(listOf(block("09:00", "10:30", "Gym")), parseSchedule("09:00-10:30 Gym"))
        assertEquals("no end runs to the next, the last an hour",
            listOf(block("09:00", "11:00", "gym"), block("11:00", "13:00", "class"), block("13:00", "14:00", "lunch")),
            parseSchedule("9 gym, 11 class, 13 lunch"))
        assertEquals("one after eleven is the afternoon", listOf(block("11:00", "13:00", "class"), block("13:00", "14:00", "lunch")), parseSchedule("11 class, 1 lunch"))
        assertEquals("one after eleven at night is the night", listOf(block("23:00", "01:00", "movie"), block("01:00", "02:00", "sleep")), parseSchedule("23 movie, 1 sleep"))
        assertEquals(listOf(block("10:00", "14:00", "class")), parseSchedule("10-2 class"))
        assertNull("words are for Claude", parseSchedule("gym in the morning then class"))
        assertNull("one bad part", parseSchedule("9 gym, then class"))
        assertNull("no such hour", parseSchedule("27 gym"))
        assertNull("no time at all", parseSchedule("9-9 gym"))
    }

    @Test fun dialsAreTwelveHoursEach() {
        assertEquals(listOf(600 to 720), arcsOn(block("10:00", "14:00", "x"), false))
        assertEquals(listOf(0 to 120), arcsOn(block("10:00", "14:00", "x"), true))
        assertEquals("past midnight comes back to the morning dial", listOf(0 to 60), arcsOn(block("23:00", "01:00", "x"), false))
        assertEquals(listOf(660 to 720), arcsOn(block("23:00", "01:00", "x"), true))
        assertEquals("ending at midnight stays on one dial", emptyList<Pair<Int, Int>>(), arcsOn(block("23:00", "00:00", "x"), false))
    }

    private val day = listOf(block("23:00", "01:00", "movie"), block("09:00", "10:00", "gym"), block("12:00", "13:00", "lunch"))

    @Test fun whereTheDayStands() {
        assertEquals(listOf("gym", "lunch", "movie"), inDayOrder(day, 5).map { it.text })
        val morning = planAt(day, at("09:30"), 5)
        assertEquals("gym", morning.current?.text)
        assertEquals(listOf("lunch", "movie"), morning.next.map { it.text })
        assertEquals(emptyList<Block>(), morning.over)
        val gap = planAt(day, at("11:00"), 5)
        assertNull(gap.current)
        assertEquals(listOf("gym"), gap.over.map { it.text })
        val night = planAt(day, at("00:30"), 5)
        assertEquals("still tonight's film", "movie", night.current?.text)
        assertEquals(emptyList<Block>(), night.next)
    }

    @Test fun aNewBlockTakesItsTime() {
        val one = listOf(block("09:00", "12:00", "class"))
        assertEquals("cuts the middle", listOf(block("09:00", "10:00", "class"), block("10:00", "11:00", "lunch"), block("11:00", "12:00", "class")),
            placeBlock(one, block("10:00", "11:00", "lunch"), 5))
        assertEquals("covers it entirely", listOf(block("08:00", "13:00", "trip")), placeBlock(one, block("08:00", "13:00", "trip"), 5))
        assertEquals("leaves the rest alone", listOf(block("09:00", "12:00", "class"), block("13:00", "14:00", "lunch")), placeBlock(one, block("13:00", "14:00", "lunch"), 5))
        assertEquals("across midnight", listOf(block("23:00", "00:00", "movie"), block("00:00", "01:00", "sleep")), placeBlock(listOf(block("23:00", "01:00", "movie")), block("00:00", "01:00", "sleep"), 5))
    }

    @Test fun noiseThatWhisperTurnsIntoWords() {
        for (s in listOf("Thank you for watching!", "Thanks for watching.", "Subtitles by the Amara.org community", "Thank you.", "Bye.", "Hmm.", "uhm")) assertTrue(s, isWhisperNoise(s))
        for (s in listOf("set an alarm for 8 tomorrow", "thank you Jarvis, add buy milk", "I had coffee")) assertFalse(s, isWhisperNoise(s))
    }

    @Test fun whatNeedsClaudeIsLeftToClaude() {
        assertNull(parseCommand("what's the weather like today"))
        assertNull(parseCommand("I'm going to tell you about my day, listen"))
        assertNull(parseCommand("give me 15 more minutes"))
    }

    @Test fun wakeAndEndWords() {
        assertEquals("set an alarm", withoutWakeWord("Hey Jarvis, set an alarm"))
        assertEquals("set an alarm", withoutWakeWord("jarvis set an alarm"))
        assertEquals("today went well", withoutEndPhrase("today went well. Okay, Jarvis."))
        assertEquals("today went well", withoutEndPhrase("today went well jarvis"))
        assertEquals("Jarvis was great today", withoutEndPhrase("Jarvis was great today"))
    }

    private val list = listOf(Todo("Write report", false), Todo("Buy milk", false), Todo("Run", true), Todo("Read report", false))

    @Test fun findingTheTaskMeant() {
        assertEquals(list[1], matchTodo("buy milk", list))
        assertEquals(list[1], matchTodo("the milk", list))
        assertEquals(list[0], matchTodo("write the report", list))
        assertNull("two reports fit", matchTodo("report", list))
        assertNull("already done", matchTodo("run", list))
        assertNull("nothing like it", matchTodo("plane ticket", list))
        assertNull(matchTodo(" ", list))
    }

    @Test fun removingFindsFinishedTasksAndPlansToo() {
        val lines = list.map { it.text } + "Dentist appointment"
        assertEquals("Run", matchLine("run", lines))
        assertEquals("Dentist appointment", matchLine("the dentist", lines))
        assertNull("two reports fit", matchLine("report", lines))
    }

    @Test fun spokenPlans() {
        assertEquals(listOf("go for a run", "write the report", "read a book"), spokenPlan("go for a run, write the report and read a book."))
        assertEquals(emptyList<String>(), spokenPlan(" . "))
    }

    @Test fun aWordOnTodayIsNotAPlanForTomorrow() {
        for (s in listOf("it went well", "Today was really bad.", "mixed", "not bad", "awful")) assertTrue(s, isOnlyMoodWord(s))
        for (s in listOf("go to the gym", "make a good breakfast", "write report, read book", "maybe")) assertFalse(s, isOnlyMoodWord(s))
    }

    @Test fun planSavedSaysHowMany() {
        assertTrue(planSavedLine(3).contains("3 tasks"))
        assertTrue(planSavedLine(0).contains("Nothing"))
    }

    @Test fun alarmsHaveNoDate() {
        val tomorrow8 = alarm(8, 0, true)
        assertTrue("rings in an hour", alarmWouldRingToday(LocalDateTime.of(2026, 11, 1, 7, 0), tomorrow8, 5))
        assertFalse("next 8 is tomorrow", alarmWouldRingToday(LocalDateTime.of(2026, 11, 1, 22, 0), tomorrow8, 5))
        assertFalse("after midnight tomorrow means this morning", alarmWouldRingToday(LocalDateTime.of(2026, 11, 1, 1, 0), tomorrow8, 5))
        assertFalse("not asked for tomorrow", alarmWouldRingToday(LocalDateTime.of(2026, 11, 1, 7, 0), alarm(8), 5))
    }

    @Test fun aBatchOfAlarmsGoesOutOneAtATime() {
        // Seven alarms from one reply, 09:00 to 09:30, all asked for at the same instant.
        var free = 0L
        val launches = List(7) { alarmSlot(1_000L, free).also { free = it.second }.first }
        assertEquals(1_000L, launches.first())
        assertEquals(7, launches.distinct().size)
        assertTrue(launches.zipWithNext().all { (a, b) -> b - a == ALARM_GAP_MS })
        assertEquals("a later, lone alarm goes at once", 99_000L, alarmSlot(99_000L, free).first)
    }

    private fun Endpointer.run(vararg frames: Pair<Double, Int>): Int? {
        var i = 0
        frames.forEach { (rms, n) -> repeat(n) { i++; if (push(rms)) return i } }
        return null
    }

    @Test fun endpointer() {
        assertEquals("ends after the silence", 30 + 12, Endpointer(1200, 6000, 30000).run(100.0 to 0, 3000.0 to 30, 100.0 to 50))
        assertEquals("gives up on no speech", 60, Endpointer(1200, 6000, 30000).run(100.0 to 100))
        val talking = Array(100) { (if (it % 2 == 0) 3000.0 else 100.0) to (if (it % 2 == 0) 5 else 3) }
        assertEquals("a long talk with breaths hits the limit", 300, Endpointer(1200, 6000, 30000).run(*talking))
        val fan = Endpointer(1200, 6000, 30000).run(800.0 to 400)
        assertTrue("steady noise stops counting as speech: $fan", fan != null && fan < 300)
        assertEquals("a pause shorter than the limit continues", 20 + 8 + 20 + 12, Endpointer(1200, 6000, 30000).run(3000.0 to 20, 100.0 to 8, 3000.0 to 20, 100.0 to 50))
    }
}
