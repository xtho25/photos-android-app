package com.voicepick.photos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.voicepick.photos.search.SemanticEngine
import com.voicepick.photos.search.SemanticSearchException
import com.voicepick.photos.search.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** TinyCLIP (ONNX Runtime) image/text embeddings for visual search. AI edition only. */
class ClipEngine private constructor(context: Context) : SemanticEngine {
    private val app = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val meta = JSONObject(app.assets.open("clip/tinyclip.json").bufferedReader().use { it.readText() })

    private val size = meta.getInt("image_size")
    private val mean = FloatArray(3) { meta.getJSONArray("mean").getDouble(it).toFloat() }
    private val std = FloatArray(3) { meta.getJSONArray("std").getDouble(it).toFloat() }
    private val prompt = meta.getString("prompt")

    override val minScore = meta.getDouble("min_score").toFloat()
    override val marginFromBest = meta.getDouble("margin_from_best").toFloat()
    override val maxResults = meta.getInt("max_results")

    // Sessions are created on first use: the indexer only needs the image model, search only the text one.
    private val imageSession by lazy { session("clip/image_encoder.onnx") }
    private val textSession by lazy { session("clip/text_encoder.onnx") }
    private val tokenizer by lazy {
        ClipTokenizer(
            vocab = app.assets.open("clip/vocab.txt").bufferedReader().readLines(),
            merges = app.assets.open("clip/merges.txt").bufferedReader().readLines(),
            contextLength = meta.getInt("context_length"),
            startToken = meta.getInt("start_token"),
            endToken = meta.getInt("end_token"),
            padToken = meta.getInt("pad_token"),
        )
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.GREEK)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build(),
        )
    }

    /** Memory-maps the (uncompressed) model straight from the APK, so it isn't copied to storage. */
    private fun session(asset: String): OrtSession {
        val fd = app.assets.openFd(asset)
        val buffer: MappedByteBuffer = FileInputStream(fd.fileDescriptor).channel.use {
            it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(buffer, options)
    }

    @Synchronized
    override fun embedImage(bitmap: Bitmap): FloatArray? = try {
        val square = centerCrop(bitmap)
        val pixels = IntArray(size * size)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        if (square !== bitmap) square.recycle()
        val chw = ClipPreprocess.toChw(pixels, size, mean, std)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            imageSession.run(mapOf("pixel_values" to input)).use { out -> firstRow(out) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "image embedding failed", e)
        null
    }

    override suspend fun embedQuery(query: String): FloatArray {
        val english = if (isGreek(query)) translate(query) else query
        val text = prompt.replace("{}", english.trim().trimEnd('.', '!', '?'))
        Log.d(TAG, "query «$query» → «$text»")
        return withContext(Dispatchers.Default) {
            synchronized(this@ClipEngine) {
                val ids = tokenizer.encode(text)
                OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                    textSession.run(mapOf("input_ids" to input)).use { out -> firstRow(out) }
                }
            }
        }
    }

    private suspend fun translate(query: String): String {
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: Exception) {
            Log.w(TAG, "translation model download failed", e)
            throw SemanticSearchException(
                "Visual search needs the Greek→English translator, which downloads once (~30 MB). " +
                    "Connect to the internet and try again, or search in English.",
            )
        }
        return try {
            translator.translate(query).await()
        } catch (e: Exception) {
            throw SemanticSearchException("Couldn't translate «$query». Try searching in English.")
        }
    }

    private fun firstRow(out: OrtSession.Result): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val v = (out[0].value as Array<FloatArray>)[0]
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x }).coerceAtLeast(1e-12f)
        return FloatArray(v.size) { v[it] / norm }
    }

    /** Shortest side → [size] (halving first for quality, like an area filter), then a centre crop. */
    private fun centerCrop(src: Bitmap): Bitmap {
        var bmp = src
        while (min(bmp.width, bmp.height) >= size * 2) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== src) bmp.recycle()
            bmp = next
        }
        val scale = size.toFloat() / min(bmp.width, bmp.height)
        val w = max(size, (bmp.width * scale).roundToInt())
        val h = max(size, (bmp.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (bmp !== src && bmp !== scaled) bmp.recycle()
        val cropped = Bitmap.createBitmap(scaled, (w - size) / 2, (h - size) / 2, size, size)
        if (scaled !== cropped && scaled !== src) scaled.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "ClipEngine"

        @Volatile private var instance: ClipEngine? = null

        fun get(context: Context): ClipEngine =
            instance ?: synchronized(this) { instance ?: ClipEngine(context).also { instance = it } }

        fun isGreek(s: String) = s.any { it in 'Ͱ'..'Ͽ' || it in 'ἀ'..'῿' }
    }
}

/** Pure pixel → model-input conversion (unit-tested). */
object ClipPreprocess {
    /** ARGB pixels (row-major, size×size) → normalised CHW floats. */
    fun toChw(pixels: IntArray, size: Int, mean: FloatArray, std: FloatArray): FloatArray {
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            out[i] = (((p shr 16) and 0xff) / 255f - mean[0]) / std[0]
            out[plane + i] = (((p shr 8) and 0xff) / 255f - mean[1]) / std[1]
            out[2 * plane + i] = ((p and 0xff) / 255f - mean[2]) / std[2]
        }
        return out
    }
}
