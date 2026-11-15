package app.jarvis

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Rules with no Android dependency, so they run under plain JUnit. */

enum class Phase { MORNING, AWAKE, EVENING }

data class Times(val wakeFrom: Int, val morningEnd: Int, val eveningFrom: Int)

fun phaseAt(hour: Int, morningDone: Boolean, eveningDone: Boolean, t: Times): Phase = when {
    hour in t.wakeFrom until t.morningEnd && !morningDone -> Phase.MORNING
    // Until the next wake-up it is still the same evening, however late it gets.
    (hour >= t.eveningFrom || hour < t.wakeFrom) && !eveningDone -> Phase.EVENING
    else -> Phase.AWAKE
}

/** The day a moment belongs to: nights count toward the day before, up to the wake-up hour. */
fun logicalDay(now: LocalDateTime, wakeFrom: Int): String = now.minusHours(wakeFrom.toLong()).toLocalDate().toString()

enum class Verdict { ALLOW, KICK, KICK_LIMIT }

data class MorningPolicy(
    val alwaysAllowed: Set<String>,
    val allowedPackages: Set<String>,
    val allowedPrefixes: List<String>,
    val allowedUrls: Set<String>,
    val browsers: Set<String>,
    val whatsapp: String = "com.whatsapp",
    val whatsappLimitMs: Long,
)

/** url is the last address seen in a browser, or null while none has been read. */
fun morningVerdict(pkg: String, url: String?, whatsappSpentMs: Long, p: MorningPolicy): Verdict = when {
    pkg in p.alwaysAllowed -> Verdict.ALLOW
    pkg == p.whatsapp -> if (whatsappSpentMs >= p.whatsappLimitMs) Verdict.KICK_LIMIT else Verdict.ALLOW
    pkg in p.allowedPackages || p.allowedPrefixes.any(pkg::startsWith) -> Verdict.ALLOW
    pkg in p.browsers -> if (url != null && p.allowedUrls.any { urlKey(url).startsWith(urlKey(it)) }) Verdict.ALLOW else Verdict.KICK
    else -> Verdict.KICK
}

/** An address without scheme and www, as a browser's address bar shows it. */
fun urlKey(url: String): String = url.trim().substringAfter("://").removePrefix("www.")

private val SHORTS = Regex("""^(?:https?://)?(?:www\.|m\.)?youtube\.com/shorts/([\w-]+)""")

fun shortsId(url: String): String? = SHORTS.find(url.trim())?.groupValues?.get(1)

enum class ShortsAction { NONE, WARN, NAG }

/** Counts swipes inside one Shorts session: warns once past the allowance, then nags on an interval if the user chose to keep going. */
class ShortsTracker(
    private val swipesAllowed: Int,
    private val nagMs: Long,
    private val sessionGapMs: Long = 3 * 60_000L,
    private val onSwiped: () -> Unit = {},
) {
    private var lastId: String? = null
    private var swipes = 0
    private var lastSeen = 0L
    private var warned = false
    private var nextNag = Long.MAX_VALUE

    private fun refresh(now: Long) {
        if (lastSeen != 0L && now - lastSeen > sessionGapMs) {
            lastId = null; swipes = 0; warned = false; nextNag = Long.MAX_VALUE
        }
        lastSeen = now
    }

    private fun check(): ShortsAction {
        if (warned || swipes <= swipesAllowed) return ShortsAction.NONE
        warned = true
        return ShortsAction.WARN
    }

    /** A Shorts address was read from a browser. */
    fun onUrl(id: String, now: Long): ShortsAction {
        refresh(now)
        if (lastId != null && lastId != id) {
            swipes++
            onSwiped()
        }
        lastId = id
        return check()
    }

    /** A swipe was seen in the YouTube app, which exposes no address. */
    fun onSwipe(now: Long): ShortsAction {
        refresh(now)
        swipes++
        onSwiped()
        return check()
    }

    fun chooseToContinue(now: Long) { nextNag = now + nagMs }

    /** Called periodically while the user is on Shorts. */
    fun tick(now: Long): ShortsAction {
        lastSeen = now
        if (now < nextNag) return ShortsAction.NONE
        nextNag = now + nagMs
        return ShortsAction.NAG
    }
}

