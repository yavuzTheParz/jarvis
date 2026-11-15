package app.jarvis

/**
 * One day's file in the vault (`Jarvis/days/yyyy-MM-dd.md`), plain Markdown that Obsidian or any editor can open.
 * Tasks live under "## Tasks", the hourly plan under "## Plan", the evening's answers under "## Journal".
 * Anything else the user writes, on the phone or on the PC, is kept exactly as it was.
 */
data class DayNote(val head: List<String>, val sections: List<Section>)

data class Section(val title: String, val lines: List<String>)

const val TASKS = "Tasks"
const val PLAN = "Plan"
const val JOURNAL = "Journal"

private val HEADING = Regex("""^##\s+(.*?)\s*$""")
private val TASK = Regex("""^\s*[-*]\s+\[([ xX>])]\s+(.*?)\s*$""")
private val BLOCK = Regex("""^\s*[-*]\s+(\d{1,2}[:.]\d{2})\s*[-–]\s*(\d{1,2}[:.]\d{2})\s+(.*?)\s*$""")
val DAY_FILE = Regex("""^(\d{4}-\d{2}-\d{2})\.md$""")

fun newDay(date: String) = DayNote(
    listOf("---", "type: day", "date: $date", "---", ""),
    listOf(Section(TASKS, listOf("")), Section(PLAN, listOf(""))),
)

fun parseDay(text: String): DayNote {
    val head = mutableListOf<String>()
    val sections = mutableListOf<Section>()
    var title: String? = null
    var body = mutableListOf<String>()
    for (line in text.removeSuffix("\n").split("\n")) {
        val h = HEADING.find(line)
        if (h != null) {
            if (title != null) sections += Section(title, body)
            title = h.groupValues[1]
            body = mutableListOf()
        } else if (title == null) head += line else body += line
    }
    if (title != null) sections += Section(title, body)
    return DayNote(head, sections)
}

fun renderDay(n: DayNote): String = buildString {
    n.head.forEach { append(it).append('\n') }
    n.sections.forEach { s ->
        append("## ").append(s.title).append('\n')
        s.lines.forEach { append(it).append('\n') }
    }
}

private fun DayNote.section(title: String) = sections.firstOrNull { normalize(it.title) == normalize(title) }

/** Replaces the lines of [title], adding the section at the end if it isn't there yet. */
private fun DayNote.withSection(title: String, lines: List<String>): DayNote {
    val s = section(title)
    return if (s != null) copy(sections = sections.map { if (it === s) it.copy(lines = lines) else it })
    else {
        // A blank line between the last section and the new heading keeps the file readable.
        val last = sections.lastOrNull()
        val fixed = if (last != null && last.lines.lastOrNull()?.isNotBlank() == true) sections.dropLast(1) + last.copy(lines = last.lines + "") else sections
        copy(sections = fixed + Section(title, lines + ""))
    }
}

/** Puts [items] where the old ones stood among the section's other lines, or after them if there were none. */
private fun replaceItems(lines: List<String>, isItem: (String) -> Boolean, items: List<String>): List<String> {
    val first = lines.indexOfFirst(isItem)
    val kept = lines.filterNot(isItem)
    val at = if (first >= 0) lines.take(first).count { !isItem(it) } else kept.indexOfLast { it.isNotBlank() } + 1
    return kept.take(at) + items + kept.drop(at).ifEmpty { listOf("") }
}

private fun isLiveTask(l: String) = TASK.find(l)?.groupValues?.get(1)?.let { it != ">" } == true

/** Open and finished tasks; ones carried to a later day ("- [>]") are history and not listed. */
fun dayTasks(n: DayNote): List<Todo> = n.section(TASKS)?.lines.orEmpty().mapNotNull { l ->
    TASK.find(l)?.groupValues?.takeIf { it[1] != ">" }?.let { Todo(it[2], it[1] != " ") }
}

fun withTasks(n: DayNote, todos: List<Todo>): DayNote = n.withSection(
    TASKS,
    replaceItems(n.section(TASKS)?.lines.orEmpty(), ::isLiveTask, todos.map { "- [${if (it.done) "x" else " "}] ${it.text}" }),
)

