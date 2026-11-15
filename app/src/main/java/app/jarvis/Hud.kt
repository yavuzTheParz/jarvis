package app.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Minutes of screen time at which the meter turns from cyan to yellow, and from yellow to red. */
const val SCREEN_WARN_MIN = 120L
const val SCREEN_HOT_MIN = 240L

/** Which of the three colors a day of [minutes] on the screen gets: 0 calm, 1 warning, 2 too much. */
fun screenLevel(minutes: Long): Int = when {
    minutes < SCREEN_WARN_MIN -> 0
    minutes < SCREEN_HOT_MIN -> 1
    else -> 2
}

/** "2S 14D" as pieces: the numbers drawn large, the letters small. Under an hour only the minutes. */
fun screenPieces(ms: Long): List<Pair<String, Boolean>> {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h == 0L) listOf("$m" to true, "M" to false)
    else listOf("$h" to true, "H" to false, " " to false, m.toString().padStart(2, '0') to true, "M" to false)
}

/** "1S 05D" or "42D": an app's time beside its bar. */
fun appTimeText(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}M" else "${minutes / 60}H ${(minutes % 60).toString().padStart(2, '0')}M"
}

/**
 * Today's screen time as a readout: the total on top decodes from scrambled digits when it appears,
 * and under it one bar per app, longest first, each anchored to the right edge and reaching left.
 * The color climbs from cyan to yellow to red, and past four hours the readout starts to glitch.
 */
class ScreenMeter(ctx: Context) : View(ctx) {
    private val t = ctx.theme()
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = t.title(ctx)
    private val small = t.mono(ctx)
    private var apps: List<Pair<String, Long>>? = null
    private var denied = false
    private var shownAt = SystemClock.uptimeMillis()
    private var nextGlitch = 0L

    /** Today's apps by name with their time, longest first, or null when usage access isn't granted. */
    fun show(byApp: List<Pair<String, Long>>?) {
        apps = byApp
        denied = byApp == null
        shownAt = SystemClock.uptimeMillis()
        nextGlitch = shownAt + 2_500
        invalidate()
    }

    private val total get() = apps?.sumOf { it.second } ?: 0L

    private fun levelColor(level: Int) = when (level) {
        0 -> t.alt
        1 -> t.hot
        else -> t.accent
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val age = now - shownAt
        val level = screenLevel(total / 60_000)
        val glitching = level == 2 && !denied && apps != null && now in nextGlitch..(nextGlitch + GLITCH_MS)
        if (now > nextGlitch + GLITCH_MS) nextGlitch = now + 3_000 + Random.nextLong(3_000)
        if (glitching) c.glitched(width, height, 0.9f, d, listOf(t.hot, t.alt)) { paint(c, age, level, now) }
        else paint(c, age, level, now)
        if (isAttachedToWindow) postInvalidateDelayed(if (age < INTRO_MS || level == 2) FRAME_MS else BLINK_MS / 2)
    }

    private fun paint(c: Canvas, age: Long, level: Int, now: Long) {
        val color = levelColor(level)
        readout(c, age, color, now)
        bars(c, age)
    }

    /** The total, right-aligned, decoding character by character from the left. */
    private fun readout(c: Canvas, age: Long, color: Int, now: Long) {
        val baseline = d * 21
        val pieces = when {
            denied -> listOf("GRANT" to false)
            apps == null -> listOf("--" to true, "M" to false)
            else -> screenPieces(total)
        }
        // While decoding, each digit that hasn't settled yet shows a random one, changing every frame.
        val noise = Random(now / FRAME_MS)
        var settled = (age / SETTLE_MS).toInt()
        val shown = pieces.map { (s, large) ->
            val out = s.map { ch ->
                if (!large || !ch.isDigit()) ch
                else if (settled-- > 0 && apps != null) ch else ('0' + noise.nextInt(10))
            }.joinToString("")
            out to large
        }
        fun font(large: Boolean) {
            p.typeface = if (large) big else small
            p.textSize = if (large) d * 21 else d * 9.5f
            p.isFakeBoldText = !large
        }
        // A caption on the left says what the number above the bars is.
        font(false)
        p.style = Paint.Style.FILL
        p.color = t.muted
        c.drawText(if (denied) "SCREEN" else "TOTAL", 0f, baseline - d * 1.5f, p)
        var x = width - d * 6
        val placed = shown.reversed().map { (s, large) ->
            font(large)
            // Small letters keep clear of the ghost that trails the number before them.
            x -= p.measureText(s) + (if (large) 0f else d * 2)
            Triple(s, large, x + (if (large) 0f else d * 2))
        }
        p.style = Paint.Style.FILL
        // The ghost sits beside the print in the other color, like the home clock.
        for ((s, large, at) in placed) {
            font(large)
            p.color = if (denied) t.hot else color
            if (large && t.style == Style.CYBER) p.setShadowLayer(1f, d * 2, 0f, if (color == t.alt) t.accent else t.alt)
            c.drawText(s, at, if (large) baseline else baseline - d * 1.5f, p)
            p.clearShadowLayer()
        }
    }

