package app.jarvis

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ReminderTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val evening = LocalDateTime.of(2026, 11, 6, 20, 0)

    @Test fun spokenAtATime() {
        assertEquals(Action.AddReminder("the call with Anna", null, "21:15"), parseCommand("remind me about the call with Anna at 21:15", today))
        assertEquals(Action.AddReminder("the call with Anna", null, "21:15"), parseCommand("Jarvis, remind me at 9:15 pm about the call with Anna.", today))
        assertEquals(Action.AddReminder("drink water", "2026-11-07", "09:00"), parseCommand("remind me to drink water tomorrow at 9", today))
        assertEquals(Action.AddReminder("take my medicine", "2026-11-06", "21:00"), parseCommand("remind me to take my medicine today at 9 in the evening", today))
        assertEquals(Action.AddReminder("the meeting", null, "10:30"), parseCommand("remind me about the meeting at 10.30", today))
        assertNull("no such hour", parseCommand("remind me at 27:15 about Anna", today))
    }

    @Test fun spokenAfterAWhile() {
        assertEquals(Action.AddReminder("the tea", inMinutes = 10), parseCommand("remind me about the tea in 10 minutes", today))
        assertEquals(Action.AddReminder("the laundry", inMinutes = 120), parseCommand("in 2 hours remind me about the laundry", today))
        assertEquals(Action.AddReminder("check the oven", inMinutes = 30), parseCommand("remind me in half an hour to check the oven", today))
    }

    @Test fun spokenNaturally() {
        val tomorrow = "2026-11-07"
        assertEquals(Action.AddReminder("take my medicine", tomorrow, "21:00"), parseCommand("can you remind me to take my medicine tomorrow evening at nine", today))
        assertEquals(Action.AddReminder("pay the bill", "2026-11-13", "09:00"), parseCommand("don't let me forget to pay the bill on friday morning", today))
        assertEquals(Action.AddReminder("leave", null, "08:45"), parseCommand("remind me at quarter to nine to leave", today))
        assertEquals(Action.AddReminder("call mum", null, "21:10"), parseCommand("remind me to call mum at ten past 9 in the evening", today))
        assertEquals(Action.AddReminder("check the oven", inMinutes = 90), parseCommand("set a reminder to check the oven in an hour and a half", today))
        assertEquals(Action.AddReminder("drink water", inMinutes = 80), parseCommand("remind me in 1 hour 20 minutes to drink water", today))
        assertEquals(Action.AddReminder("buy bread", inMinutes = 15), parseCommand("remind me in fifteen minutes to buy bread please", today))
        assertEquals(Action.AddReminder("the trash", tomorrow, "09:00"), parseCommand("remind me about the trash tomorrow", today))
        assertEquals(Action.AddReminder("the meeting", null, "20:00"), parseCommand("remind me about the meeting this evening", today))
        assertEquals(Action.AddReminder("buy two loaves", null, "20:00"), parseCommand("remind me to buy two loaves in the evening", today))
        assertEquals(Action.AddReminder("the parcel", null, "10:30"), parseCommand("remind me about the parcel at ten thirty", today))
        assertEquals(Action.AddReminder("Reminder", inMinutes = 10), parseCommand("remind me in a bit", today))
        assertNull("no time at all", parseCommand("remind me to tell him one thing", today))
    }

    @Test fun aBareHourMeansTheNextOneToCome() {
        val afternoon = LocalDateTime.of(2026, 11, 6, 14, 0)
        assertEquals(Action.AddReminder("the laundry", null, "17:00"), parseCommand("remind me about the laundry at 5", today, afternoon))
        assertEquals(Action.AddReminder("class", null, "15:30"), parseCommand("remind me about class at 3:30", today, afternoon))
        assertEquals("still to come in the morning", Action.AddReminder("x", null, "05:00"), parseCommand("remind me about x at 5", today, LocalDateTime.of(2026, 11, 6, 3, 0)))
        assertEquals("said with am, taken as said", Action.AddReminder("x", null, "05:00"), parseCommand("remind me about x at 5 am", today, afternoon))
    }

    @Test fun bellLabel() {
        val now = LocalDateTime.of(2026, 11, 6, 20, 0)
        assertEquals("21.15", nextLabel(LocalDateTime.of(2026, 11, 6, 21, 15), now))
        assertEquals("TMRW 09.00", nextLabel(LocalDateTime.of(2026, 11, 7, 9, 0), now))
        assertEquals("12.11", nextLabel(LocalDateTime.of(2026, 11, 12, 9, 0), now))
    }

    @Test fun spokenRemoval() {
        assertEquals(Action.RemoveReminder("Anna"), parseCommand("delete the Anna reminder", today))
        assertEquals(Action.RemoveReminder("the tea"), parseCommand("cancel the reminder about the tea", today))
    }

    @Test fun otherCommandsAreUntouched() {
        assertEquals(Action.SetAlarm(8, 0, true), parseCommand("set an alarm for 8 tomorrow", today))
        assertEquals(Action.AddTodo("buy milk"), parseCommand("add buy milk", today))
    }

    @Test fun clocks() {
        assertEquals(LocalTime.of(9, 5), parseClock("9:05"))
        assertEquals(LocalTime.of(21, 15), parseClock("21.15"))
        assertNull(parseClock("24:00"))
        assertNull(parseClock("21"))
    }

    @Test fun whenItGoesOff() {
        assertEquals(LocalDateTime.of(2026, 11, 6, 21, 15), reminderTime(null, "21:15", null, evening))
        assertEquals("already past today: tomorrow", LocalDateTime.of(2026, 11, 7, 19, 0), reminderTime(null, "19:00", null, evening))
        assertEquals(LocalDateTime.of(2026, 11, 8, 9, 0), reminderTime("2026-11-08", "09:00", null, evening))
        assertNull("a dated moment already gone", reminderTime("2026-11-06", "19:00", null, evening))
        assertEquals(evening.plusMinutes(30), reminderTime(null, null, 30, evening))
        assertNull(reminderTime(null, null, 0, evening))
        assertNull(reminderTime("monday", "09:00", null, evening))
        assertNull(reminderTime(null, null, null, evening))
    }

    @Test fun tasksAreRemindedAnHourBefore() {
        val event = LocalDateTime.of(2026, 11, 6, 22, 0)
        assertEquals(LocalDateTime.of(2026, 11, 6, 21, 0), leadTime(event, evening))
        assertEquals("the hour before already passed: now", evening, leadTime(LocalDateTime.of(2026, 11, 6, 20, 30), evening))
        assertNull("already begun", leadTime(evening, evening))
    }

    @Test fun pendingAndDue() {
        val gone = Reminder(1, "old", evening.minusMinutes(5))
        val soon = Reminder(2, "soon", evening.plusMinutes(5))
        val later = Reminder(3, "later", evening.plusHours(3), evening.plusHours(4), "report")
        val all = listOf(later, gone, soon)
        assertEquals(listOf(soon, later), pendingReminders(all, evening))
        assertEquals(listOf(gone), dueReminders(all, evening))
        assertEquals("2026-11-06 20:05 soon; 2026-11-06 23:00 later (event 00:00)", remindersForContext(all, evening))
        assertEquals("none", remindersForContext(listOf(gone), evening))
    }

    @Test fun whatHeSays() {
        assertEquals("Sir, you asked me to remind you: tea.", reminderLine(Reminder(1, "tea.", evening)))
        assertEquals("Sir, a reminder for 21:15: \"the call with Anna\".", reminderLine(Reminder(1, "the call with Anna", evening, LocalDateTime.of(2026, 11, 6, 21, 15))))
    }
}
