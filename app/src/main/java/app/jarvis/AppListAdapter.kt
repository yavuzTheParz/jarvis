package app.jarvis

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The search results. Builds rows only for what's on screen and reuses them while scrolling, and
 * loads icons off the main thread, so hundreds of apps can't freeze a touch or a keystroke.
 */
class AppListAdapter(private val ctx: Context, private val open: (String) -> Unit) : BaseAdapter() {
    private var rows: List<Pair<String, String>> = emptyList()
    private val icons = HashMap<String, Drawable>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun show(found: List<Pair<String, String>>) {
        rows = found
        notifyDataSetChanged()
    }

    override fun getCount() = rows.size
    override fun getItem(i: Int) = rows[i]
    override fun getItemId(i: Int) = i.toLong()

    private class Row(val icon: ImageView, val name: TextView)

    override fun getView(i: Int, reuse: View?, parent: ViewGroup): View {
        val view = reuse ?: LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(52)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            val icon = ImageView(ctx)
            val name = ctx.label("", 16f)
            addView(icon, LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { rightMargin = ctx.dp(12) })
            addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            tag = Row(icon, name)
        }
        val row = view.tag as Row
        val (name, pkg) = rows[i]
        row.name.text = name
        row.icon.tag = pkg
        row.icon.setImageDrawable(icons[pkg])
        if (pkg !in icons) loader.execute {
            val d = runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return@execute
            main.post {
                icons[pkg] = d
                if (row.icon.tag == pkg) row.icon.setImageDrawable(d)
            }
        }
        view.setOnClickListener { open(pkg) }
        return view
    }
}
