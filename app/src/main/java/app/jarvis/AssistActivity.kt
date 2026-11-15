package app.jarvis

import android.app.Activity
import android.os.Bundle

/** Lets Jarvis be picked as the phone's digital assistant: the assistant gesture starts him listening. */
class AssistActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val running = VoiceService.current
        if (running != null) running.toggle() else VoiceService.ensure(this, Store(this), listen = true)
        finish()
    }
}
