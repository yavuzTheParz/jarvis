package app.jarvis

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/** What the voice service and whichever screens are open share, all within one process. */
object Live {
    private val main = Handler(Looper.getMainLooper())
    /** Each line with the session it went into. */
    val lines = CopyOnWriteArraySet<(ChatLine, String) -> Unit>()
    val tasks = CopyOnWriteArraySet<() -> Unit>()

    /** Read by the ring on every frame, so it needs no redraw of the screen around it. */
    @Volatile var voice = VoiceState.IDLE

    /** Set while a screen is waiting for a spoken answer; returns false if it wasn't one. */
    @Volatile var answer: ((String) -> Boolean)? = null

    private fun onMain(run: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) run() else main.post(run)
    }

    fun line(l: ChatLine, session: String) = onMain { lines.forEach { it(l, session) } }
    fun tasksChanged() = onMain { tasks.forEach { it() } }
}

/** His conversation and his hands: the same whether he was typed to or spoken to. */
class Jarvis(context: Context) {
    private val ctx = context.applicationContext
    private val cfg = Config(ctx)
    private val store = Store(ctx)
    private val bridge = Bridge(cfg.bridgeUrl)
    private val main = Handler(Looper.getMainLooper())

    /** Uptime at which the clock app is ready for the next alarm of a batch. */
    private var alarmsFreeAt = 0L

    /** Into [session], or the one he is in now. */
    fun say(line: ChatLine, session: String? = null) {
        val into = if (session == null) store.addChat(line) else store.addChat(line, session)
        Live.line(line, into)
    }

