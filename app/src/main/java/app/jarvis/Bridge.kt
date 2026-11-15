package app.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class Reply(val tone: String, val text: String, val actions: List<Action>)

/** Talks to bridge/server.py on the PC over Tailscale. Blocking: call off the main thread. */
class Bridge(private val base: String) {
    private fun post(path: String, body: String, timeoutMs: Int): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5_000
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BRIDGE_TOKEN)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) error("bridge ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /** True when the bridge answers quickly; Claude itself may still fail on a chat. */
    fun isUp(): Boolean = runCatching {
        val c = URL("$base/health").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2_000
            c.readTimeout = 2_000
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BRIDGE_TOKEN)
            c.responseCode == 200
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** Messages waiting at the bridge; each is delivered once. */
    fun news(): List<String> {
        val c = URL("$base/news").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 2_000
            c.readTimeout = 3_000
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BRIDGE_TOKEN)
            if (c.responseCode != 200) return emptyList()
            val a = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("news")
            return List(a.length()) { a.getString(it) }
        } finally {
            c.disconnect()
        }
    }

    /** [session] picks the conversation on the PC, so he remembers what was said in it and only in it. */
    fun chat(message: String, context: String, session: String): Reply {
        val body = JSONObject().put("message", message).put("context", context).put("session", session)
        val o = JSONObject(post("/chat", body.toString(), 120_000))
        return Reply(o.getString("tone"), o.getString("text"), actions(o.optJSONArray("actions")))
    }

    /** The bridge has already filtered these; anything unknown here is still ignored. */
    private fun actions(a: JSONArray?): List<Action> = List(a?.length() ?: 0) { a!!.getJSONObject(it) }.mapNotNull { o ->
        val text = o.optString("text")
        when (o.optString("type")) {
            "add_todo" -> Action.AddTodo(text, o.optString("date").ifEmpty { null })
            "edit_todo" -> Action.EditTodo(text, o.optString("new_text"))
            "move_todo" -> Action.MoveTodo(text, o.optString("date"))
            "add_routine" -> Action.AddRoutine(text, o.getJSONArray("days").let { d -> List(d.length()) { d.getInt(it) }.toSet() })
            "remove_routine" -> Action.RemoveRoutine(text)
            "log_habit" -> Action.LogHabit(
                text, if (o.has("amount")) o.getDouble("amount") else null, o.optString("date").ifEmpty { null },
                o.optJSONObject("parts")?.let { p -> p.keys().asSequence().associateWith { p.getDouble(it) } }.orEmpty(),
            )
            "money" -> Action.Money(
                o.getDouble("amount"), o.optString("category").ifEmpty { UNSORTED }, o.optString("note"),
                o.optBoolean("income"), o.optString("date").ifEmpty { null },
            )
            "add_measure" -> Action.AddMeasure(
                text, o.getString("unit"),
                o.optJSONArray("parts")?.let { p -> List(p.length()) { p.getString(it) } }.orEmpty(),
                o.optString("part_unit"), o.optBoolean("split"),
            )
            "add_habit" -> Action.AddHabit(text, o.getInt("per_week"))
            "add_taper" -> Action.AddTaper(text, o.getDouble("start"), o.getDouble("step"), o.optString("unit", "dose"))
            "remove_habit" -> Action.RemoveHabit(text)
            "add_plan" -> Action.AddPlan(text)
            "complete_todo" -> Action.CompleteTodo(text)
            "remove_todo" -> Action.RemoveTodo(text)
            "set_alarm" -> Action.SetAlarm(o.getInt("hour"), o.optInt("minute"), o.optBoolean("tomorrow"))
            "log_day" -> Action.LogDay(text)
            "capture" -> Action.Capture(if (o.optString("kind") == "note") "note" else "idea", text, o.optString("title").ifEmpty { null })
            "add_reminder" -> Action.AddReminder(
                text,
                o.optString("date").ifEmpty { null },
                o.optString("time").ifEmpty { null },
                if (o.has("in_minutes")) o.getInt("in_minutes") else null,
            )
            "remove_reminder" -> Action.RemoveReminder(text)
            "listen" -> Action.Listen
            "listen_plan" -> Action.ListenPlan
            "plan_tomorrow" -> Action.PlanTomorrow
            "set_schedule" -> Action.SetSchedule(
                o.getJSONArray("blocks").let { b ->
                    List(b.length()) { b.getJSONObject(it) }.mapNotNull { x ->
                        val start = clockMinute(x.optString("start"))
                        val end = clockMinute(x.optString("end"))
                        if (start != null && end != null) Block(start, end, x.optString("text")) else null
                    }
                },
                o.optString("date").ifEmpty { null },
            )
            else -> null
        }
    }

    /** His voice: raw 22.05 kHz 16-bit mono, made on the PC. */
    fun speech(text: String): ByteArray {
        val c = URL("$base/speak").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 3_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BRIDGE_TOKEN)
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
            if (c.responseCode != 200) error("bridge ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Raw 16 kHz 16-bit mono sound to text. A long dictation can take minutes on the PC. */
    fun transcribe(pcm: InputStream, length: Long): String {
        val c = URL("$base/transcribe").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5_000
            c.readTimeout = 600_000
            c.setFixedLengthStreamingMode(length)
            c.setRequestProperty("Authorization", "Bearer " + BuildConfig.BRIDGE_TOKEN)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { pcm.copyTo(it) }
            if (c.responseCode != 200) error("bridge ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().readText()).getString("text")
        } finally {
            c.disconnect()
        }
    }

    fun syncJournal(store: Store) {
        val lines = store.unsyncedJournal()
        if (lines.isEmpty()) return
        post("/journal", lines.joinToString("\n"), 10_000)
        store.markJournalSynced(lines.size)
    }
}
