package com.voicepick.photos.search

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticRankerTest {
    private fun unit(vararg v: Float) = v

    @Test fun ranksByScoreAndAppliesThresholds() {
        val q = unit(1f, 0f)
        val photos = listOf(
            1L to unit(0.30f, 0f),
            2L to unit(0.35f, 0f),
            3L to unit(0.27f, 0f), // within min score, but too far from best (0.35 - 0.06 = 0.29)
            4L to unit(0.20f, 0f), // below min score
        )
        val hits = SemanticRanker.rank(q, photos, minScore = 0.25f, marginFromBest = 0.06f, maxResults = 10)
        assertEquals(listOf(2L, 1L), hits.map { it.first })
    }

    @Test fun capsResultsAndHandlesNoMatches() {
        val q = unit(1f)
        val photos = (1L..10L).map { it to unit(0.5f) }
        assertEquals(3, SemanticRanker.rank(q, photos, 0.2f, 0.1f, 3).size)
        assertEquals(0, SemanticRanker.rank(q, photos, 0.9f, 0.1f, 3).size)
        assertEquals(0, SemanticRanker.rank(q, emptyList(), 0.2f, 0.1f, 3).size)
    }

    @Test fun embeddingBlobRoundTrip() {
        val v = floatArrayOf(0.1f, -2.5f, 3e-7f, 1f)
        assertArrayEquals(v, SearchIndex.fromBlob(SearchIndex.toBlob(v)), 0f)
        assertEquals(0, SearchIndex.toBlob(null).size)
    }
}
