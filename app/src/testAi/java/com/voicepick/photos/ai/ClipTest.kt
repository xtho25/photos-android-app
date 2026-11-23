package com.voicepick.photos.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.LongBuffer

/**
 * Checks the Kotlin side of the AI edition against references written by tools/convert_tinyclip.py
 * (Hugging Face tokenizer + ONNX text encoder in Python).
 */
class ClipTest {
    companion object {
        private val assets = File("src/ai/assets/clip")
        private val textModel = File("../models/assets/clip/text_encoder.onnx")
        lateinit var tokenizer: ClipTokenizer
        lateinit var cases: List<Triple<String, LongArray, FloatArray>>

        @BeforeClass @JvmStatic fun setUp() {
            tokenizer = ClipTokenizer(File(assets, "vocab.txt").readLines(), File(assets, "merges.txt").readLines())
            val tsv = ClipTest::class.java.classLoader!!.getResource("clip_reference.tsv")!!.readText()
            cases = tsv.lines().filter { it.isNotEmpty() }.map { line ->
                val (prompt, ids, emb) = line.split('\t')
                Triple(
                    prompt,
                    ids.split(',').map { it.toLong() }.toLongArray(),
                    emb.split(',').map { it.toFloat() }.toFloatArray(),
                )
            }
        }
    }

    @Test fun tokenizerMatchesHuggingFace() {
        assertTrue(cases.size >= 10)
        for ((prompt, ids, _) in cases) {
            assertArrayEquals("tokens of «$prompt»", ids, tokenizer.encode(prompt))
        }
    }

    @Test fun longTextIsTruncatedWithEndToken() {
        val ids = tokenizer.encode((1..200).joinToString(" ") { "word" })
        assertEquals(77, ids.size)
        assertEquals(49406L, ids.first())
        assertEquals(49407L, ids.last())
    }

    @Test fun textEmbeddingMatchesPython() {
        assumeTrue("models/ not built; run tools/convert_tinyclip.py", textModel.isFile)
        val env = OrtEnvironment.getEnvironment()
        env.createSession(textModel.path).use { session ->
            for ((prompt, _, expected) in cases) {
                val ids = tokenizer.encode(prompt)
                val got = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { t ->
                    session.run(mapOf("input_ids" to t)).use {
                        @Suppress("UNCHECKED_CAST")
                        (it[0].value as Array<FloatArray>)[0]
                    }
                }
                val cos = got.indices.sumOf { (got[it] * expected[it]).toDouble() }
                assertTrue("embedding of «$prompt» cos=$cos", cos > 0.9999)
            }
        }
    }

    @Test fun preprocessNormalisesChannelsIntoPlanes() {
        val mean = floatArrayOf(0.5f, 0.5f, 0.5f)
        val std = floatArrayOf(0.5f, 0.25f, 0.5f)
        // 2×2: white, black, pure red, pure blue
        val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val chw = ClipPreprocess.toChw(px, 2, mean, std)
        assertArrayEquals(floatArrayOf(1f, -1f, 1f, -1f), chw.copyOfRange(0, 4), 1e-6f) // R plane
        assertArrayEquals(floatArrayOf(2f, -2f, -2f, -2f), chw.copyOfRange(4, 8), 1e-6f) // G plane
        assertArrayEquals(floatArrayOf(1f, -1f, -1f, 1f), chw.copyOfRange(8, 12), 1e-6f) // B plane
    }
}
