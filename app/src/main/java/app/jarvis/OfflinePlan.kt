package app.jarvis

/*
 * The hourly plan when the PC is out of reach: a spoken or loosely typed day, read without Claude.
 * "Gym at 9, class from 10:30 to 12, lunch at noon, then 2 hours reading, movie at 8 pm."
 * What can't be read stays for Claude: the text is queued and sent once the bridge answers again.
 */

private const val DAY = 1440

private const val HOUR_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
private val HOUR_VALUE = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
    "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
)
private val COUNT_VALUE = HOUR_VALUE + mapOf(
    "a" to 1, "an" to 1, "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty five" to 45, "forty-five" to 45, "fifty" to 50,
)

/** The same length as what was said, so ranges found here cut the original; "a.m." loses its dots so it can't end a sentence. */
private fun norm(s: String) = normalize(s).replace("a.m.", "am  ").replace("p.m.", "pm  ")

/** Where one item ends and the next begins. */
private val SPLIT = Regex(
    """\s*[,;\n]+\s*|(?<!\d)\.(?!\d)\s*|\s+(?:and\s+)?(?:then|after that|afterwards|later on|finally|at the end)(?=\s|$)\s*|\s+and\s+(?=(?:at|from)\s|\d)""",
)

/** A clock time: "9", "09:30", "at 9", "at nine", "9 pm", "nine thirty", "from 10", "to 12", "until 5". */
private val TIME = Regex(
    """(?<![\w:.])((?:at|from|until|till|to|by|after|around)\s+)?(?:(\d{1,2})(?:[:.](\d{2}))?|($HOUR_WORDS))(\s+thirty)?""" +
        """(\s*(?:am|pm|o'?clock)(?!\w))?(?!\w|:)""",
)

/** How long: "2 hours", "half an hour", "an hour and a half", "45 minutes", "1 hour 20 minutes", "for 30 mins". */
private val LENGTH = Regex(
    """(?<!\w)(?:for\s+)?(\d{1,3}|half\s+an?|forty[\s-]five|fifteen|twenty|thirty|forty|fifty|an?|$HOUR_WORDS)(\s+and\s+a\s+half)?\s+(hours?|hrs?|minutes?|mins?)(?!\w)""" +
        """(\s+and\s+a\s+half)?(?:\s+(?:and\s+)?(\d{1,2}|forty[\s-]five|fifteen|twenty|thirty|forty|fifty|ten)\s+(?:minutes?|mins?))?(?!\w)""",
)

private val PART = Regex("""(?<!\w)(?:in\s+the\s+|this\s+|at\s+)?(morning|afternoon|evening|tonight|night|noon|midday)(?!\w)""")
private val UNTIL = Regex("""(?<!\S)[-–—](?!\S)""")

/** Words that carry no part of the activity's name, when they stand at its ends. */
private val FILLER = setOf(
    "at", "from", "until", "till", "to", "then", "and", "after", "first", "i", "i'll", "ill", "i'm", "im", "will", "going", "gonna",
    "do", "some", "a", "an", "the", "my", "plan", "today", "tomorrow", "in", "for", "of", "is", "be", "have", "there's", "around",
    "about", "ish", "by", "-", "–", "—", "want", "need", "start", "starting", "with", "o'clock", "oclock",
)

private data class Piece(
    val start: Int?, val end: Int?, val length: Int?, val part: String?,
    val name: String, val reference: Boolean,
)

private fun hourOf(m: MatchResult): Int? {
    val g = m.groupValues
    return if (g[2].isNotEmpty()) g[2].toInt() else HOUR_VALUE[g[4]]
}

/** "at 9" or "9 pm" is a time; a lone "20" may be pages or a count, unless it opens the item. */
private fun isClock(m: MatchResult, piece: String): Boolean {
    val g = m.groupValues
    val prefixed = g[1].isNotEmpty()
    val meridiem = g[6].isNotEmpty()
    // Words only with "at"/"from"/... or am/pm: "at nine", never "one more walk".
    if (g[4].isNotEmpty()) return prefixed || meridiem
    if (prefixed || g[3].isNotEmpty() || meridiem) return true
    val before = piece.substring(0, m.range.first).trimEnd()
    return before.isEmpty() || before.endsWith("-") || before.endsWith("–") || PART.matches(before.substringAfterLast(' '))
}

