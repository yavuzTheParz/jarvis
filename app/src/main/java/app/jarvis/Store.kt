package app.jarvis

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** A task and the day it sits on. */
data class Placed(val text: String, val day: LocalDate)

data class ChatLine(val who: String, val tone: String?, val text: String)

/** A plan told offline: the day (yyyy-MM-dd), the words, and how the phone placed them ("" if it couldn't). */
data class PendingPlan(val date: String, val text: String, val placed: String)

/** Something said while the PC was out of reach that no fixed sentence matched; [at] is when, ISO local time. */
data class PendingSaid(val at: String, val text: String)

/** The money book's currency sign, from config.json ("currency"). */
var CURRENCY = "$"

/**
 * The settings in assets/config.json (your own copy, not in git); without one the defaults in
 * config.example.json are used, so a fresh clone builds and runs as it is.
 */
class Config(ctx: Context) {
    private val j = JSONObject(
        (if ("config.json" in ctx.assets.list("").orEmpty()) "config.json" else "config.example.json")
            .let { ctx.assets.open(it).bufferedReader().readText() },
    )
    private fun strings(k: String) = j.optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
    val times = Times(j.optInt("wakeFromHour", 5), j.optInt("morningEndHour", 13), j.optInt("eveningFromHour", 21))
    val whatsappMs = j.optInt("whatsappMinutes", 5) * 60_000L
    val swipesAllowed = j.optInt("shortsSwipesAllowed", 1)
    val nagMs = j.optInt("nagMinutes", 5) * 60_000L
    /** The WhatsApp chat the morning screen opens straight away (international format, digits only); empty opens WhatsApp. */
    val quickChatNumber: String = j.optString("quickChatNumber")
    val allowedPackages = strings("morningAllowedPackages").toSet()
    val allowedPrefixes = strings("morningAllowedPackagePrefixes")
    val allowedUrls = strings("morningAllowedUrls").toSet()
    /** Empty means no bridge: Jarvis works on the phone alone, with the fixed commands. */
    val bridgeUrl: String = j.optString("bridgeUrl")

    init { CURRENCY = j.optString("currency", "$") }
}

