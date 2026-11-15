package app.jarvis

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Behind the bell: what's waiting, and a quick way to add one by hand. */
class RemindersActivity : Activity() {
    private lateinit var store: Store
    private var input: EditText? = null
    private val refresh = { render() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = Store(this)
        paintBars()
        render()
    }

    override fun onStart() {
        super.onStart()
        Reminders.sync(this)
        Live.tasks.add(refresh)
    }

    override fun onStop() {
        Live.tasks.remove(refresh)
        super.onStop()
    }

    private fun render() {
        val typed = input?.text?.toString().orEmpty()
        val now = LocalDateTime.now()
        val pending = pendingReminders(store.reminders(), now)
        val v = column()
        v.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(heading("Reminders", 24f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Bell(context, pending.size), LinearLayout.LayoutParams(dp(44), dp(40)))
        })
        v.addView(mono("PENDING ${pending.size}  //  TASK REMINDERS RING 1 HOUR BEFORE", 11f, theme().muted).apply { setPadding(0, dp(4), 0, dp(8)) })
        v.addView(Barcode(this, faded(theme().accent, 130)), LinearLayout.LayoutParams(-1, dp(9)).apply { bottomMargin = dp(16) })

        if (cyber()) v.addView(tag("New"), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val field = field("What should I remind you of?").apply {
            setSingleLine()
            setText(typed)
            setSelection(typed.length)
        }
        input = field
        v.addView(field)
        v.addView(LinearLayout(this).apply {
            fun cell(first: Boolean = false) = LinearLayout.LayoutParams(0, -2, 1f).apply { if (!first) leftMargin = dp(6) }
            addView(chip("+10 min") { addIn(10) }, cell(true))
            addView(chip("+30 min") { addIn(30) }, cell())
            addView(chip("+1 h") { addIn(60) }, cell())
            addView(chip("Pick a time") { pickMoment() }, cell())
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(20)) })

        if (cyber()) v.addView(tag("Pending"), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        if (pending.isEmpty()) v.addView(mono("No pending reminders. Tell Jarvis \"remind me of this at 21:15\" or long-press a task.", 12f, theme().muted))
        pending.forEach { v.addView(row(it, now)) }

        setContentView(ScrollView(this).apply {
            background = backdrop()
            isFillViewport = true
            addView(v)
        })
    }

    private fun row(r: Reminder, now: LocalDateTime) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = frame(edge = faded(theme().alt, 150))
        setPadding(dp(12), dp(10), dp(8), dp(10))
        val day = when (r.at.toLocalDate()) {
            now.toLocalDate() -> "TODAY"
            now.toLocalDate().plusDays(1) -> "TOMORROW"
            else -> r.at.format(DateTimeFormatter.ofPattern("d MMM EEE", Locale.UK)).caps()
        }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(mono("$day ${clock(r.at.toLocalTime())}", 12f, theme().accent))
            addView(label(r.text, 15f))
            r.event?.let { addView(mono("EVENT ${clock(it.toLocalTime())}", 10f, theme().muted)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip("Delete") { Reminders.remove(this@RemindersActivity, r.id) }, LinearLayout.LayoutParams(dp(64), -2))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun text(): String? = input?.text?.toString()?.trim()?.ifEmpty { null }.also {
        if (it == null) Toast.makeText(this, "First write what to remind you of.", Toast.LENGTH_SHORT).show()
    }

    private fun addIn(minutes: Long) {
        val t = text() ?: return
        save(t, LocalDateTime.now().plusMinutes(minutes))
    }

    private fun pickMoment() {
        val t = text() ?: return
        val today = LocalDate.now()
        DatePickerDialog(this, { _, y, m, d ->
            val day = LocalDate.of(y, m + 1, d)
            val start = LocalTime.now().plusHours(1)
            TimePickerDialog(this, { _, h, min ->
                val at = LocalDateTime.of(day, LocalTime.of(h, min))
                if (at.isAfter(LocalDateTime.now())) save(t, at)
                else Toast.makeText(this, "That moment has passed, sir.", Toast.LENGTH_SHORT).show()
            }, start.hour, 0, true).show()
        }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
            datePicker.minDate = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }

    private fun save(text: String, at: LocalDateTime) {
        input?.text?.clear()
        Reminders.add(this, text, at)
    }
}
