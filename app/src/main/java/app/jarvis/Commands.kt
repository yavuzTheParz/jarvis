package app.jarvis

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** What Jarvis may do on the phone, and how spoken words become that. No Android here: runs under plain JUnit. */

sealed class Action {
    /** On [date] (yyyy-MM-dd); null means today. */
    data class AddTodo(val text: String, val date: String? = null) : Action()
    data class EditTodo(val text: String, val newText: String) : Action()
    data class MoveTodo(val text: String, val date: String) : Action()
    /** Comes back on these weekdays (1 Monday .. 7 Sunday) until removed. */
    data class AddRoutine(val text: String, val days: Set<Int>) : Action()
    data class RemoveRoutine(val text: String) : Action()
    /** Counts toward a habit on [date] (yyyy-MM-dd; null means today), never a day still to come. No amount means the usual: once, or that day's dose of something being cut down. */
    data class LogHabit(val text: String, val amount: Double? = null, val date: String? = null, val parts: Map<String, Double> = emptyMap()) : Action()
    /** Starts tracking an amount a day in [unit], with [parts] alongside (macros), or split into them when [split] (spending by category). */
    data class AddMeasure(val text: String, val unit: String, val parts: List<String> = emptyList(), val partUnit: String = "", val split: Boolean = false) : Action()
    data class AddHabit(val text: String, val perWeek: Int) : Action()
    /** Something to cut down: [start] of [unit] a day, [step] fewer each week. */
    data class AddTaper(val text: String, val start: Double, val step: Double, val unit: String = "dose") : Action()
    data class RemoveHabit(val text: String) : Action()
    /** A payment, or with [income] money coming in, into the money book; [date] yyyy-MM-dd, null is today. */
    data class Money(val amount: Double, val category: String, val note: String = "", val income: Boolean = false, val date: String? = null) : Action()
    data class AddPlan(val text: String) : Action()
    data class CompleteTodo(val text: String) : Action()
    data class RemoveTodo(val text: String) : Action()
    data class SetAlarm(val hour: Int, val minute: Int, val tomorrow: Boolean) : Action()
    data class LogDay(val text: String) : Action()
    /** Something kept in the notes folder: an "idea" or a "note". */
    data class Capture(val kind: String, val text: String, val title: String? = null) : Action()
    /** Goes off [inMinutes] from now, or at [time] (HH:mm) on [date] (yyyy-MM-dd; null is the next time the clock shows it). */
    data class AddReminder(val text: String, val date: String? = null, val time: String? = null, val inMinutes: Int? = null) : Action()
    data class RemoveReminder(val text: String) : Action()
    data object Listen : Action()
    /** The long recording, but what's told is the day's plan rather than the journal. */
    data object ListenPlan : Action()
    /** The evening's planning of tomorrow, by voice; there is no separate closing of the day. */
    data object PlanTomorrow : Action()
    /** Replaces the hourly plan of [date] (yyyy-MM-dd); null means today, no blocks clears it. */
    data class SetSchedule(val blocks: List<Block>, val date: String? = null) : Action()
}

// Everything below is matched against normalize()d text (lower case, same length as what was said),
// so a range found there cuts the same words out of the original sentence.

private val WAKE_PREFIX = Regex("""^\s*(?:(?:hey|ok|okay|hi)[\s,]+)?jarvis[\s,.:!]*""", RegexOption.IGNORE_CASE)
private val END_PHRASE = Regex("""[\s,.!]*(?:(?:ok|okay|thanks|thank you|that's it|that's all|done)[\s,]+)?jarvis[\s,.!]*$""", RegexOption.IGNORE_CASE)

private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
private const val DAY_WORDS = """today|tonight|tomorrow|the day after tomorrow|next week|(?:next\s+)?(?:$WEEKDAYS)"""
/** "for tomorrow", "on friday", "to monday's list". */
private const val DAY_PHRASE = """(?:(?:for|on|to|until|till)\s+)?($DAY_WORDS)(?:'s(?:\s+list)?)?"""

private val DONE_AFTER = Regex("""^(?:mark\s+|tick\s+off\s+|check\s+off\s+|cross\s+off\s+)?(.+?)\s+(?:as\s+)?(?:is\s+|are\s+)?(?:done|finished|complete|completed)$""")
private val DONE_BEFORE = Regex("""^(?:i\s+)?(?:(?:have|'ve|just)\s+)*(?:finished|completed|did|done)\s+(?:with\s+)?(.+)$""")
private val TICK = Regex("""^(?:tick|check|cross)\s+off\s+(.+)$""")
private val REMOVE = Regex("""^(?:please\s+)?(?:remove|delete|drop|scrap|cross\s+out)\s+(.+?)(?:\s+(?:from|off)\s+(?:the|my)\s+(?:list|to-?do list|to-?dos|tasks))?$""")
private val ADD_TODO = Regex("""^(?:please\s+)?(?:add|put|write down)\s+(?:a\s+)?(?:(?:to-?do|task)\s*[:,]?\s+)?(.+?)(?:\s+(?:to|on)\s+(?:the|my)\s+(?:list|to-?do list|to-?dos|tasks))?$""")
private val ADD_ON_DAY_LAST = Regex("""^(?:please\s+)?(?:add|put|write down)\s+(?:a\s+)?(?:(?:to-?do|task)\s*[:,]?\s+)?(.+?)\s+$DAY_PHRASE$""")
private val ADD_ON_DAY_FIRST = Regex("""^(?:please\s+)?(?:add|put|write down)\s+$DAY_PHRASE\s*[:,]?\s+(.+)$""")
private val MOVE = Regex("""^(?:please\s+)?(?:move|push|postpone|shift|put off)\s+(.+?)\s+$DAY_PHRASE$""")
private val EDIT = Regex("""^(?:please\s+)?(?:change|rename|edit)\s+(.+?)\s+to\s+(.+)$|^(?:please\s+)?replace\s+(.+?)\s+with\s+(.+)$""")
private val TASK_WORDS = Regex("""^(?:the|my|that|this)\s+|\s+(?:task|to-?do|item)$""")
/** "let's plan tomorrow", "plan for tomorrow", "plan my tomorrow". */
private val PLAN_TOMORROW = Regex("""\bplan\s+(?:for\s+|out\s+|my\s+)?tomorrow\b|\btomorrow's\s+plan\b""")

