package app.jarvis

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs real sound through the exact code the phone runs, piece by piece as the microphone delivers it. */
class WakeWordTest {
    private val assets = listOf("src/main/assets/wake", "app/src/main/assets/wake").map(::File).first { it.exists() }
    private val word = WakeWord(File(assets, "melspectrogram.onnx").readBytes(), File(assets, "embedding_model.onnx").readBytes(), File(assets, "jarvis.bin").readBytes())

    private fun times(clip: String): Int = times(javaClass.getResource("/wake/$clip.pcm")!!.readBytes())

    private fun clips(dir: String): List<String> =
        File(javaClass.getResource("/wake/$dir")!!.toURI()).listFiles()!!.map { "$dir/" + it.nameWithoutExtension }.sorted()

    private fun times(bytes: ByteArray): Int {
        word.reset()
        val pcm = ShortArray(bytes.size / 2).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        // A quiet room, not digital silence: a real microphone always picks something up.
        val room = java.util.Random(1)
        fun quiet() = ShortArray(WakeWord.CHUNK) { (room.nextGaussian() * 100).toInt().toShort() }
        var hits = 0
        repeat(24) { if (word.heard(quiet())) hits++ }
        val heard = ShortArray(pcm.size) { (pcm[it] + room.nextGaussian() * 100).toInt().coerceIn(-32768, 32767).toShort() }
        for (i in 0..heard.size - WakeWord.CHUNK step WakeWord.CHUNK) if (word.heard(heard.copyOfRange(i, i + WakeWord.CHUNK))) hits++
        repeat(12) { if (word.heard(quiet())) hits++ }
        return hits
    }

    /** Voices left out of training (bridge/wake/train.py --holdout), so this is how a new user's voice fares. */
    @Test fun hearsVoicesItNeverHeard() {
        val all = clips("heard")
        val heard = all.count { times(it) >= 1 }
        assertTrue("heard only $heard of ${all.size}", heard >= all.size * 9 / 10)
    }

    /** About a minute of other voices talking, some of it with near words ("Travis", "service"): one false wake at most. */
    @Test fun rarelyWakesOnOrdinaryTalk() {
        val woke = clips("talk").filter { times(it) > 0 }
        assertTrue("woke on $woke", woke.size <= 1)
    }

    /**
     * Your own voice, if you want to check it: 16 kHz mono 16-bit raw clips of you saying "Jarvis" in a folder,
     * then `JARVIS_WAKE_MINE=/that/folder ./gradlew test`. Skipped otherwise.
     */
    @Test fun hearsYouIfYouRecordedYourself() {
        val dir = System.getenv("JARVIS_WAKE_MINE")?.let(::File)?.takeIf { it.isDirectory } ?: return
        val mine = dir.listFiles()!!.filter { it.extension == "pcm" }
        val heard = mine.count { times(it.readBytes()) >= 1 }
        println("heard $heard of ${mine.size} of your clips")
        assertTrue("heard only $heard of ${mine.size}", heard >= mine.size * 8 / 10)
    }
}
