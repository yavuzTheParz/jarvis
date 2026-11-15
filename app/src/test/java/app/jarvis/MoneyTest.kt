package app.jarvis

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {
    private val coffee = Expense("2026-11-14", 45.0, "Kafe", "Latte")
    private val bus = Expense("2026-11-14", 20.0, "Transport")

    @Test
    fun theSameScreenReadTwiceAddsNothing() {
        assertEquals(listOf(coffee, bus), newExpenses(emptyList(), listOf(coffee, bus)))
        assertEquals(emptyList<Expense>(), newExpenses(listOf(coffee, bus), listOf(bus, coffee)))
        assertEquals("spelling of the category doesn't matter", emptyList<Expense>(), newExpenses(listOf(coffee), listOf(coffee.copy(category = "KAFE"))))
    }

    @Test
    fun twoIdenticalPaymentsInOneReadingAreTwo() {
        assertEquals(listOf(coffee), newExpenses(listOf(coffee), listOf(coffee, coffee)))
        assertEquals(listOf(coffee, coffee), newExpenses(emptyList(), listOf(coffee, coffee)))
    }

    @Test
    fun theHabitIsMadeFromTheBook() {
        val h = spendingHabit(emptyList())
        assertEquals("Spending", h.name)
        val book = listOf(coffee, bus, Expense("2026-11-14", 5.0, "kafe"), Expense("2026-11-13", 10.0, ""), Expense("2026-11-13", 900.0, "Salary", income = true))
        val made = spendingFrom(h, book)
        assertEquals("income isn't spending", mapOf("2026-11-14" to 70.0, "2026-11-13" to 10.0), made.log)
        assertEquals("one category under the spelling it had first", 50.0, made.parts.getValue("Kafe").getValue("2026-11-14"), 0.0)
        assertEquals(10.0, made.parts.getValue(UNSORTED).getValue("2026-11-13"), 0.0)
        assertEquals(mapOf("2026-11-13" to 900.0), moneyByDay(book, income = true).first)
        assertEquals("an existing spending measure is used", "Money", spendingHabit(listOf(Habit("Workout", 3, null), Habit("Money", null, null, measure = Measure("$", split = true)))).name)
    }

    @Test
    fun handLoggedSpendingJoinsTheBook() {
        val h = Habit("Spending", null, null, mapOf("2026-11-14" to 100.0, "2026-11-13" to 30.0), Measure("$", split = true),
            mapOf("Food" to mapOf("2026-11-14" to 80.0)))
        val book = listOf(Expense("2026-11-14", 50.0, "Food"))
        assertEquals(listOf(Expense("2026-11-14", 30.0, "Food"), Expense("2026-11-14", 20.0, UNSORTED), Expense("2026-11-13", 30.0, UNSORTED)), unbooked(h, book))
        assertEquals(emptyList<Expense>(), unbooked(spendingFrom(h, book), book))
    }

    @Test
    fun categoriesMostUsedFirstThenDefaults() {
        val book = listOf(Expense("a", 1.0, "Bokutosan"), Expense("b", 1.0, "Bokutosan"), Expense("c", 1.0, "food"), Expense("d", 1.0, UNSORTED))
        assertEquals(listOf("Bokutosan", "food", "Beverages"), categoriesOf(book, false).take(3))
        assertEquals(INCOME_DEFAULTS, categoriesOf(book, true))
        val month = listOf(coffee, bus, Expense("2026-11-02", 900.0, "Salary", income = true), Expense("2026-10-31", 1.0, "x"))
        assertEquals(MonthTotals(65.0, 900.0), monthTotals(month, java.time.YearMonth.of(2026, 11)))
        assertEquals(835.0, monthTotals(month, java.time.YearMonth.of(2026, 11)).balance, 0.0)
    }

    @Test
    fun spokenAmountsWithoutClaude() {
        assertEquals(Action.LogHabit("Spending", 245.5), parseHabit("spent 245.50 at the supermarket on spending", listOf("Workout", "Spending")))
        assertEquals(Action.LogHabit("Calories", 2000.0), parseHabit("had 2000 calories today", listOf("Calories")))
    }

    // As the app's accessibility labels came off the phone on 2026-11-14.
    private val list = listOf(
        "Menu", "Money Tracker", "2026\nNov", "Expenses\n29,307\nIncome\n43,400\nBalance\n14,093",
        "Nov 30   Monday\nIncome: 27,000", "burs\n27,000",
        "Nov 14   Saturday\nExpenses: 970\nIncome: 800", "protein bar\n-170", "bebisim\n800", "Food\n-800",
        "Nov 12   Thursday\nExpenses: 1,430", "Food\n-600", "Alcohol\n-1,690", "Add", "Home",
    )

    @Test
    fun theListGivesDaysPaymentsAndIncome() {
        assertEquals(listOf(
            SeenRow("2026-11-30", "burs", 27000.0, true),
            SeenRow("2026-11-14", "protein bar", 170.0), SeenRow("2026-11-14", "bebisim", 800.0, true), SeenRow("2026-11-14", "Food", 800.0),
            SeenRow("2026-11-12", "Food", 600.0), SeenRow("2026-11-12", "Alcohol", 1690.0),
        ), parseMoneyList(list))
        assertEquals("no month picker, not the list", emptyList<SeenRow>(), parseMoneyList(list.drop(3)))
        assertEquals("scrolled past new year", listOf(SeenRow("2026-01-02", "Food", 10.0), SeenRow("2025-12-31", "Food", 20.0)),
            parseMoneyList(listOf("2026\nJan", "Jan 2   Friday\nExpenses: 10", "Food\n-10", "Dec 31   Wednesday\nExpenses: 20", "Food\n-20")))
    }

    @Test
    fun aPaymentsPageGivesItsCategory() {
        val page = listOf("Return", "Details", "Sports", "Type", "Expense", "Amount", "1,170", "Date", "Nov 14, 2026",
            "( Nov 14, 2026 23:47:26 Add )", "Note", "protein bar", "Wispr Flow", "Open", "Edit", "Delete")
        assertEquals(Expense("2026-11-14", 1170.0, "Sports", "protein bar"), parseMoneyDetail(page))
        assertEquals(Expense("2026-11-14", 1170.0, "Sports"), parseMoneyDetail(page.take(10) + listOf("Edit", "Delete")))
        assertEquals(Expense("2026-11-14", 1170.0, "Sports", "protein bar", income = true), parseMoneyDetail(page.map { if (it == "Expense") "Income" else it }))
        assertEquals(null, parseMoneyDetail(page.map { if (it == "Expense") "Transfer" else it }))
    }

    @Test
    fun rowsTakeKnownCategoriesAndLearnedNotes() {
        val have = listOf(Expense("2026-11-01", 50.0, "Sports", "protein bar"), Expense("2026-11-02", 10.0, "Food"))
        val rows = rowsAsExpenses(parseMoneyList(list).filter { !it.income }, have)
        assertEquals(Expense("2026-11-14", 170.0, "Sports", "protein bar"), rows[0])
        assertEquals(Expense("2026-11-14", 800.0, "Food"), rows[1])
        assertEquals(Expense("2026-11-12", 1690.0, UNSORTED, "Alcohol"), rows[3])
        // Its page then puts it right, as the same payment rather than a second one.
        val page = Expense("2026-11-12", 1690.0, "Alcohol")
        assertEquals(rows[3], misfiled(rows, page))
        assertEquals(emptyList<Expense>(), newExpenses(rows, listOf(page)))
        assertEquals("income with the same label is another entry", 1, newExpenses(rows, listOf(page.copy(income = true))).size)
    }
}