private const val NUMBER_WORDS =
    "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty"
private val SMALL = mapOf(
    "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16, "seventeen" to 17,
    "eighteen" to 18, "nineteen" to 19,
)
private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50)
private const val HOUR_WORD = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
private const val NUM = """(?:\d{1,3}|(?:twenty|thirty|forty|fifty)(?:[\s-]+(?:one|two|three|four|five|six|seven|eight|nine))?|$NUMBER_WORDS|an?)"""

/** "fifteen" -> 15, "twenty-five" -> 25, "45" -> 45, "an" -> 1. */
private fun number(s: String): Int? {
    val w = s.trim().split(Regex("""[\s-]+"""))
    w.singleOrNull()?.toIntOrNull()?.let { return it }
    if (w.size == 1) SMALL[w[0]]?.let { return it }
    val tens = TENS[w[0]] ?: return null
    return tens + (w.getOrNull(1)?.let { SMALL[it]?.takeIf { n -> n < 10 } ?: return null } ?: 0)
}

private fun hourWord(w: String): Int? = SMALL[w]?.takeIf { it in 1..12 && w != "a" && w != "an" }

/** The name he was called by, when the recognizer caught it along with the command. */
fun withoutWakeWord(spoken: String): String = spoken.replaceFirst(WAKE_PREFIX, "").trim()

/** A long dictation ends with "okay Jarvis"; that isn't part of what was told. */
fun withoutEndPhrase(spoken: String): String = spoken.replace(END_PHRASE, "").trim()

private fun taskName(s: String): String {
    var out = s.trim().trim('"', '\'', '“', '”')
    while (true) {
        val next = out.replace(Regex(TASK_WORDS.pattern, RegexOption.IGNORE_CASE), "").trim()
        if (next == out) return out
        out = next
    }
}

/** The date a spoken day means: the next one to come, so on a Monday "monday" is next week. */
fun dayNamed(spoken: String, today: LocalDate): LocalDate? {
    val n = normalize(spoken).trim().removePrefix("for ").removePrefix("on ").removePrefix("to ").removePrefix("until ").removePrefix("till ")
        .removeSuffix("'s list").removeSuffix("'s").trim()
    when (n) {
        "today", "tonight" -> return today
        "tomorrow" -> return today.plusDays(1)
        "the day after tomorrow" -> return today.plusDays(2)
        "next week" -> return today.plusDays(7)
    }
    val day = DayOfWeek.entries.firstOrNull { n.removePrefix("next ") == it.name.lowercase() } ?: return null
    var d = today.plusDays(1)
    while (d.dayOfWeek != day) d = d.plusDays(1)
    return d
}

private val ALARM_CLOCK = Regex("""(?<![\d:.])(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?|o'?clock)?(?!\d|:)""")
private val ALARM_WORD = Regex("""\b($HOUR_WORD)(?:\s+(thirty|fifteen|forty[\s-]five|o'?clock))?\s*(a\.?m\.?|p\.?m\.?)?\b""")
private val ALARM_PAST = Regex("""\b(half|quarter)\s+(past|to)\s+(\d{1,2}|$HOUR_WORD)\b""")

/** Reads an alarm time out of normalized text: "set an alarm for 7:30", "wake me up at six tomorrow", "alarm at 9 pm". */
fun parseAlarm(normalized: String): Action.SetAlarm? {
    val n = normalized.replace(Regex("""\b(an|a)\s+alarm\b"""), "alarm")
    var hour: Int
    var minute = 0
    var meridiem = ""
    val past = ALARM_PAST.find(n)
    val digits = ALARM_CLOCK.find(n)
    val words = ALARM_WORD.find(n)
    when {
        past != null -> {
            val h = past.groupValues[3].toIntOrNull() ?: hourWord(past.groupValues[3]) ?: return null
            val by = if (past.groupValues[1] == "half") 30 else 15
            if (past.groupValues[2] == "past") { hour = h; minute = by } else { hour = if (h == 1) 12 else (h + 23) % 24; minute = 60 - by }
        }
        digits != null -> {
            hour = digits.groupValues[1].toInt()
            minute = digits.groupValues[2].ifEmpty { "0" }.toInt()
            meridiem = digits.groupValues[3]
        }
        words != null -> {
            hour = hourWord(words.groupValues[1]) ?: return null
            minute = when (words.groupValues[2].replace('-', ' ')) { "thirty" -> 30; "fifteen" -> 15; "forty five" -> 45; else -> 0 }
            meridiem = words.groupValues[3]
        }
        else -> return null
    }
    val pm = meridiem.startsWith("p") || (meridiem.isEmpty() && Regex("""\b(evening|tonight|afternoon)\b""").containsMatchIn(n))
    val am = meridiem.startsWith("a")
    if (pm && hour in 1..11) hour += 12
    if (am && hour == 12) hour = 0
    if (hour == 12 && meridiem.isEmpty() && n.contains("midnight")) hour = 0
    if (hour > 23 || minute > 59) return null
    return Action.SetAlarm(hour, minute, Regex("""\btomorrow\b""").containsMatchIn(n))
}