fun normalize(s: String): String = buildString {
    for (c in s) append(
        when (c) {
            'İ', 'I', 'ı' -> 'i'
            'Ş', 'ş' -> 's'
            'Ç', 'ç' -> 'c'
            'Ğ', 'ğ' -> 'g'
            'Ö', 'ö' -> 'o'
            'Ü', 'ü' -> 'u'
            else -> c.lowercaseChar()
        }
    )
}

fun matchApps(query: String, labels: List<String>): List<String> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return emptyList()
    return labels.filter { normalize(it).contains(q) }
}

private fun longestSharedRun(a: String, b: String): Int {
    var best = 0
    val row = IntArray(b.length + 1)
    for (x in a) {
        var diagonal = 0
        for (j in b.indices) {
            val above = row[j + 1]
            row[j + 1] = if (x == b[j]) diagonal + 1 else 0
            if (row[j + 1] > best) best = row[j + 1]
            diagonal = above
        }
    }
    return best
}

/**
 * Apps for the search list, best match first: the longest piece of what was typed found in the
 * name, so a slip like "kaydetci" still finds "Ses Kaydedici". Nothing typed lists them all.
 */
fun rankApps(query: String, labels: List<String>): List<String> {
    val q = normalize(query.trim())
    if (q.isEmpty()) return labels.sortedBy(::normalize)
    // Most of what was typed must appear in one piece, or "reco" would list every app with "co" in it.
    val need = if (q.length <= 2) q.length else maxOf(2, (q.length * 3 + 4) / 5)
    return labels.map { it to longestSharedRun(q, normalize(it)) }
        .filter { it.second >= need }
        .sortedWith(
            compareByDescending<Pair<String, Int>> { it.second }
                .thenByDescending { normalize(it.first).startsWith(q) }
                .thenBy { it.first.length }
                .thenBy { normalize(it.first) },
        )
        .map { it.first }
}

/** The app to open by itself: at least two letters typed and exactly one match. */
fun uniqueApp(query: String, labels: List<String>): String? {
    if (normalize(query.trim()).length < 2) return null
    return matchApps(query, labels).singleOrNull()
}

data class Todo(val text: String, val done: Boolean)

/** Open items first, finished ones sink to the bottom; order is otherwise kept. */
fun ordered(todos: List<Todo>): List<Todo> = todos.filter { !it.done } + todos.filter { it.done }

/** Next morning's list: yesterday's unfinished items, then the new plan, without repeats. */
fun rollover(old: List<Todo>, plan: List<String>): List<Todo> =
    (old.filter { !it.done }.map { it.text } + plan).distinctBy(::normalize).map { Todo(it, false) }

/** Apps no routine, limit or interruption may ever touch. */
val NEVER_BLOCKED = setOf("com.spotify.music")

enum class DayMood { PROUD, MIXED, DISAPPOINTED }

/** How the day went: tasks finished, and whether Shorts was knowingly chosen. */
fun dayMood(done: Int, total: Int, shortsChosen: Int): DayMood {
    val slacked = shortsChosen > 0 || (total > 0 && done * 2 < total)
    return when {
        slacked -> DayMood.DISAPPOINTED
        total > 0 && done == total -> DayMood.PROUD
        total > 0 -> DayMood.MIXED
        else -> DayMood.DISAPPOINTED
    }
}

/**
 * The note a finished day leaves for Jarvis's "Recent days", written when the day turns over; there is
 * no closing step. The user's own word from planning tomorrow wins, otherwise it is read from the
 * day's tasks and Shorts. A day with nothing to go on leaves no note.
 */
