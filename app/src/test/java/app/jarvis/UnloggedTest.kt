package app.jarvis

import org.junit.Assert.*
import org.junit.Test

class UnloggedTest {
    @Test fun operaIsNeverLogged() {
        listOf("com.opera.browser", "com.opera.gx", "com.opera.mini.native", "com.opera.browser.beta")
            .forEach { assertTrue(it, isUnlogged(it)) }
        listOf("com.android.chrome", "com.whatsapp", "com.operation.app").forEach { assertFalse(it, isUnlogged(it)) }
    }

    @Test fun purgeDropsOnlyOperaLines() {
        val lines = listOf(
            """{"at":"2026-11-04T10:00","app":"com.whatsapp"}""",
            """{"at":"2026-11-04T10:05","app":"com.opera.browser"}""",
            """{"at":"2026-11-04T10:06","app":"com.android.chrome","url":"example.com"}""",
            """{"at":"2026-11-04T10:07","app":"com.opera.gx"}""",
        )
        assertEquals(listOf(lines[0], lines[2]), withoutUnlogged(lines))
    }
}