    /**
     * One row per app, as many as fit: the bar's foot is the right edge and it reaches left, as long
     * as the app's share of the busiest one. The name and time sit in a lane on the left.
     */
    private fun bars(c: Canvas, age: Long) {
        val top = d * 30
        val row = d * 16
        val thick = d * 9
        val list = apps.orEmpty()
        val count = minOf(list.size, ((height - top) / row).toInt().coerceAtLeast(0))
        p.typeface = small
        p.textSize = d * 9f
        p.isFakeBoldText = false
        p.style = Paint.Style.FILL
        if (denied || apps == null || count == 0) {
            p.color = faded(t.muted, 90)
            c.drawRect(width * 0.35f, top + thick / 2, width.toFloat(), top + thick / 2 + d, p)
            return
        }
        val rows = list.take(count)
        val labels = rows.map { (name, ms) -> name.latinCaps().take(NAME_CHARS) to appTimeText(ms) }
        // The lane is as wide as the widest label, so every bar starts from the same edge.
        val lane = labels.maxOf { (n, tm) -> p.measureText(n) + d * 4 + p.measureText(tm) } + d * 6
        val room = (width - lane).coerceAtLeast(d * 12)
        val peak = rows.first().second.coerceAtLeast(1L).toFloat()
        rows.forEachIndexed { i, (_, ms) ->
            val y = top + i * row
            // Each bar slides out in steps, a little after the one above it.
            val rise = ((age - i * 60L) / RISE_MS.toFloat()).coerceIn(0f, 1f).let { floor(it * 6f) / 6f }
            val minutes = ms / 60_000
            // The color says how long that app alone took: cyan, yellow past half an hour, red past an hour.
            val col = levelColor(if (minutes < 30) 0 else if (minutes < 60) 1 else 2)
            val len = maxOf(d * 2, room * (ms / peak) * rise)
            p.color = faded(col, 60)
            c.drawRect(width - room, y + thick / 2 - d * 0.5f, width.toFloat(), y + thick / 2 + d * 0.5f, p)
            p.color = faded(col, 230)
            c.drawRect(width - len, y, width.toFloat(), y + thick, p)
            val (name, time) = labels[i]
            p.color = t.text
            c.drawText(name, 0f, y + thick - d * 0.5f, p)
            p.color = col
            c.drawText(time, p.measureText(name) + d * 4, y + thick - d * 0.5f, p)
        }
    }

    private companion object {
        const val FRAME_MS = 45L
        const val BLINK_MS = 600L
        const val SETTLE_MS = 110L
        const val INTRO_MS = 900L
        const val RISE_MS = 380L
        const val GLITCH_MS = 220L
        const val NAME_CHARS = 8
    }
}

/**
 * The reminders bell: an angular bell with scanlines inside and a cyan ghost. While reminders wait it
 * glows, swings now and then with sound marks on both sides, wears a notched badge with the count,
 * and shows when the next one goes off underneath.
 */
