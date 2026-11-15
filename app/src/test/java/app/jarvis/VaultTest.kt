package app.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultTest {
    private val written = """
        ---
        type: day
        date: 2026-11-13
        ---

        Morning note, before the headings.

        ## Tasks
        - [ ] Gym
        - [x] Class
        - [>] Left from yesterday
        A line of explanation.

        ## Plan
        - 09:00-10:30 Gym
        - 11.00–12:00 Class
        free text

        ## My own heading
        Something written by hand.
    """.trimIndent() + "\n"

    @Test fun roundTripKeepsEverything() = assertEquals(written, renderDay(parseDay(written)))

    @Test fun readsTasksButNotCarriedOnes() =
        assertEquals(listOf(Todo("Gym", false), Todo("Class", true)), dayTasks(parseDay(written)))

    @Test fun readsBlocks() =
        assertEquals(listOf(Block(540, 630, "Gym"), Block(660, 720, "Class")), dayBlocks(parseDay(written)))

    @Test fun rewritingTasksKeepsOtherLines() {
        val n = withTasks(parseDay(written), listOf(Todo("Class", true), Todo("Book", false)))
        val out = renderDay(n)
        assertTrue(out.contains("## Tasks\n- [x] Class\n- [ ] Book\n- [>] Left from yesterday\nA line of explanation.\n"))
        assertTrue(out.contains("Morning note, before the headings."))
        assertTrue(out.contains("## My own heading\nSomething written by hand.\n"))
    }

    @Test fun rewritingBlocksKeepsFreeText() {
        val out = renderDay(withBlocks(parseDay(written), listOf(Block(480, 540, "Breakfast"))))
        assertTrue(out.contains("## Plan\n- 08:00-09:00 Breakfast\nfree text\n"))
    }

    @Test fun newDayGetsSections() {
        val out = renderDay(withTasks(newDay("2026-11-14"), listOf(Todo("Gym", false))))
        assertEquals("---\ntype: day\ndate: 2026-11-14\n---\n\n## Tasks\n- [ ] Gym\n\n## Plan\n\n", out)
        assertEquals(listOf(Todo("Gym", false)), dayTasks(parseDay(out)))
    }

    @Test fun carryOverMarksOpenTasks() {
        val (open, n) = carryOver(parseDay(written))
        assertEquals(listOf("Gym"), open)
        assertTrue(renderDay(n).contains("- [>] Gym\n- [x] Class\n"))
        assertEquals(listOf(Todo("Class", true)), dayTasks(n))
        assertEquals(emptyList<String>(), carryOver(n).first)
    }

    @Test fun newDayListOrder() = assertEquals(
        listOf(Todo("From yesterday", false), Todo("Filed for today", true), Todo("Gym", false)),
        newDayList(listOf("From yesterday", "filed for today"), listOf(Todo("Filed for today", true)), listOf("Gym", "from yesterday")),
    )

    @Test fun journalAppendsAtEnd() {
        val once = withJournal(newDay("2026-11-13"), journalText("21:30", "Wrote code", "A story idea", ""))
        val twice = withJournal(once, journalText("22:00", "Read a book", "", ""))
        assertTrue(renderDay(twice).endsWith("## Journal\n**21:30** Wrote code\nMade: A story idea\n\n**22:00** Read a book\n\n"))
    }

    @Test fun dayFileNamesOnly() {
        assertTrue(DAY_FILE.matches("2026-11-13.md"))
        assertTrue(!DAY_FILE.matches("2026-11-13.sync-conflict-20261113-101010-ABC.md"))
        assertTrue(!DAY_FILE.matches(".2026-11-13.md.tmp"))
    }

    @Test fun ideaFile() {
        val (folder, name, text) = captureFile(Action.Capture("idea", "Floors of the tower\nturn like a clock"), "2026-11-14")
        assertEquals("ideas", folder)
        assertEquals("Floors of the tower", name)
        assertEquals("---\ntype: idea\ndate: 2026-11-14\n---\nFloors of the tower\nturn like a clock\n", text)
    }

    @Test fun noteFileHasItsTitle() {
        val (folder, name, text) = captureFile(Action.Capture("note", "Fencing practice is on Tuesday", "Fencing"), "2026-11-14")
        assertEquals("notes", folder)
        assertEquals("Fencing", name)
        assertEquals("---\ntype: note\ndate: 2026-11-14\n---\n# Fencing\n\nFencing practice is on Tuesday\n", text)
    }

    @Test fun namesAreSafeFileNames() {
        assertEquals("a b c", noteName("a/b:c? \nsecond line"))
        assertEquals("one two three four five six seven eight", noteName("one two three four five six seven eight nine"))
        assertEquals("Note", noteName("  "))
    }

    @Test fun spokenCaptures() {
        assertEquals(Action.Capture("idea", "an elevator for the tower"), parseCommand("Jarvis idea: an elevator for the tower"))
        assertEquals(Action.Capture("idea", "an elevator for the tower"), parseCommand("I have an idea, an elevator for the tower"))
        assertEquals(Action.Capture("note", "fencing practice on tuesday"), parseCommand("take a note: fencing practice on tuesday"))
        assertEquals(Action.Capture("note", "x"), parseCommand("note: x"))
        // A task that starts with the same word stays a task.
        assertEquals(Action.AddTodo("notebook"), parseCommand("add notebook"))
    }
}