fun inferredMood(done: Int, total: Int, shortsChosen: Int, self: DayMood?): DayMood? = when {
    self != null -> self
    total == 0 && shortsChosen == 0 -> null
    else -> dayMood(done, total, shortsChosen)
}

/** The optional short verdict given while planning tomorrow: "it went well", "mixed", "bad". */
fun moodWord(text: String): DayMood? {
    val n = normalize(text.trim())
    return when {
        Regex("""\b(mixed|so-so|so so|okay|ok|not bad|alright|meh|average)\b""").containsMatchIn(n) -> DayMood.MIXED
        Regex("""\b(bad|awful|terrible|horrible|unproductive|rough|wasted)\b|\bnot (good|great|well)\b|\bdidn'?t go well\b""").containsMatchIn(n) -> DayMood.DISAPPOINTED
        Regex("""\b(good|great|well|amazing|awesome|productive|fantastic|excellent)\b""").containsMatchIn(n) -> DayMood.PROUD
        else -> null
    }
}

data class Scripted(val label: String, val tone: String, val reply: String)

/** Jarvis without Claude: what can be said, and what he answers. */
fun offlineChoices(phase: Phase, nudge: String): List<Scripted> {
    val common = listOf(
        Scripted("I don't feel well today", "normal", "Understood, sir. No pressure today. Rest, drink water; if it is bad, please call someone."),
    )
    return when (phase) {
        Phase.MORNING -> listOf(
            Scripted("I don't want to get up", "caution", "I understand, sir. Still, you made this rule. Please get up."),
            Scripted("I'll get up in a minute", "caution", "Your record on \"in a minute\" is not encouraging, sir."),
        ) + common
        Phase.AWAKE -> listOf(
            Scripted("What should I do?", "normal", nudge),
            Scripted("I'm bored", "normal", "Noted, sir, but that is not a plan. $nudge"),
            Scripted("I finished a task", "positive", "Logged, sir. Congratulations."),
        ) + common
        Phase.EVENING -> listOf(
            Scripted("Today went well", "positive", "Glad to hear it, sir. Say \"let's plan tomorrow\" and we will set tomorrow up the same way."),
            Scripted("Today went badly", "normal", "Understood, sir. Say \"let's plan tomorrow\" and we will put tomorrow right together."),
        ) + common
    }
}

/**
 * What to suggest instead of what the user is doing: often an open todo, otherwise the current
 * book or one of their own ideas. The last resort is only mentioned, never the main suggestion.
 */
fun suggestionLine(todos: List<String>, book: String, ideas: List<String>, lastResort: String, pick: (Int) -> Int): String {
    val others = ideas + listOfNotNull(book.trim().ifEmpty { null }?.let { "If you are sitting, you could read $it" })
    val main = when {
        todos.isNotEmpty() && (others.isEmpty() || pick(2) == 0) -> "\"${todos[pick(todos.size)]}\" is still on your list, sir."
        others.isNotEmpty() -> others[pick(others.size)].trimEnd('.') + "."
        else -> null
    }
    val tail = lastResort.trim().ifEmpty { null }?.let { "Worst case: ${it.trimEnd('.')}." }
    return listOfNotNull(main, tail).joinToString(" ").ifEmpty { "I have no suggestion, sir, but I would not suggest this either." }
}

/**
 * Something to tell the user at [at]. A reminder tied to a task or a block of the day also knows
 * when that thing happens ([event]) and which one it is ([task]).
 */
data class Reminder(val id: Long, val text: String, val at: LocalDateTime, val event: LocalDateTime? = null, val task: String? = null)

/** How long before a task or block its reminder goes off, unless chosen otherwise. */
const val TASK_LEAD_MINUTES = 60L

private val CLOCK = Regex("""^(\d{1,2})[:.](\d{2})$""")

/** "21:15", "9.05" -> the time; anything else, or an impossible hour, is null. */
fun parseClock(s: String): LocalTime? {
    val m = CLOCK.find(s.trim()) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
}

