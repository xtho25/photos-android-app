package com.voicepick.photos

import android.content.Context
import com.voicepick.photos.search.SemanticEngine

/** Standard edition: text/document search only, no AI model. */
object Edition {
    const val VISUAL_SEARCH = false

    @Suppress("UNUSED_PARAMETER")
    fun semanticEngine(context: Context): SemanticEngine? = null
}
