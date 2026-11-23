package com.voicepick.photos

import android.content.Context
import com.voicepick.photos.ai.ClipEngine
import com.voicepick.photos.search.SemanticEngine

/** AI edition: adds visual search with TinyCLIP. */
object Edition {
    const val VISUAL_SEARCH = true

    fun semanticEngine(context: Context): SemanticEngine? = ClipEngine.get(context)
}
