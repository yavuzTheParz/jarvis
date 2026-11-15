package app.jarvis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Style { CYBER, HUD, HEX, INK, PIXEL }

/** The color of a mood on the cyberpunk screens: each mood has its own, like the references. */
fun toneColor(tone: String?): Int = when (tone) {
    "positive" -> Color.parseColor("#3DFF8B")
    "caution" -> Color.parseColor("#FCEE0A")
    "warning" -> Color.parseColor("#FF2D3F")
    else -> Color.parseColor("#27E7E0")
}

class Theme(
    val style: Style,
    val name: String,
    val bg: Int,
    val bg2: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val mine: Int,
    private val titleFont: Int,
    private val bodyFont: Int?,
    alt: Int? = null,
    hot: Int? = null,
    good: Int? = null,
    private val monoFont: Int? = null,
) {
    /** Controls and section labels; the same as accent unless a theme has two main colors. */
    val alt = alt ?: accent
    /** The one thing that must stand out, like the open tab. */
    val hot = hot ?: accent
    val good = good ?: accent
    fun title(c: Context): Typeface = c.resources.getFont(titleFont)
    fun body(c: Context): Typeface = bodyFont?.let(c.resources::getFont) ?: Typeface.DEFAULT
    fun mono(c: Context): Typeface = monoFont?.let(c.resources::getFont) ?: body(c)
}

object Themes {
    private val all = listOf(
        Theme(
            Style.CYBER, "Cyberpunk",
            bg = c("#04060B"), bg2 = c("#1C0B11"), surface = c("#0B1218"), text = c("#E9F1F3"), muted = c("#84919C"),
            accent = c("#FF4F4A"), mine = c("#10303A"), titleFont = R.font.chakra_petch_bold, bodyFont = R.font.chakra_petch,
            alt = toneColor("normal"), hot = toneColor("caution"), good = toneColor("positive"), monoFont = R.font.jetbrains_mono,
        ),
        Theme(
            Style.HUD, "Jarvis",
            bg = c("#040A16"), bg2 = c("#0B1530"), surface = c("#0A1B30"), text = c("#D8F3FF"), muted = c("#7296AB"),
            accent = c("#35D0F5"), mine = c("#0E3A52"), titleFont = R.font.chakra_petch, bodyFont = R.font.exo2,
        ),
        Theme(
            Style.INK, "Ink",
            bg = c("#0E0D0C"), bg2 = c("#171412"), surface = c("#1C1815"), text = c("#EFE7DA"), muted = c("#9A8F80"),
            accent = c("#EC5B45"), mine = c("#5A1F18"), titleFont = R.font.garamond, bodyFont = R.font.garamond,
        ),
        Theme(
            Style.HEX, "Hextech",
            bg = c("#010A13"), bg2 = c("#0A1E2C"), surface = c("#0C1A26"), text = c("#F0E6D2"), muted = c("#A09B8C"),
            accent = c("#C8AA6E"), mine = c("#0B4A5A"), titleFont = R.font.cinzel, bodyFont = null,
        ),
        Theme(
            Style.PIXEL, "Pixel",
            bg = c("#14102B"), bg2 = c("#231A4A"), surface = c("#1B1540"), text = c("#FFF6E0"), muted = c("#A79CCF"),
            accent = c("#FFC94D"), mine = c("#3B2E8C"), titleFont = R.font.pixelify_sans, bodyFont = R.font.pixelify_sans,
        ),
    )

    private fun c(s: String) = Color.parseColor(s)

    /** With the theme buttons gone for now, cyberpunk it is: a saved choice could never be changed back. */
    fun current(ctx: Context): Theme = all[0]
}

