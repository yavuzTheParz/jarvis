package app.jarvis

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A habit's long-press page. What leads depends on its kind: for money, what went out, what came in and where it
 * went; for food, the calories and protein had; for the rest, days done or doses. How long it has been kept up
 * sits underneath. Amounts chart as lines, several at once (spent against earned, calories against protein); days done
 * and doses as bars.
 */
class StatsActivity : Activity() {
    private lateinit var store: Store
    private lateinit var name: String
    private var span = Span.MONTH
    /** The lines switched off on the chart: [MAIN] the main amount, [INCOME] money in, otherwise a part's name. */
    private var hidden = mutableSetOf<String>()
    /** Whether [hidden] has had its first-look default yet: after that, what's switched off is the user's choice. */
    private var defaulted = false
    private val refresh = { render() }
    private val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = Store(this)
        name = intent.getStringExtra(EXTRA) ?: return finish()
        span = runCatching { Span.valueOf(s?.getString("span") ?: "") }.getOrDefault(Span.MONTH)
        s?.getStringArrayList("hidden")?.let { hidden = it.toMutableSet(); defaulted = true }
        paintBars()
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("span", span.name)
        out.putStringArrayList("hidden", ArrayList(hidden))
    }

    override fun onStart() {
        super.onStart()
        Live.tasks.add(refresh)
    }

    override fun onStop() {
        Live.tasks.remove(refresh)
        super.onStop()
    }

    private fun render() {
        val h = store.habits().firstOrNull { it.name == name } ?: return finish()
        val today = store.todayDate
        val m = h.measure
        val t = h.taper
        val book = if (m?.split == true) store.expenses() else emptyList()
        val income = if (m?.split == true) moneyByDay(book, income = true).let { (log, parts) -> h.copy(log = log, parts = parts) } else null

        val v = column()
        v.addView(heading(h.name, 24f))
        v.addView(mono(when {
            m?.split == true -> "MONEY BOOK · ${m.unit}"
            m != null -> "DAILY TRACKER · ${m.unit.caps()}" + if (m.parts.isEmpty()) "" else " + ${m.parts.joinToString(" / ").caps()}"
            t != null -> "TAPER · ${t.unit.caps()}"
            else -> "${h.perWeek} TIMES A WEEK"
        }, 11f, theme().muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        v.addView(Barcode(this, faded(theme().accent, 130)), LinearLayout.LayoutParams(-1, dp(9)).apply { bottomMargin = dp(14) })

        v.addView(chips(Span.entries.map { it.label }, Span.entries.indexOf(span)) { span = Span.entries[it]; render() }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // The headline numbers come first: they are what this page is for.
        when {
            m?.split == true -> moneyHeadline(v, h, income!!, book, today)
            m != null -> measureHeadline(v, h, m, today)
            else -> habitHeadline(v, h, today)
        }

        if (m != null) lines(v, h, m, income, today)
        else chart(v, h, t?.unit ?: "days", today)

        // Where the money went and where it came from, or what the days were made of.
        when {
            m?.split == true -> {
                shares(v, "Top spending", partTotals(h, span, today), m.unit, true)
                shares(v, "Income sources", partTotals(income!!, span, today), m.unit, true)
            }
            m != null && m.parts.isNotEmpty() -> shares(v, "Daily average", partTotals(h, span, today), m.partUnit, false)
        }

        v.addView(TextView(this), LinearLayout.LayoutParams(-1, 0, 1f))
        if (m?.split == true) v.addView(chip("Open the book") { startActivity(Intent(this, MoneyActivity::class.java)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        v.addView(chip("Remove") {
            menuDialog("Remove ${h.name}? Its whole record goes too.", Choice("Remove", danger = true) {
                store.saveHabits(store.habits().filter { it.name != h.name })
                Live.tasksChanged()
                finish()
            })
        }.apply {
            setTextColor(theme().hot); background = Glow(faded(theme().hot, 160), resources.displayMetrics.density)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        setContentView(ScrollView(this).apply {
            background = backdrop()
            isFillViewport = true
            addView(v)
        })
    }

    /** Spent, came in, what's left, and how long the book has been kept. */
    private fun moneyHeadline(v: LinearLayout, h: Habit, income: Habit, book: List<Expense>, today: LocalDate) {
        val u = h.measure!!.unit
        val out = summary(h, span, today)
        val inn = summary(income, span, today)
        val net = inn.total - out.total
        big(v, listOf(
            Triple("SPENT", "${amountText(out.total)} $u", LINE_RED),
            Triple("EARNED", "${amountText(inn.total)} $u", LINE_GREEN),
        ))
        cells(v, listOf(
            "LEFT" to "${if (net >= 0) "+" else ""}${amountText(net)} $u",
            "SPENT PER DAY" to "${amountText(out.perDay)} $u",
            "PREVIOUS ${span.label}" to (change(out.total, out.previous)?.let { "spending $it" } ?: "—"),
            "BIGGEST DAY" to (out.best?.let { "${amountText(it.second)} · ${it.first.format(fmt)}" } ?: "—"),
        ))
        val since = firstEntry(book)
        val days = book.map { it.date }.toSet()
        val (start, end) = spanDays(span, today)
        val inSpan = days.count { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { !it.isBefore(start) && !it.isAfter(end) } == true }
        kept(v, since, today, "$inSpan/${ChronoUnit.DAYS.between(start, end) + 1} days logged", "${book.size} entries")
    }

    /** The day's amount (calories) and protein on average, today's, and how long it has been logged. */
    private fun measureHeadline(v: LinearLayout, h: Habit, m: Measure, today: LocalDate) {
        val main = summary(h, span, today)
        val protein = proteinPart(h)
        val second = protein ?: m.parts.firstOrNull()
        val p = second?.let { summary(partSeries(h, it), span, today) }
        val top = mutableListOf(Triple("${m.unit.caps()} PER DAY", amountText(main.perDay) + (change(main.perDay, main.previous)?.let { "  $it" } ?: ""), if (m.parts.isEmpty()) theme().accent else LINE_RED))
        if (second != null && p != null) top += Triple("${second.caps()} PER DAY", "${amountText(p.perDay)} ${m.partUnit}", if (second == protein) LINE_GREEN else LINE_AMBER)
        big(v, top)
        val day = today.toString()
        cells(v, listOf(
            "TODAY" to listOfNotNull("${amountText(h.log[day] ?: 0.0)} ${m.unit}", second?.let { "${amountText(h.parts[it]?.get(day) ?: 0.0)}${m.partUnit} ${it.take(4).lowercase()}" }).joinToString(" · "),
            "HIGHEST" to (main.best?.let { "${amountText(it.second)} · ${it.first.format(fmt)}" } ?: "—"),
        ))
        mono("AVERAGES ONLY USE LOGGED DAYS; EMPTY DAYS DO NOT COUNT AS ZERO.", 9f, faded(theme().muted, 170)).let {
            v.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        kept(v, loggedSince(h), today, "${main.loggedDays}/${main.days} days logged", "${logStreak(h, today)} days in a row")
    }

    private fun habitHeadline(v: LinearLayout, h: Habit, today: LocalDate) {
        val sum = summary(h, span, today)
        val fmtd = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
        val t = h.taper
        val vs = change(headline(sum, aggOf(h)), sum.previous)?.let { "  $it" } ?: ""
        if (t != null) {
            big(v, listOf(Triple("PER DAY", "${amountText(sum.perDay)} ${t.unit}$vs", theme().accent)))
            cells(v, listOf(
                "TOTAL" to "${amountText(sum.total)} ${t.unit}",
                "WITHIN LIMIT" to "${sum.streak} days",
                "TODAY'S LIMIT" to "${dose(taperLimit(t, today))} ${t.unit}",
                "MOST" to (sum.best?.let { "${dose(it.second)} · ${it.first.format(fmtd)}" } ?: "—"),
            ))
        } else {
            big(v, listOf(
                Triple("DAYS DONE", "${sum.loggedDays}/${sum.days}", theme().accent),
                Triple("PER WEEK", amountText(sum.total / sum.days * 7) + vs, theme().good),
            ))
            cells(v, listOf(
                "GOAL STREAK" to "${sum.streak} weeks",
                "LAST DONE" to (h.log.filterValues { it > 0 }.keys.maxOrNull()?.let { LocalDate.parse(it).format(fmtd) } ?: "—"),
            ))
        }
        kept(v, loggedSince(h), today, null, null)
    }

    /** "112 days on record · since 25 Jul 2025", with what else is worth knowing about it. */
    private fun kept(v: LinearLayout, since: LocalDate?, today: LocalDate, a: String?, b: String?) {
        if (since == null) return
        val days = ChronoUnit.DAYS.between(since, today) + 1
        v.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = frame(theme().bg, edge = faded(theme().accent, 110))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(mono("ON RECORD", 9f, theme().muted))
            addView(label("for $days days", 18f, theme().text).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(mono(listOfNotNull("since ${since.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK))}", a, b).joinToString("  ·  ").caps(), 10f, theme().alt))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    private fun chart(v: LinearLayout, shown: Habit, unit: String, today: LocalDate) {
        val bars = buckets(shown, span, today)
        val agg = aggOf(shown)
        val readout = mono("", 12f, theme().text).apply { setTypeface(typeface, Typeface.BOLD) }
        fun show(i: Int) {
            val b = bars.getOrNull(i) ?: return
            val days = if (b.from == b.to) b.from.format(DateTimeFormatter.ofPattern("d MMMM EEE", Locale.UK)) else "${b.from.format(fmt)} – ${b.to.format(fmt)}"
            val what = when {
                shown.measure == null && shown.taper == null -> "${amountText(b.value)} days"
                b.from == b.to || agg == Agg.SUM -> "${amountText(b.value)} $unit"
                else -> "${amountText(b.value)} $unit a day"
            }
            readout.text = "${days.caps()}  //  ${what.caps()}" + (b.limit?.let { "  //  SINIR ${dose(it)}" } ?: "")
        }
        // A weekly goal only makes sense on bars that are weeks.
        val goal = shown.perWeek?.takeIf { span == Span.QUARTER }?.toDouble()
        v.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = frame(theme().bg, edge = faded(theme().alt, 150))
            setPadding(dp(10), dp(10), dp(10), dp(8))
            addView(readout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(StatChart(context, bars, goal) { show(it) }, LinearLayout.LayoutParams(-1, dp(170)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        show(bars.indexOfLast { it.current })
    }

    /**
     * A measure's lines: spent and earned for money; the main amount and each part for the rest, calories red and
     * protein green. Chips above switch lines on and off (one always stays); touching the chart reads every line there.
     */
    private fun lines(v: LinearLayout, h: Habit, m: Measure, income: Habit?, today: LocalDate) {
        val parts = partNames(h)
        val others = listOf(LINE_AMBER, LINE_BLUE, theme().accent)
        val protein = proteinPart(h)
        val all: List<Pair<String, Line>> = if (m.split) listOf(
            MAIN to Line("Spent", m.unit, LINE_RED, buckets(h, span, today), false),
            INCOME to Line("Earned", m.unit, LINE_GREEN, buckets(income!!, span, today), false),
        ) else {
            val main = MAIN to Line(if (normalize(m.unit) == "kcal") "Kalori" else h.name, m.unit, if (parts.isEmpty()) theme().accent else LINE_RED, buckets(h, span, today), true)
            var k = 0
            listOf(main) + parts.map { part ->
                part to Line(part, m.partUnit, if (part == protein) LINE_GREEN else others[k++ % others.size], buckets(partSeries(h, part), span, today), true)
            }
        }
        // At first only calories and protein (or the first part): four lines at once is a tangle.
        if (!defaulted) { defaulted = true; if (!m.split) hidden += parts.filter { it != (protein ?: parts.first()) } }
        hidden.retainAll(all.map { it.first }.toSet())
        if (hidden.size >= all.size) hidden.clear()
        val on = all.filter { it.first !in hidden }.map { it.second }

        if (all.size > 1) v.addView(LinearLayout(this).apply {
            all.forEachIndexed { i, (key, line) ->
                val off = key in hidden
                addView(chip(line.name) {
                    if (off) hidden -= key else if (hidden.size < all.size - 1) hidden += key
                    render()
                }.apply {
                    setTextColor(if (off) theme().muted else line.color)
                    background = Glow(faded(if (off) theme().muted else line.color, if (off) 90 else 190), resources.displayMetrics.density)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        val readout = mono("", 12f, theme().text).apply { setTypeface(typeface, Typeface.BOLD) }
        val mean = !m.split
        fun show(i: Int) {
            val b = on.firstOrNull()?.points?.getOrNull(i) ?: return
            val days = if (b.from == b.to) b.from.format(DateTimeFormatter.ofPattern("d MMMM EEE", Locale.UK)) else "${b.from.format(fmt)} – ${b.to.format(fmt)}"
            val text = android.text.SpannableStringBuilder(days.caps() + if (mean && b.from != b.to) "  //  PER DAY" else "")
            on.forEach { l ->
                val value = l.points.getOrNull(i)?.value ?: 0.0
                text.append("\n")
                val from = text.length
                text.append("● ${l.name.caps()}  ${if (l.gaps && value <= 0) "—" else "${amountText(value)} ${l.unit}"}")
                text.setSpan(android.text.style.ForegroundColorSpan(l.color), from, text.length, 0)
            }
            readout.text = text
        }
        v.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = frame(theme().bg, edge = faded(theme().alt, 150))
            setPadding(dp(10), dp(10), dp(10), dp(8))
            addView(readout, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(LineChart(context, on) { show(it) }, LinearLayout.LayoutParams(-1, dp(200)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        show(on.firstOrNull()?.points?.indexOfLast { it.current } ?: -1)
    }

    /** Parts as bars: categories as shares of the whole, macros as plain amounts. */
    private fun shares(v: LinearLayout, title: String, parts: List<Pair<String, Double>>, unit: String, split: Boolean) {
        if (parts.isEmpty()) return
        if (cyber()) v.addView(tag(title), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        else v.addView(heading(title, 16f), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val most = parts.maxOf { it.second }
        val whole = parts.sumOf { it.second }
        parts.take(if (split) 8 else parts.size).forEach { (p, value) ->
            v.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    addView(label(p, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(mono("${amountText(value)} $unit" + if (split) "  ${Math.round(value / whole * 100)}%" else "", 12f, theme().alt))
                })
                addView(ShareBar(context, (value / most).toFloat(), faded(theme().accent, 200)), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(4) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        if (split && parts.size > 8) v.addView(mono("+ ${parts.size - 8} MORE CATEGORIES, ${amountText(parts.drop(8).sumOf { it.second })} $unit", 10f, theme().muted), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
    }

    /** Large figures, side by side. */
    private fun big(v: LinearLayout, items: List<Triple<String, String, Int>>) {
        v.addView(LinearLayout(this).apply {
            items.forEachIndexed { i, (k, value, color) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = frame(theme().bg, edge = faded(color, 170))
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    addView(mono(k, 10f, theme().muted))
                    addView(heading(value, 22f).apply { setTextColor(color); maxLines = 1 })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    /** Smaller figures, two to a row. */
    private fun cells(v: LinearLayout, items: List<Pair<String, String>>) {
        items.chunked(2).forEach { row ->
            v.addView(LinearLayout(this).apply {
                row.forEachIndexed { i, (k, value) ->
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = frame(theme().bg, edge = faded(theme().alt, 110))
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        addView(mono(k, 9f, theme().muted))
                        addView(label(value, 15f, theme().text).apply { maxLines = 1 })
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun chips(labels: List<String>, on: Int, pick: (Int) -> Unit) = LinearLayout(this).apply {
        labels.forEachIndexed { i, s ->
            addView(chip(s) { pick(i) }.apply {
                if (i != on) { setTextColor(theme().muted); background = Glow(faded(theme().muted, 120), resources.displayMetrics.density) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
    }

    companion object {
        const val EXTRA = "habit"
        const val INCOME = "\u0000gelir"
        const val MAIN = "\u0000ana"
    }
}