fun clock(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

/**
 * When a reminder asked for in words goes off: [inMinutes] from now, or at [time] on [date]. With no
 * date, the next time the clock shows it, so "21.15" said at 22.00 means tomorrow. Null when the
 * moment is unreadable or already gone.
 */
fun reminderTime(date: String?, time: String?, inMinutes: Int?, now: LocalDateTime): LocalDateTime? {
    if (inMinutes != null) return if (inMinutes > 0) now.plusMinutes(inMinutes.toLong()) else null
    val t = time?.let(::parseClock) ?: return null
    val d = if (date == null) null else runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    val at = LocalDateTime.of(d ?: now.toLocalDate(), t)
    return when {
        at.isAfter(now) -> at
        d == null -> at.plusDays(1)
        else -> null
    }
}

/**
 * A task's or block's reminder: [lead] minutes before it starts. If that has already passed but the
 * thing itself hasn't, right away; null once it has begun.
 */
fun leadTime(event: LocalDateTime, now: LocalDateTime, lead: Long = TASK_LEAD_MINUTES): LocalDateTime? =
    if (!event.isAfter(now)) null else event.minusMinutes(lead).let { if (it.isAfter(now)) it else now }

/** Still to come, soonest first. */
fun pendingReminders(all: List<Reminder>, now: LocalDateTime): List<Reminder> = all.filter { it.at.isAfter(now) }.sortedBy { it.at }

/** Their moment has come, or passed while the phone was off. */
fun dueReminders(all: List<Reminder>, now: LocalDateTime): List<Reminder> = all.filter { !it.at.isAfter(now) }.sortedBy { it.at }

/** What he says, and the notification shows, when one goes off. */
fun reminderLine(r: Reminder): String =
    if (r.event != null) "Sir, a reminder for ${clock(r.event.toLocalTime())}: \"${r.text}\"."
    else "Sir, you asked me to remind you: ${r.text.trimEnd('.')}."

/** The waiting ones as one line for Claude's context. */
fun remindersForContext(all: List<Reminder>, now: LocalDateTime): String = pendingReminders(all, now).joinToString("; ") { r ->
    "${r.at.toLocalDate()} ${clock(r.at.toLocalTime())} ${r.text}" + (r.event?.let { " (event ${clock(it.toLocalTime())})" } ?: "")
}.ifEmpty { "none" }

/** One foreground change from the usage log: an app came to the front (`up`) or left it. */
data class UsageEvent(val pkg: String, val at: Long, val up: Boolean)

/** The screen went off: everything in front stops counting. */
const val SCREEN_OFF = ""

/**
 * Today's screen time from the usage log, the way Digital Wellbeing counts it: the time each app
 * spent in front, clipped to the day. Ignored packages (the home screen itself) don't count.
 */
fun screenTimeMs(events: List<UsageEvent>, dayStart: Long, now: Long, ignored: Set<String> = emptySet()): Long =
    screenTimeByHour(events, dayStart, now, ignored).sum()

/** The same time, split into the 24 hours of the day, so the home screen can draw when it went. */
fun screenTimeByHour(events: List<UsageEvent>, dayStart: Long, now: Long, ignored: Set<String> = emptySet()): LongArray {
    val hours = LongArray(24)
    foregroundSpans(events, dayStart, now, ignored) { _, from, end ->
        var a = from
        while (a < end) {
            val slot = ((a - dayStart) / HOUR_MS).toInt()
            if (slot !in 0..23) return@foregroundSpans
            val b = minOf(end, dayStart + (slot + 1) * HOUR_MS)
            hours[slot] += b - a
            a = b
        }
    }
    return hours
}

/** The same time per app, the longest first: what the home screen's bars show. */
fun screenTimeByApp(events: List<UsageEvent>, dayStart: Long, now: Long, ignored: Set<String> = emptySet()): List<Pair<String, Long>> {
    val apps = HashMap<String, Long>()
    foregroundSpans(events, dayStart, now, ignored) { pkg, from, end ->
        val a = maxOf(from, dayStart)
        if (end > a) apps[pkg] = (apps[pkg] ?: 0L) + (end - a)
    }
    return apps.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value }
}