/** "remind me to", "can you remind me", "set a reminder for", "don't let me forget": what makes it a reminder. */
private val REMIND_ASK = Regex(
    """(?:\b(?:can|could|would|will)\s+you\s+)?(?:\bplease\s+)?(?:\bremind\s+me\b(?:\s+(?:to|about|of|that))?|\bset\s+(?:a\s+|up\s+a\s+)?reminder\b(?:\s+(?:to|for|about))?|\bdon'?t\s+let\s+me\s+forget\b(?:\s+(?:to|about))?)(?:\s+please\b)?""",
)
private val REMIND_REMOVE = Regex("""^(?:please\s+)?(?:remove|delete|cancel|drop)\s+(?:the\s+|my\s+)?(?:reminder\s+(?:for|about|to)\s+(.+?)|(.+?)\s+reminder)$""")
/** "in 10 minutes", "in half an hour", "in an hour and a half", "in 1 hour 20 minutes", "in a quarter of an hour". */
private val REMIND_IN = Regex(
    """\bin\s+(?:(half\s+an|a\s+quarter\s+of\s+an)\s+hour|($NUM)(\s+and\s+a\s+half)?\s+(hours?|hrs?|minutes?|mins?)(?:\s+and\s+a\s+half)?(?:\s+(?:and\s+)?($NUM)\s+(?:minutes?|mins?))?)\b""",
)
private val REMIND_SOON = Regex("""\b(?:in\s+a\s+(?:bit|little\s+while|moment|while)|shortly|in\s+a\s+few\s+minutes)\b""")
/** "quarter to nine", "half past seven", "20 past 10", "ten to six". */
private val REMIND_PAST_TO = Regex("""\b(?:at\s+)?(half|quarter|$NUM)\s+(past|to|after|before)\s+(\d{1,2}|$HOUR_WORD)\b""")
private val REMIND_CLOCK = Regex("""(?:\b(at)\s+)?(?<![\d.:])(\d{1,2})[:.](\d{2})(?!\d)\s*(a\.?m\.?|p\.?m\.?)?""")
private val REMIND_HOUR = Regex("""(?:\b(at|by|around)\s+)?(?<![\d.:])(\d{1,2})(?![\d.:])\s*(a\.?m\.?\B|p\.?m\.?\B|am\b|pm\b|o'?clock\b|ish\b)?""")
private val REMIND_HOUR_WORD = Regex("""(?:\b(at|by|around)\s+)?\b($HOUR_WORD)\b(?:\s+(thirty|fifteen|forty[\s-]five))?\s*(a\.?m\.?\B|p\.?m\.?\B|am\b|pm\b|o'?clock\b)?""")
private val REMIND_DAY = Regex("""\b(?:on\s+)?(today|tomorrow|the\s+day\s+after\s+tomorrow|next\s+week|(?:next\s+)?(?:$WEEKDAYS))\b""")
private val REMIND_PART = Regex("""\b(?:(?:this|in\s+the|at)\s+)?(morning|afternoon|evening|tonight|night|noon|midday|midnight|lunchtime)\b""")
/** Words that only hold the request together; taken off the ends of what is left. */
private val EDGE_FILLER = setOf("me", "please", "jarvis", "hey", "at", "on", "to", "about", "of", "for", "that", "by", "around", "and", "in", "can", "you", "could", "would")

/**
 * A reminder said in ordinary English: "remind me to call mum at 9 pm", "remind me in 10 minutes to check
 * the oven", "set a reminder for tomorrow morning: pay the bill", "remind me at quarter to nine to leave".
 * The time can stand anywhere; what is left is the thing to remember.
 * With [now], a bare "at 5" said at 14:00 means 17:00, the first one still to come.
 */
