package com.voicepick.photos.ai

import java.text.Normalizer
import java.util.Locale

/**
 * CLIP byte-level BPE tokenizer, matching the Hugging Face tokenizer of TinyCLIP:
 * NFC, whitespace collapsed, lowercase, CLIP word regex, byte→unicode mapping, BPE with "</w>".
 *
 * @param vocab tokens in id order (line n of vocab.txt has id n)
 * @param merges "a b" pairs, highest priority first
 */
class ClipTokenizer(
    vocab: List<String>,
    merges: List<String>,
    private val contextLength: Int = 77,
    private val startToken: Int = 49406,
    private val endToken: Int = 49407,
    private val padToken: Int = 49407,
) {
    private val encoder: Map<String, Int> = HashMap<String, Int>(vocab.size * 2).apply {
        vocab.forEachIndexed { i, t -> put(t, i) }
    }
    private val ranks: Map<String, Int> = HashMap<String, Int>(merges.size * 2).apply {
        var rank = 0
        for (line in merges) {
            if (line.isBlank() || line.startsWith("#version")) continue
            put(line, rank++) // key "a b"
        }
    }
    private val cache = HashMap<String, List<String>>()

    /** Token ids, padded/truncated to [contextLength] (start + tokens + end + padding). */
    fun encode(text: String): LongArray {
        val clean = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(WHITESPACE, " ")
            .lowercase(Locale.ROOT)
        val tokens = ArrayList<Int>()
        for (m in PATTERN.findAll(clean)) {
            val word = m.value
            if (word == START_TEXT || word == END_TEXT) {
                tokens += encoder.getValue(word)
                continue
            }
            val mapped = buildString {
                for (b in word.toByteArray(Charsets.UTF_8)) append(BYTE_TO_UNICODE[b.toInt() and 0xff])
            }
            for (piece in bpe(mapped)) tokens += encoder[piece] ?: error("token not in vocab: $piece")
        }
        val out = LongArray(contextLength) { padToken.toLong() }
        out[0] = startToken.toLong()
        val body = tokens.take(contextLength - 2)
        body.forEachIndexed { i, t -> out[i + 1] = t.toLong() }
        out[body.size + 1] = endToken.toLong()
        return out
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        // Split into code points; the last one gets the end-of-word marker.
        val chars = ArrayList<String>()
        var i = 0
        while (i < token.length) {
            val cp = token.codePointAt(i)
            chars += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        if (chars.isEmpty()) return emptyList()
        chars[chars.lastIndex] = chars.last() + "</w>"

        var word: List<String> = chars
        while (word.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIdx = -1
            for (j in 0 until word.size - 1) {
                val r = ranks[word[j] + " " + word[j + 1]] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    bestIdx = j
                }
            }
            if (bestIdx < 0) break
            val first = word[bestIdx]
            val second = word[bestIdx + 1]
            // Merge every occurrence of the best pair (left to right, non-overlapping).
            val merged = ArrayList<String>(word.size)
            var k = 0
            while (k < word.size) {
                if (k < word.size - 1 && word[k] == first && word[k + 1] == second) {
                    merged += first + second
                    k += 2
                } else {
                    merged += word[k]
                    k++
                }
            }
            word = merged
        }
        cache[token] = word
        return word
    }

    companion object {
        private const val START_TEXT = "<|startoftext|>"
        private const val END_TEXT = "<|endoftext|>"
        private val WHITESPACE = Regex("\\s+")
        private val PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
        )

        /** GPT-2/CLIP reversible byte → printable-unicode mapping. */
        private val BYTE_TO_UNICODE: Array<String> = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code)
            bs += ('¡'.code..'¬'.code)
            bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            val table = arrayOfNulls<String>(256)
            bs.forEachIndexed { idx, b -> table[b] = String(Character.toChars(cs[idx])) }
            table.requireNoNulls()
        }
    }
}
