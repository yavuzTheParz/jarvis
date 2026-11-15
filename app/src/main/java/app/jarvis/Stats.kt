package app.jarvis

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** What a habit's long-press page shows: its log as bars over a span, and the numbers under them. No Android here. */

/** How far back the chart looks; past a month a bar is a week, past a quarter a month. */
enum class Span(val label: String) { WEEK("7 DAYS"), MONTH("30 DAYS"), QUARTER("3 MONTHS"), YEAR("1 YEAR") }

/**
 * How a stretch of days becomes one number: a sum (days done, money spent), a mean over every day (coffee: a day
 * without any counts), or a mean over the days that were written down (calories: a day left empty wasn't zero).
 */
enum class Agg { SUM, MEAN_ALL, MEAN_LOGGED }

fun aggOf(h: Habit): Agg = when {
    h.taper != null -> Agg.MEAN_ALL
    h.measure != null && !h.measure.split -> Agg.MEAN_LOGGED
    else -> Agg.SUM
}

/** One bar: [from]..[to] inclusive. [limit] is the taper's limit at its end, if there is one. */
data class Bucket(val label: String, val from: LocalDate, val to: LocalDate, val value: Double, val limit: Double? = null, val current: Boolean = false)

private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

fun monthName(m: Int) = MONTHS[m - 1]

/** A weekly habit counts days done; anything else, what was logged. */
private fun dayValue(h: Habit, d: LocalDate): Double? {
    val v = h.log[d.toString()] ?: return null
    return if (h.measure == null && h.taper == null) (if (v > 0) 1.0 else 0.0) else v
}

fun aggregate(values: List<Double?>, agg: Agg): Double = when (agg) {
    Agg.SUM -> values.sumOf { it ?: 0.0 }
    Agg.MEAN_ALL -> if (values.isEmpty()) 0.0 else values.sumOf { it ?: 0.0 } / values.size
    Agg.MEAN_LOGGED -> values.filterNotNull().let { if (it.isEmpty()) 0.0 else it.average() }
}

/** The span's days, oldest first, ending today. A tracking habit's first day cuts nothing: earlier imports count too. */
fun spanDays(span: Span, today: LocalDate): Pair<LocalDate, LocalDate> = when (span) {
    Span.WEEK -> today.minusDays(6) to today
    Span.MONTH -> today.minusDays(29) to today
    Span.QUARTER -> today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(12) to today
    Span.YEAR -> YearMonth.from(today).minusMonths(11).atDay(1) to today
}

fun buckets(h: Habit, span: Span, today: LocalDate): List<Bucket> {
    val agg = aggOf(h)
    val (start, _) = spanDays(span, today)
    fun bucket(label: String, from: LocalDate, to: LocalDate): Bucket {
        val end = minOf(to, today)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
        return Bucket(label, from, to, aggregate(days.map { dayValue(h, it) }, agg), h.taper?.let { taperLimit(it, end) }, !today.isBefore(from) && !today.isAfter(to))
    }
    return when (span) {
        Span.WEEK, Span.MONTH -> generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .map { d -> bucket(if (span == Span.WEEK) DAYS[d.dayOfWeek.value - 1] else d.dayOfMonth.toString(), d, d) }.toList()
        Span.QUARTER -> List(13) { i -> start.plusWeeks(i.toLong()).let { m -> bucket("${m.dayOfMonth}", m, m.plusDays(6)) } }
        Span.YEAR -> List(12) { i -> YearMonth.from(start).plusMonths(i.toLong()).let { m -> bucket(monthName(m.monthValue), m.atDay(1), m.atEndOfMonth()) } }
    }
}

/**
 * The numbers under the chart. [total] and [perDay] are over the span; [previous] is the same figure for the span
 * just before it, to compare against. [best] is the single highest day. [streak] counts weeks in a row that met a
 * weekly goal, this one included only once met, or days in a row within a taper's limit.
 */
data class Summary(
    val total: Double,
    val perDay: Double,
    val previous: Double,
    val loggedDays: Int,
    val days: Int,
    val best: Pair<LocalDate, Double>?,
    val streak: Int,
)

/** What [Summary.previous] is compared on: the per-day figure for means, the total for sums. */
fun headline(s: Summary, agg: Agg) = if (agg == Agg.SUM) s.total else s.perDay

