package app.jarvis

import org.junit.Assert.*
import org.junit.Test

class SessionTest {
    private val today = "2026-11-14"

    @Test fun eachDayStartsItsOwn() {
        val yesterday = listOf(ChatSession(daySessionId("2026-11-13"), "2026-11-13", null))
        val now = withDaySession(yesterday, today)
        assertEquals(2, now.size)
        assertEquals(ChatSession("d-2026-11-14", today, null), now.last())
        assertSame("an existing day is left alone", now, withDaySession(now, today))
    }

    @Test fun aNewDayMovesTheConversationOn() {
        val all = listOf(ChatSession("d-2026-11-13", "2026-11-13", null), ChatSession("x-1", null, null))
        assertEquals("d-2026-11-13", activeSession(all, "d-2026-11-13", "2026-11-13", "2026-11-13").id)
        assertEquals("yesterday's choice does not carry over", "d-2026-11-14", activeSession(all, "d-2026-11-13", "2026-11-13", today).id)
        assertEquals("x-1", activeSession(all, "x-1", today, today).id)
        assertEquals("an old day can be reopened", "d-2026-11-13", activeSession(all, "d-2026-11-13", today, today).id)
        assertEquals("unknown falls back to today", "d-2026-11-14", activeSession(all, "gone", today, today).id)
        assertEquals("d-2026-11-14", activeSession(emptyList(), null, null, today).id)
    }

    @Test fun titles() {
        val all = listOf(
            ChatSession(LEGACY_SESSION, null, null),
            ChatSession("d-2025-12-31", "2025-12-31", null),
            ChatSession("d-2026-11-02", "2026-11-02", null),
            ChatSession("d-2026-11-13", "2026-11-13", null),
            ChatSession("d-2026-11-14", today, null),
            ChatSession("x-1", null, null),
            ChatSession("x-2", null, "Side project"),
            ChatSession("x-3", null, "  "),
        )
        assertEquals(
            listOf("Earlier chat", "31 Dec 2025", "2 Nov", "Yesterday", "Today", "Chat 1", "Side project", "Chat 3"),
            all.map { sessionTitle(it, all, today) },
        )
    }

    @Test fun renaming() {
        val all = listOf(ChatSession("d-2026-11-14", today, null), ChatSession("x-1", null, "Eski ad"))
        assertEquals("Plan", renameSession(all, "d-2026-11-14", "  Plan ")[0].name)
        assertNull("clearing goes back to the default", renameSession(all, "x-1", " ")[1].name)
        assertEquals("Eski ad", renameSession(all, "d-2026-11-14", "Plan")[1].name)
    }

    @Test fun order() {
        val all = listOf(
            ChatSession("x-1", null, null),
            ChatSession("d-2026-11-14", today, null),
            ChatSession(LEGACY_SESSION, null, null),
            ChatSession("d-2026-11-12", "2026-11-12", null),
            ChatSession("x-2", null, null),
        )
        assertEquals(listOf(LEGACY_SESSION, "d-2026-11-12", "d-2026-11-14", "x-1", "x-2"), sessionOrder(all).map { it.id })
    }
}
