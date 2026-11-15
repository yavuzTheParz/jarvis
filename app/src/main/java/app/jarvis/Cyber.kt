package app.jarvis

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.random.Random

/** Pieces of the cyberpunk look. Those shared with other themes fall back to the theme's own colors. */

private val UI_LOCALE = Locale.ENGLISH

fun String.caps() = uppercase(UI_LOCALE)

/** Upper case for names, such as app names, independent of the phone's locale. */
fun String.latinCaps() = uppercase(Locale.ROOT)

fun faded(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

fun Context.cyber() = theme().style == Style.CYBER

/** A notched label: the small boxes that name each section. */
fun Context.tag(s: String, color: Int = theme().alt) = TextView(this).apply {
    text = s.caps()
    typeface = theme().mono(context)
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    letterSpacing = 0.08f
    setPadding(dp(8), dp(3), dp(14), dp(3))
    background = frame(theme().bg, edge = color)
}

fun Context.mono(s: String, size: Float = 11f, color: Int? = null) = label(s, size, color).apply {
    typeface = theme().mono(context)
    letterSpacing = 0.04f
}

/** A small control: a framed chip here, the underlined text elsewhere. */
fun Context.chip(s: String, onClick: () -> Unit): Button {
    if (!cyber()) return barButton(s, onClick)
    return Button(this).apply {
        text = s.caps()
        isAllCaps = false
        setTypeface(theme().mono(context), android.graphics.Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        stateListAnimator = null
        setTextColor(theme().alt)
        background = Glow(theme().alt, resources.displayMetrics.density)
        setPadding(dp(4), dp(11), dp(4), dp(11))
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { onClick() }
    }
}

/** Marks only the four corners of a section, leaving its sides open. */
class Brackets(private val color: Int, private val density: Float, private val arm: Float = 9f) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(c: Canvas) {
        val k = density * arm
        val m = density
        val l = bounds.left + m; val t = bounds.top + m; val r = bounds.right - m; val u = bounds.bottom - m
        p.strokeWidth = density * 1.5f
        p.color = color
        c.drawLine(l, t, l + k, t, p); c.drawLine(l, t, l, t + k, p)
        c.drawLine(r, t, r - k, t, p); c.drawLine(r, t, r, t + k, p)
        c.drawLine(l, u, l + k, u, p); c.drawLine(l, u, l, u - k, p)
        c.drawLine(r, u, r - k, u, p); c.drawLine(r, u, r, u - k, p)
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A magnifier with an eight-sided lens and a blocky handle. */
class Magnifier(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width * 0.45f; val cy = height * 0.45f; val r = d * 7.5f; val k = r * 0.42f
        p.color = color
        p.strokeWidth = d * 2f
        c.drawPath(Path().apply {
            moveTo(cx - k, cy - r); lineTo(cx + k, cy - r); lineTo(cx + r, cy - k); lineTo(cx + r, cy + k)
            lineTo(cx + k, cy + r); lineTo(cx - k, cy + r); lineTo(cx - r, cy + k); lineTo(cx - r, cy - k); close()
        }, p)
        p.strokeWidth = d * 3.4f
        val from = r * 0.82f; val to = r * 1.75f
        c.drawLine(cx + from, cy + from, cx + to, cy + to, p)
        p.strokeWidth = d
        c.drawLine(cx - k, cy - r * 0.45f, cx + r * 0.2f, cy - r * 0.45f, p)
    }
}

/** The search bar: a solid block with the magnifier, joined to an outlined bar with a few level marks. */
fun Context.searchBar(input: android.widget.EditText): View {
    if (!cyber()) return input
    input.background = null
    return LinearLayout(this).apply {
        val color = theme().accent
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(theme().bg)
            setStroke(dp(1) + 1, color)
        }
        gravity = Gravity.CENTER_VERTICAL
        addView(Magnifier(context, theme().bg).apply {
            setBackgroundColor(color)
            setOnClickListener { input.requestFocus() }
        }, LinearLayout.LayoutParams(dp(44), -1))
        addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Segments(context, 1f, theme().alt), LinearLayout.LayoutParams(dp(52), dp(14)).apply { rightMargin = dp(10) })
    }
}