/** The theme's frame around buttons and speech: the part that gives each theme its character. */
class Frame(private val t: Theme, private val density: Float, private val fill: Int = t.surface, private val seal: Boolean = false, private val edge: Int = t.alt) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(v: Float) = v * density

    override fun draw(c: Canvas) {
        val b = bounds
        val l = b.left.toFloat(); val t2 = b.top.toFloat(); val r = b.right.toFloat(); val u = b.bottom.toFloat()
        when (t.style) {
            Style.CYBER -> {
                // A thin outline with only the top right corner cut.
                val cut = d(11f)
                val path = Path().apply {
                    moveTo(l, t2); lineTo(r - cut, t2); lineTo(r, t2 + cut); lineTo(r, u); lineTo(l, u); close()
                }
                p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = edge; c.drawPath(path, p)
            }
            Style.HUD -> {
                val cut = d(7f)
                val path = Path().apply {
                    moveTo(l + cut, t2); lineTo(r, t2); lineTo(r, u - cut); lineTo(r - cut, u); lineTo(l, u); lineTo(l, t2 + cut); close()
                }
                p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d(1f)
                p.color = Color.argb(120, Color.red(t.accent), Color.green(t.accent), Color.blue(t.accent))
                c.drawPath(path, p)
                // Bright corner ticks, like a targeting bracket.
                val k = d(14f)
                p.strokeWidth = d(2f); p.color = t.accent
                c.drawLine(r - k, t2, r, t2, p); c.drawLine(r, t2, r, t2 + k, p)
                c.drawLine(l, u - k, l, u, p); c.drawLine(l, u, l + k, u, p)
            }
            Style.HEX -> {
                val cut = d(9f)
                val path = Path().apply {
                    moveTo(l + cut, t2); lineTo(r, t2); lineTo(r, u - cut); lineTo(r - cut, u); lineTo(l, u); lineTo(l, t2 + cut); close()
                }
                p.style = Paint.Style.FILL; p.color = fill; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = d(1.5f); p.color = t.accent; c.drawPath(path, p)
                val k = d(5f)
                p.style = Paint.Style.FILL
                c.drawPath(Path().apply { moveTo(r - k, t2); lineTo(r, t2); lineTo(r, t2 + k); close() }, p)
                c.drawPath(Path().apply { moveTo(l, u - k); lineTo(l + k, u); lineTo(l, u); close() }, p)
            }
            Style.INK -> {
                p.style = Paint.Style.FILL; p.color = fill; c.drawRect(l, t2, r, u, p)
                // A brush stroke along the bottom, thick in the middle and tapering out.
                p.color = t.accent
                val y = u - d(1f)
                c.drawPath(Path().apply {
                    moveTo(l, y); quadTo((l + r) / 2, y - d(5f), r, y - d(1f)); quadTo((l + r) / 2, y + d(1f), l, y); close()
                }, p)
                if (seal) {
                    val s = d(7f)
                    c.drawRect(l + d(8f), (t2 + u) / 2 - s / 2, l + d(8f) + s, (t2 + u) / 2 + s / 2, p)
                }
            }
            Style.PIXEL -> {
                val s = d(3f)
                p.style = Paint.Style.FILL; p.color = t.text
                c.drawRect(l + s, t2, r - s, u, p)
                c.drawRect(l, t2 + s, r, u - s, p)
                p.color = fill
                c.drawRect(l + s, t2 + s, r - s, u - s, p)
            }
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Full-screen backdrop behind Jarvis's own screens. */
class Backdrop(private val t: Theme, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun d(v: Float) = v * density
    private fun faded(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        p.style = Paint.Style.FILL
        p.color = Color.BLACK // A shader still takes its alpha from the color left by the last draw.
        p.shader = LinearGradient(0f, 0f, 0f, h, t.bg2, t.bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        when (t.style) {
            Style.CYBER -> {
                // Part of a dial at the bottom.
                p.style = Paint.Style.STROKE
                p.color = faded(t.alt, 28); p.strokeWidth = d(9f)
                c.drawCircle(w * 0.92f, h * 0.9f, w * 0.34f, p)
                p.strokeWidth = d(1f)
                c.drawCircle(w * 0.92f, h * 0.9f, w * 0.44f, p)
            }
            Style.HUD -> {
                // A faint grid and part of a scope in the corner.
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = faded(t.accent, 13)
                val step = d(32f)
                var x = 0f
                while (x < w) { c.drawLine(x, 0f, x, h, p); x += step }
                var y = 0f
                while (y < h) { c.drawLine(0f, y, w, y, p); y += step }
                p.strokeWidth = d(1f); p.color = faded(t.accent, 42)
                listOf(0.28f, 0.36f, 0.5f).forEach { f -> c.drawCircle(w * 0.98f, h * 0.02f, w * f, p) }
            }
            Style.HEX -> {
                // Thin gold border with a diamond crest, like a client window.
                p.style = Paint.Style.STROKE; p.strokeWidth = d(1f); p.color = faded(t.accent, 110)
                val m = d(7f)
                c.drawRect(m, m, w - m, h - m, p)
                p.style = Paint.Style.FILL; p.color = t.accent
                val k = d(5f)
                c.drawPath(Path().apply {
                    moveTo(w / 2, m - k); lineTo(w / 2 + k, m); lineTo(w / 2, m + k); lineTo(w / 2 - k, m); close()
                }, p)
            }
            Style.INK -> {
                // The red sun, half hidden, and a few strokes of mist.
                p.color = faded(t.accent, 46)
                c.drawCircle(w * 0.82f, h * 0.14f, w * 0.34f, p)
                p.color = faded(t.text, 14)
                listOf(0.32f, 0.36f, 0.86f, 0.9f).forEachIndexed { i, f ->
                    val y = h * f
                    c.drawPath(Path().apply {
                        moveTo(if (i % 2 == 0) 0f else w * 0.35f, y)
                        quadTo(w * 0.6f, y - d(7f), w, y - d(2f))
                        quadTo(w * 0.6f, y + d(2f), if (i % 2 == 0) 0f else w * 0.35f, y)
                        close()
                    }, p)
                }
            }
            Style.PIXEL -> {
                // Stars on a fixed seed, so they don't jump between redraws.
                val r = Random(7)
                repeat(70) {
                    p.color = faded(t.text, 40 + r.nextInt(120))
                    val s = d(if (r.nextInt(5) == 0) 3f else 2f)
                    val x = r.nextFloat() * w; val y = r.nextFloat() * h
                    c.drawRect(x, y, x + s, y + s, p)
                }
            }
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Jarvis's only presence on screen: rings turning slowly, colored by the tone of the moment. */
class Reactor(ctx: Context, tone: String) : View(ctx) {
    /** False when someone else paces the frames, as the live wallpaper does: a detached view must not queue its own. */
    var selfDriven = true
    private val cyber = ctx.theme().style == Style.CYBER
    private val accent = ctx.theme().accent
    private val color = if (cyber) toneColor(tone) else when (tone) {
        "positive" -> Color.parseColor("#4DF0B8")
        "caution" -> Color.parseColor("#FFC24D")
        "warning" -> Color.parseColor("#FF5A4D")
        else -> Color.parseColor("#35D0F5")
    }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val poly = Path()
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var clock = 0f
    private var lastFrame = SystemClock.uptimeMillis()
    private var grow = 0f

    private fun rings(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        fun ring(radius: Float, width: Float, alpha: Int, degrees: Float, gaps: Int) {
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            p.strokeWidth = width
            p.color = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
            val sweep = 360f / gaps
            repeat(gaps) { i -> c.drawArc(arc, degrees + i * sweep, sweep * 0.72f, false, p) }
        }
        ring(r * 0.95f, r * 0.018f, 150, t * 8f, 3)
        ring(r * 0.78f, r * 0.05f, 220, -t * 14f, 5)
        ring(r * 0.55f, r * 0.02f, 170, t * 22f, 8)
        ring(r * 0.36f, r * 0.07f, 255, -t * 30f, 3)
        p.style = Paint.Style.FILL
        p.color = Color.argb((190 + 50 * sin(t * 1.5)).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        c.drawCircle(cx, cy, r * 0.14f, p)
        p.style = Paint.Style.STROKE
    }

    /** Hard-edged rings: ticks, broken arcs and a hexagon around a triangle core. */
    private fun hardRings(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, col: Int, strength: Float) {
        fun tint(a: Int) = Color.argb((a * strength).toInt(), Color.red(col), Color.green(col), Color.blue(col))
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.STROKE
        p.color = tint(170); p.strokeWidth = r * 0.012f
        repeat(72) { i ->
            val a = Math.toRadians((i * 5 + t * 6).toDouble())
            val inner = if (i % 6 == 0) r * 0.9f else r * 0.95f
            c.drawLine(cx + inner * cos(a).toFloat(), cy + inner * sin(a).toFloat(), cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), p)
        }
        fun arcs(radius: Float, width: Float, alpha: Int, turn: Float, vararg spans: Pair<Float, Float>) {
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
            p.strokeWidth = width; p.color = tint(alpha)
            spans.forEach { (from, sweep) -> c.drawArc(arc, from + turn, sweep, false, p) }
        }
        arcs(r * 0.8f, r * 0.08f, 255, -t * 14f, 0f to 74f, 84f to 18f, 110f to 7f, 180f to 96f, 284f to 12f, 304f to 30f)
        arcs(r * 0.66f, r * 0.016f, 190, t * 22f, *Array(24) { i -> i * 15f to 9f })
        p.strokeWidth = r * 0.028f; p.color = tint(230); poly.reset()
        repeat(6) { i ->
            val a = Math.toRadians((i * 60 - t * 30).toDouble())
            val x = cx + r * 0.52f * cos(a).toFloat(); val y = cy + r * 0.52f * sin(a).toFloat()
            if (i == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
        }
        poly.close(); c.drawPath(poly, p)
        arcs(r * 0.37f, r * 0.055f, 255, t * 40f, 0f to 92f, 120f to 92f, 240f to 92f)
        p.style = Paint.Style.FILL
        p.color = tint((190 + 60 * sin(t * 2.0)).toInt()); poly.reset()
        repeat(3) { i ->
            val a = Math.toRadians((i * 120 - 90 + t * 18).toDouble())
            val x = cx + r * 0.2f * cos(a).toFloat(); val y = cy + r * 0.2f * sin(a).toFloat()
            if (i == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
        }
        poly.close(); c.drawPath(poly, p)
        p.style = Paint.Style.STROKE
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame) / 1000f
        lastFrame = now
        // Called by name: the rings hold still, grow a touch and the middle lights up red.
        val voice = Live.voice
        val heard = voice == VoiceState.LISTENING
        if (!heard) clock += dt
        grow = (grow + (if (heard) dt else -dt) / GROW_SECONDS).coerceIn(0f, 1f)
        val r = min(width, height) / 2f * (0.96f + 0.04f * grow)
        val t = clock
        if (cyber) {
            // A red-and-cyan ghost that jumps for a moment every few seconds, like a bad signal.
            val d = resources.displayMetrics.density
            val glitch = t % 4f < 0.14f
            val off = d * (if (glitch) 5f else 1.5f)
            val ghost = if (color == toneColor("normal")) accent else toneColor("normal")
            hardRings(c, width / 2f - off, height / 2f, r, t, ghost, 0.55f)
            hardRings(c, width / 2f + (if (glitch) off else 0f), height / 2f, r, t, color, 1f)
        } else {
            rings(c, width / 2f, height / 2f, r, t)
        }
        if (voice == VoiceState.LISTENING || voice == VoiceState.THINKING || grow > 0f) redCore(c, r, now, heard)
        if (selfDriven) postInvalidateDelayed(FRAME_MS)
    }

    /** Steady while he listens; a slow pulse while he works out the answer. */
    private fun redCore(c: Canvas, r: Float, now: Long, steady: Boolean) {
        val cx = width / 2f
        val cy = height / 2f
        val strength = if (steady) 1f else 0.55f + 0.45f * sin(now / 250.0).toFloat()
        core.shader = RadialGradient(cx, cy, r * 0.34f, intArrayOf(Color.argb((230 * strength).toInt(), 255, 30, 30), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.34f, core)
        core.shader = null
        core.color = Color.argb((255 * strength).toInt(), 255, 40, 35)
        c.drawCircle(cx, cy, r * 0.17f, core)
    }

    companion object {
        const val FRAME_MS = 50L
        private const val GROW_SECONDS = 0.18f
    }
}
