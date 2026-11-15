package app.jarvis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** The classifier trained by bridge/wake/train.py; its layout must match that file's Net. */
class WakeNet(raw: FloatArray) {
    private val w1 = raw.copyOfRange(0, INPUT * HIDDEN)
    private val b1 = raw.copyOfRange(INPUT * HIDDEN, INPUT * HIDDEN + HIDDEN)
    private val w2Start = INPUT * HIDDEN + HIDDEN
    private val w2 = raw.copyOfRange(w2Start, w2Start + HIDDEN * HIDDEN)
    private val b2 = raw.copyOfRange(w2Start + HIDDEN * HIDDEN, w2Start + HIDDEN * HIDDEN + HIDDEN)
    private val outStart = w2Start + HIDDEN * HIDDEN + HIDDEN
    private val w3 = raw.copyOfRange(outStart, outStart + HIDDEN)
    private val b3 = raw[outStart + HIDDEN]

    init { require(raw.size == outStart + HIDDEN + 1) { "wake model has ${raw.size} weights" } }

    private fun layer(x: FloatArray, w: FloatArray, b: FloatArray): FloatArray {
        val n = x.size
        val out = b.copyOf()
        for (i in 0 until n) {
            val v = x[i]
            if (v == 0f) continue
            val row = i * HIDDEN
            for (j in 0 until HIDDEN) out[j] += v * w[row + j]
        }
        for (j in 0 until HIDDEN) if (out[j] < 0f) out[j] = 0f
        return out
    }

    /** How sure it is, 0 to 1, that the [WINDOW] x [FEATURES] numbers in x end with his name. */
    fun score(x: FloatArray): Float {
        val h = layer(layer(x, w1, b1), w2, b2)
        var z = b3
        for (j in 0 until HIDDEN) z += h[j] * w3[j]
        return 1f / (1f + Math.exp(-z.toDouble().coerceIn(-30.0, 30.0)).toFloat())
    }

    companion object {
        const val WINDOW = 16
        const val FEATURES = 96
        const val INPUT = WINDOW * FEATURES
        const val HIDDEN = 64
    }
}

/**
 * Listens for "Jarvis" in 80 ms pieces of sound: sound becomes a spectrogram, that becomes speech
 * features, and the last moments of those go to [WakeNet]. All on the phone.
 */
class WakeWord(melModelFile: ByteArray, embedModelFile: ByteArray, netFile: ByteArray) {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private val melModel = env.createSession(melModelFile, options)
    private val embedModel = env.createSession(embedModelFile, options)
    private val net = WakeNet(FloatArray(netFile.size / 4).also { ByteBuffer.wrap(netFile).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) })
    private val melInput = melModel.inputNames.first()
    private val embedInput = embedModel.inputNames.first()
    private val samples = FloatArray(CHUNK + OVERLAP)
    private val mels = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private var strong = 0

    fun reset() {
        samples.fill(0f)
        mels.clear()
        features.clear()
        strong = 0
    }

    /** Takes exactly [CHUNK] samples; true when his name was just said. */
    fun heard(chunk: ShortArray): Boolean {
        System.arraycopy(samples, CHUNK, samples, 0, OVERLAP)
        for (i in 0 until CHUNK) samples[OVERLAP + i] = chunk[i].toFloat()
        spectrogram()
        if (mels.size < MEL_WINDOW) return false
        while (mels.size > MEL_WINDOW) mels.removeFirst()
        features.addLast(embedding())
        if (features.size < WakeNet.WINDOW) return false
        while (features.size > WakeNet.WINDOW) features.removeFirst()
        val x = FloatArray(WakeNet.INPUT)
        features.forEachIndexed { i, f -> System.arraycopy(f, 0, x, i * WakeNet.FEATURES, WakeNet.FEATURES) }
        strong = if (net.score(x) >= THRESHOLD) strong + 1 else 0
        if (strong < CONSECUTIVE) return false
        reset()
        return true
    }

    private fun spectrogram() {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            melModel.run(mapOf(melInput to input)).use { out ->
                val t = out[0] as OnnxTensor
                val flat = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
                var i = 0
                while (i + MEL_BINS <= flat.size) {
                    mels.addLast(FloatArray(MEL_BINS) { j -> flat[i + j] / 10f + 2f })
                    i += MEL_BINS
                }
            }
        }
    }

    private fun embedding(): FloatArray {
        val flat = FloatArray(MEL_WINDOW * MEL_BINS)
        mels.forEachIndexed { i, m -> System.arraycopy(m, 0, flat, i * MEL_BINS, MEL_BINS) }
        val shape = longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape).use { input ->
            embedModel.run(mapOf(embedInput to input)).use { out ->
                val t = out[0] as OnnxTensor
                return FloatArray(WakeNet.FEATURES).also { t.floatBuffer.get(it) }
            }
        }
    }

    companion object {
        fun from(assets: AssetManager): WakeWord {
            fun file(name: String) = assets.open("wake/$name").use { it.readBytes() }
            return WakeWord(file("melspectrogram.onnx"), file("embedding_model.onnx"), file("jarvis.bin"))
        }

        const val CHUNK = 1_280
        private const val OVERLAP = 480
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val THRESHOLD = 0.9f
        private const val CONSECUTIVE = 3
    }
}