/** The small controls' outline: a plain thin rectangle with a faint glow, quieter than a panel. */
class Glow(private val color: Int, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(c: Canvas) {
        val m = density * 3 // Room for the glow, which would otherwise be cut off at the view's edge.
        p.strokeWidth = density * 0.75f
        p.color = faded(color, 210)
        p.setShadowLayer(density * 3, 0f, 0f, faded(color, 170))
        c.drawRect(bounds.left + m, bounds.top + m, bounds.right - m, bounds.bottom - m, p)
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A sub tab's underline: a thin, uneven edge, drawn a little differently for each [shape] so neighbours don't match.
 * The open tab also gets a thick stub where its edge steps down.
 */
class Rail(private val color: Int, private val density: Float, private val open: Boolean, private val shape: Int = 0) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun draw(c: Canvas) {
        val d = density
        val l = bounds.left + d; val r = bounds.right - d; val u = bounds.bottom - d * 1.5f
        val w = r - l
        val edge = Path()
        val stubFrom: Float
        val stubTo: Float
        when (shape % 3) {
            0 -> { // Broken left end, stepping down towards the right.
                val step = l + w * 0.68f
                edge.moveTo(l + d * 2.5f, u - d * 15)
                edge.lineTo(l + d * 2.5f, u - d * 10)
                edge.lineTo(l, u - d * 7.5f)
                edge.lineTo(l, u - d * 4.5f)
                edge.lineTo(l + d * 2.5f, u - d * 2)
                edge.lineTo(step, u - d * 2)
                edge.lineTo(step + d * 2, u)
                edge.lineTo(r - d * 2.5f, u)
                edge.lineTo(r, u - d * 2.5f)
                edge.lineTo(r, u - d * 8)
                stubFrom = step + d * 3
                stubTo = r - d * 3
            }
            1 -> { // Broken right end, low on the left.
                val step = l + w * 0.3f
                edge.moveTo(l, u - d * 8)
                edge.lineTo(l, u - d * 2.5f)
                edge.lineTo(l + d * 2.5f, u)
                edge.lineTo(step, u)
                edge.lineTo(step + d * 2, u - d * 2)
                edge.lineTo(r - d * 2.5f, u - d * 2)
                edge.lineTo(r, u - d * 4.5f)
                edge.lineTo(r, u - d * 7.5f)
                edge.lineTo(r - d * 2.5f, u - d * 10)
                edge.lineTo(r - d * 2.5f, u - d * 15)
                stubFrom = l + d * 3
                stubTo = step - d
            }
            else -> { // Dips in the middle, with one long cut corner.
                val from = l + w * 0.36f
                val to = l + w * 0.64f
                edge.moveTo(l, u - d * 12)
                edge.lineTo(l, u - d * 4.5f)
                edge.lineTo(l + d * 2.5f, u - d * 2)
                edge.lineTo(from, u - d * 2)
                edge.lineTo(from + d * 2, u)
                edge.lineTo(to - d * 2, u)
                edge.lineTo(to, u - d * 2)
                edge.lineTo(r - d * 6, u - d * 2)
                edge.lineTo(r, u - d * 8)
                edge.lineTo(r, u - d * 12)
                stubFrom = from + d * 3
                stubTo = to - d * 3
            }
        }
        p.color = color
        p.strokeWidth = d * (if (open) 1f else 0.75f)
        c.drawPath(edge, p)
        if (open) {
            p.strokeWidth = d * 2.5f
            c.drawLine(stubFrom, u, stubTo, u, p)
        }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Progress in visible jumps, easing in and out, so a transition stutters on purpose rather than gliding. */
private fun stepped(t: Float): Float {
    val s = floor(t * 10f) / 10f
    return s * s * (3 - 2 * s)
}

/**
 * Fast at first and slow at the end: equal slices of time cover less and less ground, so the early frames
 * are big jumps and the last ones settle. It has already moved on the first frame.
 */
private fun settling(t: Float, steps: Int): Float {
    val slot = minOf(1f, (floor(t * steps) + 1) / steps)
    val left = 1 - slot
    return 1 - left * left * left
}

/**
 * Runs [paint] in horizontal bands, some of them torn sideways and tinted.
 * [strength] from 0 to 1: at 0 it is one plain pass. The tear holds for 45 ms at a time rather than changing every frame.
 */
internal fun Canvas.glitched(w: Int, h: Int, strength: Float, density: Float, tints: List<Int>, paint: () -> Unit) {
    val r = Random(SystemClock.uptimeMillis() / 45)
    val tint = Paint()
    val plain = { from: Float, to: Float ->
        if (to > from) {
            save()
            clipRect(0f, from, w.toFloat(), to)
            paint()
            restore()
        }
    }
    var plainFrom = 0f
    var y = 0f
    while (y < h) {
        val end = minOf(h.toFloat(), y + maxOf(density * 3, h.toFloat() / r.nextInt(16, 44)))
        if (r.nextFloat() < strength * 0.3f) {
            plain(plainFrom, y)
            save()
            clipRect(0f, y, w.toFloat(), end)
            save()
            translate((r.nextFloat() - 0.5f) * density * 28 * strength, 0f)
            paint()
            restore()
            tint.color = faded(tints[r.nextInt(tints.size)], 26)
            drawRect(0f, y, w.toFloat(), end, tint)
            restore()
            plainFrom = end
        }
        y = end
    }
    plain(plainFrom, h.toFloat())
}

/** Holds one child and shows only [fraction] of its height, so what follows moves as it opens. */
class Unfold(ctx: Context) : FrameLayout(ctx) {
    var fraction = 1f
        set(v) {
            field = v
            requestLayout()
        }
    private var running: ValueAnimator? = null
    private var playing = false
    private var progress = 0f
    private val edge = Paint().apply { style = Paint.Style.STROKE }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(measuredWidth, (measuredHeight * fraction).toInt())
    }

    fun play(open: Boolean) {
        running?.cancel()
        val from = fraction
        val to = if (open) 1f else 0f
        playing = true
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 340
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                fraction = from + (to - from) * stepped(progress)
                // Dips now and then, like a bad connection.
                alpha = if (progress < 0.85f && Random.nextFloat() < 0.2f) 0.45f else 1f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    playing = false
                    alpha = 1f
                    invalidate()
                }
            })
            start()
        }
    }

    override fun dispatchDraw(c: Canvas) {
        if (!playing) return super.dispatchDraw(c)
        val d = resources.displayMetrics.density
        c.glitched(width, height, 1 - progress, d, listOf(context.theme().hot, context.theme().alt)) { super.dispatchDraw(c) }
        // The edge that does the opening.
        edge.color = context.theme().hot
        edge.strokeWidth = d
        val y = height - d / 2
        c.drawLine(0f, y, width.toFloat(), y, edge)
        edge.strokeWidth = d * 2.5f
        c.drawLine(width * 0.78f, y, width.toFloat(), y, edge)
    }
}