    private fun context(): String {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE HH:mm", Locale.ENGLISH))
        val todos = store.todos().joinToString("; ") { (if (it.done) "[x] " else "[ ] ") + it.text }
        val day = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)
        val week = (0L..7L).joinToString(", ") { store.todayDate.plusDays(it).let { d -> "${d.format(day)}=$d" } }
        val later = store.agenda().entries.joinToString("; ") { (d, l) -> "$d: ${l.joinToString(", ")}" }
        val routines = store.routines().joinToString("; ") { "${it.text} (days ${it.days.sorted().joinToString(",")})" }
        val days = store.recentMoods().takeLast(7).joinToString(",")
        val date = store.today
        val habits = store.habits().joinToString("; ") { h ->
            val t = h.taper
            val m = h.measure
            val parts = partNames(h).joinToString(", ") { p -> "$p ${dose(h.parts[p]?.get(date) ?: 0.0)}" }
            when {
                t != null -> "${h.name} (taper, unit ${t.unit}): today ${dose(h.log[date] ?: 0.0)}, target at most ${dose(taperLimit(t, store.todayDate))}"
                m != null && m.split -> {
                    val book = store.expenses()
                    val month = monthTotals(book, java.time.YearMonth.from(store.todayDate))
                    "${h.name} (money book, written with the money action; spending categories: ${categoriesOf(book, false).take(15).joinToString(", ")}; income categories: ${categoriesOf(book, true).take(8).joinToString(", ")}): spent today ${dose(h.log[date] ?: 0.0)}, spent this month ${amountText(month.spent)}, earned this month ${amountText(month.earned)}"
                }
                m != null -> "${h.name} (tracker, unit ${m.unit}${if (m.parts.isEmpty()) "" else "; alongside ${m.parts.joinToString(", ")} (${m.partUnit})"}): today ${dose(h.log[date] ?: 0.0)}${if (parts.isEmpty()) "" else " ($parts)"}"
                else -> "${h.name}: this week ${weekCount(h, store.todayDate)}/${h.perWeek}"
            }
        }
        val hourly = store.schedules().entries.joinToString("; ") { (d, l) -> "$d: ${l.joinToString(", ") { "${clockText(it.start)}-${clockText(it.end)} ${it.text}" }}" }
        val pastDays = (7L downTo 1L).joinToString(", ") { store.todayDate.minusDays(it).let { d -> "${d.format(day)}=$d" } }
        val done = store.habits().joinToString("; ") { h ->
            "${h.name}: " + (7L downTo 0L).map { store.todayDate.minusDays(it).toString() }.filter { (h.log[it] ?: 0.0) > 0 }
                .joinToString(", ") { "$it=${dose(h.log.getValue(it))}" }.ifEmpty { "none" }
        }
        val reminders = remindersForContext(store.reminders(), LocalDateTime.now())
        return "Pending reminders (when they fire and text): $reminders. Hourly plan: $hourly. Habits: $habits. Habit log of the last 8 days: $done. Dates of past days: $pastDays. Date and time: $now. Dates of the lists (from today): $week. Later tasks: $later. Weekly routines (1 Monday, 7 Sunday): $routines. Now: ${store.phase(cfg)}. Today's list: $todos. Tomorrow's plan (moves to today's list at wake-up time): ${store.plan().joinToString("; ")}. Recent days (oldest first): ${days.ifEmpty { "no record" }}. Today's verdict (the user's own word): ${store.selfMood?.name ?: "not given"}. Book being read: ${store.book}. Things you may suggest: ${store.ideas.joinToString("; ")}. Recent ideas: ${store.vault.recentIdeas(15).joinToString("; ").ifEmpty { "none yet" }}"
    }

    /** Asks Claude. [unreachable] decides what happens when the bridge can't answer. */
    fun ask(message: String, shown: Boolean, onReply: (String, List<Action>) -> Unit = { _, _ -> }, unreachable: () -> Unit) {
        // The answer belongs where the question was asked, even if another session is open by then.
        val session = store.activeChat().id
        if (shown) say(ChatLine("me", null, message), session)
        val about = context()
        thread {
            val r = runCatching { bridge.syncJournal(store); bridge.chat(message, about, session) }.getOrNull()
            main.post {
                if (r == null) return@post unreachable()
                val failures = r.actions.mapNotNull(::perform)
                // Claude writes its reply before anything has run; a failure outranks what it claimed.
                val line = if (failures.isEmpty()) ChatLine("jarvis", r.tone, r.text) else ChatLine("jarvis", "caution", failures.joinToString(" "))
                say(line, session)
                onReply(line.text, r.actions)
            }
        }
    }

    /** Without Claude only the fixed sentences work. Returns what he said about it. */
    fun offline(spoken: String): Pair<String, Action?> {
        val a = parseHabit(spoken, store.habits().map { it.name }, store.todayDate) ?: parseCommand(spoken, now = LocalDateTime.now())
        val failure = a?.let(::perform)
        if (a == null) store.queueSaid(PendingSaid(LocalDateTime.now().withNano(0).toString(), spoken.trim()))
        val line = when {
            a == null -> ChatLine("jarvis", "normal", "I cannot reach your computer, sir. I have kept what you said and will see to it once the connection is back. To save it as an idea or a note right away, start with \"idea:\" or \"note:\".")
            failure != null -> ChatLine("jarvis", "caution", failure)
            else -> ChatLine("jarvis", "positive", confirmation(a))
        }
        say(line)
        return line.text to a
    }

    /** Does one thing Jarvis was asked to do; returns what to tell the user if it couldn't be done. */
    fun perform(a: Action): String? = when (a) {
        is Action.AddTodo -> {
            val date = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: store.todayDate
            if (store.addOn(date, a.text)) { Live.tasksChanged(); null } else "$date is in the past, sir; I did not add it."
        }
        is Action.EditTodo -> withTask(a.text) { store.rename(it, a.newText); null }
        is Action.MoveTodo -> withTask(a.text) { t ->
            val date = runCatching { LocalDate.parse(a.date) }.getOrNull()
            if (date != null && store.move(t, date)) null else "${a.date} is not a valid date, sir; I did not move it."
        }
        is Action.AddRoutine -> {
            store.saveRoutines(store.routines().filter { normalize(it.text) != normalize(a.text) } + Routine(a.text, a.days))
            Live.tasksChanged()
            null
        }
        is Action.LogHabit -> withHabit(a.text) {
            val day = habitDay(a.date, store.todayDate)
            val m = it.measure
            when {
                day == null -> "${a.date} has not come yet or is not a valid date, sir; I did not log it."
                m != null && a.amount == null && (!m.split || a.parts.isEmpty()) -> "I did not hear how much for ${it.name}, sir; I did not log it."
                m == null && a.amount != null && a.amount > 50 -> "${dose(a.amount)} looks like too much for ${it.name}, sir; I did not log it."
                else -> {
                    store.logHabit(it.name, a.amount ?: if (m != null) null else usualAmount(it, day), day.toString(), if (m != null) a.parts else emptyMap())
                    null
                }
            }
        }
        is Action.Money -> {
            val day = habitDay(a.date, store.todayDate)
            if (day == null) "${a.date} has not come yet or is not a valid date, sir; I did not write it down."
            else {
                // A category already in the book keeps its spelling.
                val c = categoriesOf(store.expenses(), a.income).firstOrNull { normalize(it) == normalize(a.category) } ?: a.category.trim()
                store.addExpense(Expense(day.toString(), a.amount, c, a.note.trim(), a.income))
                Live.tasksChanged()
                null
            }
        }
        is Action.AddMeasure -> {
            store.saveHabits(store.habits().filter { normalize(it.name) != normalize(a.text) } + Habit(a.text, null, null, measure = Measure(a.unit, a.parts, a.partUnit, a.split)))
            Live.tasksChanged()
            null
        }
        is Action.AddHabit -> {
            store.saveHabits(store.habits().filter { normalize(it.name) != normalize(a.text) } + Habit(a.text, a.perWeek, null))
            Live.tasksChanged()
            null
        }
        is Action.AddTaper -> {
            store.saveHabits(store.habits().filter { normalize(it.name) != normalize(a.text) } + Habit(a.text, null, Taper(a.start, a.step, store.today, a.unit)))
            Live.tasksChanged()
            null
        }
        is Action.RemoveHabit -> withHabit(a.text) { hit -> store.saveHabits(store.habits().filter { it.name != hit.name }); null }
        is Action.RemoveRoutine -> {
            val have = store.routines()
            val hit = matchLine(a.text, have.map { it.text })
            if (hit == null) "I could not find \"${a.text}\" in your routines, sir." else {
                store.saveRoutines(have.filter { it.text != hit })
                Live.tasksChanged()
                null
            }
        }
        is Action.AddPlan -> {
            store.addToPlan(a.text)
            Live.tasksChanged()
            null
        }
        is Action.CompleteTodo -> {
            val have = store.todos()
            val hit = matchTodo(a.text, have)
            if (hit == null) {
                "I could not find a task clearly matching \"${a.text}\", sir."
            } else {
                store.saveTodos(have.map { if (it === hit) it.copy(done = true) else it })
                Live.tasksChanged()
                null
            }
        }
        is Action.RemoveTodo -> withTask(a.text) { store.remove(it); null }
        is Action.SetAlarm -> setAlarm(a)
        is Action.AddReminder -> {
            val at = reminderTime(a.date, a.time, a.inMinutes, LocalDateTime.now())
            if (at == null) "The reminder time has passed or is unclear, sir; I did not set it." else {
                Reminders.add(ctx, a.text, at)
                null
            }
        }
        is Action.RemoveReminder -> {
            val have = pendingReminders(store.reminders(), LocalDateTime.now())
            val hit = matchLine(a.text, have.map { it.text }.distinct())
            if (hit == null) "No pending reminder clearly matches \"${a.text}\", sir."
            else {
                have.filter { it.text == hit }.forEach { Reminders.remove(ctx, it.id) }
                null
            }
        }
        is Action.LogDay -> {
            store.logJournal(JSONObject().put("did", a.text).put("via", "ses"))
            thread { runCatching { bridge.syncJournal(store) } }
            null
        }
        is Action.Capture -> {
            store.vault.capture(a, store.today)
            null
        }
        Action.Listen -> if (VoiceService.current?.dictate() != null) null else "The microphone is off, sir. You can turn it on from the home screen."
        Action.ListenPlan -> if (VoiceService.current?.dictate(plan = true) != null) null else "The microphone is off, sir. You can turn it on from the home screen."
        is Action.SetSchedule -> {
            val date = a.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: store.todayDate
            if (date.isBefore(store.todayDate)) "$date is in the past, sir; I did not plan it." else {
                store.saveSchedule(date, a.blocks)
                Live.tasksChanged()
                null
            }
        }
        Action.PlanTomorrow -> {
            ctx.startActivity(
                Intent(ctx, ChatActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ChatActivity.EXTRA_PLAN_TOMORROW, true)
                    .putExtra(ChatActivity.EXTRA_VOICE, true),
            )
            null
        }
    }

    /** What the bridge has to say that isn't an answer, like a finished feature request. */
    fun pullNews() {
        val news = runCatching { bridge.news() }.getOrNull().orEmpty()
        main.post { news.forEach { say(ChatLine("jarvis", "positive", it)) } }
        syncPlans()
    }

    /**
     * A plan told while the PC is out of reach: the phone places what it can read right away, and
     * keeps the words so Claude can look them over once the bridge answers. Returns what he said.
     */
    fun planOffline(day: LocalDate, text: String): String {
        val now = LocalDateTime.now()
        val nowMinute = if (day == store.todayDate) now.hour * 60 + now.minute else null
        val blocks = parseSpokenSchedule(text, nowMinute, cfg.times.wakeFrom)
        blocks?.forEach { store.placeOnSchedule(day, it) }
        store.queuePlan(PendingPlan(day.toString(), text, blocks?.let(::scheduleText).orEmpty()))
        Live.tasksChanged()
        val line = if (blocks == null) ChatLine(
            "jarvis", "caution",
            "I cannot reach your computer and could not put that on the clock, sir. I have kept it and will place it once the connection is back. You can also say it with times: \"gym at 9, class from 11 to 1\".",
        ) else ChatLine(
            "jarvis", "normal",
            "No connection, sir; for now I have placed it like this: ${scheduleText(blocks)}. I will look it over again once the connection is back.",
        )
        say(line)
        return line.text
    }

    /** Sends the plans told offline to Claude, oldest first. Blocking: call off the main thread. */
    fun syncPlans() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            for (p in store.pendingPlans()) {
                if (runCatching { LocalDate.parse(p.date) }.getOrNull()?.isBefore(store.todayDate) != false) {
                    store.dropPendingPlan(p)
                    continue
                }
                val placed = if (p.placed.isEmpty()) "The phone could not put this on the clock; nothing was written to the plan." else "The phone placed it on its own like this: ${p.placed}."
                val message = "While offline I told you my hourly plan for ${p.date}: \"${p.text}\". $placed " +
                    "If anything is missing or misplaced compared with what I said, fix that day's plan with set_schedule; keep any changes I made later in the hourly plan in the context. " +
                    "If everything is right, leave the plan alone. Answer in one sentence."
                val session = store.activeChat().id
            val r = runCatching { bridge.chat(message, context(), session) }.getOrNull() ?: return
                store.dropPendingPlan(p)
                main.post {
                    val failures = r.actions.mapNotNull(::perform)
                    say(if (failures.isEmpty()) ChatLine("jarvis", r.tone, r.text) else ChatLine("jarvis", "caution", failures.joinToString(" ")), session)
                }
            }
            syncSaid()
        } finally {
            syncing.set(false)
        }
    }

    /** What was said offline goes to Claude now, oldest first, as if it had just been said then. */
    private fun syncSaid() {
        for (p in store.pendingSaid()) {
            val at = runCatching { LocalDateTime.parse(p.at) }.getOrNull()
            val whenSaid = at?.format(DateTimeFormatter.ofPattern("d MMMM EEEE HH:mm", Locale.ENGLISH)) ?: p.at
            val message = "While offline, at $whenSaid, I said this and the phone could not understand it, so it kept it: \"${p.text}\". " +
                "Do now whatever I meant: capture if it is an idea or a note; add_todo if it is a task (read relative times like \"tomorrow\" from when I said it); " +
                "log_day if I was telling you about my day; set it if it is a reminder or an alarm whose time has not passed. If it is a question or chat, answer briefly now. " +
                "Say what you did in one sentence, and mention that this was said while offline."
            val session = store.activeChat().id
            val r = runCatching { bridge.chat(message, context(), session) }.getOrNull() ?: return
            store.dropSaid(p)
            main.post {
                val failures = r.actions.mapNotNull(::perform)
                say(if (failures.isEmpty()) ChatLine("jarvis", r.tone, r.text) else ChatLine("jarvis", "caution", failures.joinToString(" ")), session)
            }
        }
    }

    private fun withHabit(name: String, act: (Habit) -> String?): String? {
        val all = store.habits()
        val hit = matchLine(name, all.map { it.name }) ?: return "I could not find \"$name\" in your habits, sir."
        val failure = act(all.first { it.name == hit })
        if (failure == null) Live.tasksChanged()
        return failure
    }

    /** Finds a task by name on any day and acts on it; says so if none or several fit. */
    private fun withTask(name: String, act: (Placed) -> String?): String? {
        val all = store.allTasks()
        val hit = matchLine(name, all.map { it.text }) ?: return "No task clearly matches \"$name\", sir."
        val failure = act(all.first { it.text == hit })
        if (failure == null) Live.tasksChanged()
        return failure
    }

    private fun setAlarm(a: Action.SetAlarm): String? {
        if (alarmWouldRingToday(LocalDateTime.now(), a, cfg.times.wakeFrom)) {
            return "I did not set the alarm, sir: Android alarms have no date, %02d:%02d would ring today. Ask again this evening and I will set it for tomorrow.".format(a.hour, a.minute)
        }
        val now = SystemClock.uptimeMillis()
        val (at, free) = alarmSlot(now, alarmsFreeAt)
        alarmsFreeAt = free
        if (at <= now) return launchAlarm(a)
        // The rest of a batch: a failure can no longer change the reply, so it is said on its own.
        main.postAtTime({ launchAlarm(a)?.let { say(ChatLine("jarvis", "caution", it)) } }, at)
        return null
    }

    private fun launchAlarm(a: Action.SetAlarm): String? = try {
        // Intents that differ only in extras look like the same task to Android, which then just
        // brings the first back to front; a task of its own makes each one reach the clock app.
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
        )
        null
    } catch (_: ActivityNotFoundException) {
        "I could not find a clock app that can set alarms on this phone, sir."
    }

    private companion object {
        // Shared by every Jarvis: the voice service and the chat both poll, and a plan must go out once.
        val syncing = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