/** Each stretch an app spent in front today, cut off at [now]. */
private fun foregroundSpans(
    events: List<UsageEvent>, dayStart: Long, now: Long, ignored: Set<String>,
    span: (pkg: String, from: Long, to: Long) -> Unit,
) {
    fun add(pkg: String, from: Long, to: Long) {
        val end = minOf(to, now)
        if (from < end) span(pkg, from, end)
    }
    val since = HashMap<String, Long>()
    val seen = HashSet<String>()
    fun close(pkg: String, at: Long) {
        // Left the front without a start today: it was already open when the day began.
        val from = since.remove(pkg) ?: if (seen.add(pkg)) dayStart else return
        add(pkg, from, at)
    }
    for (e in events.sortedBy { it.at }) {
        if (e.pkg == SCREEN_OFF) {
            since.keys.toList().forEach { close(it, e.at) }
            // Nothing could have been open since midnight past a screen-off.
            seen += events.map { it.pkg }
            continue
        }
        if (e.pkg in ignored) continue
        if (e.up) {
            seen += e.pkg
            since.putIfAbsent(e.pkg, maxOf(e.at, dayStart))
        } else close(e.pkg, e.at)
    }
    // Whatever is still in front counts up to now.
    since.forEach { (pkg, from) -> add(pkg, from, now) }
}

private const val HOUR_MS = 3_600_000L

/** "2 SA 05 DK", or just minutes under an hour. */
fun screenTimeLabel(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0L) "$m DK" else "$h SA ${m.toString().padStart(2, '0')} DK"
}

/**
 * One conversation with Jarvis. A day's own has [day] set; extra ones the user opens have none.
 * [name] is what the user called it; until then it goes by its day or its number.
 */
data class ChatSession(val id: String, val day: String?, val name: String?)

/** The old single conversation, kept as a session of its own so nothing said there is lost. */
const val LEGACY_SESSION = "legacy"

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun daySessionId(day: String) = "d-$day"

fun sessionTitle(s: ChatSession, all: List<ChatSession>, today: String): String {
    s.name?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (s.id == LEGACY_SESSION) return "Earlier chat"
    val day = s.day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    if (day != null) {
        val t = LocalDate.parse(today)
        return when (day) {
            t -> "Today"
            t.minusDays(1) -> "Yesterday"
            else -> "${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}" + if (day.year != t.year) " ${day.year}" else ""
        }
    }
    val extras = all.filter { it.day == null && it.id != LEGACY_SESSION }
    return "Chat ${extras.indexOfFirst { it.id == s.id } + 1}"
}

/** Every day has its own session from the moment it starts; nothing else is made or lost. */
fun withDaySession(all: List<ChatSession>, today: String): List<ChatSession> =
    if (all.any { it.day == today }) all else all + ChatSession(daySessionId(today), today, null)

/** The strip's order, oldest left: the old conversation, the days, then the extras as they were opened. */
fun sessionOrder(all: List<ChatSession>): List<ChatSession> {
    val legacy = all.filter { it.id == LEGACY_SESSION }
    val days = all.filter { it.day != null && it.id != LEGACY_SESSION }.sortedBy { it.day }
    val extras = all.filter { it.day == null && it.id != LEGACY_SESSION }
    return legacy + days + extras
}

/**
 * Which session the conversation is in. A choice holds for the day it was made; a new day
 * starts in its own session, as if the screen had been opened fresh that morning.
 */
fun activeSession(all: List<ChatSession>, chosen: String?, chosenOn: String?, today: String): ChatSession {
    val sessions = withDaySession(all, today)
    val own = sessions.first { it.day == today }
    if (chosenOn != today) return own
    return sessions.firstOrNull { it.id == chosen } ?: own
}

fun renameSession(all: List<ChatSession>, id: String, name: String): List<ChatSession> =
    all.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { null }) else it }