/**
 * Lies over the window while the panel changes size: a picture of the old screen, with the panel's outline
 * jumping from its old place to its new one. The layout underneath has already changed; this only hides the jump.
 */
class PanelMorph(
    ctx: Context,
    private val old: Bitmap,
    private val oldPanel: Rect,
    private val panel: () -> View?,
    private val growing: Boolean,
    private val done: () -> Unit,
) : View(ctx) {
    private var progress = 0f
    private var running: ValueAnimator? = null
    private val at = IntArray(2)
    private val edge = Paint().apply { style = Paint.Style.STROKE }

    init {
        isClickable = true // Nothing underneath should take a touch while it is hidden.
    }

    fun start() {
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = finish()
            })
            start()
        }
    }

    fun finish() {
        (parent as? ViewGroup)?.run {
            removeView(this@PanelMorph)
            done()
        }
        running?.cancel()
    }

    override fun onDraw(c: Canvas) {
        val p = panel()?.takeIf { it.isAttachedToWindow } ?: return
        p.getLocationInWindow(at)
        val s = settling(progress, 7)
        val mix = { a: Int, b: Int -> a + (b - a) * s }
        val box = RectF(mix(oldPanel.left, at[0]), mix(oldPanel.top, at[1]), mix(oldPanel.right, at[0] + p.width), mix(oldPanel.bottom, at[1] + p.height))
        val d = resources.displayMetrics.density
        val strength = 1 - progress
        c.glitched(width, height, strength, d, listOf(context.theme().hot, context.theme().alt)) {
            if (growing) {
                c.drawBitmap(old, 0f, 0f, null)
                c.save()
                c.clipRect(box)
                c.translate(box.left, box.top)
                p.draw(c)
                c.restore()
            } else {
                c.save()
                c.clipRect(box)
                c.translate(box.left - oldPanel.left, box.top - oldPanel.top)
                c.drawBitmap(old, 0f, 0f, null)
                c.restore()
            }
        }
        // Scan lines across the moving panel, fading with the tear.
        edge.color = faded(context.theme().alt, (70 * strength).toInt())
        edge.strokeWidth = 1f
        var y = box.top
        while (y < box.bottom) {
            c.drawLine(box.left, y, box.right, y, edge)
            y += d * 3
        }
        edge.color = context.theme().hot
        edge.strokeWidth = d
        c.drawRect(box, edge)
        edge.strokeWidth = d * 3
        c.drawLine(box.left + box.width() * 0.78f, box.bottom, box.right, box.bottom, edge)
    }
}

