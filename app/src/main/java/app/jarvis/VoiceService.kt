package app.jarvis

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Waits for "Jarvis" on the phone itself; no sound leaves it until he is called. After that the
 * words go to Whisper on the PC, or to the phone's own recognizer while the PC is out of reach.
 */
class VoiceService : Service() {
    private enum class Mode { WAKE, COMMAND, DICTATE, PAUSED, RECOGNIZER }
    private enum class Request { LISTEN, DICTATE, STOP }

    private val main = Handler(Looper.getMainLooper())
    private val request = AtomicReference<Request?>(null)
    private lateinit var jarvis: Jarvis
    private lateinit var store: Store
    private lateinit var bridge: Bridge
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterSpeech: (() -> Unit)? = null
    @Volatile private var speechTurn = 0
    @Volatile private var dictatingPlan = false
    /** The next thing heard is the day's plan, told to the phone's recognizer while the PC is away. */
    @Volatile private var planByRecognizer = false
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var mode = Mode.WAKE
    @Volatile private var running = false
    @Volatile private var bridgeUp = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // Android allows the microphone only when started from a visible screen. Home retries on its next resume.
            Log.w(TAG, "not allowed to listen right now", e)
            stopSelf()
            return
        }
        current = this
        running = true
        jarvis = Jarvis(this)
        store = Store(this)
        bridge = Bridge(Config(this).bridgeUrl)
        audio = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale.UK).let { it != null && it >= TextToSpeech.LANG_AVAILABLE }
        }.apply {
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { spoke() } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { main.post { spoke() } }
            })
        }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:voice").apply { acquire() }
        thread(name = "jarvis-mic") { runCatching(::micLoop).onFailure { Log.e(TAG, "microphone stopped", it) } }
        thread(name = "jarvis-link", isDaemon = true) { linkLoop() }
    }

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.getBooleanExtra(EXTRA_PLAN, false) == true) askForPlan()
        else if (i?.getBooleanExtra(EXTRA_LISTEN, false) == true) toggle()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        current = null
        Live.voice = VoiceState.IDLE
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        tts?.shutdown()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis is listening", NotificationManager.IMPORTANCE_LOW))
        val home = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is waiting for his name")
            .setContentText("Audio does not leave the phone until you call him.")
            .setContentIntent(home)
            .setOngoing(true)
            .build()
    }

    /** Called by the ring, the assistant gesture or the side key: starts listening, or ends what's being recorded. */
    fun toggle() {
        request.set(if (mode == Mode.WAKE) Request.LISTEN else Request.STOP)
        if (mode == Mode.RECOGNIZER) main.post { recognizer?.stopListening() }
    }

    /** The long "listen to my day" recording; waits until he has finished saying he's listening. */
    fun dictate(plan: Boolean = false) {
        val pending = afterSpeech
        afterSpeech = { pending?.invoke(); dictatingPlan = plan; request.set(Request.DICTATE) }
    }

    /** The plan tab's button: same long recording, with the words going to Jarvis as the day's plan. */
    fun askForPlan() {
        // With the PC out of reach a recording would wait for it; the phone's own dictation lets him place it now.
        if (!bridgeUp && SpeechRecognizer.isRecognitionAvailable(this)) {
            planByRecognizer = true
            return speak("Your computer is off, sir; I am listening on the phone. Tell me with times.") { request.set(Request.LISTEN) }
        }
        dictate(plan = true)
        speak("Listening, sir. Say my name when you are done.")
    }

    /** A question whose answer is expected right away, so no need to call his name again. */
    fun speakAndListen(text: String) = speak(text) { request.set(Request.LISTEN) }

    fun speak(text: String, then: () -> Unit = {}) {
        val pending = afterSpeech
        afterSpeech = { then(); pending?.invoke() }
        mode = Mode.PAUSED
        Live.voice = VoiceState.SPEAKING
        val turn = ++speechTurn
        if (!bridgeUp) return phoneVoice(text)
        thread(name = "jarvis-voice") {
            val pcm = runCatching { bridge.speech(text) }.onFailure { Log.w(TAG, "his voice unavailable", it) }.getOrNull()
            if (turn != speechTurn) return@thread
            if (pcm == null) {
                main.post { phoneVoice(text) }
                return@thread
            }
            play(pcm)
            main.post { if (turn == speechTurn) spoke() }
        }
    }

    /** The PC is out of reach: the phone's own voice, or silence if it has no Turkish. */
    private fun phoneVoice(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) else spoke()
    }

    private fun play(pcm: ByteArray) {
        val format = AudioFormat.Builder().setSampleRate(VOICE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size * 1000L / (VOICE_RATE * 2) + 100)
        } finally {
            track.release()
        }
    }

    private fun spoke() {
        val next = afterSpeech
        afterSpeech = null
        // Set before resting, so a follow-up keeps the microphone when "MIC OFF" would let it go.
        next?.invoke()
        rest()
    }

    private fun rest() {
        focus?.let(audio::abandonAudioFocusRequest)
        focus = null
        Live.voice = VoiceState.IDLE
        mode = Mode.WAKE
        // Called by hand with "MIC OFF": once done he stops holding the microphone.
        if (!store.voiceOn && afterSpeech == null && request.get() == null) stopSelf()
    }

    /** Spotify would otherwise be louder in the recording than the speaker. */
    private fun quietOthers() {
        if (focus != null) return
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .build()
            .also(audio::requestAudioFocus)
    }

    private fun linkLoop() {
        while (running) {
            bridgeUp = bridge.isUp()
            if (bridgeUp) {
                retrySavedDictations()
                jarvis.pullNews()
            }
            Thread.sleep(LINK_POLL_MS)
        }
    }

    private fun micLoop() {
        val wakeWord = runCatching { WakeWord.from(assets) }.onFailure { Log.e(TAG, "wake word unavailable; the ring still works", it) }.getOrNull()
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 8),
        )
        val frame = ShortArray(FRAME)
        val bytes = ByteArray(FRAME * 2)
        val preRoll = ArrayDeque<ByteArray>()
        var command = ByteArrayOutputStream()
        var end: Endpointer? = null
        var dictation: OutputStream? = null
        var dictated = 0L
        var staleStream = false
        var sinceFlagCheck = 0
        var collecting = false

        // A short read is rare and only costs one moment of listening.
        fun spotted(n: Int) = n == FRAME && wakeWord?.heard(frame) == true

        fun beginDictation() {
            dictation = File(cacheDir, "dictation.pcm").outputStream().buffered()
            dictated = 0
            mode = Mode.DICTATE
            main.post { Live.voice = VoiceState.LISTENING; quietOthers(); beep() }
        }

        fun endDictation() {
            mode = Mode.PAUSED
            Live.voice = VoiceState.THINKING
            dictation?.close()
            // The name remembers what it was for, in case Whisper only gets it after a restart.
            val sending = File(cacheDir, "sending-${if (dictatingPlan) "plan-" else ""}${System.currentTimeMillis()}.pcm")
            dictatingPlan = false
            File(cacheDir, "dictation.pcm").renameTo(sending)
            thread { deliverDictation(sending) }
        }

        fun beginCommand() {
            if (!bridgeUp) {
                mode = Mode.RECOGNIZER
                main.post { useRecognizer() }
                return
            }
            command = ByteArrayOutputStream()
            preRoll.forEach(command::write)
            end = Endpointer(if (Live.answer != null) ANSWER_SILENCE_MS else COMMAND_SILENCE_MS, NO_SPEECH_MS, COMMAND_MAX_MS, FRAME_MS)
            mode = Mode.COMMAND
            main.post { Live.voice = VoiceState.LISTENING; quietOthers(); beep() }
        }

        fun endCommand(byHand: Boolean) {
            val pcm = command.toByteArray()
            mode = Mode.PAUSED
            // Stopped by hand, whatever was said counts, however quiet.
            if (!byHand && end?.heardSpeech != true) {
                main.post { rest() }
                return
            }
            Live.voice = VoiceState.THINKING
            thread { deliverCommand(pcm) }
        }

        rec.startRecording()
        var recording = true
        while (running) {
            if (mode == Mode.RECOGNIZER) {
                // Only one of us can hold the microphone reliably.
                if (recording) { rec.stop(); recording = false }
                Thread.sleep(100)
                continue
            }
            if (!recording) { rec.startRecording(); recording = true }
            val n = rec.read(frame, 0, FRAME)
            if (n <= 0) { Thread.sleep(50); continue }
            for (i in 0 until n) {
                bytes[i * 2] = frame[i].toByte()
                bytes[i * 2 + 1] = (frame[i].toInt() shr 8).toByte()
            }
            preRoll.addLast(bytes.copyOf(n * 2))
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

            // Debug builds: `touch cache/collect` records the raw microphone for retraining the wake word.
            if (BuildConfig.DEBUG && ++sinceFlagCheck >= 12) {
                sinceFlagCheck = 0
                collecting = File(cacheDir, "collect").exists()
            }
            if (collecting) {
                File(cacheDir, "collect.pcm").appendBytes(bytes.copyOf(n * 2))
                continue
            }

            val asked = request.getAndSet(null)
            when (mode) {
                Mode.WAKE -> when {
                    asked == Request.DICTATE -> beginDictation()
                    asked == Request.LISTEN || spotted(n) -> beginCommand()
                }
                Mode.COMMAND -> {
                    command.write(bytes, 0, n * 2)
                    var sum = 0.0
                    for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
                    val done = end?.push(sqrt(sum / n)) == true
                    if (done || asked == Request.STOP) endCommand(asked == Request.STOP)
                }
                Mode.DICTATE -> {
                    dictation?.write(bytes, 0, n * 2)
                    dictated += n * 2
                    // His name ends it, so "tamam Jarvis" works hands-free.
                    if (spotted(n) || asked == Request.STOP || dictated >= DICTATION_MAX_BYTES) endDictation()
                }
                Mode.PAUSED, Mode.RECOGNIZER -> {}
            }
            // Whatever he heard while busy must not count as his name once he's waiting again.
            if (mode != Mode.WAKE && mode != Mode.DICTATE) staleStream = true
            if (mode == Mode.WAKE && staleStream) {
                wakeWord?.reset()
                staleStream = false
            }
        }
        rec.stop()
        rec.release()
    }

    private fun beep() = runCatching {
        Log.i(TAG, "listening")
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60).run {
            startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({ release() }, 400)
        }
    }

    private fun deliverCommand(pcm: ByteArray) {
        val text = runCatching { withoutWakeWord(bridge.transcribe(pcm.inputStream(), pcm.size.toLong())) }.getOrElse {
            Log.w(TAG, "transcription failed", it)
            bridgeUp = false
            main.post { speak("I could not reach your computer, sir. Please say it once more.") }
            return
        }
        main.post { if (text.isEmpty() || isWhisperNoise(text)) rest() else heard(text) }
    }

    private fun heard(text: String) {
        Live.voice = VoiceState.THINKING
        if (planByRecognizer) {
            planByRecognizer = false
            return toldPlan(text)
        }
        if (Live.answer?.invoke(text) == true) {
            if (afterSpeech == null && Live.voice == VoiceState.THINKING) rest()
            return
        }
        jarvis.ask(text, shown = true, onReply = { reply, _ -> speak(reply) }, unreachable = {
            speak(jarvis.offline(text).first)
        })
    }

    /** A long story can't be asked for twice, so it stays on disk until Whisper has it. */
    private fun deliverDictation(file: File) {
        val text = runCatching { file.inputStream().use { withoutEndPhrase(bridge.transcribe(it, file.length())) } }.getOrElse {
            Log.w(TAG, "dictation kept for later", it)
            file.renameTo(File(cacheDir, file.name.replace("sending-", "dictation-")))
            main.post { speak("I kept what you said, sir. I will transcribe it when your computer is on.") }
            return
        }
        file.delete()
        main.post { if ("plan-" in file.name) toldPlan(text) else dictated(text) }
    }

    private fun toldPlan(text: String) {
        if (text.isEmpty()) return speak("I did not hear anything, sir.")
        jarvis.say(ChatLine("me", null, text))
        val day = if (Regex("""\byarin\b""").containsMatchIn(normalize(text))) store.todayDate.plusDays(1) else store.todayDate
        if (!bridgeUp) return speak(jarvis.planOffline(day, text))
        jarvis.ask(
            "Here is my hourly plan: $text", shown = false,
            onReply = { reply, _ -> speak(reply) },
            unreachable = { speak(jarvis.planOffline(day, text)) },
        )
    }

    private fun dictated(text: String) {
        if (text.isEmpty()) return speak("I did not hear anything, sir.")
        if (Live.answer?.invoke(text) == true) return rest()
        jarvis.say(ChatLine("me", null, text))
        jarvis.perform(Action.LogDay(text))
        jarvis.ask(
            "I described my day, it is written above and saved to the journal. Reply briefly.", shown = false,
            onReply = { reply, _ -> speak(reply) },
            unreachable = { speak("On record, sir.") },
        )
    }

    private fun retrySavedDictations() {
        if (mode == Mode.DICTATE) return
        cacheDir.listFiles { f -> f.name.matches(Regex("""dictation-(plan-)?\d+\.pcm""")) }?.sortedBy { it.name }?.forEach { f ->
            val text = runCatching { f.inputStream().use { withoutEndPhrase(bridge.transcribe(it, f.length())) } }.getOrNull() ?: return
            f.delete()
            if (text.isNotEmpty()) main.post {
                if ("plan-" in f.name) return@post toldPlan(text)
                jarvis.say(ChatLine("me", null, text))
                jarvis.perform(Action.LogDay(text))
            }
        }
    }

    /** The PC is out of reach: the phone's own recognizer, which listens and decides when you've finished by itself. */
    private fun useRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return speak("I cannot reach your computer, sir, and there is no dictation on the phone.")
        Live.voice = VoiceState.LISTENING
        quietOthers()
        beep()
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) {
                    val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    mode = Mode.PAUSED
                    if (text.isEmpty()) { planByRecognizer = false; rest() } else heard(withoutWakeWord(text))
                }
                override fun onError(error: Int) {
                    Log.w(TAG, "recognizer error $error")
                    if (planByRecognizer) {
                        planByRecognizer = false
                        return speak("I did not catch the plan, sir. You can type it in the Plan tab with times: \"9 gym, 11 class\".")
                    }
                    rest()
                }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { Live.voice = VoiceState.THINKING }
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                .apply {
                    // A plan is told with pauses to think; not every recognizer honours these.
                    if (planByRecognizer) {
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8_000L)
                    }
                })
        }
    }

    companion object {
        private const val TAG = "JarvisVoice"
        private const val CHANNEL = "voice"
        private const val NOTIFICATION = 7
        private const val UTTERANCE = "jarvis"
        private const val RATE = 16_000
        private const val VOICE_RATE = 22_050
        private const val FRAME = WakeWord.CHUNK
        private const val FRAME_MS = 80
        private const val PRE_ROLL_FRAMES = 4
        private const val COMMAND_SILENCE_MS = 1_600
        private const val ANSWER_SILENCE_MS = 5_000
        private const val NO_SPEECH_MS = 7_000
        private const val COMMAND_MAX_MS = 60_000
        private const val DICTATION_MAX_BYTES = 60L * 1024 * 1024
        private const val LINK_POLL_MS = 60_000L
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_PLAN = "plan"

        @Volatile var current: VoiceService? = null
            private set

        fun hasMic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Starts listening for his name if the user wants that; safe to call on every resume. */
        fun ensure(ctx: Context, store: Store, listen: Boolean = false, plan: Boolean = false) {
            if (!hasMic(ctx) || (!store.voiceOn && !listen)) return
            if (current != null && !listen) return
            try {
                ctx.startForegroundService(Intent(ctx, VoiceService::class.java).putExtra(EXTRA_LISTEN, listen).putExtra(EXTRA_PLAN, plan))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start from here", e)
            }
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, VoiceService::class.java))
    }
}