private fun count(s: String): Int? = s.trim().toIntOrNull() ?: COUNT_VALUE[s.trim()]

private fun lengthOf(m: MatchResult): Int? {
    val g = m.groupValues
    val n = g[1].replace(Regex("""\s+"""), " ")
    val half = g[2].isNotEmpty() || g[4].isNotEmpty()
    val hours = g[3].startsWith("h")
    val extra = g[5].takeIf { it.isNotEmpty() }?.let(::count) ?: 0
    val minutes = if (hours) when {
        n.startsWith("half") -> 30
        else -> (count(n) ?: return null) * 60 + if (half) 30 else 0
    } else count(n) ?: return null
    return (minutes + extra).takeIf { it in 5..(16 * 60) }
}

/** The hour as said, put on the clock: "8 pm" or "8 in the evening" is 20:00, "12 at night" midnight, "1 at noon" 13:00. */
private fun onClock(hour: Int, minute: Int, part: String?, meridiem: String): Int? {
    if (hour > 23 || minute > 59) return null
    var h = hour
    when {
        meridiem == "pm" -> if (h in 1..11) h += 12
        meridiem == "am" -> if (h == 12) h = 0
        part == "evening" || part == "afternoon" || part == "tonight" -> if (h in 1..11) h += 12
        part == "noon" || part == "midday" -> if (h in 1..6) h += 12
        part == "night" -> if (h == 12) h = 0 else if (h in 6..11) h += 12
    }
    return h * 60 + minute
}

private fun partDefault(part: String): Int = when (part) {
    "morning" -> 9 * 60
    "noon", "midday" -> 12 * 60
    "afternoon" -> 14 * 60
    "evening" -> 19 * 60
    "tonight" -> 20 * 60
    else -> 23 * 60
}

/** The activity's name: what is left of the item, in the user's own words, capitalized. */
private fun nameOf(original: String, used: BooleanArray): String {
    val kept = original.indices.joinToString("") { if (used[it]) " " else original[it].toString() }
    val words = kept.split(Regex("""\s+""")).map { it.trim('"', ':', '.', '!', '?') }.filter { it.isNotEmpty() }
    val trimmed = words.dropWhile { normalize(it) in FILLER }.dropLastWhile { normalize(it) in FILLER }
    val name = trimmed.joinToString(" ")
    if (name.isEmpty()) return ""
    return "${name[0].uppercaseChar()}${name.substring(1)}"
}

private fun readPiece(original: String): Piece {
    val n = norm(original)
    val used = BooleanArray(n.length)
    fun take(r: IntRange) { for (i in r) used[i] = true }
    val part = PART.find(n)?.also { take(it.range) }?.groupValues?.get(1)
    val length = LENGTH.find(n)?.let { m -> lengthOf(m)?.also { take(m.range) } }
    val clock = TIME.findAll(n).filter { m -> m.range.none { used[it] } && isClock(m, n) }.mapNotNull { m ->
        val hour = hourOf(m) ?: return@mapNotNull null
        val minute = m.groupValues[3].ifEmpty { if (m.groupValues[5].isNotEmpty()) "30" else "0" }.toInt()
        val meridiem = m.groupValues[6].trim().take(2).let { if (it == "am" || it == "pm") it else "" }
        val at = onClock(hour, minute, part, meridiem) ?: return@mapNotNull null
        take(m.range)
        // "9" could be morning or evening, settled when the day is put together; "09:00", "21", "9 pm" or "9 in the evening" could not.
        at to (part == null && meridiem.isEmpty() && hour in 1..11 && !m.groupValues[2].startsWith("0"))
    }.toList()
    UNTIL.findAll(n).forEach { take(it.range) }
    val name = nameOf(original, used)
    // "After breakfast, gym": an item named only to say what comes after it.
    val reference = clock.isEmpty() && length == null && Regex("""^\s*after\s""").containsMatchIn(n)
    val ambiguous = clock.firstOrNull()?.second == true
    return Piece(clock.getOrNull(0)?.first, clock.getOrNull(1)?.first, length, if (ambiguous) AMBIGUOUS else part, name, reference)
}

