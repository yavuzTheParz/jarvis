package app.jarvis

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class HomeActivity : Activity() {
    private lateinit var cfg: Config
    private lateinit var store: Store
    @Volatile private var apps: Map<String, String> = emptyMap()
    private var awaitingConfirm = false
    private var shownPhase: Phase? = null
    private var shownTab = 0
    private var shownMode = 0
    private var planTomorrow = false
    private var bigDials = false
    private var planField: EditText? = null
    private var ring: View? = null
    private var listScroll: ScrollView? = null
    private var askedForMic = false
    /** The bridge as last seen from home; null until checked. */
    private var linkUp: Boolean? = null
    private val appList by lazy { AppListAdapter(this) { pkg -> Launch.app(this, pkg) } }
    private var searchQuery: (() -> String)? = null
    private var closeSearch: (() -> Unit)? = null
    /** The task and habit panel has the whole screen. Lasts only while looked at: see [onStop]. */
    private var panelFull = false
    private var homePanel: View? = null
    private var morph: PanelMorph? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        alive = true
        // Coming up over the phone's own launcher: an animated entry would show the two on top of each other.
        overridePendingTransition(0, 0)
        cfg = Config(this)
        store = Store(this)
        // Before the first frame is drawn, and during a return from another app, the window shows this rather than black.
        window.setBackgroundDrawable(backdrop())
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Also catches what came due while the phone was off or the alarm was held back.
        Reminders.sync(this)
        askForVault()
        render()
        wakeVoice()
        // Recents and app transitions show the system wallpaper, not this screen: make that Jarvis too.
        JarvisWallpaper.ensure(this)
        // Asking the system for hundreds of apps and names takes long enough to freeze the screen.
        thread {
            val found = launchableApps()
            runOnUiThread {
                apps = found
                searchQuery?.let { showApps(it()) }
            }
        }
    }

    /** Once: the vault can only reach the PC from the shared Jarvis folder, which needs "all files" access. */
    private fun askForVault() {
        if (store.vault.shared || store.askedForVault) return
        store.askedForVault = true
        Toast.makeText(this, "For the vault: give Jarvis access to all files", Toast.LENGTH_LONG).show()
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }

    /** Android lets a service take the microphone only from a visible screen, so home is where it starts. */
    private fun wakeVoice() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !askedForMic) {
            askedForMic = true
            requestPermissions(missing.toTypedArray(), ASK_MIC)
        }
        VoiceService.ensure(this, store)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        if (request == ASK_MIC) VoiceService.ensure(this, store)
    }

    private val tasksChanged = { render() }

    /** Re-draws only when the routine moved on, so a half-typed answer is never lost to the clock. */
    private val clock = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (store.phase(cfg) != shownPhase) render()
            else if (shownMode == 2 && searchQuery == null && planField?.text.isNullOrEmpty()) keepingScroll { render() }
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(clock, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, RECEIVER_NOT_EXPORTED)
        Live.tasks.add(tasksChanged)
    }

    override fun onStop() {
        unregisterReceiver(clock)
        Live.tasks.remove(tasksChanged)
        // Screen off or another app: either way it comes back to the normal home, without a flash of the old one.
        morph?.finish()
        if (panelFull) {
            panelFull = false
            render()
        }
        super.onStop()
    }

    private fun render() {
        val phase = store.phase(cfg)
        shownPhase = phase
        when (phase) {
            Phase.MORNING -> if (System.currentTimeMillis() < store.graceUntil) awake() else morning()
            Phase.AWAKE, Phase.EVENING -> awake()
        }
    }

    private fun show(v: LinearLayout, black: Boolean = true, fixed: Boolean = false) {
        // Only the fixed home has the panel; the morning can take over while it is enlarged.
        if (!fixed) panelFull = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // The morning stays pure black; the rest of the day is plain navy, with no wallpaper.
            val color = if (black) Color.BLACK else NAVY
            if (!black && (cyber() || theme().style == Style.HUD)) background = backdrop() else setBackgroundColor(color)
            window.statusBarColor = if (!black && cyber()) theme().bg2 else color
            window.navigationBarColor = if (!black && cyber()) theme().bg else color
        }
        root.addView(if (fixed) v else ScrollView(this).apply {
            isFillViewport = true
            addView(v)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = alwaysThere(ideas = fixed)
        if (!panelFull) root.addView(bar)
        // The fixed home gives its room to the keyboard: the ring and bar step aside while typing.
        if (fixed && !panelFull) root.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val typing = bottom - top < resources.displayMetrics.heightPixels * 0.72f
            val shown = if (typing) View.GONE else View.VISIBLE
            if (bar.visibility != shown) root.post {
                bar.visibility = shown
                ring?.visibility = shown
            }
        }
        setContentView(root)
        window.insetsController?.run {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (panelFull) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
        }
    }

    /** On every screen, whatever the routine says: Spotify and the chat. */
    private fun alwaysThere(ideas: Boolean) = LinearLayout(this).apply {
        setPadding(dp(13), dp(4), dp(13), dp(10))
        fun cell() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
        val spotify = NEVER_BLOCKED.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (spotify != null) addView(chip("SPOTIFY") { Launch.app(this@HomeActivity, spotify) }, cell())
        addView(chip(if (store.voiceOn) "MIC ON" else "MIC OFF") {
            store.voiceOn = !store.voiceOn
            if (store.voiceOn) VoiceService.ensure(this@HomeActivity, store) else VoiceService.stop(this@HomeActivity)
            render()
        }, cell())
        if (ideas) addView(chip("My suggestions") { startActivity(Intent(this@HomeActivity, IdeasActivity::class.java)) }, cell())
        // At home his ring opens the chat; the morning screens have no ring to press, so they keep this.
        if (!ideas) addView(chip("💬 JARVIS") { startActivity(Intent(this@HomeActivity, ChatActivity::class.java)) }, cell())
    }

    private fun morning() {
        val v = column(center = true)
        if (awaitingConfirm) {
            v.addView(speech("caution", "Are you sure, sir?"))
            v.addView(button("Yes, I'm up") {
                awaitingConfirm = false
                store.morningDoneOn = store.today
                render()
            })
            v.addView(button("You're right, I'm going") { awaitingConfirm = false; render() })
        } else if (store.limitHit) {
            v.addView(speech("warning", "Time is up, sir. I must ask you to get up and wash your face."))
            v.addView(button("I want to negotiate") { bargain() })
            v.addView(button("I washed my face, I'm up") { awaitingConfirm = true; render() })
            v.addView(button("Emergency") { grant(15) })
        } else {
            v.addView(whatsappIcon())
            val pending = store.todos().count { !it.done }
            val tail = if (pending > 0) " $pending tasks you set for today are waiting for you." else ""
            v.addView(speech(null, "Good morning, sir. A glass of water, then wash your face.$tail"))
            v.addView(button("I washed my face, I'm up") { awaitingConfirm = true; render() })
            v.addView(button("Emergency") { grant(15) })
        }
        show(v)
    }

    private fun whatsappIcon(): View {
        val open = { Launch.whatsapp(this, cfg) }
        val icon = runCatching { packageManager.getApplicationIcon("com.whatsapp") }.getOrNull()
            ?: return button("WhatsApp") { open() }.apply { textSize = 22f }
        return ImageView(this).apply {
            setImageDrawable(icon)
            contentDescription = "WhatsApp"
            setOnClickListener { open() }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(16)
            }
        }
    }

    /** Offline ladder: each round of bargaining buys less, and the third buys nothing. */
    private fun bargain() {
        val n = store.bargains
        store.bargains = n + 1
        val (tone, line, minutes) = when (n) {
            0 -> Triple("caution", "Understood, sir. Five minutes. Noted.", 5)
            1 -> Triple("caution", "Two minutes, sir. I am noting this as the final extension.", 2)
            else -> Triple("warning", "I decline this request, sir. You made the rule; we can talk once you are up.", 0)
        }
        val v = column(center = true)
        v.addView(speech(tone, line))
        v.addView(button("OK") { if (minutes > 0) grant(minutes) else render() })
        show(v)
    }

    private fun grant(minutes: Int) {
        store.graceUntil = System.currentTimeMillis() + minutes * 60_000L
        store.logActivity("jarvis.grant", "$minutes min")
        render()
    }

    private fun awake() {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), 0)
        }
        v.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextClock(context).apply {
                format24Hour = "HH:mm"
                format12Hour = "HH:mm"
                typeface = theme().title(context)
                setTextColor(theme().accent)
                textSize = 62f
                // A cyan ghost beside the digits, like a misaligned print.
                if (cyber()) setShadowLayer(1f, dp(3).toFloat(), 0f, theme().alt)
                contentDescription = "Open the clock app"
                setOnClickListener { Launch.clock(this@HomeActivity) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val now = LocalDate.now()
            val day = { p: String -> now.format(DateTimeFormatter.ofPattern(p, Locale.ENGLISH)).caps() }
            val info = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            info.addView(mono(day("EEEE"), 12f, theme().alt).apply { gravity = Gravity.END })
            info.addView(mono(day("d MMMM"), 12f, theme().muted).apply { gravity = Gravity.END })
            val link = mono("LINK: ...", 11f, theme().muted).apply {
                gravity = Gravity.END
                setPadding(0, dp(4), 0, 0)
            }
            info.addView(link)
            thread {
                val up = Bridge(cfg.bridgeUrl).isUp()
                runOnUiThread {
                    linkUp = up
                    link.text = if (up) "LINK: ON" else "LINK: OFF"
                    link.setTextColor(if (up) theme().good else theme().muted)
                }
                // Back online: plans told while it was away go to Jarvis now.
                if (up) Jarvis(this@HomeActivity).syncPlans()
            }
            val waiting = pendingReminders(store.reminders(), java.time.LocalDateTime.now())
            addView(Bell(context, waiting.size, waiting.firstOrNull()?.at).apply {
                contentDescription = if (waiting.isEmpty()) "Reminders" else "Reminders, ${waiting.size} waiting"
                setOnClickListener { startActivity(Intent(this@HomeActivity, RemindersActivity::class.java)) }
            }, LinearLayout.LayoutParams(dp(48), dp(58)).apply { rightMargin = dp(6) })
            addView(info)
        })
        v.addView(Barcode(this, faded(theme().accent, 130)), LinearLayout.LayoutParams(-1, dp(9)).apply { setMargins(0, 0, 0, dp(10)) })

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Leaves the scrollbar its own lane, clear of the rings at the end of each task.
            setPadding(0, 0, dp(14), 0)
        }
        val search = field("Search apps").apply { setSingleLine() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable) {
                val q = e.toString()
                val labels = apps.keys.toList()
                val one = uniqueApp(q, labels)
                if (one != null) {
                    e.clear()
                    Launch.app(this@HomeActivity, apps.getValue(one))
                    return
                }
                showApps(q)
            }
        })
        v.addView(searchBar(search), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        // Only this panel grows and scrolls; everything around it stays where it is.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = frame()
            setPadding(dp(10), dp(10), dp(10), dp(8))
        }
        homePanel = panel
        val todos = store.todos()
        val done = todos.count { it.done }
        panel.addView(LinearLayout(this).apply {
            addView(modeTab("Tasks", 0), LinearLayout.LayoutParams(0, -2, 1f))
            gravity = Gravity.CENTER_VERTICAL
            addView(modeTab("Habits", 1), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            addView(modeTab("Plan", 2), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            addView(mono(if (panelFull) "▼" else "▲", 16f, theme().accent).apply {
                gravity = Gravity.CENTER
                contentDescription = if (panelFull) "Exit full screen" else "Full screen"
                setOnClickListener { setPanelFull(!panelFull) }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        if (shownMode == 2) panel.addView(LinearLayout(this).apply {
            addView(subTab("Today", !planTomorrow, 0) { planTomorrow = false }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(subTab("Tomorrow", planTomorrow, 1) { planTomorrow = true }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        if (shownMode == 0) panel.addView(LinearLayout(this).apply {
            addView(tab("Today $done/${todos.size}", 0), LinearLayout.LayoutParams(0, -2, 1f))
            addView(tab("Tomorrow ${store.plan().size}", 1), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            addView(tab("Week ${store.agenda().values.sumOf { it.size }}", 2), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        })
        if (shownMode == 0) panel.addView(Segments(this, if (todos.isEmpty()) 0f else done.toFloat() / todos.size, theme().hot), LinearLayout.LayoutParams(-1, dp(5)).apply { setMargins(0, dp(9), 0, dp(4)) })
        val scroll = ScrollView(this).apply {
            defaultFocusHighlightEnabled = false
            addView(list)
        }
        listScroll = scroll
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (shownMode == 1) {
            panel.addView(LinearLayout(this).apply {
                addView(chip("+ Habit") { addHabit() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip("+ Taper") { addTaper() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
                addView(chip("+ Tracker") { addMeasure() }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        } else if (shownMode == 2) {
            panel.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(planInput(), LinearLayout.LayoutParams(0, -2, 1f))
                addView(chip("Describe the plan") {
                    val voice = VoiceService.current
                    if (voice != null) voice.askForPlan() else VoiceService.ensure(this@HomeActivity, store, listen = true, plan = true)
                }.apply { setPadding(dp(12), paddingTop, dp(12), paddingBottom) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        } else panel.addView(field(listOf("Add", "Add to tomorrow", "Add, then pick a day")[shownTab]).apply {
            setSingleLine()
            setOnEditorActionListener { _, _, _ ->
                val t = text.toString().trim()
                if (t.isNotEmpty()) when (shownTab) {
                    0 -> { store.addOn(store.todayDate, t); render() }
                    1 -> { store.addOn(store.todayDate.plusDays(1), t); render() }
                    else -> pickDay { day -> store.addOn(day, t); render() }
                }
                true
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        v.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        when (shownMode) {
            0 -> fillList(list)
            1 -> fillHabits(list)
            else -> fillPlan(list)
        }

        // Searching swaps the tasks for the matching apps, right under the bar.
        val appsScroll = ListView(this).apply {
            adapter = appList
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            visibility = View.GONE
        }
        v.addView(appsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        search.setOnFocusChangeListener { _, focused ->
            if (focused) {
                panel.visibility = View.GONE
                appsScroll.visibility = View.VISIBLE
                searchQuery = { search.text.toString() }
                showApps(search.text.toString())
            }
        }
        closeSearch = {
            searchQuery = null
            search.text.clear()
            search.clearFocus()
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
            appsScroll.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }

        // His ring has a fixed place; a longer list only means more scrolling above it.
        ring = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { startActivity(Intent(this@HomeActivity, ChatActivity::class.java)) }
            // Long press: talk to him without calling his name, with or without "MIC ON".
            setOnLongClickListener {
                val running = VoiceService.current
                if (running != null) running.toggle() else VoiceService.ensure(this@HomeActivity, store, listen = true)
                true
            }
            // Left: the two counts, one under the other.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, 0, 0)
                addView(mono("SHORTS", 10f, theme().muted))
                addView(heading("${store.shortsSwiped}", 22f))
                addView(mono("TASK", 10f, theme().muted).apply { setPadding(0, dp(6), 0, 0) })
                addView(heading("$done/${todos.size}", 22f))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Reactor(context, homeTone()), LinearLayout.LayoutParams(dp(RING_DP), dp(RING_DP)))
            // Right: the total, then a bar per app with its foot on the screen's right edge.
            addView(screenTime(), LinearLayout.LayoutParams(0, dp(RING_DP), 1f).apply { leftMargin = dp(8) })
        }
        // The row reaches past the page's side padding, so the bars can touch the edge of the glass.
        v.addView(ring, LinearLayout.LayoutParams(-1, -2).apply { setMargins(-dp(16), dp(6), -dp(16), dp(6)) })
        if (panelFull) {
            v.setPadding(0, 0, 0, 0)
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child !== panel) child.visibility = View.GONE
            }
        }
        show(v, black = false, fixed = true)
    }

    /** Redraws with the list where it was, so enlarging it doesn't lose your place. */
    private fun setPanelFull(on: Boolean) {
        val at = listScroll?.scrollY ?: 0
        morph?.finish()
        val decor = window.decorView as ViewGroup
        val before = homePanel?.let { p ->
            val place = IntArray(2).also { p.getLocationInWindow(it) }
            Rect(place[0], place[1], place[0] + p.width, place[1] + p.height)
        }
        // A picture of the screen we are leaving. Without one it simply changes at once.
        val shot = runCatching {
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }.getOrNull()
        panelFull = on
        render()
        listScroll?.post { listScroll?.scrollTo(0, at) }
        if (shot != null && before != null) morph = PanelMorph(this, shot, before, { homePanel }, on) { morph = null }.also {
            decor.addView(it, ViewGroup.LayoutParams(-1, -1))
            it.start()
        }
    }

    /** Today's screen time beside the ring; a tap opens Digital Wellbeing, or asks for usage access. */
    private fun screenTime() = ScreenMeter(this).apply {
        val meter = this
        contentDescription = "Screen time"
        thread {
            val apps = runCatching { screenAppsToday() }.getOrNull()
            runOnUiThread {
                meter.show(apps)
                if (apps == null) {
                    meter.contentDescription = "Grant usage access for screen time"
                    meter.setOnClickListener { Launch.usageAccess(this@HomeActivity) }
                } else {
                    meter.contentDescription = "Screen time today ${screenTimeLabel(apps.sumOf { it.second })}, open Digital Wellbeing"
                    meter.setOnClickListener { Launch.wellbeing(this@HomeActivity) }
                }
            }
        }
    }

    private fun modeTab(text: String, index: Int) = tab(text, shownMode == index) { shownMode = index }

    private fun keepingScroll(redraw: () -> Unit) {
        val at = listScroll?.scrollY ?: 0
        redraw()
        listScroll?.post { listScroll?.scrollTo(0, at) }
    }

    private val planDay get() = if (planTomorrow) store.todayDate.plusDays(1) else store.todayDate

    /** Hours in digits are written down at once, PC or no PC; anything looser is for Jarvis to work out. */
    private fun planInput() = field("09:00-10:30 Gym").apply {
        planField = this
        setSingleLine()
        setOnEditorActionListener { _, _, _ ->
            val typed = text.toString().trim()
            val day = planDay
            val blocks = parseSchedule(typed)
            when {
                typed.isEmpty() -> {}
                blocks != null -> {
                    blocks.forEach { store.placeOnSchedule(day, it) }
                    setText("")
                    keepingScroll { render() }
                }
                else -> {
                    setText("")
                    val jarvis = Jarvis(context)
                    // Known to be down: no point waiting for the bridge to time out.
                    val offline = {
                        Toast.makeText(this@HomeActivity, jarvis.planOffline(day, typed), Toast.LENGTH_LONG).show()
                        keepingScroll { render() }
                    }
                    if (linkUp == false) offline() else {
                        Toast.makeText(context, "Sent to Jarvis", Toast.LENGTH_SHORT).show()
                        jarvis.ask("My hourly plan for $day: $typed", shown = true, onReply = { reply, _ ->
                            Toast.makeText(this@HomeActivity, reply, Toast.LENGTH_LONG).show()
                        }, unreachable = { linkUp = false; offline() })
                    }
                }
            }
            true
        }
    }

    private fun fillPlan(list: LinearLayout) {
        val day = planDay
        val blocks = store.schedule(day)
        val colors = sliceColors(blocks)
        val time = LocalTime.now()
        val minute = if (planTomorrow) null else time.hour * 60 + time.minute
        fun dial(pm: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(mono(if (pm) "12–24 PM" else "00–12 AM", 10f, theme().muted).apply { gravity = Gravity.CENTER })
            addView(DayDial(context, blocks, pm, minute, colors, cfg.times.wakeFrom), LinearLayout.LayoutParams(-1, -2))
            contentDescription = "Clock dial, ${if (pm) "PM" else "AM"}. Tap to enlarge or shrink."
            setOnClickListener {
                bigDials = !bigDials
                keepingScroll { render() }
            }
        }
        list.addView(LinearLayout(this).apply {
            orientation = if (bigDials) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val cell = { LinearLayout.LayoutParams(if (bigDials) -1 else 0, -2, if (bigDials) 0f else 1f) }
            addView(dial(false), cell())
            addView(dial(true), cell().apply { if (bigDials) topMargin = dp(10) else leftMargin = dp(6) })
        })
        fun section(s: String) = mono(s, 11f, theme().alt).apply { setPadding(0, dp(12), 0, dp(2)) }
        if (blocks.isEmpty()) {
            list.addView(mono("No hourly plan for this day. Press \"Describe the plan\"; a rough \"gym at nine, then study\" is enough. You can also type times below.", 12f, theme().muted).apply { setPadding(0, dp(12), 0, 0) })
            return
        }
        val state = minute?.let { store.planNow(it) }
        if (state != null) {
            list.addView(section("NOW"))
            val current = state.current
            if (current != null) {
                val left = (current.end - minute + 1440) % 1440
                list.addView(planRow(current, colors, false, day, "$left min left"))
            } else list.addView(label(if (state.next.isEmpty()) "The day's plan is done." else "Free time.", 15f, theme().muted).apply { setPadding(0, dp(6), 0, dp(6)) })
            if (state.next.isNotEmpty()) {
                list.addView(section("UP NEXT"))
                state.next.take(3).forEach { list.addView(planRow(it, colors, false, day)) }
            }
        }
        list.addView(section("ALL DAY"))
        blocks.forEach { list.addView(planRow(it, colors, state != null && it in state.over, day)) }
    }

    private fun planRow(b: Block, colors: Map<String, Int>, over: Boolean, day: LocalDate, note: String? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(40)
        setPadding(0, dp(4), 0, dp(4))
        addView(View(context).apply { setBackgroundColor(if (over) faded(colors.of(b), 110) else colors.of(b)) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        addView(mono("${clockText(b.start)}–${clockText(b.end)}", 12f, if (over) theme().muted else theme().alt), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        addView(label(b.text, 15f, if (over) theme().muted else theme().text).apply { strike(over) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (note != null) addView(mono(note, 11f, theme().muted))
        setOnLongClickListener { blockMenu(b, day); true }
    }

    private fun blockMenu(b: Block, day: LocalDate) {
        menuDialog(b.text, Choice("Edit") { editBlock(b, day) }, Choice("Delete", danger = true) {
            store.saveSchedule(day, store.schedule(day) - b)
            keepingScroll { render() }
        })
    }

    private fun editBlock(b: Block, day: LocalDate) {
        val written = "${clockText(b.start)}-${clockText(b.end)} ${b.text}"
        val input = field("09:00-10:30 Gym").apply { setText(written); setSelection(written.length) }
        formDialog("Edit block", "Save", listOf(input)) {
            val new = parseSchedule(input.text.toString())
            if (new == null) Toast.makeText(this, "Write it like this: 09:00-10:30 Gym", Toast.LENGTH_LONG).show() else {
                store.saveSchedule(day, store.schedule(day) - b)
                new.forEach { store.placeOnSchedule(day, it) }
                keepingScroll { render() }
            }
        }
    }

    private fun fillHabits(list: LinearLayout) {
        val day = store.todayDate
        val habits = store.habits()
        val weekly = habits.filter { it.taper == null }
        if (weekly.isNotEmpty()) {
            val doneToday = weekly.count { (it.log[day.toString()] ?: 0.0) > 0 }
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(this@HomeActivity.tag(day.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.ENGLISH))))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(mono("TODAY ", 10f, theme().muted))
                addView(mono("$doneToday/${weekly.size}", 13f, if (doneToday == weekly.size) theme().good else theme().alt).apply { setTypeface(typeface, Typeface.BOLD) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        habits.forEach { h ->
            val row = when {
                h.taper != null -> taperRow(h, h.taper)
                h.measure != null -> measureRow(h, h.measure)
                else -> weeklyRow(h, day)
            }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
        list.addView(mono("LONG-PRESS A CARD FOR STATS", 9f, faded(theme().muted, 160)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        if (habits.none { it.taper != null }) list.addView(mono("To cut down on coffee, press \"+ Taper\".", 12f, theme().muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Each habit sits in its own frame, lit in green once its goal is met. */
    private fun habitCard(edge: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = frame(theme().bg, edge = edge)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** Tap to mark today done or undo it; the week shows underneath, and its past days can be tapped the same way. */
    private fun weeklyRow(h: Habit, day: LocalDate) = habitCard(faded(if (weekCount(h, day) >= (h.perWeek ?: 0)) theme().good else theme().alt, 150)).apply {
        val doneToday = (h.log[day.toString()] ?: 0.0) > 0
        val count = weekCount(h, day)
        val goal = h.perWeek ?: 0
        val met = count >= goal
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(h.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mono("WEEK ", 10f, theme().muted))
            addView(mono("$count/$goal", 14f, if (met) theme().good else theme().alt).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(Tick(context, doneToday), LinearLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(12) })
        })
        addView(Segments(context, if (goal == 0) 0f else (count.toFloat() / goal).coerceAtMost(1f), if (met) theme().good else theme().alt), LinearLayout.LayoutParams(-1, dp(3)).apply { setMargins(0, dp(8), 0, dp(8)) })
        // Each day of the week can be tapped too, for a day that was done but not logged; days to come can't.
        addView(LinearLayout(context).apply {
            weekDays(day).forEachIndexed { i, d ->
                val had = h.log[d.toString()] ?: 0.0
                val future = d.isAfter(day)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(DayBox(context, had > 0, future, d == day), LinearLayout.LayoutParams(-1, -2))
                    addView(mono(DAY_NAMES[i].caps(), 9f, when { d == day -> theme().accent; future -> faded(theme().muted, 110); else -> theme().muted }).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(2), 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                    contentDescription = "${DAY_NAMES[i]}: ${if (had > 0) "done" else "not done"}"
                    if (!future) setOnClickListener {
                        store.logHabit(h.name, if (had > 0) -had else 1.0, d.toString())
                        keepingScroll { render() }
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        })
        setOnClickListener {
            store.logHabit(h.name, if (doneToday) -(h.log[day.toString()] ?: 0.0) else 1.0)
            keepingScroll { render() }
        }
        setOnLongClickListener { openStats(h); true }
    }

    /** Today's doses against a limit that falls each week, with the last week for comparison. */
    private fun taperRow(h: Habit, t: Taper) = run {
        val day = store.todayDate
        val had = h.log[day.toString()] ?: 0.0
        val limit = taperLimit(t, day)
        val over = had > limit
        val unit = t.unit.substringAfterLast(' ').caps()
        habitCard(faded(if (over) theme().hot else theme().alt, 150)).apply {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(h.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(mono("TAPER · ${t.unit.caps()}", 9f, theme().muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(heading(dose(had), 24f).apply { setTextColor(if (over) theme().hot else theme().good) })
                addView(mono(" / ${dose(limit)} $unit", 12f, theme().muted))
            })
            addView(Segments(context, if (limit <= 0) (if (had > 0) 1f else 0f) else (had / limit).toFloat().coerceAtMost(1f), if (over) theme().hot else theme().good), LinearLayout.LayoutParams(-1, dp(3)).apply { setMargins(0, dp(8), 0, dp(8)) })
            addView(LinearLayout(context).apply {
                listOf(-0.5 to "− ½", -0.25 to "− ¼", 0.25 to "+ ¼", 0.5 to "+ ½", 1.0 to "+ 1").forEachIndexed { i, (v, s) ->
                    addView(chip(s) { store.logHabit(h.name, v); keepingScroll { render() } }.apply {
                        if (v < 0) { setTextColor(theme().muted); background = Glow(faded(theme().muted, 160), resources.displayMetrics.density) }
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(if (i == 2) 12 else 5) })
                }
            })
            // The last week as columns; tap one to write down what was had that day.
            val days = (6L downTo 0L).map { day.minusDays(it) }
            val top = maxOf(t.start, days.maxOf { h.log[it.toString()] ?: 0.0 }, 0.5)
            addView(LinearLayout(context).apply {
                setPadding(0, dp(12), 0, 0)
                days.forEach { d ->
                    val v = h.log[d.toString()] ?: 0.0
                    val isToday = d == day
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(mono(dose(v), 10f, if (isToday) theme().text else theme().alt).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                        addView(DoseBar(context, v, taperLimit(t, d), top, isToday), LinearLayout.LayoutParams(-1, dp(38)).apply { topMargin = dp(2) })
                        addView(mono(if (isToday) "TDY" else DAY_NAMES[d.dayOfWeek.value - 1].caps(), 9f, if (isToday) theme().accent else theme().muted).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(3), 0, 0)
                        }, LinearLayout.LayoutParams(-1, -2))
                        contentDescription = "${d.dayOfMonth}: ${dose(v)} ${t.unit}"
                        setOnClickListener { logPastDose(h, t, d) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(mono("EVERY WEEK −${dose(t.step)}", 10f, theme().muted), LinearLayout.LayoutParams(0, -2, 1f))
                addView(mono("NEXT WEEK ", 10f, theme().muted))
                addView(mono("≤ ${dose(taperLimit(t, day.plusDays(7)))}", 11f, theme().alt).apply { setTypeface(typeface, Typeface.BOLD) })
            })
            setOnLongClickListener { openStats(h); true }
        }
    }

    private fun logPastDose(h: Habit, t: Taper, d: LocalDate) {
        val had = h.log[d.toString()] ?: 0.0
        val amount = field("How many ${t.unit}?").apply {
            setText(dose(had))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val title = "${h.name}, ${d.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.ENGLISH))} (${t.unit})"
        formDialog(title, "Save", listOf(amount)) {
            val v = amount.text.toString().replace(',', '.').toDoubleOrNull()
            if (v != null && v >= 0 && v <= 50) {
                store.logHabit(h.name, v - had, d.toString())
                keepingScroll { render() }
            }
        }
    }

    private val DAY_NAMES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    private fun openStats(h: Habit) {
        startActivity(Intent(this, StatsActivity::class.java).putExtra(StatsActivity.EXTRA, h.name))
    }

    /** Today's amount, its parts beside it, and the last week as columns; tap to add, tap a column to set that day. */
    private fun measureRow(h: Habit, m: Measure) = run {
        val day = store.todayDate
        val had = h.log[day.toString()] ?: 0.0
        habitCard(faded(if (had > 0) theme().accent else theme().alt, 150)).apply {
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(h.name, 16f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(mono(if (m.split) "SPENDING · TODAY" else "TRACKER · TODAY", 9f, theme().muted))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(heading(amountText(had), 24f).apply { setTextColor(if (had > 0) theme().accent else theme().muted) })
                addView(mono(" ${m.unit}", 12f, theme().muted))
            })
            if (m.split) {
                // The money card: this month at a glance; the book opens on a tap.
                val month = monthTotals(store.expenses(), java.time.YearMonth.from(day))
                addView(mono("THIS MONTH  −${amountText(month.spent)}  +${amountText(month.earned)}  ${m.unit}", 10f, theme().alt).apply { setPadding(0, dp(4), 0, 0) })
            }
            val names = partNames(h)
            val todayParts = names.mapNotNull { p -> h.parts[p]?.get(day.toString())?.takeIf { it > 0 }?.let { p to it } }
            val shown = if (m.split) todayParts.sortedByDescending { it.second }.take(3) else names.map { p -> p to (h.parts[p]?.get(day.toString()) ?: 0.0) }
            if (shown.isNotEmpty()) addView(mono(shown.joinToString("  ·  ") { (p, v) ->
                "${p.caps()} ${amountText(v)}${if (m.split) "" else m.partUnit}"
            }, 10f, theme().alt).apply { setPadding(0, dp(4), 0, 0) })
            val days = (6L downTo 0L).map { day.minusDays(it) }
            val top = maxOf(days.maxOf { h.log[it.toString()] ?: 0.0 }, 1.0)
            addView(LinearLayout(context).apply {
                setPadding(0, dp(10), 0, 0)
                days.forEach { d ->
                    val v = h.log[d.toString()] ?: 0.0
                    val isToday = d == day
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(mono(if (v > 0) shortAmount(v) else "·", 9f, if (isToday) theme().text else theme().alt).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(-1, -2))
                        addView(DoseBar(context, v, null, top, isToday), LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(2) })
                        addView(mono(if (isToday) "TDY" else DAY_NAMES[d.dayOfWeek.value - 1].caps(), 9f, if (isToday) theme().accent else theme().muted).apply {
                            gravity = Gravity.CENTER
                            setPadding(0, dp(3), 0, 0)
                        }, LinearLayout.LayoutParams(-1, -2))
                        contentDescription = "${d.dayOfMonth}: ${amountText(v)} ${m.unit}"
                        setOnClickListener { if (m.split) openBook() else setMeasureDay(h, m, d) }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
            setOnClickListener { if (m.split) openBook() else addToMeasure(h, m) }
            setOnLongClickListener { openStats(h); true }
        }
    }

    private fun openBook() = startActivity(Intent(this, MoneyActivity::class.java))

    /** 1250 as "1,3B": a narrow column has room for little. */
    private fun shortAmount(v: Double) = if (v >= 1000) amountText(v / 1000).let { if (it.length > 3) it.substringBefore(',') else it } + "B" else amountText(v)

    private fun number(hint: String) = field(hint).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

    private fun numberOf(f: android.widget.EditText) = parseAmount(f.text.toString())

    /** Adds to today: the amount and each part. */
    private fun addToMeasure(h: Habit, m: Measure) {
        val amount = number("How many ${m.unit}?")
        val parts = m.parts.map { it to number("$it (${m.partUnit})") }
        formDialog("${h.name}: add to today", "Add", listOf(amount) + parts.map { it.second }) {
            val v = numberOf(amount)
            val p = parts.mapNotNull { (n, f) -> numberOf(f)?.takeIf { it >= 0 }?.let { n to it } }.toMap()
            if ((v == null || v <= 0) && p.isEmpty()) return@formDialog
            store.logHabit(h.name, v?.coerceAtLeast(0.0) ?: 0.0, store.today, p)
            keepingScroll { render() }
        }
    }

    /** Writes a day's figures over what was there, to fix a slip or fill in a day that was missed. */
    private fun setMeasureDay(h: Habit, m: Measure, d: LocalDate) {
        val title = "${h.name}, ${d.format(DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.ENGLISH))}"
        val amount = number("${m.unit}").apply { h.log[d.toString()]?.let { setText(dose(it)) } }
        val parts = m.parts.map { it to number("$it (${m.partUnit})").apply { h.parts[it]?.get(d.toString())?.let { v -> setText(dose(v)) } } }
        formDialog(title, "Save", listOf(amount) + parts.map { it.second }) {
            val v = (numberOf(amount) ?: 0.0).coerceAtLeast(0.0)
            store.setMeasureDay(h.name, d.toString(), v, parts.associate { (n, f) -> n to (numberOf(f) ?: 0.0).coerceAtLeast(0.0) })
            keepingScroll { render() }
        }
    }

    private fun addMeasure() {
        fun make(h: Habit) {
            store.saveHabits(store.habits().filter { normalize(it.name) != normalize(h.name) } + h)
            render()
        }
        val have = store.habits()
        menuDialog(
            "Add a tracker",
            Choice("Spending ($, with categories)") {
                if (have.any { it.measure?.split == true }) Toast.makeText(this, "Bir harcama takibi zaten var.", Toast.LENGTH_SHORT).show()
                else make(Habit("Spending", null, null, measure = Measure(CURRENCY, split = true)))
            },
            Choice("Nutrition (kcal + protein, carbs, fat)") {
                make(Habit("Nutrition", null, null, measure = Measure("kcal", listOf("Protein", "Carbs", "Fat"), "g")))
            },
            Choice("Something else") {
                val name = field("What? (e.g. Water)").apply { setSingleLine() }
                val unit = field("Unit (e.g. glass)").apply { setSingleLine() }
                val parts = field("Also track, comma separated (optional)").apply { setSingleLine() }
                val partUnit = field("Their unit (e.g. g)").apply { setSingleLine() }
                formDialog("Add a tracker", "Add", listOf(name, unit, parts, partUnit)) {
                    val n = name.text.toString().trim()
                    val u = unit.text.toString().trim()
                    if (n.isNotEmpty() && u.isNotEmpty()) make(Habit(n, null, null, measure = Measure(
                        u, parts.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() }, partUnit.text.toString().trim(),
                    )))
                }
            },
        )
    }

    private fun addHabit() {
        val name = field("What? (e.g. Gym)").apply { setSingleLine() }
        val times = field("How many times a week?").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        formDialog("Add habit", "Add", listOf(name, times)) {
            val n = name.text.toString().trim()
            val w = times.text.toString().toIntOrNull()?.coerceIn(1, 7)
            if (n.isNotEmpty() && w != null) {
                store.saveHabits(store.habits().filter { normalize(it.name) != normalize(n) } + Habit(n, w, null))
                render()
            }
        }
    }

    private fun addTaper() {
        val name = field("What?").apply { setSingleLine(); setText("Coffee") }
        val unit = field("Unit").apply { setSingleLine(); setText("tablespoon") }
        val start = field("How many per day? (e.g. 2)").apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        formDialog("Add taper", "Start", listOf(name, unit, start)) {
            val n = name.text.toString().trim()
            val s = start.text.toString().replace(',', '.').toDoubleOrNull()
            if (n.isNotEmpty() && s != null && s > 0) {
                store.saveHabits(store.habits().filter { normalize(it.name) != normalize(n) } + Habit(n, null, Taper(s, taperStep(s), store.today, unit.text.toString().trim().ifEmpty { "dose" })))
                render()
            }
        }
    }

    private fun showApps(query: String) {
        val found = apps
        appList.show(rankApps(query, found.keys.toList()).map { it to found.getValue(it) })
    }

    private fun pickDay(chosen: (LocalDate) -> Unit) {
        dateDialog("Pick a day", store.todayDate.plusDays(2), store.todayDate, chosen)
    }

    /** Long press on a task: everything Jarvis can do to it, without Jarvis. */
    private fun taskMenu(t: Placed) {
        val waiting = Reminders.forTask(this, t.text)
        menuDialog(
            t.text,
            *listOfNotNull(
                Choice("Edit") { editTask(t) },
                Choice("Move to another day") { pickDay { day -> store.move(t, day); render() } },
                Choice("Remind me (1 hour before)") { remindTask(t) },
                if (waiting.isEmpty()) null else Choice("Remove reminder (${waiting.size})") { waiting.forEach { Reminders.remove(this, it.id) } },
                Choice("Delete", danger = true) { store.remove(t); render() },
            ).toTypedArray(),
        )
    }

    /** Asks when the task happens on its day and sets the reminder an hour before it. */
    private fun remindTask(t: Placed) {
        val next = java.time.LocalTime.now().plusHours(2)
        android.app.TimePickerDialog(this, { _, h, m ->
            // Past midnight today's list still belongs to the day before; the event can't be in the past.
            val event = (if (t.day.isBefore(LocalDate.now())) LocalDate.now() else t.day).atTime(h, m)
            val at = leadTime(event, java.time.LocalDateTime.now())
            if (at == null) {
                android.widget.Toast.makeText(this, "That time has passed, sir.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                Reminders.add(this, t.text, at, event, t.text)
                android.widget.Toast.makeText(this, "Reminder ${at.toLocalDate()} ${clock(at.toLocalTime())}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }, next.hour, 0, true).show()
    }

    private fun editTask(t: Placed) {
        val input = field("Task").apply { setText(t.text); setSelection(t.text.length) }
        formDialog("Edit task", "Save", listOf(input)) {
            val new = input.text.toString().trim()
            if (new.isNotEmpty()) { store.rename(t, new); render() }
        }
    }

    private fun routineMenu(r: Routine) {
        menuDialog(r.text, Choice("Remove from routine", danger = true) {
            store.saveRoutines(store.routines().filter { it != r })
            render()
        })
    }

    private fun tab(text: String, index: Int) = subTab(text, shownTab == index, index) { shownTab = index }

    /** The second level: only an uneven edge underneath, so it can't be mistaken for the filled main tabs above it. */
    private fun subTab(text: String, open: Boolean, shape: Int, choose: () -> Unit) = TextView(this).apply {
        this.text = text.caps()
        gravity = Gravity.CENTER
        typeface = theme().mono(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(if (open) theme().alt else theme().muted)
        background = Rail(if (open) theme().alt else faded(theme().muted, 150), resources.displayMetrics.density, open, shape)
        setPadding(dp(4), dp(6), dp(4), dp(9))
        setOnClickListener {
            choose()
            render()
        }
    }

    private fun tab(text: String, open: Boolean, choose: () -> Unit): View {
        val color = if (open) theme().hot else theme().muted
        return TextView(this).apply {
            this.text = text.caps()
            gravity = Gravity.CENTER
            setTypeface(theme().mono(context), Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (open) theme().bg else color)
            background = frame(if (open) color else theme().bg, edge = color)
            setPadding(dp(6), dp(7), dp(6), dp(7))
            setOnClickListener {
                choose()
                render()
            }
        }
    }

    private fun fillList(list: LinearLayout) {
        list.removeAllViews()
        val now = store.todayDate
        if (shownTab == 2) return fillWeek(list)
        if (shownTab == 1) {
            val plan = store.plan()
            if (plan.isEmpty()) list.addView(mono("No plan yet. Add one below or tell Jarvis.", 12f, theme().muted).apply { setPadding(0, dp(10), 0, 0) })
            plan.forEachIndexed { i, line -> list.addView(taskRow(i, line, false, Placed(line, now.plusDays(1)), null)) }
            return
        }
        ordered(store.todos()).forEachIndexed { i, todo ->
            list.addView(taskRow(i, todo.text, todo.done, Placed(todo.text, now)) {
                val at = listScroll?.scrollY ?: 0
                store.saveTodos(store.todos().map { if (it == todo) it.copy(done = !it.done) else it })
                render()
                listScroll?.post { listScroll?.scrollTo(0, at) }
            })
        }
    }

    /** Everything planned after tomorrow by day, then what comes back every week. */
    private fun fillWeek(list: LinearLayout) {
        val title = DateTimeFormatter.ofPattern("d MMMM EEEE", Locale.ENGLISH)
        val agenda = store.agenda()
        val routines = store.routines()
        fun heading(s: String) = mono(s, 11f, theme().alt).apply { setPadding(0, dp(12), 0, dp(2)) }
        if (agenda.isEmpty()) list.addView(mono("Nothing after tomorrow. Add below or say \"add this to Monday\".", 12f, theme().muted).apply { setPadding(0, dp(10), 0, 0) })
        agenda.forEach { (d, lines) ->
            val day = LocalDate.parse(d)
            list.addView(heading(day.format(title).caps()))
            lines.forEachIndexed { i, line -> list.addView(taskRow(i, line, false, Placed(line, day), null)) }
        }
        if (routines.isNotEmpty()) list.addView(heading("EVERY WEEK"))
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        routines.forEachIndexed { i, r ->
            val row = taskRow(i, "${r.text}  (${r.days.sorted().joinToString(" ") { names[it - 1] }})", false, null, null)
            row.setOnLongClickListener { routineMenu(r); true }
            list.addView(row)
        }
    }

    private fun taskRow(i: Int, text: String, done: Boolean, task: Placed?, onClick: (() -> Unit)?) = LinearLayout(this).apply {
        if (task != null) setOnLongClickListener { taskMenu(task); true }
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        setPadding(0, dp(6), 0, dp(6))
        addView(mono("%02d".format(i + 1), 12f, if (done) theme().muted else theme().alt), LinearLayout.LayoutParams(dp(26), -2))
        addView(label(text, 15f, if (done) theme().muted else theme().text).apply { strike(done) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (onClick != null) {
            addView(Tick(context, done), LinearLayout.LayoutParams(dp(26), dp(26)).apply { leftMargin = dp(8) })
            setOnClickListener { onClick() }
        }
    }

    private fun homeTone(): String {
        val todos = store.todos()
        return if (todos.isNotEmpty() && todos.all { it.done }) "positive" else "normal"
    }

    /** Home has nowhere to go back to; back only shrinks the panel or leaves the search. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panelFull) setPanelFull(false) else closeSearch?.invoke()
    }

    companion object {
        /** False after an update or a kill, when the system falls back to the phone's own launcher. */
        @Volatile var alive = false
        val NAVY = Color.parseColor("#0B1530")
        const val RING_DP = 112
        const val ASK_MIC = 3
    }
}
