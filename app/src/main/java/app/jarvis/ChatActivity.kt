package app.jarvis

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

class ChatActivity : Activity() {
    private lateinit var face: LinearLayout
    private lateinit var status: TextView
    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var composer: LinearLayout
    private lateinit var bridge: Bridge
    private lateinit var store: Store
    private lateinit var cfg: Config
    private lateinit var jarvis: Jarvis
    private var voicePlanning = false
    private var lastSpoken: String? = null
    private lateinit var tabs: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    /** The session on screen; lines going into any other are written there but not shown here. */
    private lateinit var shown: ChatSession
    private val onLine: (ChatLine, String) -> Unit = { line, session ->
        if (session == shown.id) {
            if (line.tone != null) showTone(line.tone)
            bubble(line)
            scrollDown()
        }
    }
    private var online: Boolean? = null
    private var planning = false
    private var planDraft = ""
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            thread {
                val up = bridge.isUp()
                if (up) jarvis.pullNews()
                runOnUiThread { setOnline(up) }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Config(this); store = Store(this); bridge = Bridge(cfg.bridgeUrl); jarvis = Jarvis(this)
        Live.lines.add(onLine)
        paintBars()
        val root = column().apply {
            background = backdrop()
            setPadding(dp(10), dp(14), dp(10), dp(8))
        }
        face = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Touch only: focused, the keyboard's Enter would press it and start him listening.
            isFocusable = false
            setOnClickListener {
                val running = VoiceService.current
                if (running != null) running.toggle() else VoiceService.ensure(this@ChatActivity, store, listen = true)
            }
        }
        status = mono("", 11f, theme().muted)
        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            defaultFocusHighlightEnabled = false
            addView(log)
        }
        composer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(face, LinearLayout.LayoutParams(dp(96), dp(96)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(tag(">> JARVIS.exe"), LinearLayout.LayoutParams(-2, -2))
                addView(status.apply { setPadding(0, dp(6), dp(6), 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(10) })
        })
        root.addView(Barcode(this, faded(theme().accent, 90)), LinearLayout.LayoutParams(-1, dp(6)).apply { setMargins(0, dp(6), 0, dp(6)) })
        tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(composer)
        setContentView(root)
        // Jarvis starting the talk, or planning tomorrow, are today's business.
        if (intent.hasExtra(EXTRA_OPENING) || intent.getBooleanExtra(EXTRA_PLAN_TOMORROW, false)) {
            store.chooseChat(daySessionId(store.today))
        }
        show(store.activeChat())
        intent.getStringExtra(EXTRA_OPENING)?.let { ask(it, shown = false) }
        if (intent.getBooleanExtra(EXTRA_PLAN_TOMORROW, false) && store.planningDue(cfg)) planning = true
        voicePlanning = planning && intent.getBooleanExtra(EXTRA_VOICE, false)
        refreshComposer()
    }

    /** Puts [session] on screen: its tab lit, its conversation in the log. */
    private fun show(session: ChatSession) {
        shown = session
        log.removeAllViews()
        val history = store.chatHistory(session.id)
        showTone(history.lastOrNull { it.tone != null }?.tone ?: "normal")
        history.forEach(::bubble)
        scrollDown()
        drawTabs()
    }

    private fun pick(session: ChatSession) {
        store.chooseChat(session.id)
        show(session)
    }

    private fun drawTabs() {
        tabs.removeAllViews()
        val all = store.chatSessions()
        val today = store.today
        var lit: TextView? = null
        all.forEach { s ->
            val on = s.id == shown.id
            val t = tag(sessionTitle(s, all, today), if (on) theme().accent else theme().muted).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(6), dp(16), dp(6))
                setOnClickListener { if (!on) pick(s) }
                setOnLongClickListener { renameTab(s, all, today); true }
            }
            if (on) lit = t
            tabs.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        tabs.addView(tag("+ NEW", theme().alt).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(6), dp(16), dp(6))
            setOnClickListener { newSession() }
        })
        // The open one in view, wherever it sits on the strip.
        tabScroll.post { lit?.let { tabScroll.smoothScrollTo((it.left - dp(24)).coerceAtLeast(0), 0) } }
    }

    private fun newSession() {
        val name = field("Name (optional)").apply { setSingleLine() }
        formDialog("New session", "Open", listOf(name)) { show(store.newChat(name.text.toString())) }
    }

    private fun renameTab(s: ChatSession, all: List<ChatSession>, today: String) {
        val name = field("Name (leave empty for the default)").apply {
            setSingleLine()
            setText(s.name.orEmpty())
        }
        formDialog("Rename: ${sessionTitle(s, all, today)}", "Save", listOf(name)) {
            store.renameChat(s.id, name.text.toString())
            drawTabs()
        }
    }

    override fun onResume() {
        super.onResume()
        // A new day may have begun, or the voice may have moved him on, while this screen was away.
        val now = store.activeChat()
        if (now.id != shown.id) show(now) else drawTabs()
        main.post(poll)
        Live.answer = ::spokenAnswer
    }

    /** While tomorrow is being planned, what's said is the plan; otherwise it's for Jarvis as usual. */
    private fun spokenAnswer(text: String): Boolean {
        if (!planning || !store.planningDue(cfg)) return false
        voicePlanning = true
        if (isOnlyMoodWord(text)) {
            // Just a word on how today went: kept, and the question about tomorrow still stands.
            replied(text)
            store.selfMood = moodWord(text)
            VoiceService.current?.speakAndListen("Noted, sir. What is on for tomorrow?")
            return true
        }
        finishPlanning(spokenPlan(text).joinToString("\n"))
        store.chatHistory().lastOrNull()?.let { VoiceService.current?.speak(it.text) }
        return true
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        Live.answer = null
        super.onPause()
    }

    override fun onDestroy() {
        Live.lines.remove(onLine)
        super.onDestroy()
    }

    private fun setOnline(up: Boolean) {
        if (online == up) return
        online = up
        status.text = if (up) "LINK: ON // online" else "LINK: OFF // continuing with buttons"
        refreshComposer()
    }

    private fun refreshComposer() {
        composer.removeAllViews()
        if (planning && store.planningDue(cfg)) return planStep()
        planning = false
        if (store.planningDue(cfg)) composer.addView(button("Plan tomorrow") {
            planning = true
            refreshComposer()
        })
        when (online) {
            true -> showInput()
            false -> {
                showButtons()
                showInput()
            }
            null -> {}
        }
    }

    /**
     * The evening's only step: tomorrow's work, spoken or typed. Works the same with the bridge on or off.
     * How today went is one optional tap; left out, it is read from the day's tasks when the day turns over.
     */
    private fun planStep() {
        asks("normal", PLAN_QUESTION)
        val plan = field("Tomorrow's tasks").apply { minLines = 3; setText(planDraft) }
        composer.addView(plan)
        composer.addView(button("Save the plan") { finishPlanning(plan.text.toString()) })
        val picked = store.selfMood
        composer.addView(mono("How did today go (optional):", 11f, theme().muted).apply { setPadding(0, dp(8), 0, dp(2)) })
        composer.addView(LinearLayout(this).apply {
            MOOD_CHOICES.forEach { (mood, label) ->
                addView(chip(if (mood == picked) "[$label]" else label) {
                    // Redrawing would otherwise drop what was already typed for tomorrow.
                    planDraft = plan.text.toString()
                    store.selfMood = if (mood == picked) null else mood
                    refreshComposer()
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            }
        })
        composer.addView(chip("Later") {
            planning = false
            store.eveningSnoozedUntil = System.currentTimeMillis() + WatcherService.EVENING_SNOOZE_MS
            refreshComposer()
        })
    }

    /** Coming back to a question shouldn't repeat it in the conversation. */
    private fun asks(tone: String, question: String) {
        if (store.chatHistory(shown.id).lastOrNull()?.text != question) say(ChatLine("jarvis", tone, question))
        // Mid-conversation by voice: he reads it out and waits, so his name needn't be called again.
        if (voicePlanning && question != lastSpoken) {
            lastSpoken = question
            VoiceService.current?.speakAndListen(question)
        }
    }

    private fun replied(text: String) = say(ChatLine("me", null, text))

    private fun finishPlanning(planText: String) {
        val plan = planText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (plan.isNotEmpty()) replied(plan.joinToString("\n"))
        // Whatever he was already asked to add for tomorrow stays alongside tonight's list.
        val all = (store.plan() + plan).distinctBy(::normalize)
        store.savePlan(all)
        if (plan.isNotEmpty()) store.logJournal(JSONObject().put("plan", plan.joinToString("\n")))
        store.tomorrowPlannedOn = store.today
        planning = false
        planDraft = ""
        say(ChatLine("jarvis", "positive", planSavedLine(all.size)))
        refreshComposer()
    }

    private fun showInput() {
        val input = field("Message").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine()
        }
        fun send() {
            val m = input.text.toString().trim()
            if (m.isEmpty()) return
            input.setText("")
            if (online == false) {
                say(ChatLine("me", null, m))
                jarvis.offline(m)
            } else {
                ask(m, shown = true)
            }
        }
        input.setOnEditorActionListener { _, _, _ -> send(); true }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(chip("Send") { send() }, LinearLayout.LayoutParams(dp(72), -2).apply { leftMargin = dp(6) })
        composer.addView(row)
    }

    private fun showButtons() {
        offlineChoices(store.phase(cfg), nudge(store)).forEach { c ->
            composer.addView(button(c.label) {
                say(ChatLine("me", null, c.label))
                say(ChatLine("jarvis", c.tone, c.reply))
            })
        }
    }

    private fun showTone(tone: String) {
        face.removeAllViews()
        face.addView(Reactor(this, tone), LinearLayout.LayoutParams(dp(96), dp(96)))
    }

    /**
     * Talking here keeps him in the session on screen. Past midnight, an old day left open stays
     * the conversation until another tab is picked, instead of the next line landing in a new day.
     */
    private fun hold() {
        if (store.activeChat().id != shown.id) store.chooseChat(shown.id)
    }

    private fun say(line: ChatLine) {
        hold()
        jarvis.say(line)
    }

    private fun ask(message: String, shown: Boolean) {
        hold()
        // Typed questions get written answers. The exception starts a long recording, which waits for him to finish saying so.
        jarvis.ask(message, shown, onReply = { reply, actions ->
            if (Action.Listen in actions || Action.ListenPlan in actions) VoiceService.current?.speak(reply)
        }, unreachable = {
            if (shown) jarvis.offline(message) else say(ChatLine("jarvis", "caution", "I cannot reach the bridge, sir. We can continue with the buttons."))
            setOnline(false)
        })
    }

    private fun bubble(line: ChatLine) {
        val mine = line.who == "me"
        val text = label(line.text, 16f).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = frame(if (mine) theme().mine else null, edge = if (mine) theme().accent else if (cyber()) toneColor(line.tone) else null)
            maxWidth = (resources.displayMetrics.widthPixels * 0.72).toInt()
        }
        val row = LinearLayout(this).apply {
            gravity = if (mine) Gravity.END else Gravity.START or Gravity.BOTTOM
            setPadding(0, dp(3), 0, dp(3))
        }
        row.addView(text)
        log.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    private fun scrollDown() = scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }

    companion object {
        const val EXTRA_OPENING = "opening"
        const val EXTRA_PLAN_TOMORROW = "planTomorrow"
        const val EXTRA_VOICE = "voice"
        const val POLL_MS = 30_000L
        const val PLAN_QUESTION = "What is on for tomorrow, sir? Tell me, or write one task per line. If you like, tell me in a word how today went."
        private val MOOD_CHOICES = listOf(DayMood.PROUD to "Good", DayMood.MIXED to "Mixed", DayMood.DISAPPOINTED to "Bad")
    }
}