/** A window in the app's own look instead of the system's: its title over a strip of bars, then what [build] adds. */
fun Context.cyberDialog(title: String, build: LinearLayout.(Dialog) -> Unit) = Dialog(this).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = frame(theme().bg, edge = theme().accent)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(heading(title, 16f).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, dp(12), 0) // Clear of the frame's cut corner.
        })
        addView(Barcode(context, faded(theme().accent, 130)), LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0, dp(6), 0, dp(10)) })
    }
    box.build(this)
    setContentView(ScrollView(context).apply { addView(box) })
    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
    show()
}

class Choice(val text: String, val danger: Boolean = false, val act: () -> Unit)

/** What a long press opens: one row per choice, the ones that destroy something in red. */
fun Context.menuDialog(title: String, vararg choices: Choice) = cyberDialog(title) { dialog ->
    choices.forEach { c ->
        addView(choiceRow(c.text, '>', if (c.danger) toneColor("warning") else theme().alt) {
            dialog.dismiss()
            c.act()
        })
    }
}

/** A few fields and a choice between giving up and [confirm], which runs [onConfirm]. */
fun Context.formDialog(title: String, confirm: String, fields: List<View>, onConfirm: () -> Unit) = cyberDialog(title) { dialog ->
    fields.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
    addView(LinearLayout(context).apply {
        addView(chip("Cancel") { dialog.dismiss() }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip(confirm) {
            dialog.dismiss()
            onConfirm()
        }.apply {
            setTextColor(theme().hot)
            background = Glow(theme().hot, resources.displayMetrics.density)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
}

/** A calendar page in the same look. Days before [earliest] can't be chosen; choosing a day closes the window. */
fun Context.dateDialog(title: String, start: LocalDate, earliest: LocalDate, chosen: (LocalDate) -> Unit) = cyberDialog(title) { dialog ->
    val page = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var month = YearMonth.from(start)
    fun cell() = LinearLayout.LayoutParams(0, dp(40), 1f)
    fun draw() {
        page.removeAllViews()
        val canGoBack = month > YearMonth.from(earliest)
        page.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(mono("◀", 16f, if (canGoBack) theme().accent else faded(theme().muted, 70)).apply {
                gravity = Gravity.CENTER
                contentDescription = "Previous month"
                if (canGoBack) setOnClickListener { month = month.minusMonths(1); draw() }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
            addView(mono(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", UI_LOCALE)).caps(), 13f, theme().alt).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mono("▶", 16f, theme().accent).apply {
                gravity = Gravity.CENTER
                contentDescription = "Next month"
                setOnClickListener { month = month.plusMonths(1); draw() }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        })
        page.addView(LinearLayout(context).apply {
            listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").forEach { addView(mono(it.caps(), 10f, theme().muted).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f)) }
        })
        monthCells(month).chunked(7).forEach { week ->
            page.addView(LinearLayout(context).apply {
                week.forEach { day ->
                    val open = day != null && day >= earliest
                    addView(mono(day?.dayOfMonth?.toString() ?: "", 14f, if (open) theme().text else faded(theme().muted, 90)).apply {
                        gravity = Gravity.CENTER
                        if (day == earliest) background = Brackets(theme().accent, resources.displayMetrics.density, arm = 4f)
                        if (open) setOnClickListener {
                            dialog.dismiss()
                            chosen(day!!)
                        }
                    }, cell())
                }
            })
        }
    }
    draw()
    addView(page)
}

/** Keeps its height equal to its width, so a row of them stays square on any phone. */
class Square(ctx: Context) : FrameLayout(ctx) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}

/** The hazard tape behind a warning. */
class Hazard(private val color: Int, private val density: Float) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        val b = bounds
        p.color = Color.BLACK
        c.drawRect(b, p)
        p.color = color
        val s = 13 * density
        val h = b.height().toFloat()
        var x = b.left - h
        c.save()
        c.clipRect(b)
        while (x < b.right) {
            c.drawPath(Path().apply {
                moveTo(x, b.bottom.toFloat()); lineTo(x + h, b.top.toFloat()); lineTo(x + h + s, b.top.toFloat()); lineTo(x + s, b.bottom.toFloat()); close()
            }, p)
            x += s * 2
        }
        c.restore()
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Black with a little grit: the ground under every warning. */
class Grit(private val color: Int, private val density: Float) : Drawable() {
    private val p = Paint()

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        c.drawColor(Color.BLACK)
        val r = Random(11)
        p.color = faded(color, 26)
        repeat(260) { c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, density * r.nextFloat() * 0.7f, p) }
    }

    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Decorative bars on a fixed seed, so they don't change between redraws. */
class Barcode(ctx: Context, private val color: Int, private val seed: Int = 5) : View(ctx) {
    private val p = Paint()

    override fun onDraw(c: Canvas) {
        val r = Random(seed)
        val d = resources.displayMetrics.density
        p.color = color
        var x = 0f
        while (x < width) {
            val bar = d * (1 + r.nextInt(3))
            c.drawRect(x, 0f, x + bar, height.toFloat(), p)
            x += bar + d * (1 + r.nextInt(3))
        }
    }
}

/** A row of thin bars, filled up to [fraction]. */
class Segments(ctx: Context, private val fraction: Float, private val color: Int) : View(ctx) {
    private val p = Paint()

    override fun onDraw(c: Canvas) {
        val n = 34
        val step = width.toFloat() / n
        repeat(n) { i ->
            p.color = if ((i + 0.5f) / n <= fraction) color else faded(color, 38)
            c.drawRect(i * step, 0f, i * step + step * 0.62f, height.toFloat(), p)
        }
    }
}

/** A task's state: an empty ring, or a green ring with a check. */
class Tick(ctx: Context, private val done: Boolean) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val t = context.theme()
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - d * 2
        p.strokeWidth = d * 1.8f
        p.color = if (done) t.good else faded(t.alt, 130)
        c.drawCircle(cx, cy, r, p)
        if (done) c.drawPath(Path().apply {
            moveTo(cx - r * 0.5f, cy); lineTo(cx - r * 0.1f, cy + r * 0.42f); lineTo(cx + r * 0.52f, cy - r * 0.38f)
        }, p)
    }
}

/** The check or cross at the end of a choice. */
class Mark(ctx: Context, private val glyph: Char, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat(); val h = height.toFloat(); val m = w * 0.28f
        p.strokeWidth = d; p.color = faded(color, 120)
        c.drawRect(d, d, w - d, h - d, p)
        p.strokeWidth = d * 2.4f; p.color = color
        p.setShadowLayer(d * 4, 0f, 0f, color)
        when (glyph) {
            'x' -> { c.drawLine(m, m, w - m, h - m, p); c.drawLine(w - m, m, m, h - m, p) }
            'v' -> c.drawPath(Path().apply { moveTo(m, h * 0.52f); lineTo(w * 0.44f, h - m); lineTo(w - m, m) }, p)
            else -> { c.drawLine(m, h / 2, w - m, h / 2, p); c.drawLine(w * 0.56f, m, w - m, h / 2, p); c.drawLine(w * 0.56f, h - m, w - m, h / 2, p) }
        }
        p.clearShadowLayer()
    }
}

/** The warning triangle, glowing in the color of the moment. */
class Warning(ctx: Context, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val w = height * 0.56f; val top = d * 6; val u = height - d * 6
        p.color = color; p.strokeWidth = d * 4
        p.setShadowLayer(d * 8, 0f, 0f, color)
        c.drawPath(Path().apply { moveTo(cx, top); lineTo(cx + w, u); lineTo(cx - w, u); close() }, p)
        val mid = (top + u) * 0.56f
        c.drawLine(cx, mid - height * 0.2f, cx, mid + height * 0.1f, p)
        c.drawPoint(cx, mid + height * 0.22f, p)
        p.clearShadowLayer()
    }
}

