package app.jarvis

import java.time.LocalDate

/** Money in and out: Jarvis's own book of payments and income, and what's read off Money Tracker. No Android here. */

/** One payment, or with [income] money coming in: [date] yyyy-MM-dd, [amount] in lira, positive. [id] is given when kept. */
data class Expense(
    val date: String,
    val amount: Double,
    val category: String,
    val note: String = "",
    val income: Boolean = false,
    val id: Long = 0,
)

/** What the spending app's list shows of an entry: in or out, its day, amount, and its note, or its category when it has none. */
private fun key(e: Expense) = "${if (e.income) "+" else "-"}|${e.date}|${Math.round(e.amount * 100)}|${normalize(e.note.ifBlank { e.category })}"

/**
 * The entries in [seen] that aren't in [have] yet. The same screen is read again and again while scrolling, so an
 * entry seen twice is one entry, unless it shows up twice in one reading: two identical coffees on the same day
 * are two, and only the ones beyond what's kept count as new.
 */
fun newExpenses(have: List<Expense>, seen: List<Expense>): List<Expense> {
    val kept = have.groupingBy(::key).eachCount()
    return seen.groupBy(::key).flatMap { (k, l) -> l.drop(kept[k] ?: 0) }
}

/** The habit the payments show up in, made if there isn't one: money a day, split by category. */
fun spendingHabit(habits: List<Habit>): Habit =
    habits.firstOrNull { it.measure?.split == true } ?: Habit("Spending", null, null, measure = Measure(CURRENCY, split = true))

/** Days and categories of [l]'s payments (or, with [income], of what came in), as a split measure keeps them. */
fun moneyByDay(l: List<Expense>, income: Boolean = false): Pair<Map<String, Double>, Map<String, Map<String, Double>>> {
    val mine = l.filter { it.income == income }
    val log = mine.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.amount } }
    // A category under two spellings is one category, under the spelling it had first.
    val names = mutableMapOf<String, String>()
    val parts = mine.groupBy { names.getOrPut(normalize(it.category.ifBlank { UNSORTED })) { it.category.ifBlank { UNSORTED } } }
        .mapValues { (_, v) -> v.groupBy { it.date }.mapValues { (_, d) -> d.sumOf { it.amount } } }
    return log to parts
}

/** The spending habit made from the book: its days and categories are always just the payments in it. */
fun spendingFrom(h: Habit, l: List<Expense>): Habit = moneyByDay(l).let { (log, parts) -> h.copy(log = log, parts = parts) }

/**
 * What was logged on the spending habit by hand before the book existed, as entries, so nothing is lost when the
 * habit starts being made from the book: whatever a day or category holds beyond the book's payments.
 */
fun unbooked(h: Habit, l: List<Expense>): List<Expense> {
    val (log, parts) = moneyByDay(l)
    val out = mutableListOf<Expense>()
    for ((day, total) in h.log) {
        var left = total - (log[day] ?: 0.0)
        if (left < 0.005) continue
        for ((p, days) in h.parts) {
            val extra = (days[day] ?: 0.0) - (parts.entries.firstOrNull { normalize(it.key) == normalize(p) }?.value?.get(day) ?: 0.0)
            if (extra >= 0.005 && left >= 0.005) {
                val v = minOf(extra, left)
                out += Expense(day, v, p)
                left -= v
            }
        }
        if (left >= 0.005) out += Expense(day, left, UNSORTED)
    }
    return out
}

/** Categories by how often they were used, most first; [defaults] fill in until there are enough. */
fun categoriesOf(l: List<Expense>, income: Boolean, defaults: List<String> = if (income) INCOME_DEFAULTS else SPENDING_DEFAULTS): List<String> {
    val used = l.filter { it.income == income && it.category != UNSORTED }.groupingBy { it.category }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }
    return (used + defaults.filter { d -> used.none { normalize(it) == normalize(d) } }).distinctBy(::normalize)
}

val SPENDING_DEFAULTS = listOf("Food", "Beverages", "Transportation", "Home", "Health", "Shopping", "Entertainment", "Gifts")
val INCOME_DEFAULTS = listOf("Salary", "Freelance", "Gift")

/** A month of the book: what went out, what came in, and what's left. */
data class MonthTotals(val spent: Double, val earned: Double) {
    val balance get() = earned - spent
}

