package com.voicepick.photos

import java.text.Normalizer
import java.util.Locale

/**
 * Finds a user-chosen word (or short phrase) in recognized speech.
 *
 * Case and accents are ignored. Greek words are compared by sound, so spellings that are
 * pronounced the same match each other: "αυτή" also matches "αυτοί", "αυτί" and "αφτή",
 * which the speech recognizer may write instead.
 */
class KeywordMatcher(phrase: String) {
    private val target: List<String> = tokens(phrase)

    val isValid: Boolean get() = target.isNotEmpty()

    /** How many times the phrase occurs in [text]. */
    fun count(text: String): Int {
        if (target.isEmpty()) return 0
        val words = tokens(text)
        var n = 0
        var i = 0
        while (i + target.size <= words.size) {
            if (words.subList(i, i + target.size) == target) {
                n++
                i += target.size
            } else {
                i++
            }
        }
        return n
    }

    companion object {
        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val MARKS = Regex("\\p{M}+")
        private val REPEATS = Regex("(.)\\1+")

        private fun tokens(s: String): List<String> =
            normalize(s).split(SPLIT).filter { it.isNotEmpty() }.map(::soundKey)

        /** Lowercase, accents removed. */
        fun normalize(s: String): String =
            MARKS.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

        /** Maps Greek spellings that sound alike to the same key. Latin text passes through mostly unchanged. */
        private fun soundKey(word: String): String {
            var s = word.replace('ς', 'σ')
            s = s.replace("ου", "u")                     // "ou" sound: protect before υ → ι
            s = s.replace("αυ", "αφ").replace("ευ", "εφ") // αυ/ευ read as af/ef (or av/ev)
            s = s.replace("αβ", "αφ").replace("εβ", "εφ")
            s = s.replace("ει", "ι").replace("οι", "ι").replace("υι", "ι")
            s = s.replace("αι", "ε")
            s = s.replace('η', 'ι').replace('υ', 'ι').replace('ω', 'ο')
            return REPEATS.replace(s, "$1")               // λλ → λ, σσ → σ, ...
        }
    }
}