/** Counts down the seconds the screen stays locked, then simply stops. */
class LockTimer(ctx: Context, private val durationMs: Long, private val color: Int) : View(ctx) {
    private val started = SystemClock.uptimeMillis()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = ctx.theme().title(ctx)
    private val mono = ctx.theme().mono(ctx)

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val left = (durationMs - (SystemClock.uptimeMillis() - started)).coerceAtLeast(0)
        p.color = color
        p.typeface = mono; p.textSize = 11 * d * resources.configuration.fontScale
        c.drawText("UNTIL UNLOCK", 0f, 14 * d, p)
        p.typeface = title; p.textSize = 38 * d
        c.drawText("00:00:%02d".format((left + 999) / 1000), 0f, 54 * d, p)
        val top = 66 * d; val bar = 10 * d
        val n = 44; val step = width.toFloat() / n
        val filled = left.toFloat() / durationMs
        repeat(n) { i ->
            p.color = if ((i + 0.5f) / n <= filled) color else faded(color, 40)
            c.drawRect(i * step, top, i * step + step * 0.6f, top + bar, p)
        }
        if (left > 0) postInvalidateDelayed(FRAME_MS)
    }

    private companion object { const val FRAME_MS = 60L }
}

/** A choice in a warning: "> WHAT YOU SAY" and a check or cross. */
fun Context.choiceRow(s: String, glyph: Char, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
    gravity = Gravity.CENTER_VERTICAL
    background = frame(Color.BLACK, edge = color)
    setPadding(dp(14), dp(10), dp(10), dp(10))
    isClickable = true
    setOnClickListener { onClick() }
    addView(TextView(context).apply {
        text = "> " + s.caps()
        typeface = theme().title(context)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }, LinearLayout.LayoutParams(0, -2, 1f))
    addView(Mark(context, glyph, color), LinearLayout.LayoutParams(dp(36), dp(36)))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
}