fun parseReminder(text: String, today: LocalDate, now: LocalDateTime? = null): Action? {
    val n = normalize(text)
    fun cut(r: IntRange) = text.substring(r.first, r.last + 1).trim()
    REMIND_REMOVE.find(n)?.let { m -> return Action.RemoveReminder(cut((m.groups[1] ?: m.groups[2]!!).range)) }
    val used = BooleanArray(n.length)
    fun take(r: IntRange) { for (i in r) used[i] = true }
    fun free(r: IntRange) = r.none { used[it] }
    fun <T> first(re: Regex, read: (MatchResult) -> T?): T? {
        for (m in re.findAll(n)) {
            if (m.value.isBlank() || !free(m.range)) continue
            val v = read(m) ?: continue
            take(m.range)
            return v
        }
        return null
    }
    REMIND_ASK.findAll(n).forEach { take(it.range) }

    var minutes = first(REMIND_IN) { m ->
        val g = m.groupValues
        when {
            g[1].startsWith("half") -> 30
            g[1].startsWith("a quarter") -> 15
            else -> {
                val count = number(g[2]) ?: return@first null
                val hours = g[4].startsWith("h")
                val half = g[3].isNotEmpty() || m.value.endsWith("and a half")
                val extra = g[5].takeIf { it.isNotEmpty() }?.let { number(it) ?: return@first null } ?: 0
                (if (hours) count * 60 + (if (half) 30 else 0) else count) + extra
            }
        }
    }
    if (minutes == null) minutes = first(REMIND_SOON) { 10 }

    var date: LocalDate? = null
    var part: String? = null
    var hour: Int? = null
    var minute = 0
    var meridiem = ""
    if (minutes == null) {
        date = first(REMIND_DAY) { m -> dayNamed(m.groupValues[1].replace(Regex("""\s+"""), " "), today) }
        first(REMIND_PAST_TO) { m ->
            val h = m.groupValues[3].let { it.toIntOrNull() ?: hourWord(it) } ?: return@first null
            val by = when (m.groupValues[1]) { "half" -> 30; "quarter" -> 15; else -> number(m.groupValues[1]) } ?: return@first null
            if (by !in 1..59 || h > 23) return@first null
            if (m.groupValues[2] == "to" || m.groupValues[2] == "before") { hour = (h + 23) % 24; minute = 60 - by } else { hour = h; minute = by }
            Unit
        }
        part = first(REMIND_PART) { it.groupValues[1] }
        if (hour == null) first(REMIND_CLOCK) { m ->
            hour = m.groupValues[2].toInt(); minute = m.groupValues[3].toInt(); meridiem = m.groupValues[4]
            Unit
        } ?: first(REMIND_HOUR) { m ->
            // A bare number is an hour only with "at", "am/pm", "o'clock" or a part of the day ("9 tonight").
            if (m.groupValues[1].isEmpty() && m.groupValues[3].isEmpty() && part == null) return@first null
            hour = m.groupValues[2].toInt(); minute = 0; meridiem = m.groupValues[3]
            Unit
        } ?: first(REMIND_HOUR_WORD) { m ->
            // "one" or "ten" alone are just words ("one more thing"); they need "at", "o'clock" or am/pm.
            if (m.groupValues[1].isEmpty() && m.groupValues[4].isEmpty()) return@first null
            hour = hourWord(m.groupValues[2]) ?: return@first null
            minute = when (m.groupValues[3].replace('-', ' ')) { "thirty" -> 30; "fifteen" -> 15; "forty five" -> 45; else -> 0 }
            meridiem = m.groupValues[4]
            Unit
        }
        if (hour == null) when (part) {
            // Only a part of the day, or only a day: its usual hour.
            "morning" -> hour = 9
            "noon", "midday", "lunchtime" -> hour = 12
            "afternoon" -> hour = 15
            "evening", "tonight" -> hour = 20
            "night" -> hour = 23
            "midnight" -> hour = 0
            null -> if (date != null) hour = 9
        } else {
            val h = hour!!
            when {
                meridiem.startsWith("p") -> if (h in 1..11) hour = h + 12
                meridiem.startsWith("a") -> if (h == 12) hour = 0
                part == "evening" || part == "afternoon" || part == "tonight" -> if (h in 1..11) hour = h + 12
                part == "noon" || part == "midday" || part == "lunchtime" -> if (h in 1..5) hour = h + 12
                part == "night" -> if (h == 12) hour = 0 else if (h in 7..11) hour = h + 12
                part == "midnight" -> hour = 0
            }
        }
    }
    if (minutes == null && hour == null) return null

    val what = cleanEdges(buildString { text.forEachIndexed { i, ch -> append(if (used[i]) ' ' else ch) } }).ifEmpty { "Reminder" }

    if (minutes != null) return if (minutes > 0) Action.AddReminder(what, inMinutes = minutes) else null
    var h = hour!!
    if (h > 23 || minute > 59) return null
    // "at 5" in the afternoon: the 17:00 still to come, not tomorrow's 05:00.
    if (now != null && part == null && meridiem.isEmpty() && h in 1..11 && (date == null || date == now.toLocalDate())) {
        val t = now.toLocalTime()
        if (!LocalTime.of(h, minute).isAfter(t) && LocalTime.of(h + 12, minute).isAfter(t)) h += 12
    }
    return Action.AddReminder(what, date?.toString(), "%02d:%02d".format(h, minute))
}

/** What is left of a sentence once the time and the request are taken out, without the little words at its ends. */
private fun cleanEdges(s: String): String {
    var words = s.replace(Regex("""\s+"""), " ").trim().split(" ").filter { it.isNotEmpty() }
    fun bare(w: String) = normalize(w).trim(',', '.', ';', ':', '-', '!', '?', '\'', '’')
    while (words.isNotEmpty() && (bare(words.first()) in EDGE_FILLER || bare(words.first()).isEmpty())) words = words.drop(1)
    while (words.isNotEmpty() && (bare(words.last()) in EDGE_FILLER || bare(words.last()).isEmpty())) words = words.dropLast(1)
    return words.joinToString(" ").trim(' ', ',', '.', ';', ':', '-', '\'', '’')
}