class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val wakeFrom = Config(ctx).times.wakeFrom
    val today get() = logicalDay(LocalDateTime.now(), wakeFrom)

    var morningDoneOn: String
        get() = prefs.getString("morningDoneOn", "")!!
        set(v) = prefs.edit().putString("morningDoneOn", v).apply()
    /** The day whose evening tomorrow was planned on; the key keeps its old name so an update doesn't ask again. */
    var tomorrowPlannedOn: String
        get() = prefs.getString("eveningDoneOn", "")!!
        set(v) = prefs.edit().putString("eveningDoneOn", v).apply()

    /** Until this instant morning rules are lifted: a won negotiation or an emergency. */
    var graceUntil: Long
        get() = prefs.getLong("graceUntil", 0)
        set(v) = prefs.edit().putLong("graceUntil", v).apply()
    var bargains: Int
        get() = if (prefs.getString("bargainDay", "") == today) prefs.getInt("bargains", 0) else 0
        set(v) = prefs.edit().putString("bargainDay", today).putInt("bargains", v).apply()
    var whatsappSpentMs: Long
        get() = if (prefs.getString("whatsappDay", "") == today) prefs.getLong("whatsappSpent", 0) else 0
        set(v) = prefs.edit().putString("whatsappDay", today).putLong("whatsappSpent", v).apply()
    var limitHit: Boolean
        get() = prefs.getString("limitDay", "") == today
        set(v) = prefs.edit().putString("limitDay", if (v) today else "").apply()

    /** Times today the user was stopped on Shorts and chose to keep going. */
    var shortsChosen: Int
        get() = if (prefs.getString("shortsDay", "") == today) prefs.getInt("shortsChosen", 0) else 0
        set(v) = prefs.edit().putString("shortsDay", today).putInt("shortsChosen", v).apply()

    /** Every swipe between Shorts today, warned about or not. */
    var shortsSwiped: Int
        get() = if (prefs.getString("swipedDay", "") == today) prefs.getInt("shortsSwiped", 0) else 0
        set(v) = prefs.edit().putString("swipedDay", today).putInt("shortsSwiped", v).apply()

    /** Past days' moods, oldest first; today is left out so it can be judged against them. */
    fun recentMoods(): List<DayMood> {
        val o = JSONObject(prefs.getString("moods", "{}")!!)
        return o.keys().asSequence().filter { it != today }.sorted().map { DayMood.valueOf(o.getString(it)) }.toList()
    }

    private fun recordMood(day: String, m: DayMood) {
        val o = JSONObject(prefs.getString("moods", "{}")!!).put(day, m.name)
        o.keys().asSequence().toList().sorted().dropLast(30).forEach(o::remove)
        prefs.edit().putString("moods", o.toString()).apply()
    }

    /** The user's own optional word on today, given while planning tomorrow. */
    var selfMood: DayMood?
        get() = if (prefs.getString("selfMoodDay", "") == today) prefs.getString("selfMood", null)?.let { DayMood.valueOf(it) } else null
        set(v) = prefs.edit().putString("selfMoodDay", today).putString("selfMood", v?.name).apply()

    /** Waits until this instant before she calls the user over again. */
    var eveningSnoozedUntil: Long
        get() = prefs.getLong("eveningSnoozedUntil", 0)
        set(v) = prefs.edit().putLong("eveningSnoozedUntil", v).apply()

    var askedForVault: Boolean
        get() = prefs.getBoolean("askedForVault", false)
        set(v) = prefs.edit().putBoolean("askedForVault", v).apply()

    /** Whether the phone listens for his name. The ring still works with this off. */
    var voiceOn: Boolean
        get() = prefs.getBoolean("voiceOn", true)
        set(v) = prefs.edit().putBoolean("voiceOn", v).apply()

    /** The book being read now and the user's own suggestions, editable in the app at any time. */
    var book: String
        get() = prefs.getString("book", "Dune")!!
        set(v) = prefs.edit().putString("book", v.trim()).apply()
    var lastResort: String
        get() = prefs.getString("lastResort", "You could stare at the wall, clear your head and put your thoughts in order")!!
        set(v) = prefs.edit().putString("lastResort", v.trim()).apply()
    var ideas: List<String>
        get() {
            val a = JSONArray(prefs.getString("ideas", null) ?: JSONArray(listOf("You could start working on the Babel project")).toString())
            return List(a.length()) { a.getString(it) }
        }
        set(v) = prefs.edit().putString("ideas", JSONArray(v).toString()).apply()

    fun phase(cfg: Config): Phase = phaseAt(
        LocalDateTime.now().hour,
        morningDoneOn == today,
        tomorrowPlannedOn == today || System.currentTimeMillis() < eveningSnoozedUntil,
        cfg.times,
    )

    /** Tomorrow isn't planned yet this evening, whether or not he is calling right now. */
    fun planningDue(cfg: Config): Boolean =
        phaseAt(LocalDateTime.now().hour, true, tomorrowPlannedOn == today, cfg.times) == Phase.EVENING

    /** Tasks, the hourly plan and the evening journal live in the vault's day files, shared with the PC. */
    val vault = Vault(ctx)

    private fun tasksOn(d: LocalDate): List<Todo> {
        rolloverIfNewDay()
        return vault.read(d.toString())?.let(::dayTasks).orEmpty()
    }

    private fun saveTasksOn(d: LocalDate, l: List<Todo>) = vault.update(d.toString()) { withTasks(it, l) }

    fun todos(): List<Todo> = tasksOn(todayDate)

    fun saveTodos(l: List<Todo>) = saveTasksOn(todayDate, l)

    /** Tomorrow's list; its file becomes today's at the next wake-up hour. */
    fun plan(): List<String> = tasksOn(todayDate.plusDays(1)).map { it.text }

    fun savePlan(lines: List<String>) {
        val old = tasksOn(todayDate.plusDays(1)).associate { normalize(it.text) to it.done }
        saveTasksOn(todayDate.plusDays(1), lines.map { Todo(it, old[normalize(it)] ?: false) })
    }

    fun removeFromPlan(line: String) = savePlan(plan().filter { it != line })

    /** Adds to tomorrow's list. */
    fun addToPlan(line: String) {
        addOn(todayDate.plusDays(1), line)
    }

    /** Everything being tracked; sport three times a week is there from the start. */
    fun habits(): List<Habit> {
        val a = JSONArray(prefs.getString("habits", """[{"n":"Workout","w":3}]"""))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val log = o.optJSONObject("l") ?: JSONObject()
            fun days(j: JSONObject) = j.keys().asSequence().associateWith { j.getDouble(it) }
            val parts = o.optJSONObject("pl") ?: JSONObject()
            val named = o.optJSONArray("mp") ?: JSONArray()
            Habit(
                o.getString("n"),
                if (o.has("w")) o.getInt("w") else null,
                if (o.has("ts")) Taper(o.getDouble("ts"), o.getDouble("tp"), o.getString("tf"), o.optString("tu", "dose")) else null,
                days(log),
                if (o.has("mu")) Measure(o.getString("mu"), List(named.length()) { named.getString(it) }, o.optString("mq"), o.optBoolean("ms")) else null,
                parts.keys().asSequence().associateWith { days(parts.getJSONObject(it)) },
            )
        }
    }

    fun saveHabits(l: List<Habit>) {
        val a = JSONArray()
        l.forEach { h ->
            val o = JSONObject().put("n", h.name)
            h.perWeek?.let { o.put("w", it) }
            h.taper?.let { o.put("ts", it.start).put("tp", it.step).put("tf", it.from).put("tu", it.unit) }
            h.measure?.let { o.put("mu", it.unit).put("mp", JSONArray(it.parts)).put("mq", it.partUnit).put("ms", it.split) }
            fun days(m: Map<String, Double>) = JSONObject().also { j -> m.forEach { (d, v) -> j.put(d, v) } }
            if (h.parts.isNotEmpty()) o.put("pl", JSONObject().also { j -> h.parts.forEach { (k, m) -> j.put(k, days(m)) } })
            a.put(o.put("l", days(h.log)))
        }
        prefs.edit().putString("habits", a.toString()).apply()
    }

    /**
     * Adds to [day]'s count (yyyy-MM-dd, today unless said), never below zero. A measure's [parts] are added too;
     * a split measure's amount is what its parts add up to unless it was given.
     */
    fun logHabit(name: String, amount: Double?, day: String = today, parts: Map<String, Double> = emptyMap()) {
        // Money goes into the book, which the spending habit is made from.
        if (habits().firstOrNull { it.name == name }?.measure?.split == true) {
            val l = parts.filterValues { it > 0 }.map { (c, v) -> Expense(day, v, c) }
            val rest = (amount ?: 0.0) - l.sumOf { it.amount }
            val all = if (rest >= 0.005) l + Expense(day, rest, UNSORTED) else l
            if (all.isNotEmpty()) keepBook(withIds(expenses(), all))
            return
        }
        saveHabits(habits().map { h ->
            if (h.name != name) h else {
                val main = amount ?: parts.values.sum()
                val v = ((h.log[day] ?: 0.0) + main).coerceAtLeast(0.0)
                val p = h.parts.toMutableMap()
                parts.forEach { (k, a) ->
                    val known = partNames(h).firstOrNull { normalize(it) == normalize(k) } ?: k
                    val m = p[known].orEmpty()
                    p[known] = m + (day to ((m[day] ?: 0.0) + a).coerceAtLeast(0.0))
                }
                h.copy(log = h.log + (day to v), parts = p)
            }
        })
    }

    /** Sets [day] of a measure to exactly these figures, replacing what was there. */
    fun setMeasureDay(name: String, day: String, amount: Double, parts: Map<String, Double>) {
        saveHabits(habits().map { h ->
            if (h.name != name) h else h.copy(
                log = h.log + (day to amount),
                parts = h.parts + parts.mapValues { (k, v) -> h.parts[k].orEmpty() + (day to v) },
            )
        })
    }

    /**
     * Jarvis's money book: every payment and income, entered here, told to Jarvis or read off Money Tracker.
     * The spending habit's days and categories are made from it. Entries kept before ids existed get theirs now.
     */
    fun expenses(): List<Expense> {
        val a = JSONArray(prefs.getString("expenses", "[]"))
        val l = List(a.length()) { a.getJSONObject(it).let { o ->
            Expense(o.getString("d"), o.getDouble("a"), o.getString("c"), o.optString("t"), o.optBoolean("i"), o.optLong("id"))
        } }
        if (l.none { it.id == 0L }) return l
        var next = (l.maxOf { it.id }).coerceAtLeast(1)
        return l.map { if (it.id == 0L) it.copy(id = next++) else it }.also(::saveExpenses)
    }

    private fun saveExpenses(l: List<Expense>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("d", it.date).put("a", it.amount).put("c", it.category).put("t", it.note).put("i", it.income).put("id", it.id)) }
        prefs.edit().putString("expenses", a.toString()).apply()
    }

    /** Saves the book and makes the spending habit from it again. Before the first time, what was logged by hand joins the book. */
    private fun keepBook(l: List<Expense>) {
        var book = l
        val all = habits()
        val h = spendingHabit(all)
        if (!prefs.getBoolean("bookMade", false)) {
            book = withIds(book, unbooked(h, book))
            prefs.edit().putBoolean("bookMade", true).apply()
        }
        saveExpenses(book)
        val made = spendingFrom(h, book)
        saveHabits(if (all.any { it.name == h.name }) all.map { if (it.name == h.name) made else it } else all + made)
    }

    private fun withIds(have: List<Expense>, fresh: List<Expense>): List<Expense> {
        var next = maxOf(System.currentTimeMillis(), (have.maxOfOrNull { it.id } ?: 0) + 1)
        return have + fresh.map { it.copy(id = next++) }
    }

    fun addExpense(e: Expense) = keepBook(withIds(expenses(), listOf(e)))

    fun updateExpense(e: Expense) = keepBook(expenses().map { if (it.id == e.id) e else it })

    fun deleteExpense(id: Long) = keepBook(expenses().filter { it.id != id })

    /**
     * An entry read off its own page in Money Tracker, where its category is certain: added if it's new, or moved
     * to its category if it was filed under another one. True if anything changed.
     */
    fun fileExpense(e: Expense): Boolean {
        val have = expenses()
        val wrong = misfiled(have, e) ?: return importExpenses(listOf(e)) > 0
        keepBook(have.map { if (it.id == wrong.id) it.copy(category = e.category, note = e.note) else it })
        return true
    }

    /** Adds the entries in [seen] not had before; returns how many were new. */
    fun importExpenses(seen: List<Expense>): Int {
        val have = expenses()
        val fresh = newExpenses(have, seen)
        if (fresh.isEmpty()) return 0
        keepBook(withIds(have, fresh))
        return fresh.size
    }

    /** Every hourly plan from today on, by date. Past days aren't kept. */
    fun schedules(): Map<String, List<Block>> {
        migrateToVault()
        return vault.days().filter { it >= today }
            .associateWith { d -> vault.read(d)?.let(::dayBlocks).orEmpty() }
            .filterValues { it.isNotEmpty() }.toSortedMap()
    }

    fun schedule(day: LocalDate): List<Block> {
        migrateToVault()
        return inDayOrder(vault.read(day.toString())?.let(::dayBlocks).orEmpty(), wakeFrom)
    }

    fun saveSchedule(day: LocalDate, blocks: List<Block>) = vault.update(day.toString()) { withBlocks(it, inDayOrder(blocks, wakeFrom)) }

    fun placeOnSchedule(day: LocalDate, new: Block) = saveSchedule(day, placeBlock(schedule(day), new, wakeFrom))

    /** Plans told while the PC was out of reach, oldest first, for Jarvis to look over once it answers. */
    fun pendingPlans(): List<PendingPlan> {
        val a = JSONArray(prefs.getString("pendingPlans", "[]"))
        return List(a.length()) { a.getJSONObject(it).let { o -> PendingPlan(o.getString("date"), o.getString("text"), o.optString("placed")) } }
    }

    fun queuePlan(p: PendingPlan) = savePendingPlans(pendingPlans() + p)

    fun dropPendingPlan(p: PendingPlan) = savePendingPlans(pendingPlans().filter { it != p })

    private fun savePendingPlans(l: List<PendingPlan>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("date", it.date).put("text", it.text).put("placed", it.placed)) }
        prefs.edit().putString("pendingPlans", a.toString()).apply()
    }

    /** Words said offline, oldest first, waiting for Jarvis to put them where they belong. Never dropped unread. */
    fun pendingSaid(): List<PendingSaid> {
        val a = JSONArray(prefs.getString("pendingSaid", "[]"))
        return List(a.length()) { a.getJSONObject(it).let { o -> PendingSaid(o.getString("at"), o.getString("text")) } }
    }

    fun queueSaid(p: PendingSaid) = savePendingSaid(pendingSaid() + p)

    fun dropSaid(p: PendingSaid) = savePendingSaid(pendingSaid().filter { it != p })

    private fun savePendingSaid(l: List<PendingSaid>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("at", it.at).put("text", it.text)) }
        // Committed at once: this is the only copy of what was said.
        prefs.edit().putString("pendingSaid", a.toString()).commit()
    }

    /** Where the wall clock stands in today's plan. */
    fun planNow(minute: Int): PlanNow = planAt(schedule(todayDate), minute, wakeFrom)

    /** Tasks kept for dates after tomorrow. */
    fun agenda(): Map<String, List<String>> {
        rolloverIfNewDay()
        val after = todayDate.plusDays(1).toString()
        return vault.days().filter { it > after }
            .associateWith { d -> vault.read(d)?.let(::dayTasks).orEmpty().map { it.text } }
            .filterValues { it.isNotEmpty() }.toSortedMap()
    }

    fun routines(): List<Routine> {
        val a = JSONArray(prefs.getString("routines", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val d = o.getJSONArray("d")
            Routine(o.getString("t"), List(d.length()) { d.getInt(it) }.toSet())
        }
    }

    fun saveRoutines(l: List<Routine>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.text).put("d", JSONArray(it.days.sorted()))) }
        prefs.edit().putString("routines", a.toString()).apply()
    }

    /** Every reminder not yet delivered; gone ones are removed when they go off. */
    fun reminders(): List<Reminder> {
        val a = JSONArray(prefs.getString("reminders", "[]"))
        return List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Reminder(
                o.getLong("i"),
                o.getString("t"),
                LocalDateTime.parse(o.getString("a")),
                o.optString("e").ifEmpty { null }?.let(LocalDateTime::parse),
                o.optString("k").ifEmpty { null },
            )
        }
    }

    fun saveReminders(l: List<Reminder>) {
        val a = JSONArray()
        l.forEach { r ->
            a.put(JSONObject().put("i", r.id).put("t", r.text).put("a", r.at.toString()).apply {
                r.event?.let { put("e", it.toString()) }
                r.task?.let { put("k", it) }
            })
        }
        // Committed at once: the alarm that reads this may run the moment the app goes away.
        prefs.edit().putString("reminders", a.toString()).commit()
    }

    val todayDate: LocalDate get() = LocalDate.parse(today)

    /** Files a task under its date, wherever that date's list lives. False for a day already gone. */
    fun addOn(date: LocalDate, text: String): Boolean {
        if (date.isBefore(todayDate)) return false
        val have = tasksOn(date)
        if (have.none { normalize(it.text) == normalize(text) }) saveTasksOn(date, have + Todo(text, false))
        return true
    }

    /** Every task on every day, nearest first, for finding one by name. */
    fun allTasks(): List<Placed> {
        val now = todayDate
        return todos().map { Placed(it.text, now) } + plan().map { Placed(it, now.plusDays(1)) } +
            agenda().flatMap { (d, l) -> l.map { Placed(it, LocalDate.parse(d)) } }
    }

    fun remove(t: Placed) = saveTasksOn(t.day, tasksOn(t.day).filter { it.text != t.text })

    /** Rewrites a task where it stands, keeping its place in the list and whether it's done. */
    fun rename(t: Placed, newText: String) =
        saveTasksOn(t.day, tasksOn(t.day).map { if (it.text == t.text) it.copy(text = newText) else it })

    fun move(t: Placed, to: LocalDate): Boolean {
        if (to.isBefore(todayDate)) return false
        remove(t)
        return addOn(to, t.text)
    }

    /**
     * At the first look of a new day: unfinished tasks of the days since the last one move to today
     * (marked "- [>]" where they were, so the day files stay an honest record) and today's routines join them.
     */
    private fun rolloverIfNewDay() {
        migrateToVault()
        val last = prefs.getString("todoDay", null)
        if (last == today) return
        // The day that just ended leaves its note for "Recent days" on its own; nothing has to close it.
        if (last != null) {
            val old = vault.read(last)?.let(::dayTasks).orEmpty()
            val shorts = if (prefs.getString("shortsDay", "") == last) prefs.getInt("shortsChosen", 0) else 0
            val self = if (prefs.getString("selfMoodDay", "") == last) prefs.getString("selfMood", null)?.let { DayMood.valueOf(it) } else null
            inferredMood(old.count { it.done }, old.size, shorts, self)?.let { recordMood(last, it) }
        }
        val carried = vault.days().filter { it < today && (last == null || it >= last) }.flatMap { d ->
            val (open, marked) = carryOver(vault.day(d))
            if (open.isNotEmpty()) vault.write(d, marked)
            open
        }
        val have = vault.read(today)?.let(::dayTasks).orEmpty()
        vault.update(today) { withTasks(it, newDayList(carried, have, routinesOn(routines(), todayDate))) }
        prefs.edit().putString("todoDay", today).apply()
    }

    /** Once: what the phone kept before the vault existed is written into the day files. The old copy stays in prefs. */
    private fun migrateToVault() {
        if (prefs.getBoolean("vaultMigrated", false)) return
        prefs.edit().putBoolean("vaultMigrated", true).apply()
        val day = LocalDate.parse(prefs.getString("todoDay", null) ?: today)
        fun add(d: String, l: List<Todo>) {
            if (l.isEmpty()) return
            vault.update(d) { n -> val have = dayTasks(n); withTasks(n, have + l.filter { t -> have.none { normalize(it.text) == normalize(t.text) } }) }
        }
        val t = JSONArray(prefs.getString("todos", "[]"))
        add(day.toString(), List(t.length()) { t.getJSONObject(it).let { o -> Todo(o.getString("t"), o.getBoolean("d")) } })
        val p = JSONArray(prefs.getString("plan", "[]"))
        add(day.plusDays(1).toString(), List(p.length()) { Todo(p.getString(it), false) })
        val ag = JSONObject(prefs.getString("agenda", "{}")!!)
        ag.keys().forEach { d -> ag.getJSONArray(d).let { a -> add(d, List(a.length()) { Todo(a.getString(it), false) }) } }
        val sc = JSONObject(prefs.getString("schedule", "{}")!!)
        sc.keys().forEach { d ->
            val a = sc.getJSONArray(d)
            val blocks = List(a.length()) { i -> a.getJSONObject(i).let { Block(it.getInt("s"), it.getInt("e"), it.getString("t")) } }
            if (blocks.isNotEmpty()) vault.update(d) { withBlocks(it, inDayOrder(blocks, wakeFrom)) }
        }
    }

    /** All conversations, today's included. The one from before sessions existed is kept as its own. */
    fun chatSessions(): List<ChatSession> {
        val a = JSONArray(prefs.getString("chatSessions", "[]"))
        var all = List(a.length()) { a.getJSONObject(it) }.map { o ->
            ChatSession(o.getString("id"), o.optString("day").ifEmpty { null }, o.optString("name").ifEmpty { null })
        }
        if (all.none { it.id == LEGACY_SESSION } && File(ctx.filesDir, LEGACY_CHAT).exists()) all = listOf(ChatSession(LEGACY_SESSION, null, null)) + all
        val now = withDaySession(all, today)
        if (now.size != a.length()) saveChatSessions(now)
        return sessionOrder(now)
    }

    private fun saveChatSessions(l: List<ChatSession>) {
        val a = JSONArray()
        l.forEach { s -> a.put(JSONObject().put("id", s.id).apply { s.day?.let { put("day", it) }; s.name?.let { put("name", it) } }) }
        prefs.edit().putString("chatSessions", a.toString()).apply()
    }

    /** The conversation he is in: whichever was picked today, otherwise today's own. */
    fun activeChat(): ChatSession =
        activeSession(chatSessions(), prefs.getString("chatSession", null), prefs.getString("chatSessionOn", null), today)

    fun chooseChat(id: String) = prefs.edit().putString("chatSession", id).putString("chatSessionOn", today).apply()

    /** An extra conversation, tied to no day; it becomes the one he is in. */
    fun newChat(name: String): ChatSession {
        val s = ChatSession("x-${System.currentTimeMillis()}", null, name.trim().ifEmpty { null })
        saveChatSessions(chatSessions() + s)
        chooseChat(s.id)
        return s
    }

    fun renameChat(id: String, name: String) = saveChatSessions(renameSession(chatSessions(), id, name))

    private fun chatFile(session: String) =
        if (session == LEGACY_SESSION) File(ctx.filesDir, LEGACY_CHAT) else File(File(ctx.filesDir, "chat").apply { mkdirs() }, "$session.jsonl")

    /** Writes into [session], the one he is in unless said otherwise; returns which it went to. */
    fun addChat(l: ChatLine, session: String = activeChat().id): String {
        chatFile(session).appendText(
            JSONObject().put("who", l.who).put("text", l.text).apply { if (l.tone != null) put("tone", l.tone) }.toString() + "\n",
        )
        return session
    }

    fun chatHistory(session: String = activeChat().id): List<ChatLine> =
        chatFile(session).takeIf { it.exists() }?.readLines().orEmpty().takeLast(300).map {
            val o = JSONObject(it)
            ChatLine(o.getString("who"), o.optString("tone").ifEmpty { null }, o.getString("text"))
        }

    fun logActivity(pkg: String, url: String?) {
        if (isUnlogged(pkg)) return
        append(
            "activity.jsonl",
            JSONObject().put("at", LocalDateTime.now().toString()).put("app", pkg).apply { if (url != null) put("url", url) },
        )
    }

    /** Erases what was written before an app became unlogged. */
    fun purgeUnloggedActivity() {
        val f = File(ctx.filesDir, "activity.jsonl").takeIf { it.exists() } ?: return
        val lines = f.readLines()
        val kept = withoutUnlogged(lines)
        if (kept.size != lines.size) f.writeText(kept.joinToString("") { it + "\n" })
    }

    /** Evening answers, kept for the bridge. */
    fun logJournal(entry: JSONObject) {
        append("journal.jsonl", entry.put("at", LocalDateTime.now().toString()).put("date", today))
        val text = journalText(clockText(LocalDateTime.now().let { it.hour * 60 + it.minute }), entry.optString("did"), entry.optString("creative"), entry.optString("plan"))
        vault.update(today) { withJournal(it, text) }
    }

    fun unsyncedJournal(): List<String> =
        File(ctx.filesDir, "journal.jsonl").takeIf { it.exists() }?.readLines().orEmpty().drop(prefs.getInt("journalSynced", 0))

    fun markJournalSynced(n: Int) =
        prefs.edit().putInt("journalSynced", prefs.getInt("journalSynced", 0) + n).apply()

    private fun append(name: String, o: JSONObject) = File(ctx.filesDir, name).appendText(o.toString() + "\n")

    private companion object {
        const val LEGACY_CHAT = "chat-jarvis.jsonl"
    }
}