/**
 * The screen that interrupts: hazard tape for a warning, a window for anything else, with Jarvis's
 * words under it. Returns the view to show and the slot where the lock timer or choices go.
 */
fun Context.alertBody(tone: String, window: String?, text: String): Pair<View, LinearLayout> {
    val color = toneColor(tone)
    val v = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(44), 0, dp(24))
    }
    fun gutter(child: View, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(-1, -2)) =
        v.addView(child, lp.apply { leftMargin = dp(20); rightMargin = dp(20) })

    if (window != null) gutter(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(mono(">> $window", 14f, color), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Hazard(color, resources.displayMetrics.density).let { h -> View(context).apply { background = h } }, LinearLayout.LayoutParams(dp(72), dp(12)))
    })
    if (tone == "caution" && window != null) {
        v.addView(FrameLayout(this).apply {
            background = Hazard(color, resources.displayMetrics.density)
            addView(heading("Warning", 52f, center = true).apply {
                setTextColor(color)
                background = frame(Color.BLACK, edge = color)
                setPadding(dp(8), dp(4), dp(8), dp(6))
            }, FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(30), dp(18), dp(30), dp(18)) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    } else if (tone == "warning") {
        v.addView(Warning(this, color), LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(14) })
    }
    gutter(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Reactor(context, tone), LinearLayout.LayoutParams(dp(if (tone == "caution" && window != null) 54 else 108), dp(if (tone == "caution" && window != null) 54 else 108)))
        addView(tag("JARVIS", color), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        addView(mono("↘", 26f, color), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(8) })
    gutter(mono(text, 16f, color).apply {
        setTypeface(theme().mono(context), android.graphics.Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    })
    val slot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    gutter(slot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    if (window != null) gutter(mono("Your call, sir.", 12f, faded(color, 170)), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    gutter(Barcode(this, faded(color, 110)), LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
    val page = ScrollView(this).apply {
        isFillViewport = true
        background = Grit(color, resources.displayMetrics.density)
        addView(v)
    }
    return page to slot
}