/** Any of the ways to ask for a reminder. */
fun asksReminder(normalized: String) = REMIND_ASK.containsMatchIn(normalized)

/** "idea: ...", "I have an idea, ...", "take a note: ...", "note: ..." — kept in the notes folder even without the PC. */
// A bare "note" needs a colon or comma after it, so a task like "notebook" or "note the date" stays a task.
private val CAPTURE = Regex(
    """^(?:(i\s+have\s+an\s+idea|i've\s+got\s+an\s+idea|i\s+got\s+an\s+idea|new\s+idea|idea\s*[:,])|(take\s+a\s+note|make\s+a\s+note|note\s+this(?:\s+down)?|note\s*[:,]))[\s:,.-]*(.+)$""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

/** The fixed sentences that work with the PC off. Anything else needs Claude and gets null. */
fun parseCommand(spoken: String, today: LocalDate = LocalDate.now(), now: LocalDateTime? = null): Action? {
    val text = withoutWakeWord(spoken).trimEnd('.', '!', '?', ',', ' ')
    val n = normalize(text)
    if (asksReminder(n)) parseReminder(text, today, now)?.let { return it }
    REMIND_REMOVE.find(n)?.let { return parseReminder(text, today, now) }
    if (Regex("""\balarm\b|\bwake\s+me\b""").containsMatchIn(n)) return parseAlarm(n)
    if (PLAN_TOMORROW.containsMatchIn(n)) return Action.PlanTomorrow
    CAPTURE.find(text)?.let { m ->
        val body = m.groupValues[3].trim()
        if (body.isNotEmpty()) return Action.Capture(if (m.groupValues[2].isNotEmpty()) "note" else "idea", body)
    }
    fun cut(m: MatchResult, g: Int) = text.substring(m.groups[g]!!.range).trim()
    EDIT.find(n)?.let { m -> return if (m.groups[1] != null) Action.EditTodo(taskName(cut(m, 1)), cut(m, 2)) else Action.EditTodo(taskName(cut(m, 3)), cut(m, 4)) }
    MOVE.find(n)?.let { m -> dayNamed(m.groupValues[2], today)?.let { return Action.MoveTodo(taskName(cut(m, 1)), it.toString()) } }
    ADD_ON_DAY_FIRST.find(n)?.let { m -> dayNamed(m.groupValues[1], today)?.let { return Action.AddTodo(taskName(cut(m, 2)), it.toString()) } }
    ADD_ON_DAY_LAST.find(n)?.let { m -> dayNamed(m.groupValues[2], today)?.let { return Action.AddTodo(taskName(cut(m, 1)), it.takeIf { d -> d != today }?.toString()) } }
    TICK.find(n)?.let { return Action.CompleteTodo(taskName(cut(it, 1))) }
    DONE_AFTER.find(n)?.let { return Action.CompleteTodo(taskName(cut(it, 1))) }
    DONE_BEFORE.find(n)?.let { return Action.CompleteTodo(taskName(cut(it, 1))) }
    REMOVE.find(n)?.let { return Action.RemoveTodo(taskName(cut(it, 1))) }
    ADD_TODO.find(n)?.let { return Action.AddTodo(taskName(cut(it, 1))) }
    return null
}

private fun stems(s: String) = Regex("""[\p{L}\p{N}]+""").findAll(normalize(s)).map { it.value }
    .filter { it.length >= 3 && it !in STOP_WORDS }.map { it.take(4) }.toSet()

private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "that", "this", "your", "my")

/**
 * The open task meant by a loosely spoken name, or null when none or several fit equally.
 * Compares word beginnings, so "the reports" still finds "Write report".
 */
fun matchTodo(spoken: String, todos: List<Todo>): Todo? {
    val open = todos.filter { !it.done }
    return matchLine(spoken, open.map { it.text })?.let { hit -> open.first { it.text == hit } }
}

/** The line meant by a loosely spoken name, or null when none or several fit equally. */
fun matchLine(spoken: String, lines: List<String>): String? {
    val q = normalize(spoken.trim())
    if (q.isEmpty()) return null
    lines.firstOrNull { normalize(it) == q }?.let { return it }
    lines.filter { normalize(it).contains(q) || q.contains(normalize(it)) }.singleOrNull()?.let { return it }
    val want = stems(spoken)
    val scored = lines.map { it to (stems(it) intersect want).size }.filter { it.second > 0 }
    val best = scored.maxOfOrNull { it.second } ?: return null
    return scored.filter { it.second == best }.singleOrNull()?.first
}

/** A spoken plan as a list: "go for a run, write the report and read a book" is three tasks. */
fun spokenPlan(text: String): List<String> =
    text.split(Regex("""[,.;\n]+|\s+and\s+then\s+|\s+then\s+|\s+and\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }

/** Said while planning tomorrow, "it went well" or "bad" is how today went, not a task for tomorrow. */
fun isOnlyMoodWord(text: String): Boolean {
    val n = normalize(text.trim()).trimEnd('.', '!', ' ')
    return moodWord(n) != null &&
        Regex("""^(?:(?:today|it|the day)\s+(?:was|went)\s+)?(?:(?:very|really|pretty|quite|so|kind of|kinda|not)\s+)?\S+(?:\s+(?:day|today))?$""").matches(n)
}

