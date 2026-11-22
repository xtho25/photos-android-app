package com.voicepick.photos.search

import com.voicepick.photos.search.Category.BANK_CARD
import com.voicepick.photos.search.Category.DOCUMENT
import com.voicepick.photos.search.Category.ID_CARD
import com.voicepick.photos.search.Category.LICENCE
import com.voicepick.photos.search.Category.PASSPORT
import com.voicepick.photos.search.Category.RECEIPT
import com.voicepick.photos.search.Category.SCREENSHOT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentClassifierTest {
    private fun classify(text: String, path: String = "DCIM/Camera/", name: String = "IMG_1.jpg") =
        DocumentClassifier.classify(text, path, name)

    @Test fun passportFromMrz() {
        val mrz = """
            REPUBLIC OF UTOPIA
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            L898902C36UTO7408122F1204159ZE184226B<<<<<10
        """.trimIndent()
        val c = classify(mrz)
        assertTrue(PASSPORT in c)
        assertTrue(DOCUMENT in c)
        assertTrue(ID_CARD !in c)
    }

    @Test fun passportFromWordOnly() {
        assertTrue(PASSPORT in classify("ΔΙΑΒΑΤΗΡΙΟ / PASSPORT\nHELLENIC REPUBLIC"))
    }

    @Test fun idCardFromMrz() {
        val td1 = """
            I<UTOD231458907<<<<<<<<<<<<<<<
            7408122F1204159UTO<<<<<<<<<<<6
            ERIKSSON<<ANNA<MARIA<<<<<<<<<<
        """.trimIndent()
        val c = classify(td1)
        assertTrue(ID_CARD in c)
        assertTrue(PASSPORT !in c)
    }

    @Test fun idCardFromWords() {
        assertTrue(ID_CARD in classify("HELLENIC REPUBLIC\nIDENTITY CARD\nSURNAME"))
    }

    @Test fun licence() {
        assertTrue(LICENCE in classify("DRIVING LICENCE\n1. PAPADOPOULOS\n2. GIORGOS"))
    }

    @Test fun receipt() {
        val text = "LIDL HELLAS\nMILK 1,29\nBREAD 0,99\nCHEESE 3,49\nTOTAL 5,77\nVISA"
        assertTrue(RECEIPT in classify(text))
    }

    @Test fun receiptWithoutKeywordsButManyPrices() {
        assertTrue(RECEIPT in classify("1,00\n2,50\n3,10\n4,99\n0,50\n12,09"))
    }

    @Test fun bankCard() {
        val c = classify("VISA\n4111 1111 1111 1111\nVALID THRU 12/27\nJOHN DOE")
        assertTrue(BANK_CARD in c)
    }

    @Test fun screenshotFromPath() {
        assertTrue(SCREENSHOT in classify("", "Pictures/Screenshots/", "Screenshot_2024.png"))
        assertTrue(SCREENSHOT in classify("", "DCIM/Screenshots/", "x.jpg"))
    }

    @Test fun longTextIsDocument() {
        val words = (1..30).joinToString(" ") { "word" }
        assertEquals(setOf(DOCUMENT), classify(words))
    }

    @Test fun ordinaryPhotoHasNoCategory() {
        assertEquals(emptySet<Category>(), classify(""))
        assertEquals(emptySet<Category>(), classify("EXIT"))
        assertEquals(emptySet<Category>(), classify("OPEN 9.00 - 17.00"))
    }
}

class SearchQueryTest {
    private fun cats(q: String) = SearchQuery.parse(q).categories

    @Test fun greekAndEnglishCategoryWords() {
        assertEquals(setOf(PASSPORT), cats("διαβατήρια"))
        assertEquals(setOf(PASSPORT), cats("Διαβατήριο"))
        assertEquals(setOf(PASSPORT), cats("Passports"))
        assertEquals(setOf(RECEIPT), cats("αποδείξεις"))
        assertEquals(setOf(LICENCE), cats("δίπλωμα οδήγησης"))
        assertEquals(setOf(LICENCE), cats("Driving licences"))
        assertEquals(setOf(SCREENSHOT), cats("στιγμιότυπα"))
        assertEquals(setOf(DOCUMENT), cats("Documents & text"))
    }

    @Test fun cardWordsDependOnContext() {
        assertEquals(setOf(ID_CARD), cats("ID cards"))
        assertEquals(setOf(ID_CARD), cats("κάρτα ταυτότητας"))
        assertEquals(setOf(ID_CARD), cats("ταυτότητες"))
        assertEquals(setOf(BANK_CARD), cats("Bank cards"))
        assertEquals(setOf(BANK_CARD), cats("πιστωτική κάρτα"))
    }

    @Test fun severalCategories() {
        assertEquals(setOf(PASSPORT, ID_CARD), cats("διαβατήρια και ταυτότητες"))
    }

    @Test fun freeTextTerms() {
        val q = SearchQuery.parse("αποδείξεις Lidl")
        assertEquals(setOf(RECEIPT), q.categories)
        assertEquals(listOf("lidl"), q.terms)
        assertEquals(listOf("aegean"), SearchQuery.parse("show me Aegean").terms)
    }

    @Test fun greekFreeTextIsFlagged() {
        assertEquals(listOf("μπανανα"), SearchQuery.parse("μπανάνα").greekTerms)
        assertTrue(SearchQuery.parse("  ").isEmpty)
    }
}