fun monthTotals(l: List<Expense>, month: java.time.YearMonth): MonthTotals {
    val m = l.filter { runCatching { java.time.YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month }
    return MonthTotals(m.filter { !it.income }.sumOf { it.amount }, m.filter { it.income }.sumOf { it.amount })
}

/** The first day anything was written in the book. */
fun firstEntry(l: List<Expense>): LocalDate? = l.minOfOrNull { it.date }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

// ---------- Optional: Money Tracker (com.freeman.moneymanager), read off its screen while you use it ----------

const val MONEY_TRACKER = "com.freeman.moneymanager"

/** A placeholder category until the payment's own page has been seen. */
const val UNSORTED = "Other"

private val EN_MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val PICKED_MONTH = Regex("""^(\d{4})\n([A-Z][a-z]{2})$""")
private val DAY_HEADER = Regex("""^([A-Z][a-z]{2}) (\d{1,2})\s+[A-Za-z]+\n""")
private val ROW = Regex("""^([^\n]+)\n(-?[\d,]+(?:\.\d+)?)$""")
private val LONG_DATE = Regex("""^([A-Z][a-z]{2}) (\d{1,2}), (\d{4})$""")

private fun enAmount(s: String) = s.replace(",", "").toDoubleOrNull()

private fun month(abbr: String) = EN_MONTHS.indexOf(abbr).takeIf { it >= 0 }?.plus(1)

/** A row of the list: the day it sits under, what it shows (a note, or the category), how much, and whether it came in. */
data class SeenRow(val date: String, val label: String, val amount: Double, val income: Boolean = false)

/**
 * The home list, from the accessibility labels in screen order: the picked month ("2026\nNov") gives the year,
 * each day header ("Nov 14   Saturday\nExpenses: 970") the day, and each row under it ("protein bar\n-170") a
 * payment ("bebisim\n800", without a minus, is income).
 */
fun parseMoneyList(labels: List<String>): List<SeenRow> {
    val picked = labels.firstNotNullOfOrNull { PICKED_MONTH.find(it) } ?: return emptyList()
    val year = picked.groupValues[1].toInt()
    val shown = month(picked.groupValues[2]) ?: return emptyList()
    var day: String? = null
    val out = mutableListOf<SeenRow>()
    for (l in labels) {
        val h = DAY_HEADER.find(l)
        if (h != null) {
            // The picker follows the scroll and shows the month at the top; the list runs back in time, so a
            // later month further down is the year before ("2026 Jan" with "Dec 31" under it).
            val m = month(h.groupValues[1])
            day = m?.let { runCatching { java.time.LocalDate.of(if (it > shown) year - 1 else year, it, h.groupValues[2].toInt()).toString() }.getOrNull() }
            continue
        }
        val r = ROW.find(l) ?: continue
        val v = enAmount(r.groupValues[2]) ?: continue
        if (day != null && v != 0.0) out += SeenRow(day, r.groupValues[1].trim(), Math.abs(v), v > 0)
    }
    return out
}

/** A payment's own page: its category over "Type", then Type, Amount, Date and an optional Note, each label before its value. */
fun parseMoneyDetail(texts: List<String>): Expense? {
    val type = texts.indexOf("Type").takeIf { it > 0 } ?: return null
    fun after(label: String) = texts.indexOf(label).takeIf { it >= 0 }?.let { texts.getOrNull(it + 1) }
    val income = when (after("Type")) { "Expense" -> false; "Income" -> true; else -> return null }
    val amount = after("Amount")?.let(::enAmount)?.takeIf { it > 0 } ?: return null
    val d = after("Date")?.let { LONG_DATE.find(it) } ?: return null
    val m = month(d.groupValues[1]) ?: return null
    val date = runCatching { java.time.LocalDate.of(d.groupValues[3].toInt(), m, d.groupValues[2].toInt()).toString() }.getOrNull() ?: return null
    val category = texts[type - 1].takeIf { it != "Details" && it != "Return" } ?: return null
    val note = texts.indexOf("Note").takeIf { it >= 0 }?.let { texts.getOrNull(it + 1) }?.takeIf { it != "Edit" }.orEmpty()
    return Expense(date, amount, category, note, income)
}

/**
 * Rows of the list as payments. A row showing a category's name is that category; a row showing a note gets the
 * category that note had before, or [UNSORTED] until the payment's own page is opened.
 */
fun rowsAsExpenses(rows: List<SeenRow>, have: List<Expense>): List<Expense> {
    fun known(income: Boolean) = have.filter { it.income == income && it.category != UNSORTED }
    val categories = listOf(false, true).associateWith { i -> known(i).map { it.category }.associateBy(::normalize) }
    val byNote = listOf(false, true).associateWith { i -> known(i).filter { it.note.isNotBlank() }.associate { normalize(it.note) to it.category } }
    return rows.map { r ->
        val c = categories.getValue(r.income)[normalize(r.label)]
        if (c != null) Expense(r.date, r.amount, c, income = r.income)
        else Expense(r.date, r.amount, byNote.getValue(r.income)[normalize(r.label)] ?: UNSORTED, r.label, r.income)
    }
}

/** The kept entry [e] stands for, if it was filed under another category; null when there's nothing to fix. */
fun misfiled(have: List<Expense>, e: Expense): Expense? = have.firstOrNull { key(it) == key(e) && it.category != e.category }
