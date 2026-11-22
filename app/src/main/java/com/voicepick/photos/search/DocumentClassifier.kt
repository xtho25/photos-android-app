package com.voicepick.photos.search

import com.voicepick.photos.KeywordMatcher

/** Kinds of photos that content search can find. */
enum class Category(val key: String, val label: String) {
    PASSPORT("passport", "Passports"),
    ID_CARD("idcard", "ID cards"),
    LICENCE("licence", "Driving licences"),
    RECEIPT("receipt", "Receipts"),
    BANK_CARD("bankcard", "Bank cards"),
    SCREENSHOT("screenshot", "Screenshots"),
    DOCUMENT("document", "Documents & text"),
    ;

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

/**
 * Decides what a photo shows from the text recognized in it (Latin script) and its file location.
 * Pure logic, so it can be unit-tested.
 */
object DocumentClassifier {
    private val PRICE = Regex("""\b\d{1,5}[.,]\d{2}\b""")
    private val CARD_NUMBER = Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b""")
    private val EXPIRY = Regex("""\b(0[1-9]|1[0-2])\s?/\s?\d{2}\b""")
    private val WORD = Regex("""\b\p{L}{3,}\b""")

    private val PASSPORT_WORDS = listOf("PASSPORT", "PASSEPORT", "PASAPORTE", "REISEPASS", "PASSAPORTO", "PASSAPORTE")
    private val ID_WORDS = listOf(
        "IDENTITY CARD", "CARTE D'IDENTITE", "CARTE NATIONALE D'IDENTITE", "PERSONALAUSWEIS",
        "DOCUMENTO DE IDENTIDAD", "CARTA D'IDENTITA", "CARTAO DE CIDADAO", "IDENTITEITSKAART",
    )
    private val LICENCE_WORDS = listOf(
        "DRIVING LICENCE", "DRIVING LICENSE", "DRIVER LICENSE", "DRIVER'S LICENSE", "DRIVERS LICENSE",
        "PERMIS DE CONDUIRE", "FUHRERSCHEIN", "PATENTE DI GUIDA", "PERMISO DE CONDUCCION",
    )
    private val RECEIPT_WORDS = listOf(
        "TOTAL", "SUBTOTAL", "VAT", "TAX", "CASH", "CHANGE", "EUR", "€", "RECEIPT", "INVOICE", "AFM", "A.F.M",
    )
    private val CARD_WORDS = listOf("VISA", "MASTERCARD", "MAESTRO", "AMERICAN EXPRESS", "DEBIT", "CREDIT", "VALID THRU", "GOOD THRU")

    fun classify(text: String, relPath: String?, displayName: String?): Set<Category> {
        val out = HashSet<Category>()
        val path = (relPath.orEmpty() + "/" + displayName.orEmpty()).lowercase()
        if ("screenshot" in path || "screen_shot" in path || "screen shot" in path) out += Category.SCREENSHOT

        if (text.isBlank()) return out
        val upper = KeywordMatcher.normalize(text).uppercase()
        val lines = upper.lines().map { it.replace(" ", "") }

        // Machine-readable zone: "P<GRCSURNAME<<NAME<<<" (passport) or "I<GRC…" / "IDGRC…" (ID card).
        val mrzLines = lines.filter { it.length >= 25 && it.count { c -> c == '<' } >= 3 }
        val passportMrz = mrzLines.any { Regex("^P[A-Z<][A-Z]{3}").containsMatchIn(it) }
        val idMrz = mrzLines.any { Regex("^[IAC][A-Z<][A-Z]{3}").containsMatchIn(it) } ||
            mrzLines.size >= 2 && !passportMrz

        if (passportMrz || PASSPORT_WORDS.any { it in upper }) out += Category.PASSPORT
        if (Category.PASSPORT !in out && (idMrz || ID_WORDS.any { it in upper })) out += Category.ID_CARD
        if (LICENCE_WORDS.any { it in upper }) out += Category.LICENCE

        val prices = PRICE.findAll(upper).count()
        val receiptWords = RECEIPT_WORDS.count { Regex("(^|[^A-Z])" + Regex.escape(it) + "([^A-Z]|$)").containsMatchIn(upper) }
        if (prices >= 6 || (prices >= 3 && receiptWords >= 1)) out += Category.RECEIPT

        if (CARD_NUMBER.containsMatchIn(upper) && (EXPIRY.containsMatchIn(upper) || CARD_WORDS.any { it in upper })) {
            out += Category.BANK_CARD
        }

        val words = WORD.findAll(upper).count()
        if (words >= 25 || out.any { it in setOf(Category.PASSPORT, Category.ID_CARD, Category.LICENCE, Category.RECEIPT) }) {
            out += Category.DOCUMENT
        }
        return out
    }
}
