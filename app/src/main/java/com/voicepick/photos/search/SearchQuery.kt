package com.voicepick.photos.search

import com.voicepick.photos.KeywordMatcher

/**
 * A parsed search: photos must be in any of [categories] (if not empty) AND contain every one of
 * [terms] in their recognized text.
 */
data class SearchQuery(val categories: Set<Category>, val terms: List<String>) {
    val isEmpty: Boolean get() = categories.isEmpty() && terms.isEmpty()

    /** Terms written in Greek: these can't match, since recognized text is Latin-only. */
    val greekTerms: List<String> get() = terms.filter { t -> t.any { it in 'Ͱ'..'Ͽ' } }

    companion object {
        /** Word prefixes (accent-free, lowercase) that mean a category, in Greek and English. */
        private val SYNONYMS: List<Pair<String, Category>> = listOf(
            "διαβατηρ" to Category.PASSPORT, "passport" to Category.PASSPORT,
            "ταυτοτητ" to Category.ID_CARD, "identity" to Category.ID_CARD, "idcard" to Category.ID_CARD,
            "διπλωμ" to Category.LICENCE, "licen" to Category.LICENCE, "δελτι" to Category.ID_CARD,
            "αποδειξ" to Category.RECEIPT, "αποδειχ" to Category.RECEIPT, "τιμολογ" to Category.RECEIPT,
            "λογαριασμ" to Category.RECEIPT, "receipt" to Category.RECEIPT, "invoice" to Category.RECEIPT,
            "bill" to Category.RECEIPT,
            "καρτ" to Category.BANK_CARD, "card" to Category.BANK_CARD, "πιστωτικ" to Category.BANK_CARD,
            "χρεωστικ" to Category.BANK_CARD, "τραπεζικ" to Category.BANK_CARD, "bank" to Category.BANK_CARD,
            "στιγμιοτυπ" to Category.SCREENSHOT, "screenshot" to Category.SCREENSHOT, "σκρινσοτ" to Category.SCREENSHOT,
            "εγγραφ" to Category.DOCUMENT, "document" to Category.DOCUMENT, "χαρτι" to Category.DOCUMENT,
            "κειμεν" to Category.DOCUMENT, "text" to Category.DOCUMENT, "paper" to Category.DOCUMENT,
        )
        private val EXACT = mapOf("id" to Category.ID_CARD, "ids" to Category.ID_CARD)

        /** Words that only glue the query together ("passports and ID cards", "οι αποδείξεις μου"). */
        private val STOP = setOf(
            "και", "η", "ο", "το", "οι", "τα", "τις", "τους", "της", "του", "των", "μου", "με", "για", "σε", "στο",
            "στα", "στη", "στην", "ολα", "ολες", "ολοι", "δειξε", "βρες",
            "and", "or", "the", "a", "an", "my", "of", "with", "all", "show", "find", "me", "photos", "pictures",
            "φωτογραφιες", "φωτογραφια", "εικονες", "οδηγησης", "driving", "driver", "drivers", "credit", "debit",
        )

        fun parse(input: String): SearchQuery {
            val tokens = KeywordMatcher.normalize(input).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            val tagged = tokens.map { t -> EXACT[t] ?: SYNONYMS.firstOrNull { (prefix, _) -> t.startsWith(prefix) }?.second }
            val cats = LinkedHashSet<Category>()
            val terms = ArrayList<String>()
            for ((i, t) in tokens.withIndex()) {
                val cat = tagged[i]
                // "ID card" / "κάρτα ταυτότητας": the word "card" belongs to the ID, it isn't a bank card.
                val cardOfId = cat == Category.BANK_CARD && (t.startsWith("card") || t.startsWith("καρτ")) &&
                    (tagged.getOrNull(i - 1) == Category.ID_CARD || tagged.getOrNull(i + 1) == Category.ID_CARD)
                when {
                    cardOfId -> Unit
                    cat != null -> cats += cat
                    t in STOP -> Unit
                    else -> terms += t
                }
            }
            // "driving licence", "credit card": the qualifier alone still means the category.
            if (cats.isEmpty() && tokens.any { it == "οδηγησης" || it == "driving" }) cats += Category.LICENCE
            return SearchQuery(cats, terms)
        }
    }
}
