package app.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** One day of a habit's week: filled and glowing when done, an outline when missed, faint when still to come. Today wears brackets. */
class DayBox(ctx: Context, private val done: Boolean, private val future: Boolean, private val today: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, minOf(width, context.dp(30)))
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val t = context.theme()
        val s = height - d * 6
        val l = (width - s) / 2f; val top = d * 3; val r = l + s; val u = top + s
        when {
            done -> {
                p.style = Paint.Style.FILL; p.color = faded(t.good, 60)
                c.drawRect(l, top, r, u, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.6f; p.color = t.good
                p.setShadowLayer(d * 5, 0f, 0f, t.good)
                c.drawRect(l, top, r, u, p)
                val m = s * 0.28f
                c.drawPath(Path().apply {
                    moveTo(l + m, top + s * 0.52f); lineTo(l + s * 0.44f, u - m); lineTo(r - m, top + m)
                }, p)
                p.clearShadowLayer()
            }
            future -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = faded(t.muted, 60)
                c.drawRect(l, top, r, u, p)
            }
            else -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.2f; p.color = faded(t.alt, 120)
                c.drawRect(l, top, r, u, p)
            }
        }
        if (today) {
            // Corner brackets just outside the box, in the accent color.
            val o = d * 2.5f; val k = d * 5
            val a = l - o; val b = top - o + d; val e = r + o; val f = u + o - d
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.5f; p.color = t.accent
            c.drawLine(a, b, a + k, b, p); c.drawLine(a, b, a, b + k, p)
            c.drawLine(e, b, e - k, b, p); c.drawLine(e, b, e, b + k, p)
            c.drawLine(a, f, a + k, f, p); c.drawLine(a, f, a, f - k, p)
            c.drawLine(e, f, e - k, f, p); c.drawLine(e, f, e, f - k, p)
        }
    }
}

/** A day of a cut-down habit as a column: how much was had against [top], with that day's limit as a tick across, if it has one. */
class DoseBar(ctx: Context, private val had: Double, private val limit: Double?, private val top: Double, private val today: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val t = context.theme()
        val h = height.toFloat()
        val w = minOf(width * 0.5f, d * 16)
        val l = (width - w) / 2f; val r = l + w
        fun y(v: Double) = h - (h - d * 2) * (v / top).toFloat().coerceIn(0f, 1f)
        val over = limit != null && had > limit + 1e-9
        val color = if (over) t.hot else if (limit == null) t.accent else t.good
        // The empty well, then the fill.
        p.style = Paint.Style.FILL; p.color = faded(t.alt, 22)
        c.drawRect(l, d * 2, r, h, p)
        if (had > 0) {
            p.color = faded(color, if (today) 220 else 150)
            if (today) p.setShadowLayer(d * 5, 0f, 0f, color)
            c.drawRect(l, y(had), r, h, p)
            p.clearShadowLayer()
        }
        if (limit == null) return
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.5f; p.color = if (today) t.accent else faded(t.muted, 170)
        val ly = y(limit)
        c.drawLine(l - d * 4, ly, r + d * 4, ly, p)
    }
}

/**
 * The long-press page's chart: a bar per bucket against a faint grid, the current one lit. A taper's limits run
 * across as ticks, a weekly goal as a dashed line. Touching a bar picks it; [picked] hears which, -1 for none.
 */