/** The day's unfinished tasks, and the day with them marked as moved on. */
fun carryOver(n: DayNote): Pair<List<String>, DayNote> {
    val s = n.section(TASKS) ?: return emptyList<String>() to n
    val open = dayTasks(n).filter { !it.done }.map { it.text }
    if (open.isEmpty()) return open to n
    return open to n.withSection(TASKS, s.lines.map { l ->
        val m = TASK.find(l)
        if (m != null && m.groupValues[1] == " ") l.replaceFirst("[ ]", "[>]") else l
    })
}

/** A new morning's list: what was left unfinished, what was already filed for today, then today's routines; each once. */
fun newDayList(carried: List<String>, have: List<Todo>, routines: List<String>): List<Todo> {
    val there = have.map { normalize(it.text) }.toSet()
    fun fresh(l: List<String>) = l.filter { normalize(it) !in there }.map { Todo(it, false) }
    return (fresh(carried) + have + fresh(routines)).distinctBy { normalize(it.text) }
}

fun dayBlocks(n: DayNote): List<Block> = n.section(PLAN)?.lines.orEmpty().mapNotNull { l ->
    BLOCK.find(l)?.groupValues?.let { g ->
        val s = clockMinute(g[1]) ?: return@let null
        val e = clockMinute(g[2]) ?: return@let null
        Block(s, e, g[3])
    }
}

fun withBlocks(n: DayNote, blocks: List<Block>): DayNote = n.withSection(
    PLAN,
    replaceItems(n.section(PLAN)?.lines.orEmpty(), { BLOCK.matches(it) }, blocks.map { "- ${clockText(it.start)}-${clockText(it.end)} ${it.text}" }),
)

/** Adds a paragraph to the evening's journal, after whatever is already there. */
fun withJournal(n: DayNote, text: String): DayNote {
    if (text.isBlank()) return n
    val old = n.section(JOURNAL)?.lines.orEmpty().dropLastWhile { it.isBlank() }
    return n.withSection(JOURNAL, (if (old.isEmpty()) emptyList() else old + "") + text.trim().split("\n") + "")
}

/** The journal paragraph for one evening answer or spoken log. */
fun journalText(time: String, did: String, creative: String, plan: String): String {
    val l = buildList {
        if (did.isNotBlank()) add(did.trim())
        if (creative.isNotBlank()) add("Made: ${creative.trim()}")
        if (plan.isNotBlank()) add("For tomorrow: ${plan.trim().replace("\n", "; ")}")
    }
    return if (l.isEmpty()) "" else (listOf("**$time** ${l[0]}") + l.drop(1)).joinToString("\n")
}

// ---------- Ideas and notes ----------

private val BAD_NAME = Regex("""[\\/:*?"<>|#\[\]\n\r\t]""")

/** A file name from the first words of what was said. */
fun noteName(text: String): String {
    val words = BAD_NAME.replace(text.trim().lineSequence().firstOrNull().orEmpty(), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.take(8).joinToString(" ").take(80).trim('.', ' ').ifEmpty { "Note" }
}

private fun cleanName(s: String) = BAD_NAME.replace(s, " ").replace(Regex("\\s+"), " ").trim().take(120).trim('.', ' ')

/** Where a captured thing goes (folder, file name without ".md") and what is written there. */
fun captureFile(c: Action.Capture, date: String): Triple<String, String, String> {
    val name = c.title?.let(::cleanName)?.ifEmpty { null } ?: noteName(c.text)
    return if (c.kind == "note") Triple("notes", name, "---\ntype: note\ndate: $date\n---\n# $name\n\n${c.text.trim()}\n")
    else Triple("ideas", name, "---\ntype: idea\ndate: $date\n---\n${c.text.trim()}\n")
}

/** The plain "key: value" lines of a note's frontmatter. */
fun frontOf(text: String): Map<String, String> {
    if (!text.startsWith("---\n")) return emptyMap()
    val end = text.indexOf("\n---", 4).takeIf { it > 0 } ?: return emptyMap()
    return text.substring(4, end).lines().mapNotNull { l ->
        val i = l.indexOf(':')
        if (i <= 0 || l.startsWith(" ") || l.startsWith("-")) null
        else l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"', '\'')
    }.toMap()
}

/** A note's title: its first "# " heading, or its file name. */
fun titleOf(fileName: String, text: String): String =
    text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim() ?: fileName.removeSuffix(".md")