/** What he says once tomorrow's list is set. */
fun planSavedLine(count: Int): String =
    if (count == 0) "Nothing for tomorrow yet, sir. Whenever something comes to mind, just say \"add this for tomorrow\"."
    else "$count ${if (count == 1) "task is" else "tasks are"} ready for tomorrow, sir. They will be on your list when you wake up."


/**
 * Android's alarm takes a time but no date: it rings the next time the clock shows it. Asked at
 * 07:00 for "tomorrow at 8", that would be an hour from now. After midnight "tomorrow" still means
 * the coming morning, which is when it rings anyway.
 */
fun alarmWouldRingToday(now: LocalDateTime, a: Action.SetAlarm, wakeFrom: Int): Boolean =
    a.tomorrow && now.hour >= wakeFrom && LocalTime.of(a.hour, a.minute).isAfter(now.toLocalTime())

/** Time between two alarms handed to the clock app, so it finishes one before the next arrives. */
const val ALARM_GAP_MS = 800L

/**
 * When the next alarm may go to the clock app, given when the last one's slot ends. A reply can
 * ask for several at once (09:00 to 09:30 every five minutes); sent together, the clock app only
 * takes the first. Returns the launch time and the end of this alarm's slot.
 */
fun alarmSlot(now: Long, nextFree: Long, gap: Long = ALARM_GAP_MS): Pair<Long, Long> {
    val at = maxOf(now, nextFree)
    return at to at + gap
}

/** What he says after a command done without Claude. */
fun confirmation(a: Action): String = when (a) {
    is Action.AddTodo -> "\"${a.text}\" added to ${if (a.date == null) "the list" else a.date}, sir."
    is Action.EditTodo -> "Changed it to \"${a.newText}\", sir."
    is Action.MoveTodo -> "Moved it to ${a.date}, sir."
    is Action.AddRoutine -> "\"${a.text}\" added to your routine, sir."
    is Action.RemoveRoutine -> "Removed from your routine, sir."
    is Action.LogHabit -> "\"${a.text}\" logged${if (a.date == null) "" else " for " + a.date}, sir."
    is Action.AddHabit -> "\"${a.text}\" added to your habits, sir: ${a.perWeek} times a week."
    is Action.AddTaper -> "A taper plan for \"${a.text}\" has begun, sir."
    is Action.AddMeasure -> "Now tracking \"${a.text}\" in ${a.unit}, sir."
    is Action.Money -> "${amountText(a.amount)} written down as ${if (a.income) "income" else "spending"} under ${a.category}, sir."
    is Action.RemoveHabit -> "Removed from your habits, sir."
    is Action.AddPlan -> "\"${a.text}\" added to tomorrow's plan, sir."
    is Action.CompleteTodo -> "Marked as done, sir."
    is Action.RemoveTodo -> "Removed from the list, sir."
    is Action.SetAlarm -> "Alarm set for %02d:%02d, sir.".format(a.hour, a.minute)
    is Action.LogDay -> "On record, sir."
    is Action.Capture -> if (a.kind == "note") "Added to your notes, sir." else "Added to your ideas, sir."
    is Action.AddReminder -> "Reminder set for \"${a.text}\", sir: " +
        (if (a.inMinutes != null) "in ${a.inMinutes} minutes." else listOfNotNull(a.date, a.time?.replace(':', '.')).joinToString(" ") + ".")
    is Action.RemoveReminder -> "Reminder removed, sir."
    Action.Listen, Action.ListenPlan -> "Listening, sir."
    Action.PlanTomorrow -> "Let us plan tomorrow, sir."
    is Action.SetSchedule -> "I have written the hourly plan, sir."
}

/** Decides when someone has finished talking, from the loudness of each short frame of sound. */
class Endpointer(private val silenceEndMs: Int, private val noSpeechMs: Int, private val maxMs: Int, private val frameMs: Int = 100) {
    private var floor = 200.0
    private var elapsed = 0
    private var quiet = 0
    var heardSpeech = false
        private set

    /** Takes one frame's loudness; true means stop recording now. */
    fun push(rms: Double): Boolean {
        elapsed += frameMs
        // The floor drops at once to a quieter room, and rises only slowly so speech can't raise it.
        floor = minOf(rms.coerceAtLeast(1.0), floor * 1.01)
        if (rms > maxOf(floor * 3, MIN_SPEECH_RMS)) {
            heardSpeech = true
            quiet = 0
        } else {
            quiet += frameMs
        }
        return elapsed >= maxMs || (if (heardSpeech) quiet >= silenceEndMs else elapsed >= noSpeechMs)
    }

    private companion object { const val MIN_SPEECH_RMS = 300.0 }
}

data class Routine(val text: String, val days: Set<Int>)

/** Tasks kept for later dates, split the morning a new day begins. */
data class Agenda(val dueNow: List<String>, val tomorrow: List<String>, val later: Map<String, List<String>>)

/** Anything dated today or earlier is due, even after days without opening the phone. */
fun splitAgenda(agenda: Map<String, List<String>>, today: LocalDate): Agenda {
    val tomorrow = today.plusDays(1).toString()
    return Agenda(
        agenda.filterKeys { it <= today.toString() }.toSortedMap().values.flatten(),
        agenda[tomorrow].orEmpty(),
        agenda.filterKeys { it > tomorrow },
    )
}

