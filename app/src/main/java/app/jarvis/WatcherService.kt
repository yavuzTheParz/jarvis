package app.jarvis

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.concurrent.thread

class WatcherService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private lateinit var store: Store
    private lateinit var shorts: ShortsTracker
    private lateinit var policy: MorningPolicy

    private var foreground: String? = null
    private var lastUrl: String? = null
    private var browserSince = 0L
    private var inShorts = false
    private var lastSwipe = 0L
    private var lastTick = 0L
    private val isAppCache = HashMap<String, Boolean>()

    override fun onServiceConnected() {
        cfg = Config(this)
        store = Store(this)
        store.purgeUnloggedActivity()
        shorts = ShortsTracker(cfg.swipesAllowed, cfg.nagMs, onSwiped = { store.shortsSwiped += 1 })
        policy = MorningPolicy(
            alwaysAllowed = ALWAYS_ALLOWED + NEVER_BLOCKED + packageName,
            allowedPackages = cfg.allowedPackages,
            allowedPrefixes = cfg.allowedPrefixes,
            allowedUrls = cfg.allowedUrls,
            browsers = URL_BARS.keys,
            whatsappLimitMs = cfg.whatsappMs,
        )
        lastTick = System.currentTimeMillis()
        main.post(ticker)
        main.postDelayed({ restoreHomeIfOnLauncher() }, 500)
        main.postDelayed({ restoreHomeIfOnLauncher() }, 2_000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** The phone's own launchers, which the system falls back to when home has died. */
    private val otherHomes by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo }
    }

    /** Brings home back if it died, as an update does. Does nothing while it is alive. */
    private fun restoreHome() {
        if (HomeActivity.alive) return
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    /** An update restarts this service after the launcher has come up, so no window change will ever tell us. */
    private fun restoreHomeIfOnLauncher() {
        val on = rootInActiveWindow?.packageName?.toString() ?: return
        if (otherHomes.any { it.packageName == on }) restoreHome()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        // A ringing alarm or a call always wins: clear any cover at once, even for window-less packages.
        if (pkg in URGENT && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) clearOverlay()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && otherHomes.any { it.name == e.className?.toString() }) restoreHome()
        // Keyboards, the status bar and other window-less packages are not "where the user is".
        if (!isApp(pkg)) return
        // After a service restart the app on top sends no window change, so also trust the active window.
        val switched = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || rootInActiveWindow?.packageName == pkg
        if (switched && pkg != foreground) {
            foreground = pkg
            lastUrl = null
            inShorts = false
            browserSince = System.currentTimeMillis()
            store.logActivity(pkg, null)
            enforce()
        }
        if (pkg != foreground) return
        when {
            pkg in URL_BARS -> readBrowser(pkg)
            pkg == YOUTUBE -> readYoutube(e)
            pkg == MONEY_TRACKER -> readMoney()
        }
    }

    private var lastMoneyRead = 0L
    private var moneyPending = false

    /**
     * The spending app: what its list shows goes into the spending measure, and a payment's own page settles
     * its category. Reads at most every so often, once the screen has settled, since scrolling floods events.
     */
    private fun readMoney() {
        if (moneyPending) return
        moneyPending = true
        val wait = (lastMoneyRead + MONEY_READ_MS - System.currentTimeMillis()).coerceAtLeast(MONEY_SETTLE_MS)
        main.postDelayed({
            moneyPending = false
            lastMoneyRead = System.currentTimeMillis()
            val root = rootInActiveWindow?.takeIf { it.packageName == MONEY_TRACKER } ?: return@postDelayed
            val labels = mutableListOf<String>()
            val texts = mutableListOf<String>()
            fun walk(n: android.view.accessibility.AccessibilityNodeInfo?) {
                n ?: return
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(labels::add)
                (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(root)
            val changed = runCatching {
                val detail = parseMoneyDetail(texts)
                if (detail != null) store.fileExpense(detail)
                else store.importExpenses(rowsAsExpenses(parseMoneyList(labels), store.expenses())) > 0
            }.getOrDefault(false)
            if (changed) Live.tasksChanged()
        }, wait)
    }

    private fun isApp(pkg: String) = isAppCache.getOrPut(pkg) {
        val keyboards = getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
        pkg != SYSTEM_UI && pkg !in keyboards &&
            (pkg == packageName || pkg in ALWAYS_ALLOWED || packageManager.getLaunchIntentForPackage(pkg) != null)
    }

    private fun readBrowser(pkg: String) {
        val url = rootInActiveWindow
            ?.findAccessibilityNodeInfosByViewId(URL_BARS.getValue(pkg))
            ?.firstOrNull()?.text?.toString() ?: return
        // The bar holds what is being typed while focused; only complete-looking addresses count.
        if (url == lastUrl || !url.contains('.') || url.contains(' ')) return
        lastUrl = url
        store.logActivity(pkg, url)
        val id = shortsId(url)
        inShorts = id != null
        if (id != null) act(shorts.onUrl(id, System.currentTimeMillis()))
        enforce()
    }

    private fun readYoutube(e: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        inShorts = YOUTUBE_REEL_IDS.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }
        if (!inShorts || e.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val now = System.currentTimeMillis()
        if (now - lastSwipe < SWIPE_DEBOUNCE_MS) return
        lastSwipe = now
        act(shorts.onSwipe(now))
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTick
        lastTick = now
        val awake = getSystemService(PowerManager::class.java).isInteractive
        if (!awake) return
        if (foreground == policy.whatsapp && store.phase(cfg) == Phase.MORNING && now >= store.graceUntil) {
            store.whatsappSpentMs += elapsed
        }
        enforce()
        if (inShorts) act(shorts.tick(now))
        // The evening no longer covers the screen to call him over: closing the day is offered in the chat.
    }

    private fun enforce() {
        val pkg = foreground ?: return
        val now = System.currentTimeMillis()
        if (store.phase(cfg) != Phase.MORNING || now < store.graceUntil) return
        // A browser gets a moment to show its address before it is judged.
        if (pkg in URL_BARS && lastUrl == null && now - browserSince < BROWSER_GRACE_MS) return
        when (morningVerdict(pkg, lastUrl, store.whatsappSpentMs, policy)) {
            Verdict.ALLOW -> {}
            Verdict.KICK -> goHome()
            Verdict.KICK_LIMIT -> { store.limitHit = true; goHome() }
        }
    }

    private fun goHome() {
        foreground = packageName
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun act(a: ShortsAction) {
        if (overlay != null) return
        val now = { System.currentTimeMillis() }
        val keepWatching = { shorts.chooseToContinue(now()) }
        when (a) {
            ShortsAction.NONE -> {}
            ShortsAction.WARN -> showOverlay(
                "caution", "SHORTS.exe", "Sir, that is the second swipe. Is this part of what you intended for today?\n\n${nudge(store)}",
                "You stopped me while I was watching Shorts, let's talk.", keepWatching,
                Choice("Yes, I'm watching on purpose", "normal", "Understood, sir. I will ask again in five minutes.") {
                    store.shortsChosen += 1
                    keepWatching()
                },
                Choice("You're right, I'm leaving", "positive", "A wise decision, sir.") { goHome() },
            )
            ShortsAction.NAG -> showOverlay(
                "caution", "SHORTS.exe", "Five more minutes have passed, sir. Is your intent still the same?\n\n${nudge(store)}",
                "I'm still watching Shorts, you came back.", {},
                Choice("A bit more", "normal", "Noted, sir.") {},
                Choice("OK, I'm leaving", "positive", "Thank you, sir.") { goHome() },
            )
        }
    }

    private class Choice(val name: String, val tone: String?, val reaction: String?, val run: () -> Unit)

    /**
     * Covers the screen and swallows touches; the choices appear only after the block ends.
     * Buttons always work. Talking is offered on top of them only while the bridge answers.
     */
    private fun showOverlay(tone: String, window: String, text: String, talkOpening: String?, onTalk: () -> Unit, vararg choices: Choice) {
        val fancy = cyber()
        val color = toneColor(tone)
        fun body(t: String, title: String?, line: String): Pair<View, LinearLayout> =
            if (fancy) alertBody(t, title, line)
            else column(center = true).let { c ->
                c.background = backdrop()
                c.addView(speech(t, line))
                c to c
            }
        // Touches stop here, whichever page is showing.
        val box = FrameLayout(this).apply { isClickable = true }
        val (page, slot) = body(tone, window, text)
        box.addView(page)
        if (fancy) slot.addView(LockTimer(this, BLOCK_MS, color), LinearLayout.LayoutParams(-1, dp(82)))
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE,
        )
        wm.addView(box, params)
        overlay = box
        val close = {
            if (overlay === box) {
                wm.removeView(box)
                overlay = null
            }
        }
        fun option(name: String, glyph: Char, run: () -> Unit) =
            if (fancy) choiceRow(name, glyph, color, run) else button(name, run)
        var bridgeUp = false
        if (talkOpening != null) thread { bridgeUp = Bridge(cfg.bridgeUrl).isUp() }
        main.postDelayed({
            if (overlay === box) {
                if (fancy) slot.removeAllViews()
                choices.forEach { c ->
                    slot.addView(option(c.name, if (c.reaction == null || c.tone == "positive") 'v' else 'x') {
                        if (c.reaction == null) {
                            close()
                            c.run()
                        } else {
                            box.removeAllViews()
                            box.addView(body(c.tone ?: "normal", null, c.reaction).first)
                            main.postDelayed({
                                close()
                                c.run()
                            }, REACTION_MS)
                        }
                    })
                }
                if (bridgeUp) slot.addView(option("Let's talk", '>') {
                    close()
                    onTalk()
                    startActivity(
                        Intent(this, ChatActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(ChatActivity.EXTRA_OPENING, talkOpening),
                    )
                })
            }
        }, BLOCK_MS)
    }

    override fun onInterrupt() {}

    private fun clearOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        clearOverlay()
        super.onDestroy()
    }

    companion object {
        // Shared by service instances: a rebind must never stack a second cover on the first.
        private var overlay: View? = null

        const val TICK_MS = 5_000L
        const val BLOCK_MS = 4_000L
        const val REACTION_MS = 2_500L
        const val SWIPE_DEBOUNCE_MS = 1_200L
        const val MONEY_READ_MS = 1_500L
        const val MONEY_SETTLE_MS = 400L
        const val BROWSER_GRACE_MS = 4_000L
        const val EVENING_SNOOZE_MS = 60 * 60_000L
        const val SYSTEM_UI = "com.android.systemui"
        const val YOUTUBE = "com.google.android.youtube"

        // Unverified against the phone's current versions: check with `adb shell uiautomator dump`.
        val URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        )
        val YOUTUBE_REEL_IDS = listOf(
            "com.google.android.youtube:id/reel_recycler",
            "com.google.android.youtube:id/reel_player_page_container",
        )

        // What must interrupt anything, including Jarvis himself.
        val URGENT = setOf(
            "com.samsung.android.dialer", "com.samsung.android.incallui", "com.android.server.telecom",
            "com.android.phone", "com.google.android.dialer", "com.sec.android.app.clockpackage",
            "com.google.android.deskclock", "com.samsung.android.emergency", "com.sec.android.emergencylauncher",
        )

        // Calls, messages, alarms and the way out (Settings) are never blocked.
        val ALWAYS_ALLOWED = URGENT + setOf(
            "com.samsung.android.messaging", "com.google.android.apps.messaging",
            "com.android.settings", "com.google.android.permissioncontroller",
            "com.sec.android.app.launcher",
        )
    }
}