class StatChart(
    ctx: Context,
    private val bars: List<Bucket>,
    private val goal: Double?,
    private val picked: (Int) -> Unit,
) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pick = -1
    private val labelSpace get() = resources.displayMetrics.density * 16

    private fun top() = maxOf(bars.maxOfOrNull { maxOf(it.value, it.limit ?: 0.0) } ?: 0.0, goal ?: 0.0).let { if (it <= 0) 1.0 else it * 1.12 }

    override fun onDraw(c: Canvas) {
        if (bars.isEmpty()) return
        val d = resources.displayMetrics.density
        val t = context.theme()
        val h = height - labelSpace
        val slot = width.toFloat() / bars.size
        val bw = minOf(slot * 0.62f, d * 22)
        val top = top()
        fun y(v: Double) = h - (h - d * 4) * (v / top).toFloat().coerceIn(0f, 1f)
        // Grid: four faint lines.
        p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.6f; p.color = faded(t.alt, 40)
        for (i in 1..4) c.drawLine(0f, h * i / 4f, width.toFloat(), h * i / 4f, p)
        goal?.let { g ->
            p.color = faded(t.good, 170); p.strokeWidth = d * 1.2f
            val gy = y(g)
            var x = 0f
            while (x < width) { c.drawLine(x, gy, minOf(x + d * 6, width.toFloat()), gy, p); x += d * 10 }
        }
        // Only every so many labels fit: a month of days shows one in five.
        val every = when { bars.size > 20 -> 5; bars.size > 13 -> 2; else -> 1 }
        p.textSize = d * 9; p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.MONOSPACE
        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            val l = cx - bw / 2; val r = cx + bw / 2
            val over = b.limit != null && b.value > b.limit + 1e-9
            val color = when { over -> t.hot; b.limit != null -> t.good; else -> t.accent }
            val lit = i == pick || (pick < 0 && b.current)
            p.style = Paint.Style.FILL
            p.color = faded(t.alt, 18)
            c.drawRect(l, d * 4, r, h, p)
            if (b.value > 0) {
                p.color = faded(color, if (lit) 235 else 140)
                if (lit) p.setShadowLayer(d * 6, 0f, 0f, color)
                c.drawRect(l, y(b.value), r, h, p)
                p.clearShadowLayer()
            }
            b.limit?.let { lim ->
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.4f; p.color = faded(t.muted, 190)
                c.drawLine(l - d * 2, y(lim), r + d * 2, y(lim), p)
            }
            if (i % every == 0 || i == bars.lastIndex && bars.size <= 13 || lit) {
                p.style = Paint.Style.FILL
                p.color = if (lit) t.accent else t.muted
                c.drawText(b.label, cx, height - d * 3, p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bars.isEmpty()) return false
        val i = (e.x / (width.toFloat() / bars.size)).toInt().coerceIn(0, bars.lastIndex)
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (i != pick) { pick = i; picked(i); invalidate() }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Line colours that read the same on every theme: spending and calories red, income and protein green. */
const val LINE_RED = 0xFFFF4D5E.toInt()
const val LINE_GREEN = 0xFF3DDC84.toInt()
const val LINE_AMBER = 0xFFFFB020.toInt()
const val LINE_BLUE = 0xFF4DA3FF.toInt()

/** One line of a [LineChart]. With [gaps], a bucket at zero is a day nothing was logged, not a zero: it gets no point. */
class Line(val name: String, val unit: String, val color: Int, val points: List<Bucket>, val gaps: Boolean)

/**
 * The long-press page's chart for amounts: each series a smooth line through its points with a soft fill under it.
 * Lines in the same unit share a scale; a second unit (calories against grams) gets its own, its top marked on the
 * right. Touching picks the nearest point across all lines; [picked] hears which, -1 for none.
 */
class LineChart(ctx: Context, private val lines: List<Line>, private val picked: (Int) -> Unit) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pick = -1
    private val n get() = lines.maxOfOrNull { it.points.size } ?: 0
    private val d get() = resources.displayMetrics.density
    private val side get() = d * 10

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // the glow under each line needs it

    private fun x(i: Int) = if (n <= 1) width / 2f else side + (width - side * 2) * i / (n - 1f)

    override fun onDraw(c: Canvas) {
        if (n == 0) return
        val t = context.theme()
        val h = height - d * 16
        val roof = d * 18
        val units = lines.map { it.unit }.distinct()
        val tops = units.associateWith { u ->
            (lines.filter { it.unit == u }.flatMap { l -> l.points.map { it.value } }.maxOrNull() ?: 0.0).let { if (it <= 0) 1.0 else it * 1.08 }
        }
        fun y(l: Line, v: Double) = h - (h - roof) * (v / tops.getValue(l.unit)).toFloat().coerceIn(0f, 1f)

        p.style = Paint.Style.STROKE; p.strokeWidth = d * 0.6f; p.color = faded(t.alt, 40); p.pathEffect = null
        for (i in 0..4) { val gy = roof + (h - roof) * i / 4f; c.drawLine(0f, gy, width.toFloat(), gy, p) }

        val at = if (pick >= 0) pick else lines.firstOrNull()?.points?.indexOfLast { it.current } ?: -1
        if (at >= 0) {
            p.color = faded(t.text, 90); p.strokeWidth = d; p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(d * 3, d * 3), 0f)
            c.drawLine(x(at), roof - d * 4, x(at), h, p)
            p.pathEffect = null
        }

        lines.forEach { l ->
            val pts = l.points.indices.filter { !(l.gaps && l.points[it].value <= 0) }
            if (pts.isEmpty()) return@forEach
            val path = Path()
            pts.forEachIndexed { k, i ->
                val px = x(i); val py = y(l, l.points[i].value)
                if (k == 0) path.moveTo(px, py) else {
                    val j = pts[k - 1]
                    val mx = (x(j) + px) / 2
                    path.cubicTo(mx, y(l, l.points[j].value), mx, py, px, py)
                }
            }
            val area = Path(path).apply { lineTo(x(pts.last()), h); lineTo(x(pts.first()), h); close() }
            p.style = Paint.Style.FILL
            p.shader = android.graphics.LinearGradient(0f, roof, 0f, h, faded(l.color, 80), faded(l.color, 0), android.graphics.Shader.TileMode.CLAMP)
            c.drawPath(area, p)
            p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = d * 2.2f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
            p.color = l.color
            p.setShadowLayer(d * 5, 0f, 0f, faded(l.color, 200))
            c.drawPath(path, p)
            p.clearShadowLayer()
            // Points: small rings, the picked one larger and filled.
            val few = l.points.size <= 31
            pts.forEach { i ->
                val big = i == at
                if (!few && !big) return@forEach
                val px = x(i); val py = y(l, l.points[i].value)
                p.style = Paint.Style.FILL; p.color = if (big) l.color else t.bg
                c.drawCircle(px, py, if (big) d * 4.5f else d * 2.6f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d * 1.6f; p.color = l.color
                c.drawCircle(px, py, if (big) d * 4.5f else d * 2.6f, p)
            }
        }

        // Each scale's top, in the colour of its first line: the first unit on the left, a second on the right.
        p.style = Paint.Style.FILL; p.textSize = d * 9; p.typeface = android.graphics.Typeface.MONOSPACE
        units.take(2).forEachIndexed { k, u ->
            p.color = faded(lines.first { it.unit == u }.color, 220)
            p.textAlign = if (k == 0) Paint.Align.LEFT else Paint.Align.RIGHT
            c.drawText("${amountText(tops.getValue(u))} $u", if (k == 0) 0f else width.toFloat(), d * 10, p)
        }

        val labels = lines.maxBy { it.points.size }.points
        val every = when { n > 20 -> 5; n > 13 -> 2; else -> 1 }
        p.textAlign = Paint.Align.CENTER
        labels.forEachIndexed { i, b ->
            if (i % every == 0 || i == at) {
                p.color = if (i == at) t.accent else t.muted
                c.drawText(b.label, x(i), height - d * 3, p)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (n == 0) return false
        val i = if (n <= 1) 0 else ((e.x - side) / ((width - side * 2) / (n - 1f)) + 0.5f).toInt().coerceIn(0, n - 1)
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (i != pick) { pick = i; picked(i); invalidate() }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** One part's share as a thin bar under its name; [fraction] of the widest. */
class ShareBar(ctx: Context, private val fraction: Float, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        p.color = faded(context.theme().alt, 30)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.color = color
        p.setShadowLayer(d * 4, 0f, 0f, color)
        c.drawRect(0f, 0f, width * fraction.coerceIn(0f, 1f), height.toFloat(), p)
        p.clearShadowLayer()
    }
}
