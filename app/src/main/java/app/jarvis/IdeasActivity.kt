package app.jarvis

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView

/** The suggestions Jarvis makes when he interrupts: the user's to change at any time. */
class IdeasActivity : Activity() {
    private lateinit var store: Store

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        store = Store(this)
        paintBars()
        render()
    }

    private fun render() {
        val v = column()
        v.addView(heading("My suggestions", 24f))

        v.addView(heading("Book I'm reading", 16f).apply { setPadding(0, dp(24), 0, dp(4)) })
        val book = field("Book title").apply { setSingleLine(); setText(store.book) }
        v.addView(book)

        v.addView(heading("Things I could do", 16f).apply { setPadding(0, dp(24), 0, dp(4)) })
        store.ideas.forEachIndexed { i, idea ->
            val row = LinearLayout(this)
            row.addView(label(idea, 16f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(barButton("Delete") {
                store.ideas = store.ideas.filterIndexed { n, _ -> n != i }
                saveText(book)
                render()
            }, LinearLayout.LayoutParams(dp(56), -2))
            v.addView(row)
        }
        val add = field("Add a new task").apply {
            setSingleLine()
            setOnEditorActionListener { _, _, _ ->
                val t = text.toString().trim()
                if (t.isNotEmpty()) {
                    store.ideas = store.ideas + t
                    saveText(book)
                    render()
                }
                true
            }
        }
        v.addView(add)

        v.addView(heading("Worst case", 16f).apply { setPadding(0, dp(24), 0, dp(4)) })
        last = field("If I can't do anything").apply { setText(store.lastResort) }
        v.addView(last)

        bookField = book
        setContentView(ScrollView(this).apply {
            background = backdrop()
            isFillViewport = true
            addView(v)
        })
    }

    private var bookField: android.widget.EditText? = null
    private var last: android.widget.EditText? = null

    private fun saveText(book: android.widget.EditText) {
        store.book = book.text.toString()
        last?.let { store.lastResort = it.text.toString() }
    }

    override fun onPause() {
        bookField?.let(::saveText)
        super.onPause()
    }
}