class Bell(ctx: Context, private val count: Int, private val next: LocalDateTime? = null) : View(ctx) {
    private val t = ctx.theme()
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.MITER }
    private var ringAt = SystemClock.uptimeMillis() + 700
    private val ringer = Runnable { invalidate() }

    override fun onDetachedFromWindow() {
        removeCallbacks(ringer)
        super.onDetachedFromWindow()
    }

    private fun bellPath(cx: Float, top: Float, u: Float, w: Float) = Path().apply {
        moveTo(cx - w * 0.55f, top); lineTo(cx + w * 0.55f, top); lineTo(cx + w * 0.8f, top + (u - top) * 0.45f)
        lineTo(cx + w, u); lineTo(cx - w, u); lineTo(cx - w * 0.8f, top + (u - top) * 0.45f); close()
    }

    override fun onDraw(c: Canvas) {
        val live = count > 0
        val now = SystemClock.uptimeMillis()
        val bellBox = height - (if (live && next != null) d * 13 else 0f)
        val cx = width * 0.42f; val top = bellBox * 0.22f; val u = bellBox * 0.74f; val w = width * 0.25f
        val ring = if (live) ((now - ringAt) / RING_MS.toFloat()).takeIf { it in 0f..1f } else null
        if (live && now > ringAt + RING_MS) ringAt = now + 4_500 + Random.nextLong(4_000)
        val swing = ring?.let { (16 * sin(it * 5 * PI) * (1 - it)).toFloat() } ?: 0f
        val color = if (live) t.accent else faded(t.alt, 150)
        val path = bellPath(cx, top, u, w)

        c.save()
        c.rotate(swing, cx, top - d * 3)
        if (live && t.style == Style.CYBER) {
            // The misprint: a cyan copy a step to the left.
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.2f; p.color = faded(t.alt, 140)
            c.save(); c.translate(-d * 2, 0f); c.drawPath(path, p); c.restore()
        }
        // Scanlines inside the bell.
        c.save(); c.clipPath(path)
        p.style = Paint.Style.FILL; p.color = faded(color, if (live) 70 else 35)
        var y = top
        while (y < u) { c.drawRect(cx - w, y, cx + w, y + d * 0.8f, p); y += d * 2.6f }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.8f; p.color = color
        if (live) p.setShadowLayer(d * 4, 0f, 0f, faded(t.accent, 170))
        c.drawPath(path, p)
        c.drawLine(cx, top - d * 3, cx, top, p)
        c.drawLine(cx - w * 1.15f, u, cx + w * 1.15f, u, p)
        p.clearShadowLayer()
        c.restore()

        // The clapper, a small diamond that swings the other way.
        val kx = cx - swing * d * 0.25f; val ky = u + d * 4
        p.style = Paint.Style.FILL; p.color = color
        c.drawPath(Path().apply { moveTo(kx, ky - d * 2.2f); lineTo(kx + d * 2.2f, ky); lineTo(kx, ky + d * 2.2f); lineTo(kx - d * 2.2f, ky); close() }, p)

        // Sound marks while it rings: two chevrons on each side, fading out.
        if (ring != null) {
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.4f
            p.color = faded(t.hot, (255 * (1 - ring)).toInt())
            val mid = (top + u) / 2
            for (k in 1..2) {
                val off = w + d * (3 + k * 3.5f) + ring * d * 3
                val arm = d * (2.5f + k)
                for (side in listOf(-1f, 1f)) {
                    val x = cx + side * off
                    c.drawPath(Path().apply { moveTo(x - side * arm * 0.6f, mid - arm); lineTo(x, mid); lineTo(x - side * arm * 0.6f, mid + arm) }, p)
                }
            }
        }

        if (live) {
            badge(c)
            next?.let { label(c, it) }
        }
        if (live) {
            if (ring != null) postInvalidateDelayed(FRAME_MS)
            else { removeCallbacks(ringer); postDelayed(ringer, (ringAt - now).coerceAtLeast(FRAME_MS)) }
        }
    }

    /** A filled notched badge in the top right corner with how many are waiting. */
    private fun badge(c: Canvas) {
        val s = d * 16; val r = width - d; val b = d + s
        p.style = Paint.Style.FILL; p.color = t.hot
        c.drawPath(Path().apply {
            moveTo(r - s, d); lineTo(r - d * 4, d); lineTo(r, d + d * 4); lineTo(r, b); lineTo(r - s, b); close()
        }, p)
        p.color = t.bg
        p.typeface = t.mono(context); p.isFakeBoldText = true; p.textSize = d * 10.5f; p.textAlign = Paint.Align.CENTER
        c.drawText(if (count > 9) "9+" else "$count", r - s / 2, b - d * 4.3f, p)
        p.textAlign = Paint.Align.LEFT; p.isFakeBoldText = false
    }

    /** When the next one goes off: "21.15" today, "YRN 09.00" tomorrow, the date further away. */
    private fun label(c: Canvas, at: LocalDateTime) {
        p.style = Paint.Style.FILL; p.color = t.alt
        p.typeface = t.mono(context); p.textSize = d * 9; p.textAlign = Paint.Align.CENTER
        c.drawText(nextLabel(at, LocalDateTime.now()), width / 2f, height - d * 2, p)
        p.textAlign = Paint.Align.LEFT
    }

    private companion object {
        const val FRAME_MS = 40L
        const val RING_MS = 1_100L
    }
}

/** The bell's line under it for a reminder at [at]. */
fun nextLabel(at: LocalDateTime, now: LocalDateTime): String {
    val time = "%02d.%02d".format(at.hour, at.minute)
    return when (at.toLocalDate()) {
        now.toLocalDate() -> time
        now.toLocalDate().plusDays(1) -> "TMRW $time"
        else -> "%02d.%02d".format(at.dayOfMonth, at.monthValue)
    }
}