/** Apps whose use is never written down, not even when they opened or closed. */
val UNLOGGED_PREFIXES = listOf("com.opera.")

fun isUnlogged(pkg: String) = UNLOGGED_PREFIXES.any { pkg.startsWith(it) }

private val APP_FIELD = Regex(""""app"\s*:\s*"([^"]*)"""")

fun withoutUnlogged(lines: List<String>): List<String> =
    lines.filter { line -> line.isNotBlank() && APP_FIELD.find(line)?.groupValues?.get(1)?.let(::isUnlogged) != true }

/**
 * The vault on the phone: Markdown day files, ideas and notes. With "all files" access it is the shared
 * `Jarvis` folder in the phone's storage, which Syncthing (or any sync app) can keep equal to a folder on the PC; until then it is the app's own folder, moved over once access is given.
 */
class Vault(private val ctx: Context) {
    val shared: Boolean get() = Environment.isExternalStorageManager()

    val root: File
        get() = if (shared) File(Environment.getExternalStorageDirectory(), "Jarvis").also(::adopt) else File(ctx.filesDir, "vault")

    private fun dayFile(d: String) = File(root, "days/$d.md")

    fun read(d: String): DayNote? = dayFile(d).takeIf { it.exists() }?.let { parseDay(it.readText()) }

    fun day(d: String): DayNote = read(d) ?: newDay(d)

    /** Written aside and renamed, so Syncthing never carries half a file. */
    fun write(d: String, n: DayNote) {
        val f = dayFile(d)
        f.parentFile!!.mkdirs()
        val tmp = File(f.parentFile, ".$d.md.tmp")
        tmp.writeText(renderDay(n))
        if (!tmp.renameTo(f)) {
            f.writeText(renderDay(n))
            tmp.delete()
        }
    }

    /** Writes only if something changed, so looking at an empty day never makes a file for it. */
    fun update(d: String, change: (DayNote) -> DayNote) {
        val old = day(d)
        val new = change(old)
        if (new != old) write(d, new)
    }

    /** Writes a captured idea, note or work under a name not yet taken; returns where, relative to the vault. */
    fun capture(c: Action.Capture, date: String): String {
        val (folder, name, text) = captureFile(c, date)
        val dir = File(root, folder)
        dir.mkdirs()
        var f = File(dir, "$name.md")
        var n = 2
        while (f.exists()) f = File(dir, "$name ${n++}.md")
        val tmp = File(dir, ".${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
        return f.relativeTo(root).path
    }

    /** The newest ideas' titles, newest first. */
    fun recentIdeas(n: Int): List<String> =
        File(root, "ideas").listFiles { f -> f.isFile && f.extension == "md" && !f.name.startsWith(".") }.orEmpty()
            .sortedByDescending { it.lastModified() }.take(n).map { titleOf(it.name, it.readText()).take(80) }

    fun days(): List<String> =
        File(root, "days").list().orEmpty().mapNotNull { DAY_FILE.find(it)?.groupValues?.get(1) }.sorted()

    private fun adopt(shared: File) {
        val own = File(ctx.filesDir, "vault")
        if (!own.exists()) return
        own.walkTopDown().filter { it.isFile }.forEach { f ->
            var to = File(shared, f.relativeTo(own).path)
            // Never over what the PC already put there: the phone's copy sits beside it for the user to merge.
            if (to.exists()) to = File(to.parentFile, to.nameWithoutExtension + ".phone." + to.extension)
            to.parentFile!!.mkdirs()
            f.copyTo(to, overwrite = true)
        }
        own.deleteRecursively()
    }
}
