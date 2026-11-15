package app.jarvis

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reminders on the phone itself, so they go off with the PC off too. Only the soonest one is
 * handed to Android; when it goes off, everything due is delivered and the next one is set.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private var lastId = 0L

    fun add(ctx: Context, text: String, at: LocalDateTime, event: LocalDateTime? = null, task: String? = null): Reminder {
        val store = Store(ctx)
        // Unique even when two are made in the same millisecond, as a reply with several actions can.
        val id = maxOf(System.currentTimeMillis(), lastId + 1).also { lastId = it }
        val r = Reminder(id, text.trim(), at, event, task)
        store.saveReminders(store.reminders() + r)
        changed(ctx)
        return r
    }

    fun remove(ctx: Context, id: Long) {
        val store = Store(ctx)
        store.saveReminders(store.reminders().filter { it.id != id })
        changed(ctx)
    }

    /** The ones waiting for this task, so its menu can show and clear them. */
    fun forTask(ctx: Context, task: String): List<Reminder> =
        pendingReminders(Store(ctx).reminders(), LocalDateTime.now()).filter { it.task == task }

    private fun changed(ctx: Context) {
        sync(ctx)
        Live.tasksChanged()
    }

    /** Delivers whatever is due and sets the alarm for the next one. Safe to call any time. */
    fun sync(ctx: Context) {
        val store = Store(ctx)
        val now = LocalDateTime.now()
        val all = store.reminders()
        val due = dueReminders(all, now)
        if (due.isNotEmpty()) {
            store.saveReminders(all - due.toSet())
            due.forEach { deliver(ctx, it) }
            Live.tasksChanged()
        }
        schedule(ctx, pendingReminders(store.reminders(), now).firstOrNull())
    }

    private fun schedule(ctx: Context, next: Reminder?) {
        val alarms = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (next == null) return alarms.cancel(pi)
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // Without the exact-alarm right Android may run it some minutes late, but it still runs.
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun deliver(ctx: Context, r: Reminder) {
        val line = reminderLine(r)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, r.id.toInt(), Intent(ctx, RemindersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            nm.notify(
                r.id.toInt(),
                Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("JARVIS // REMINDER")
                    .setContentText(r.text)
                    .setStyle(Notification.BigTextStyle().bigText(line))
                    .setCategory(Notification.CATEGORY_REMINDER)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
        val chat = ChatLine("jarvis", "caution", line)
        Live.line(chat, Store(ctx).addChat(chat))
        // Out loud only when he is free: never over the user talking or over his own answer.
        if (Live.voice == VoiceState.IDLE) VoiceService.current?.speak(line)
    }
}

/** The alarm going off, the phone starting up, or the app being updated: all mean "check again". */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) = Reminders.sync(ctx)
}
