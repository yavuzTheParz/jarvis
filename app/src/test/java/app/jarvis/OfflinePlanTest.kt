package app.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlanTest {
    private fun at(s: String) = clockMinute(s)!!
    private fun plan(text: String, now: String? = null) = parseSpokenSchedule(text, now?.let(::at), 5)?.let(::scheduleText)

    @Test fun aSpokenDay() {
        assertEquals(
            "09:00-10:30 Gym, 10:30-12:00 Class, 12:00-13:00 Lunch, 13:00-15:00 Reading, 20:00-21:00 Movie",
            plan("Gym at 9 in the morning, class from 10:30 to 12, lunch at noon, then 2 hours reading, movie at 8 pm"),
        )
    }

    @Test fun hoursInWords() {
        assertEquals("09:00-10:00 Breakfast, 10:00-11:00 Class", plan("breakfast at nine, class at ten"))
        assertEquals("21:30-22:30 Reading", plan("reading at nine thirty in the evening"))
    }

    @Test fun thenWithoutHoursFollowsThePrevious() {
        assertEquals("09:00-10:00 Go to the gym, 10:00-10:30 Shower, 10:30-11:30 Report",
            plan("I'll go to the gym at 9 then half an hour shower then report"))
        assertEquals("a reference to what came before is not a block of its own",
            "08:00-09:00 Breakfast, 09:00-10:00 Gym", plan("breakfast at 8, after breakfast, gym"))
        assertEquals("a time said with 'after' belongs to what it names",
            "15:00-16:00 Gym", plan("after 3 gym"))
    }

    @Test fun morningOrAfternoon() {
        assertEquals("one after eleven is the afternoon", "11:00-13:00 Class, 13:00-14:00 Lunch", plan("class at 11, lunch at 1"))
        assertEquals("a bare hour already gone today is this afternoon's", "15:00-16:00 Meeting", plan("meeting at 3", now = "14:00"))
        assertEquals("but not one still to come this morning", "09:00-10:00 Meeting", plan("meeting at 9", now = "07:10"))
        assertEquals("night stays night", "23:00-01:00 Movie, 01:00-02:00 Sleep", plan("movie at 23, sleep at 1"))
        assertEquals("1 at night is one at night", "01:00-02:00 Sleep", plan("sleep at 1 at night"))
        assertEquals("am and pm are taken as said", "07:00-08:00 Run, 19:00-20:00 Dinner", plan("run at 7 am, dinner at 7 pm"))
        assertEquals("ranges move together", "15:00-17:00 Meeting", plan("3-5 meeting", now = "14:00"))
        assertEquals("09:00-10:00 Class", plan("09:00 class", now = "14:00"))
    }

    @Test fun todayStartsNowWhenNoHourIsSaid() {
        assertEquals("14:15-15:15 Laundry, 15:15-16:15 Reading", plan("laundry then reading", now = "14:03"))
    }

    @Test fun countsAreNotHours() {
        assertEquals("09:00-10:00 Read 20 pages", plan("at 9 read 20 pages"))
        assertEquals("10:00-11:00 Gym, 11:00-12:00 One more walk", plan("gym at 10, one more walk"))
    }

    @Test fun notAPlan() {
        assertNull("nothing to anchor it for another day", parseSpokenSchedule("gym then class", null, 5))
        assertNull(parseSpokenSchedule("   ", at("10:00"), 5))
    }
}