private const val AMBIGUOUS = "?"

/**
 * Reads a plan said in ordinary English into blocks. [nowMinute] is given when planning today: then a
 * bare "3" said at 14:00 is the afternoon's three, and an item with no time at all starts now.
 * Null when nothing in it looks like a plan.
 */
fun parseSpokenSchedule(text: String, nowMinute: Int? = null, wakeFrom: Int = 5): List<Block>? {
    val n = norm(text)
    val cuts = SPLIT.findAll(n).map { it.range }.toList()
    var at = 0
    val raw = ArrayList<String>()
    for (c in cuts) {
        raw += text.substring(at, c.first)
        at = c.last + 1
    }
    raw += text.substring(at)
    val pieces = raw.map { it.trim() }.filter { it.isNotEmpty() }.map(::readPiece)
    if (pieces.none { it.name.isNotEmpty() && !it.reference }) return null
    if (nowMinute == null && pieces.none { it.start != null || it.part != null }) return null

    // Times run forward through the day, counted from the wake-up hour so that 01:00 comes after 23:00.
    val wake = wakeFrom * 60
    fun since(m: Int) = (m - wake + DAY) % DAY
    var cursor: Int? = nowMinute?.let { since(((it + 14) / 15) * 15 % DAY) }
    var carriedStart: Int? = null
    var carriedEnd: Int? = null
    data class Draft(val start: Int, var end: Int?, val length: Int?, val name: String, val explicit: Boolean)
    val drafts = ArrayList<Draft>()
    for (p in pieces) {
        val said = p.start
        val after = cursor
        // A bare hour: the first of morning and evening still to come after the previous item (or now); before wake-up, the afternoon.
        val start: Int? = when {
            said == null || p.part != AMBIGUOUS -> said
            after != null -> listOf(said, said + 12 * 60).filter { since(it) >= after }.minByOrNull { since(it) } ?: said
            said / 60 < wakeFrom -> said + 12 * 60
            else -> said
        }
        val end = p.end?.let { e -> if (p.part == AMBIGUOUS && start != null && e <= start && e + 12 * 60 > start) e + 12 * 60 else e }
        if (p.name.isEmpty() || p.reference) {
            // "After 9:": the time belongs to whatever is named next.
            if (start != null) { carriedStart = start; carriedEnd = end }
            continue
        }
        val explicit = start != null || carriedStart != null || (p.part != null && p.part != AMBIGUOUS)
        val s = start ?: carriedStart ?: p.part?.takeIf { it != AMBIGUOUS }?.let { part ->
            val d = partDefault(part)
            // "Lunch at noon" after a long morning starts when the morning is done.
            cursor?.takeIf { it > since(d) && it - since(d) < 3 * 60 }?.let { (it + wake) % DAY } ?: d
        } ?: cursor?.let { (it + wake) % DAY } ?: (if (nowMinute == null) 9 * 60 else nowMinute)
        val e = end ?: carriedEnd ?: p.length?.let { (s + it) % DAY }
        carriedStart = null; carriedEnd = null
        drafts += Draft(s, e, p.length, p.name, explicit)
        cursor = since(e ?: (s + 60) % DAY)
    }
    if (drafts.isEmpty()) return null
    // An open end runs to the next item when that comes soon after; otherwise it lasts an hour.
    for ((i, d) in drafts.withIndex()) {
        if (d.end != null) continue
        val next = drafts.getOrNull(i + 1)?.takeIf { it.explicit }
        d.end = if (next != null && since(next.start) - since(d.start) in 1..(3 * 60)) next.start else (d.start + 60) % DAY
    }
    return drafts.map { Block(it.start, it.end!!, it.name) }.filter { blockLength(it) > 0 }.ifEmpty { null }
}

/** "09:00-10:30 Gym, 11:00-12:00 Class": how a plan is read back. */
fun scheduleText(blocks: List<Block>): String = blocks.joinToString(", ") { "${clockText(it.start)}-${clockText(it.end)} ${it.text}" }
