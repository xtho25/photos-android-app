package com.voicepick.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeywordMatchTest {
    private val auti = KeywordMatcher("Αυτή")

    @Test fun matchesAccentCaseAndSoundAlikeSpellings() {
        listOf("Αυτή", "αυτή!", "ΑΥΤΗ", "αυτη", "αφτή", "αυτοί").forEach {
            assertEquals(it, 1, auti.count(it))
        }
    }

    @Test fun countsRepeats() {
        assertEquals(2, auti.count("αυτή, και αυτή"))
    }

    @Test fun ignoresOtherWords() {
        assertEquals(0, auti.count("αυτός αυτό αυτήν αυτές"))
        assertEquals(0, auti.count("όχι"))
        assertEquals(0, auti.count(""))
    }

    @Test fun customGreekWord() {
        val m = KeywordMatcher("Ναι")
        assertEquals(1, m.count("ναι"))
        assertEquals(1, m.count("Νε"))   // same sound
        assertEquals(0, m.count("νάνι"))
    }

    @Test fun customEnglishWordAndPhrase() {
        assertEquals(1, KeywordMatcher("this one").count("I like this one a lot"))
        assertEquals(0, KeywordMatcher("this one").count("this is one"))
        assertEquals(1, KeywordMatcher("Keep").count("keep!"))
        assertEquals(0, KeywordMatcher("Keep").count("keeper"))
    }

    @Test fun emptyWordIsInvalid() {
        assertFalse(KeywordMatcher("  !! ").isValid)
    }

    @Test fun newFolderPaths() {
        assertEquals("Pictures/Επιλογές/", MediaRepository.relPathForNewFolder("  Επιλογές "))
        assertEquals("Pictures/ab/", MediaRepository.relPathForNewFolder("a/b"))
        assertEquals(null, MediaRepository.relPathForNewFolder(" / "))
    }
}