fun routinesOn(routines: List<Routine>, day: LocalDate): List<String> =
    routines.filter { day.dayOfWeek.value in it.days }.map { it.text }

/** A daily limit that falls each week, from [start] on [from] (yyyy-MM-dd). */
data class Taper(val start: Double, val step: Double, val from: String, val unit: String = "dose")

/**
 * An amount a day in [unit]: calories, money. [parts] are tracked next to it in [partUnit] (protein, carbs, fat),
 * or, when [split], are what the amount is made of (spending by category; new categories may turn up).
 */
data class Measure(val unit: String, val parts: List<String> = emptyList(), val partUnit: String = "", val split: Boolean = false)

/**
 * Done [perWeek] times a week, held under a falling [taper], or a [measure] of how much a day. What was logged, by day, is in [log];
 * a measure's parts are in [parts], part name to day to amount.
 */
data class Habit(
    val name: String,
    val perWeek: Int?,
    val taper: Taper?,
    val log: Map<String, Double> = emptyMap(),
    val measure: Measure? = null,
    val parts: Map<String, Map<String, Double>> = emptyMap(),
)

/** A measure's parts in order: the named ones first, then any that only turned up in the log. */
fun partNames(h: Habit): List<String> {
    val named = h.measure?.parts.orEmpty()
    return named + h.parts.keys.filter { k -> named.none { normalize(it) == normalize(k) } }.sorted()
}

fun taperLimit(t: Taper, day: LocalDate): Double {
    val weeks = ChronoUnit.DAYS.between(LocalDate.parse(t.from), day).coerceAtLeast(0) / 7
    return (t.start - t.step * weeks).coerceAtLeast(0.0)
}

/** What "I had it" counts as when no amount was said. */
fun usualAmount(h: Habit, day: LocalDate): Double = h.taper?.let { taperLimit(it, day) } ?: 1.0

/** A sensible weekly cut: about a quarter of the start, in half doses, so it takes around a month. */
fun taperStep(start: Double): Double = (Math.round(start / 4 * 2) / 2.0).coerceAtLeast(0.5)

/** Days done this Monday-to-Sunday week. */
fun weekCount(h: Habit, day: LocalDate): Int = weekDays(day).count { (h.log[it.toString()] ?: 0.0) > 0 }

fun weekDays(day: LocalDate): List<LocalDate> {
    val monday = day.minusDays(day.dayOfWeek.value - 1L)
    return List(7) { monday.plusDays(it.toLong()) }
}

/** A month as a calendar page: Monday first, always six weeks, blanks where it has no day. */
fun monthCells(month: YearMonth): List<LocalDate?> {
    val days = arrayOfNulls<LocalDate>(42)
    val first = month.atDay(1).dayOfWeek.value - 1
    for (d in 1..month.lengthOfMonth()) days[first + d - 1] = month.atDay(d)
    return days.toList()
}

/** The day a habit log goes to: [date] (yyyy-MM-dd) or today. Null if it isn't a date or is still to come. */
fun habitDay(date: String?, today: LocalDate): LocalDate? {
    val day = if (date == null) today else runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    return day.takeIf { !it.isAfter(today) }
}

fun dose(d: Double): String = if (d == Math.floor(d)) d.toInt().toString() else d.toString()

private val HABIT_DONE = Regex("""\b(had|drank|did|went|finished|completed|ate|spent|paid|took|logged|done)\b""")

/** "I had two coffees", "did my workout yesterday": a habit by name, how much and, given [today], which day, for use without Claude. */
fun parseHabit(spoken: String, names: List<String>, today: LocalDate? = null): Action.LogHabit? {
    val n = normalize(withoutWakeWord(spoken))
    if (!HABIT_DONE.containsMatchIn(n)) return null
    if (Regex("""\b(need|have)\s+to\b|\bshould\b|\bwill\b|\bgonna\b|\?""").containsMatchIn(n)) return null
    val name = names.firstOrNull { h -> Regex("""\b${Regex.escape(normalize(h).take(4))}""").containsMatchIn(n) } ?: return null
    val amount = when {
        Regex("""\bthree quarters?\b""").containsMatchIn(n) -> 0.75
        Regex("""\b(a\s+)?quarter\b""").containsMatchIn(n) -> 0.25
        Regex("""\b(one|1) and a half\b|\ba (cup|coffee|spoon) and a half\b""").containsMatchIn(n) -> 1.5
        Regex("""\b(two|2) and a half\b""").containsMatchIn(n) -> 2.5
        Regex("""\bhalf\b""").containsMatchIn(n) -> 0.5
        Regex("""\bthree\b""").containsMatchIn(n) -> 3.0
        Regex("""\btwo\b""").containsMatchIn(n) -> 2.0
        else -> Regex("""\b(\d+(?:[.,]\d+)?)\b""").find(n)?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
    }
    val date = today?.let { t ->
        when {
            Regex("""\bthe day before yesterday\b""").containsMatchIn(n) -> t.minusDays(2).toString()
            Regex("""\byesterday\b|\blast night\b""").containsMatchIn(n) -> t.minusDays(1).toString()
            else -> null
        }
    }
    return Action.LogHabit(name, amount, date)
}

private const val DAY_MINUTES = 1440
private const val DISC_MINUTES = 720

/** One stretch of a day's plan, in minutes after midnight. An end before the start falls after midnight. */
data class Block(val start: Int, val end: Int, val text: String)

