package app.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Told apart by adjacent slices and by colorblind eyes on the themes' dark surfaces (checked with the dataviz validator). */
private val SLICE_COLORS = listOf("#3987E5", "#D95926", "#199E70", "#C98500", "#D55181", "#008300", "#9085E9", "#E66767").map(Color::parseColor)

/** An activity keeps its color all day; past eight they share a gray and rely on their written names. */
fun Context.sliceColors(blocks: List<Block>): Map<String, Int> =
    blocks.map { normalize(it.text) }.distinct().mapIndexed { i, name -> name to (SLICE_COLORS.getOrNull(i) ?: theme().muted) }.toMap()

fun Map<String, Int>.of(b: Block): Int = getValue(normalize(b.text))

/** Twelve hours of a day's plan as a clock face. [nowMinute] is the wall clock on the day being shown, otherwise null. */
class DayDial(
    ctx: Context,
    private val blocks: List<Block>,
    private val pm: Boolean,
    private val nowMinute: Int?,
    private val colors: Map<String, Int>,
    private val wakeFrom: Int,
) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val size = MeasureSpec.getSize(w)
        setMeasuredDimension(size, size)
    }

    private fun angle(minute: Int) = minute / 720f * 360f - 90f

    override fun onDraw(c: Canvas) {
        val t = context.theme()
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val numerals = 8.5f * d * 1.5f
        val outer = min(cx, cy) - numerals
        val inner = outer * 0.24f
        val ring = RectF(cx - outer, cy - outer, cx + outer, cy + outer)
        val hole = RectF(cx - inner, cy - inner, cx + inner, cy + inner)

        fill.style = Paint.Style.FILL
        fill.color = faded(t.muted, 34)
        c.drawCircle(cx, cy, outer, fill)

        val state = nowMinute?.let { planAt(blocks, it, wakeFrom) }
        blocks.forEach { b ->
            val color = colors.of(b)
            val over = state != null && b in state.over
            arcsOn(b, pm).forEach { (from, to) ->
                val start = angle(from)
                val sweep = (to - from) / 720f * 360f
                val slice = Path().apply {
                    arcTo(ring, start, sweep, true)
                    arcTo(hole, start + sweep, -sweep)
                    close()
                }
                fill.style = Paint.Style.FILL
                fill.color = faded(color, if (over) 45 else if (state?.current == b) 215 else 145)
                c.drawPath(slice, fill)
                // The gap between neighbors, and a solid rim that carries the color past the dim fill.
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = d * 1.5f
                fill.color = t.bg
                c.drawPath(slice, fill)
                fill.strokeWidth = d * 2.5f
                fill.color = if (over) faded(color, 110) else color
                c.drawArc(RectF(ring).apply { inset(d * 1.2f, d * 1.2f) }, start + 0.8f, (sweep - 1.6f).coerceAtLeast(0.5f), false, fill)
                label(c, b.text, start + sweep / 2, sweep, cx, cy, inner, outer, if (over) t.muted else t.text)
            }
        }

        text.typeface = t.mono(context)
        text.textSize = 8.5f * d * (if (width > d * 220) 1.25f else 1f)
        text.textAlign = Paint.Align.CENTER
        text.color = t.muted
        val at = outer + numerals * 0.5f
        repeat(12) { i ->
            val a = Math.toRadians(angle(i * 60).toDouble())
            val shown = "%02d".format(i + if (pm) 12 else 0)
            c.drawText(shown, cx + at * cos(a).toFloat(), cy + at * sin(a).toFloat() + text.textSize * 0.36f, text)
        }

        val onThisDial = nowMinute != null && (nowMinute >= 720) == pm
        if (onThisDial) {
            val a = Math.toRadians(angle(nowMinute!! % 720).toDouble())
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = d * 2f
            fill.color = t.hot
            c.drawLine(cx, cy, cx + outer * cos(a).toFloat(), cy + outer * sin(a).toFloat(), fill)
            fill.style = Paint.Style.FILL
            c.drawCircle(cx, cy, d * 3.5f, fill)
        }
    }

    /** Written along the slice's radius, always upright; a slice too thin for a line is left to the list below. */
    private fun label(c: Canvas, name: String, mid: Float, sweep: Float, cx: Float, cy: Float, inner: Float, outer: Float, color: Int) {
        val d = resources.displayMetrics.density
        text.typeface = context.theme().body(context)
        text.color = color
        text.textSize = d * (if (width > d * 220) 13f else 10f)
        val midRadius = (inner + outer) * 0.55f
        if (Math.toRadians(sweep.toDouble()) * midRadius < text.textSize * 1.05f) return
        val room = outer - inner - d * 8
        val fitted = TextUtils.ellipsize(name, text, room, TextUtils.TruncateAt.END).toString()
        if (fitted.isEmpty()) return
        val flipped = ((mid % 360f) + 360f) % 360f in 90f..270f
        c.save()
        c.translate(cx, cy)
        c.rotate(if (flipped) mid + 180f else mid)
        val middle = (inner + outer) / 2 * (if (flipped) -1 else 1)
        text.textAlign = Paint.Align.CENTER
        c.drawText(fitted, middle, text.textSize * 0.36f, text)
        c.restore()
    }
}
