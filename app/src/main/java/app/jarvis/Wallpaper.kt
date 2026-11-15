package app.jarvis

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import kotlin.concurrent.thread

/**
 * The system wallpaper, painted as Jarvis's screen. It is what the phone shows wherever the home
 * screen itself isn't drawn: behind the recent apps, in the moment an app opens or closes, under
 * the notification shade, on the lock screen. One UI also takes its colors from it.
 *
 * The live one ([JarvisLiveWallpaper]) turns the ring like the home screen does. Android only lets
 * the user pick a live wallpaper, so until they have, a still picture of it stands in.
 */
object JarvisWallpaper {
    /** Raise this when the picture changes, so phones that already have the old one get the new one. */
    private const val VERSION = 1

    fun liveIsSet(ctx: Context) = WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName

    /** The system screen that previews the turning ring and sets it on the home and lock screens. */
    fun pickLiveIntent(ctx: Context) = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(ctx, JarvisLiveWallpaper::class.java))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Sets the still picture once per version, unless the live one is already up; cheap to call on every resume. */
    fun ensure(ctx: Context) {
        if (liveIsSet(ctx)) return
        val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (prefs.getInt("wallpaperVersion", 0) == VERSION) return
        val wm = WallpaperManager.getInstance(ctx)
        if (!wm.isWallpaperSupported || !wm.isSetWallpaperAllowed) return
        // Drawn here on the main thread, since it borrows the ring's view; only the slow hand-over is moved off it.
        val picture = runCatching { render(ctx) }.onFailure { Log.w(TAG, "could not draw the wallpaper", it) }.getOrNull() ?: return
        prefs.edit().putInt("wallpaperVersion", VERSION).apply()
        thread(name = "jarvis-wallpaper") {
            runCatching { wm.setBitmap(picture, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
                .onFailure {
                    Log.w(TAG, "wallpaper refused", it)
                    prefs.edit().putInt("wallpaperVersion", 0).apply()
                }
            picture.recycle()
        }
    }

    /** Where the ring sits on a w×h screen: its side and its top edge. */
    fun ringBox(w: Int, h: Int): Pair<Int, Float> {
        val size = (w * 0.62f).toInt()
        return size to h * 0.44f - size / 2f
    }

    /** The home screen's background with his ring in the middle, at the size of the whole screen. */
    fun render(ctx: Context): Bitmap {
        val bounds = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return render(ctx, bounds.width(), bounds.height(), withRing = true)
    }

    /** The picture without the ring, too, for the live wallpaper to turn the ring over it. */
    fun render(ctx: Context, w: Int, h: Int, withRing: Boolean): Bitmap {
        val d = ctx.resources.displayMetrics.density
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        ctx.backdrop().apply { setBounds(0, 0, w, h) }.draw(c)
        val t = ctx.theme()

        val (size, top) = ringBox(w, h)
        if (withRing) {
            val ring = Reactor(ctx, "normal").apply { selfDriven = false }
            ring.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
            ring.layout(0, 0, size, size)
            c.save()
            c.translate((w - size) / 2f, top)
            ring.draw(c)
            c.restore()
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = t.title(ctx)
            textSize = d * 26
            color = t.accent
            letterSpacing = 0.3f
            setShadowLayer(1f, d * 3, 0f, t.alt)
        }
        c.drawText("JARVIS", w / 2f, top + size + d * 48, p)
        p.apply {
            clearShadowLayer()
            typeface = t.mono(ctx)
            textSize = d * 11
            color = faded(t.alt, 200)
            letterSpacing = 0.2f
        }
        c.drawText("// SYSTEM ONLINE", w / 2f, top + size + d * 70, p)
        return bmp
    }

    const val TAG = "JarvisWallpaper"
}

/**
 * Jarvis's ring, turning on the home and lock screens. It only draws while it can be seen, so a
 * dark or covered screen costs nothing.
 */
class JarvisLiveWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = RingEngine()

    private inner class RingEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private var still: Bitmap? = null
        private var ring: Reactor? = null
        private var w = 0
        private var h = 0
        private var visible = false
        private var drawnTheme: Any? = null

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) main.postDelayed(this, Reactor.FRAME_MS)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            w = width
            h = height
            rebuild()
            draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            main.removeCallbacks(frame)
            if (visible) {
                // The theme may have changed while it was out of sight.
                if (drawnTheme !== theme()) rebuild()
                main.post(frame)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            main.removeCallbacks(frame)
        }

        override fun onDestroy() {
            main.removeCallbacks(frame)
            still?.recycle()
            still = null
        }

        /** Dark, so One UI writes the clock and the icons over it in light colors. */
        override fun onComputeColors(): WallpaperColors {
            val t = theme()
            return WallpaperColors(Color.valueOf(t.bg), Color.valueOf(toneColor("normal")), Color.valueOf(t.accent), WallpaperColors.HINT_SUPPORTS_DARK_THEME)
        }

        private fun rebuild() {
            if (w == 0 || h == 0) return
            val ctx = this@JarvisLiveWallpaper
            drawnTheme = theme()
            still?.recycle()
            still = runCatching { JarvisWallpaper.render(ctx, w, h, withRing = false) }
                .onFailure { Log.w(JarvisWallpaper.TAG, "could not draw the live wallpaper", it) }.getOrNull()
            val (size, _) = JarvisWallpaper.ringBox(w, h)
            ring = Reactor(ctx, "normal").apply {
                selfDriven = false
                measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
                layout(0, 0, size, size)
            }
            notifyColorsChanged()
        }

        private fun draw() {
            val bg = still ?: return
            val r = ring ?: return
            val c = runCatching { surfaceHolder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                c.drawBitmap(bg, 0f, 0f, null)
                val (size, top) = JarvisWallpaper.ringBox(w, h)
                c.save()
                c.translate((w - size) / 2f, top)
                r.draw(c)
                c.restore()
            } finally {
                surfaceHolder.unlockCanvasAndPost(c)
            }
        }
    }
}