fun summary(h: Habit, span: Span, today: LocalDate): Summary {
    val agg = aggOf(h)
    val (start, end) = spanDays(span, today)
    val n = ChronoUnit.DAYS.between(start, end).toInt() + 1
    fun days(from: LocalDate, count: Int) = List(count) { from.plusDays(it.toLong()) }
    val now = days(start, n)
    val vals = now.map { dayValue(h, it) }
    val before = days(start.minusDays(n.toLong()), n).map { dayValue(h, it) }
    val prev = aggregate(before, agg)
    val total = vals.sumOf { it ?: 0.0 }
    val logged = vals.count { it != null && it > 0 }
    val perDay = when (agg) {
        Agg.MEAN_LOGGED -> aggregate(vals, agg)
        else -> total / n
    }
    val best = now.zip(vals).filter { (it.second ?: 0.0) > 0 }.maxByOrNull { it.second!! }?.let { it.first to it.second!! }
    return Summary(total, perDay, prev, logged, n, best, streak(h, today))
}

fun streak(h: Habit, today: LocalDate): Int {
    val t = h.taper
    if (t != null) {
        // Days in a row within the limit, today included while it still is.
        val from = LocalDate.parse(t.from)
        var d = today
        var n = 0
        while (!d.isBefore(from) && (h.log[d.toString()] ?: 0.0) <= taperLimit(t, d) + 1e-9) { n++; d = d.minusDays(1) }
        return n
    }
    val goal = h.perWeek ?: return 0
    var n = if (weekCount(h, today) >= goal) 1 else 0
    var week = today.minusWeeks(1)
    while (weekCount(h, week) >= goal) { n++; week = week.minusWeeks(1) }
    return n
}

/** A measure's parts over the span: a sum for a split (category totals), otherwise a mean over the days each was logged. */
fun partTotals(h: Habit, span: Span, today: LocalDate): List<Pair<String, Double>> {
    val (start, end) = spanDays(span, today)
    val split = h.measure?.split == true
    return partNames(h).map { name ->
        val log = h.parts[name].orEmpty().filterKeys { k -> runCatching { LocalDate.parse(k) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
        val v = if (split) log.values.sum() else log.values.filter { it > 0 }.let { if (it.isEmpty()) 0.0 else it.average() }
        name to v
    }.filter { it.second > 0 }.let { l -> if (split) l.sortedByDescending { it.second } else l }
}

/** "+12%" against the span before; null when there's nothing to compare against. */
fun change(now: Double, before: Double): String? {
    if (before <= 0) return null
    val p = Math.round((now - before) / before * 100).toInt()
    return if (abs(p) < 1) "±0%" else (if (p > 0) "+" else "−") + abs(p) + "%"
}

/** 1234.5 as "1,235"; at most one decimal, none when it's whole or large. */
fun amountText(v: Double): String {
    val r = if (abs(v) >= 100) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
    val whole = r.toLong()
    val frac = Math.round(abs(r - whole) * 10).toInt()
    val digits = abs(whole).toString().reversed().chunked(3).joinToString(",").reversed()
    return (if (r < 0) "−" else "") + digits + if (frac > 0) ".$frac" else ""
}

/**
 * "45.50", "1,250", "1,250.75", and the European "45,50" or "1.250,75" as numbers; null when it isn't one.
 * With both marks the last one is the decimal point; a lone comma before exactly three digits groups thousands.
 */
fun parseAmount(s: String): Double? {
    val t = s.trim().replace(Regex("""^\p{Sc}+|\p{Sc}+$"""), "").removeSuffix(CURRENCY).removePrefix(CURRENCY).trim()
    val plain = when {
        ',' in t && '.' in t -> if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        Regex("""\d{1,3}(,\d{3})+""").matches(t) -> t.replace(",", "")
        ',' in t -> t.replace(',', '.')
        Regex("""\d{1,3}(\.\d{3}){2,}""").matches(t) -> t.replace(".", "")
        else -> t
    }
    return plain.toDoubleOrNull()
}

/** The first day anything was logged. */
fun loggedSince(h: Habit): LocalDate? =
    h.log.filterValues { it > 0 }.keys.minOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Days in a row with something logged, up to today; a today not yet logged doesn't break it. */
fun logStreak(h: Habit, today: LocalDate): Int {
    fun has(d: LocalDate) = (h.log[d.toString()] ?: 0.0) > 0
    var d = if (has(today)) today else today.minusDays(1)
    var n = 0
    while (has(d)) { n++; d = d.minusDays(1) }
    return n
}

/** One of a measure's parts as a habit of its own, so it can be charted and summed like the main amount. */
fun partSeries(h: Habit, part: String): Habit =
    h.copy(log = h.parts[part].orEmpty(), parts = emptyMap(), measure = h.measure?.copy(unit = h.measure.partUnit, parts = emptyList(), split = false))

/** The part that reads as protein, if the measure has one. */
fun proteinPart(h: Habit): String? = partNames(h).firstOrNull { normalize(it).contains("protein") }
