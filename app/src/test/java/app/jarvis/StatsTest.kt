package app.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class StatsTest {
    private val today = LocalDate.of(2026, 11, 14) // a Saturday

    private fun days(vararg p: Pair<Int, Double>) = p.associate { (back, v) -> today.minusDays(back.toLong()).toString() to v }

    @Test
    fun weeklyHabitBarsCountDays() {
        val sport = Habit("Workout", 3, null, days(0 to 1.0, 2 to 1.0, 9 to 2.0))
        val week = buckets(sport, Span.WEEK, today)
        assertEquals(7, week.size)
        assertEquals("SU", week.first().label)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0), week.map { it.value })
        assertEquals(true, week.last().current)
        val quarter = buckets(sport, Span.QUARTER, today)
        assertEquals(13, quarter.size)
        assertEquals("a day logged as 2 is still one day", 1.0, quarter[11].value, 0.0)
        assertEquals(2.0, quarter[12].value, 0.0)
    }

    @Test
    fun yearIsTwelveMonthsEndingThisOne() {
        val year = buckets(Habit("Workout", 3, null), Span.YEAR, today)
        assertEquals(12, year.size)
        assertEquals("DEC", year.first().label)
        assertEquals("NOV", year.last().label)
        assertEquals(LocalDate.of(2025, 12, 1), year.first().from)
    }

    @Test
    fun meansSkipEmptyDaysOnlyForMeasures() {
        val food = Habit("Food", null, null, days(0 to 2000.0, 1 to 1000.0), Measure("kcal"))
        assertEquals(1500.0, summary(food, Span.WEEK, today).perDay, 0.0)
        val coffee = Habit("Coffee", null, Taper(2.0, 0.5, "2026-11-01"), days(0 to 2.0, 1 to 1.5))
        assertEquals(0.5, summary(coffee, Span.WEEK, today).perDay, 0.0)
        val money = Habit("Spending", null, null, days(0 to 100.0, 3 to 50.0, 8 to 300.0), Measure("$", split = true))
        val s = summary(money, Span.WEEK, today)
        assertEquals(150.0, s.total, 0.0)
        assertEquals(300.0, s.previous, 0.0)
        assertEquals("−50%", change(headline(s, Agg.SUM), s.previous))
        assertEquals(today to 100.0, s.best)
    }

    @Test
    fun quarterBarsOfAMeasureAverageTheirWeek() {
        val food = Habit("Food", null, null, days(0 to 2000.0, 1 to 1000.0, 7 to 1800.0), Measure("kcal"))
        val q = buckets(food, Span.QUARTER, today)
        assertEquals(1500.0, q.last().value, 0.0)
        assertEquals(1800.0, q[11].value, 0.0)
    }

    @Test
    fun taperBarsCarryTheirLimit() {
        val coffee = Habit("Coffee", null, Taper(2.0, 0.5, "2026-11-01"), days(0 to 2.0, 1 to 1.0))
        val week = buckets(coffee, Span.WEEK, today)
        assertEquals(1.5, week.last().limit!!, 0.0)
        assertEquals("today over the limit ends the run", 0, streak(coffee, today))
        assertEquals("from the first day on, all within", 14, streak(coffee.copy(log = days(1 to 1.0)), today))
    }

    @Test
    fun weeklyStreakCountsThisWeekOnceMet() {
        val logged = (0..20).filter { it % 7 in setOf(0, 1, 2) }.associate { today.minusDays(it.toLong()).toString() to 1.0 }
        assertEquals(3, streak(Habit("Workout", 3, null, logged), today))
        assertEquals(0, streak(Habit("Workout", 3, null, days(0 to 1.0)), today))
    }

    @Test
    fun partTotalsSumCategoriesAndAverageMacros() {
        val money = Habit("Spending", null, null, measure = Measure("$", split = true),
            parts = mapOf("Transport" to days(0 to 20.0), "Market" to days(0 to 100.0, 2 to 50.0), "Old" to days(60 to 9.0)))
        assertEquals(listOf("Market" to 150.0, "Transport" to 20.0), partTotals(money, Span.WEEK, today))
        val food = Habit("Food", null, null, measure = Measure("kcal", listOf("Protein", "Fat"), "g"),
            parts = mapOf("Protein" to days(0 to 100.0, 1 to 140.0)))
        assertEquals(listOf("Protein" to 120.0), partTotals(food, Span.WEEK, today))
    }

    @Test
    fun amounts() {
        assertEquals("1,235", amountText(1234.5))
        assertEquals("45.5", amountText(45.5))
        assertEquals("0", amountText(0.0))
        assertEquals(45.5, parseAmount("45,50")!!, 0.0)
        assertEquals(45.5, parseAmount("45.5")!!, 0.0)
        assertEquals(1250.0, parseAmount("1,250")!!, 0.0)
        assertEquals(1250.75, parseAmount("$1,250.75")!!, 0.0)
        assertEquals(1250.75, parseAmount("€1.250,75")!!, 0.0)
        assertNull(parseAmount("lots"))
        assertNull(change(10.0, 0.0))
    }

    @Test
    fun howLongItHasBeenKept() {
        val food = Habit("Food", null, null, days(0 to 2000.0, 1 to 1800.0, 2 to 1900.0, 5 to 1500.0), Measure("kcal", listOf("Protein", "Fat"), "g"),
            mapOf("Protein" to days(0 to 120.0, 1 to 100.0)))
        assertEquals(today.minusDays(5), loggedSince(food))
        assertEquals(3, logStreak(food, today))
        assertEquals("today not logged yet doesn't break it", 2, logStreak(food.copy(log = days(1 to 1.0, 2 to 1.0)), today))
        assertEquals("Protein", proteinPart(food))
        val p = partSeries(food, "Protein")
        assertEquals(Agg.MEAN_LOGGED, aggOf(p))
        assertEquals(110.0, summary(p, Span.WEEK, today).perDay, 0.0)
    }
}
