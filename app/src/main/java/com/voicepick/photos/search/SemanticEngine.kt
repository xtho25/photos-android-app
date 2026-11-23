package com.voicepick.photos.search

import android.graphics.Bitmap

/**
 * Visual ("what is in the photo") search. Only the AI edition provides one
 * (see `Edition.semanticEngine`); the standard edition has none.
 * Embeddings are L2-normalised, so a dot product is the cosine similarity.
 */
interface SemanticEngine {
    /** Embedding of a photo, or null if it can't be computed. Blocking; call off the main thread. */
    fun embedImage(bitmap: Bitmap): FloatArray?

    /**
     * Embedding of a search query (Greek is translated to English first).
     * @throws SemanticSearchException with a message that can be shown to the user.
     */
    suspend fun embedQuery(query: String): FloatArray

    /** Scores below this never count as a match. */
    val minScore: Float

    /** Matches must also be within this distance of the best score. */
    val marginFromBest: Float

    val maxResults: Int
}

class SemanticSearchException(message: String) : Exception(message)

/** Pure ranking logic, unit-tested. */
object SemanticRanker {
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
        return s
    }

    /** Photo ids matching [query], best first. */
    fun rank(
        query: FloatArray,
        photos: List<Pair<Long, FloatArray>>,
        minScore: Float,
        marginFromBest: Float,
        maxResults: Int,
    ): List<Pair<Long, Float>> {
        val scored = photos.asSequence()
            .map { (id, e) -> id to dot(query, e) }
            .filter { it.second >= minScore }
            .sortedByDescending { it.second }
            .toList()
        val best = scored.firstOrNull()?.second ?: return emptyList()
        return scored.takeWhile { it.second >= best - marginFromBest }.take(maxResults)
    }
}
