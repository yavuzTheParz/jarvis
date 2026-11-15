package app.jarvis

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import java.time.LocalDate
import java.time.ZoneId
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.theme() = Themes.current(this)

fun Context.frame(fill: Int? = null, seal: Boolean = false, edge: Int? = null) =
    theme().let { Frame(it, resources.displayMetrics.density, fill ?: it.surface, seal, edge ?: it.alt) }

fun Context.backdrop() = Backdrop(theme(), resources.displayMetrics.density)

fun Context.label(s: String, size: Float = 18f, color: Int? = null, center: Boolean = false) =
    TextView(this).apply {
        text = s
        typeface = theme().body(context)
        setTextColor(color ?: theme().text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (center) gravity = Gravity.CENTER
    }

/** Headings and the clock: the theme's display face, in its accent color. */
fun Context.heading(s: String, size: Float = 18f, center: Boolean = false) = label(if (cyber()) s.caps() else s, size, theme().accent, center).apply {
    typeface = theme().title(context)
    letterSpacing = 0.06f
}

fun Context.button(s: String, onClick: () -> Unit) = Button(this).apply {
    text = if (cyber()) s.caps() else s
    isAllCaps = false
    setTypeface(theme().body(context), Typeface.BOLD)
    stateListAnimator = null
    setTextColor(theme().text)
    background = frame(if (cyber()) theme().mine else null, seal = true)
    setPadding(dp(24), dp(12), dp(24), dp(12))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
    setOnClickListener { onClick() }
}

/** The small always-there controls: no frame, accent text. */
fun Context.barButton(s: String, onClick: () -> Unit) = Button(this).apply {
    text = s
    isAllCaps = false
    // Bold and underlined in the accent color, so it reads as something to press.
    setTypeface(theme().body(context), Typeface.BOLD)
    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
    stateListAnimator = null
    setTextColor(theme().accent)
    setBackgroundColor(Color.TRANSPARENT)
    setPadding(dp(2), dp(8), dp(2), dp(8))
    minWidth = 0
    minimumWidth = 0
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    setOnClickListener { onClick() }
}

fun Context.field(hint: String) = EditText(this).apply {
    this.hint = hint
    typeface = theme().body(context)
    setTextColor(theme().text)
    setHintTextColor(theme().muted)
    if (cyber()) {
        // A framed terminal prompt instead of an underline.
        this.hint = "> " + hint.caps()
        typeface = theme().mono(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = frame(theme().bg, edge = faded(theme().alt, 170))
        setPadding(dp(12), dp(11), dp(12), dp(11))
    } else {
        backgroundTintList = ColorStateList.valueOf(theme().accent)
    }
}

/** Jarvis talking: no face, only his ring in the tone of what he says. */
fun Context.speech(tone: String?, text: String, size: Float = 20f) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    val edge = if (cyber()) toneColor(tone) else null
    if (cyber() && tone == "warning") {
        addView(Warning(context, toneColor(tone)), LinearLayout.LayoutParams(dp(150), dp(120)).apply { gravity = Gravity.CENTER })
    } else if (tone != null) {
        addView(Reactor(context, tone), LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = Gravity.CENTER })
    }
    addView(heading("JARVIS", 13f, center = true).apply {
        if (edge != null) setTextColor(edge)
        setPadding(0, dp(10), 0, dp(6))
    })
    addView(label(text, size, edge, center = true).apply {
        background = frame(edge = edge)
        setPadding(dp(18), dp(16), dp(18), dp(18))
    }, LinearLayout.LayoutParams(-1, -2))
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
}

fun Context.column(center: Boolean = false) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    if (center) gravity = Gravity.CENTER
    setPadding(dp(24), dp(48), dp(24), dp(32))
}

/** Paints the system bars to match the screen behind them. */
fun Activity.paintBars() {
    window.statusBarColor = theme().bg2
    window.navigationBarColor = theme().bg
}

fun TextView.strike(on: Boolean) {
    paintFlags = if (on) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
}

object Launch {
    fun app(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
    }

    fun url(ctx: Context, url: String) = view(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun whatsapp(ctx: Context, cfg: Config) {
        if (cfg.quickChatNumber.isEmpty()) app(ctx, "com.whatsapp")
        else view(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${cfg.quickChatNumber}")).setPackage("com.whatsapp"))
    }

    /** The phone's clock app itself, the way its icon opens it; only without one, whatever shows the alarms. */
    fun clock(ctx: Context) = first(ctx, launchers(ctx, CLOCKS) + Intent(AlarmClock.ACTION_SHOW_ALARMS))

    /** Digital Wellbeing (Samsung's or Google's); without it, the system settings. */
    fun wellbeing(ctx: Context) = first(ctx, listOf(
        Intent().setClassName("com.samsung.android.forest", "com.samsung.android.forest.launcher.LauncherActivity"),
        Intent().setClassName("com.google.android.apps.wellbeing", "com.google.android.apps.wellbeing.settings.TopLevelSettingsActivity"),
    ) + launchers(ctx, WELLBEING) + Intent(Settings.ACTION_SETTINGS))

    /** Where the user grants usage access, opened on Jarvis when the phone allows it. */
    fun usageAccess(ctx: Context) = first(ctx, listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    ))

    private val CLOCKS = listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock")
    private val WELLBEING = listOf("com.samsung.android.forest", "com.google.android.apps.wellbeing")

    private fun launchers(ctx: Context, pkgs: List<String>) = pkgs.mapNotNull { ctx.packageManager.getLaunchIntentForPackage(it) }

    private fun first(ctx: Context, candidates: List<Intent>) {
        for (i in candidates) {
            if (i.resolveActivity(ctx.packageManager) == null) continue
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    private fun view(ctx: Context, i: Intent) {
        try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }
}

fun Context.hasUsageAccess(): Boolean {
    val ops = getSystemService(AppOpsManager::class.java)
    return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
}

/** Today's screen time since midnight per app, by name and longest first, or null while usage access isn't granted. */
fun Context.screenAppsToday(): List<Pair<String, Long>>? {
    val events = usageEventsToday() ?: return null
    val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return screenTimeByApp(events, dayStart, System.currentTimeMillis(), ignored = setOf(packageName)).map { (pkg, ms) ->
        val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.substringAfterLast('.'))
        name to ms
    }
}

private fun Context.usageEventsToday(): List<UsageEvent>? {
    if (!hasUsageAccess()) return null
    val now = System.currentTimeMillis()
    val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val log = getSystemService(UsageStatsManager::class.java).queryEvents(dayStart, now)
    val events = ArrayList<UsageEvent>()
    val e = UsageEvents.Event()
    while (log.getNextEvent(e)) when (e.eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> events += UsageEvent(e.packageName, e.timeStamp, true)
        UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> events += UsageEvent(e.packageName, e.timeStamp, false)
        // The screen going off ends whatever was in front.
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> events += UsageEvent(SCREEN_OFF, e.timeStamp, false)
    }
    return events
}


/** Installed apps by their name, except Jarvis. */
fun Context.launchableApps(): Map<String, String> {
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(main, PackageManager.ResolveInfoFlags.of(0))
        .filter { it.activityInfo.packageName != packageName }
        .associate { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
}

/** A line for the moments Jarvis interrupts. */
fun nudge(store: Store): String = suggestionLine(
    store.todos().filter { !it.done }.map { it.text }, store.book, store.ideas, store.lastResort, Random::nextInt,
)
