package app.jarvis

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Jarvis's money book, in place of Money Tracker: a month at a time, what went out and came in, every entry by
 * day. Entries are added, changed and deleted here; the spending habit's numbers are made from them.
 */
class MoneyActivity : Activity() {
    private lateinit var store: Store
    private var month = YearMonth.now()
    private val refresh = { render() }
    private val tr = Locale.UK

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = Store(this)
        month = s?.getString("month")?.let { runCatching { YearMonth.parse(it) }.getOrNull() } ?: YearMonth.from(store.todayDate)
        paintBars()
        render()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("month", month.toString())
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
        val book = store.expenses()
        val totals = monthTotals(book, month)
        val v = column()
        v.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Money Book", 24f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip("Stats") {
                val h = spendingHabit(store.habits())
                if (store.habits().none { it.name == h.name }) store.saveHabits(store.habits() + spendingFrom(h, book))
                startActivity(Intent(this@MoneyActivity, StatsActivity::class.java).putExtra(StatsActivity.EXTRA, h.name))
            }.apply { setPadding(dp(12), paddingTop, dp(12), paddingBottom) })
        })
        v.addView(Barcode(this, faded(theme().accent, 130)), LinearLayout.LayoutParams(-1, dp(9)).apply { setMargins(0, dp(8), 0, dp(12)) })

        // The month, with arrows either side.
        v.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mono("◀", 18f, theme().accent).apply {
                gravity = Gravity.CENTER
                contentDescription = "Previous month"
                setOnClickListener { month = month.minusMonths(1); render() }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
            addView(mono(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", tr)).caps(), 15f, theme().alt).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener { month = YearMonth.from(store.todayDate); render() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mono("▶", 18f, theme().accent).apply {
                gravity = Gravity.CENTER
                contentDescription = "Next month"
                setOnClickListener { month = month.plusMonths(1); render() }
            }, LinearLayout.LayoutParams(dp(48), dp(44)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        v.addView(LinearLayout(this).apply {
            listOf(
                Triple("SPENT", amountText(totals.spent), theme().hot),
                Triple("EARNED", amountText(totals.earned), theme().good),
                Triple("LEFT", (if (totals.balance >= 0) "+" else "") + amountText(totals.balance), if (totals.balance >= 0) theme().alt else theme().hot),
            ).forEachIndexed { i, (k, value, color) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = frame(theme().bg, edge = faded(color, 160))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    addView(mono(k, 9f, theme().muted))
                    addView(label("$value $CURRENCY", 16f, color).apply { maxLines = 1; setTypeface(typeface, Typeface.BOLD) })
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        v.addView(LinearLayout(this).apply {
            addView(chip("− Expense") { entry(null, income = false) }.apply { setTextColor(theme().hot); background = Glow(theme().hot, resources.displayMetrics.density) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(chip("+ Income") { entry(null, income = true) }.apply { setTextColor(theme().good); background = Glow(theme().good, resources.displayMetrics.density) }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val shown = book.filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month }
        if (shown.isEmpty()) v.addView(mono("Nothing this month. Add above or tell Jarvis \"I spent 24 on groceries\".", 12f, theme().muted))
        val unsorted = shown.count { it.category == UNSORTED }
        if (unsorted > 0) v.addView(mono("$unsorted ENTRIES WITHOUT A CATEGORY (\"$UNSORTED\"): TAP TO PICK ONE.", 10f, theme().hot), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        shown.groupBy { it.date }.toSortedMap(reverseOrder()).forEach { (day, l) ->
            val d = LocalDate.parse(day)
            val out = l.filter { !it.income }.sumOf { it.amount }
            val inn = l.filter { it.income }.sumOf { it.amount }
            v.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
                addView(mono(d.format(DateTimeFormatter.ofPattern("d MMMM EEEE", tr)).caps(), 11f, if (d == store.todayDate) theme().accent else theme().muted), LinearLayout.LayoutParams(0, -2, 1f))
                if (inn > 0) addView(mono("+${amountText(inn)}  ", 11f, theme().good))
                if (out > 0) addView(mono("−${amountText(out)}", 11f, theme().hot))
            })
            l.sortedByDescending { it.id }.forEach { e -> v.addView(row(e)) }
        }

        setContentView(ScrollView(this).apply {
            background = backdrop()
            isFillViewport = true
            addView(v)
        })
    }

    private fun row(e: Expense) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = frame(edge = faded(if (e.category == UNSORTED) theme().hot else theme().alt, 120))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(e.note.ifBlank { e.category }, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (e.note.isNotBlank()) addView(mono(e.category.caps(), 9f, if (e.category == UNSORTED) theme().hot else theme().muted))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(label((if (e.income) "+" else "−") + amountText(e.amount) + " " + CURRENCY, 15f, if (e.income) theme().good else theme().hot).apply { setTypeface(typeface, Typeface.BOLD) })
        setOnClickListener { entry(e, e.income) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
    }

    /** Adds a new entry ([old] null) or changes one: amount, category, note, day, and whether it came in or went out. */
    private fun entry(old: Expense?, income: Boolean) {
        var isIncome = income
        var day = old?.date?.let(LocalDate::parse) ?: if (month == YearMonth.from(store.todayDate)) store.todayDate else month.atEndOfMonth()
        var category = old?.category?.takeIf { it != UNSORTED }
        val book = store.expenses()
        cyberDialog(if (old == null) (if (income) "Add income" else "Add expense") else "Edit entry") { dialog ->
            val amount = field("Amount ($CURRENCY)").apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                old?.let { setText(dose(it.amount)) }
            }
            val note = field("Note (e.g. groceries, taxi)").apply { setSingleLine(); setText(old?.note.orEmpty()) }
            val custom = field("ya da yeni kategori").apply { setSingleLine() }
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val typeRow = LinearLayout(context)
            val dayChip = chip("") {}
            fun drawDay() { dayChip.text = day.format(DateTimeFormatter.ofPattern("d MMMM yyyy EEE", tr)) }
            fun drawGrid() {
                grid.removeAllViews()
                categoriesOf(book, isIncome).take(12).chunked(3).forEach { r ->
                    grid.addView(LinearLayout(context).apply {
                        r.forEachIndexed { i, c ->
                            val on = category != null && normalize(c) == normalize(category!!)
                            addView(chip(c) { category = c; custom.text.clear(); drawGrid() }.apply {
                                maxLines = 1
                                if (!on) { setTextColor(theme().muted); background = Glow(faded(theme().muted, 110), resources.displayMetrics.density) }
                            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(5) })
                        }
                        repeat(3 - r.size) { addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(5) }) }
                    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
                }
            }
            fun drawType() {
                typeRow.removeAllViews()
                listOf(false to "Expense", true to "Income").forEachIndexed { i, (inc, s) ->
                    typeRow.addView(chip(s) {
                        if (isIncome != inc) { isIncome = inc; category = null; drawType(); drawGrid() }
                    }.apply {
                        if (isIncome != inc) { setTextColor(theme().muted); background = Glow(faded(theme().muted, 110), resources.displayMetrics.density) }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
                }
            }
            dayChip.setOnClickListener {
                dateDialog("Pick a day", day, LocalDate.of(2000, 1, 1)) { day = it; drawDay() }
            }
            drawDay(); drawGrid(); drawType()
            if (old != null) addView(typeRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(amount, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(mono("CATEGORY", 9f, theme().muted), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            addView(grid)
            addView(custom, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(dayChip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(LinearLayout(context).apply {
                if (old != null) addView(chip("Delete") {
                    dialog.dismiss()
                    menuDialog("Delete this entry?", Choice("Delete", danger = true) { store.deleteExpense(old.id); Live.tasksChanged() })
                }.apply { setTextColor(theme().hot) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                else addView(chip("Cancel") { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
                addView(chip("Save") { save(dialog, old, amount, custom, note, isIncome, category, day) }.apply {
                    setTextColor(theme().hot)
                    background = Glow(theme().hot, resources.displayMetrics.density)
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
    }

    private fun save(dialog: Dialog, old: Expense?, amount: EditText, custom: EditText, note: EditText, income: Boolean, picked: String?, day: LocalDate) {
        val v = parseAmount(amount.text.toString())
        if (v == null || v <= 0) return toast("Enter the amount.")
        val typed = custom.text.toString().trim()
        // A typed name that's already a category keeps that category's spelling.
        val category = typed.ifEmpty { null }?.let { t -> categoriesOf(store.expenses(), income).firstOrNull { normalize(it) == normalize(t) } ?: t }
            ?: picked ?: return toast("Pick a category.")
        dialog.dismiss()
        val e = Expense(day.toString(), v, category, note.text.toString().trim(), income, old?.id ?: 0)
        if (old == null) store.addExpense(e) else store.updateExpense(e)
        month = YearMonth.from(day)
        Live.tasksChanged()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