fun blockLength(b: Block): Int = (b.end - b.start + DAY_MINUTES) % DAY_MINUTES

fun clockText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

fun clockMinute(s: String): Int? {
    val m = Regex("""^(\d{1,2})[:.](\d{2})$""").find(s.trim()) ?: return null
    val (h, min) = m.destructured
    return if (h.toInt() <= 23 && min.toInt() <= 59) h.toInt() * 60 + min.toInt() else null
}

/** The day runs from the wake-up hour to the next one, so 01:00 comes after 23:00. */
private fun sinceWake(minute: Int, wakeFrom: Int) = (minute - wakeFrom * 60 + DAY_MINUTES) % DAY_MINUTES

fun inDayOrder(blocks: List<Block>, wakeFrom: Int): List<Block> = blocks.sortedBy { sinceWake(it.start, wakeFrom) }

/** The part of a block on the morning or the afternoon dial, in minutes from that dial's twelve. */
fun arcsOn(b: Block, pm: Boolean): List<Pair<Int, Int>> {
    val end = b.start + blockLength(b)
    return (if (pm) listOf(DISC_MINUTES) else listOf(0, DAY_MINUTES)).mapNotNull { from ->
        val a = maxOf(b.start, from)
        val z = minOf(end, from + DISC_MINUTES)
        if (a < z) (a - from) to (z - from) else null
    }
}

data class PlanNow(val current: Block?, val next: List<Block>, val over: List<Block>)

fun planAt(blocks: List<Block>, minute: Int, wakeFrom: Int): PlanNow {
    val now = sinceWake(minute, wakeFrom)
    val ordered = inDayOrder(blocks, wakeFrom)
    return PlanNow(
        ordered.firstOrNull { now >= sinceWake(it.start, wakeFrom) && now < sinceWake(it.start, wakeFrom) + blockLength(it) },
        ordered.filter { sinceWake(it.start, wakeFrom) > now },
        ordered.filter { sinceWake(it.start, wakeFrom) + blockLength(it) <= now },
    )
}

/** Puts a block in the day; whatever else was planned for that time gives way to it. */
fun placeBlock(blocks: List<Block>, new: Block, wakeFrom: Int): List<Block> {
    val a = sinceWake(new.start, wakeFrom)
    val z = a + blockLength(new)
    fun back(m: Int) = (m + wakeFrom * 60) % DAY_MINUTES
    val kept = blocks.flatMap { b ->
        val s = sinceWake(b.start, wakeFrom)
        val e = s + blockLength(b)
        listOfNotNull(
            if (s < a && minOf(e, a) > s) Block(back(s), back(minOf(e, a)), b.text) else null,
            if (e > z && e > maxOf(s, z)) Block(back(maxOf(s, z)), back(e), b.text) else null,
        )
    }
    return inDayOrder(kept + new, wakeFrom)
}

private val PLAN_LINE = Regex("""^(?:at\s+)?(\d{1,2})(?:[:.](\d{2}))?(?:\s*[-–—]\s*(\d{1,2})(?:[:.](\d{2}))?)?\s+(.+)$""", RegexOption.IGNORE_CASE)

/** Reads "1" after 11:00 as the afternoon's one, while "1" after 23:00 stays the night's. */
private fun afterward(minute: Int, previous: Int): Int =
    if (minute < previous && minute < DISC_MINUTES && minute + DISC_MINUTES > previous) minute + DISC_MINUTES else minute

/**
 * A typed plan with its hours in digits: "09:00-10:30 Gym, 11 Class, 13 Lunch". A block with no end
 * lasts until the next one, the last of them an hour. Null unless every part reads this way, since
 * anything looser is for Claude.
 */
fun parseSchedule(text: String): List<Block>? {
    val parts = text.split(Regex("""[,;\n]+""")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    fun minute(h: String, m: String): Int? {
        val hour = h.toInt()
        val min = m.ifEmpty { "0" }.toInt()
        return if (hour <= 23 && min <= 59) hour * 60 + min else null
    }
    var previous = -1
    val read = parts.map { part ->
        val g = PLAN_LINE.find(part)?.groupValues ?: return null
        val start = afterward(minute(g[1], g[2]) ?: return null, previous)
        val end = if (g[3].isEmpty()) null else afterward(minute(g[3], g[4]) ?: return null, start)
        if (end == start) return null
        previous = end ?: start
        Triple(start, end, g[5].trim())
    }
    return read.mapIndexed { i, (start, end, name) ->
        Block(start, end ?: read.getOrNull(i + 1)?.first ?: ((start + 60) % DAY_MINUTES), name)
    }.filter { blockLength(it) > 0 }
}

private val WHISPER_NOISE = Regex(
    """^(?:thank you|thanks)(?: (?:so much|very much))?(?: for watching| for listening)?[.!]*$|^(?:please )?(?:like and )?subscribe|^subtitles? by|^transcribed by|^(?:bye|bye-bye|you)[.!]*$|^(?:hm+|uh+m*|um+|mm+|ah+)[.!?]*$|^\[.*]$|^\(.*\)$""",
    RegexOption.IGNORE_CASE,
)

/** What Whisper writes when it hears noise instead of speech: learned from video subtitles, not said by anyone. */
fun isWhisperNoise(heard: String): Boolean = WHISPER_NOISE.containsMatchIn(heard.trim())
