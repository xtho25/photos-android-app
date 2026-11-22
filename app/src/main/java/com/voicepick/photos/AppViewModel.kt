package com.voicepick.photos

import android.app.Application
import android.content.Context
import android.content.IntentSender
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface Screen {
    data object Folders : Screen
    data object Grid : Screen
    data class Viewer(val startIndex: Int) : Screen
}

/** One-off messages for the viewer (flash text, delete confirmation). */
sealed interface Feedback {
    data object Added : Feedback
    data object AlreadyThere : Feedback
    data object Removed : Feedback
    data object NotInFolder : Feedback
    data class Failed(val reason: String) : Feedback
    data class ConfirmDelete(val request: IntentSender) : Feedback
}

/** Languages offered for speech recognition: tag → label. */
val SPEECH_LANGUAGES = linkedMapOf(
    "el-GR" to "Ελληνικά",
    "en-US" to "English",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Folders)
        private set

    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var albumsLoading by mutableStateOf(false)
        private set

    var photos by mutableStateOf<List<Photo>>(emptyList())
        private set
    var photosLoading by mutableStateOf(false)
        private set

    /** Working folder as a MediaStore RELATIVE_PATH, e.g. "Pictures/Favourites/". */
    var folder by mutableStateOf(prefs.getString(KEY_FOLDER, null))
        private set
    val folderName: String get() = folder?.let(MediaRepository::folderName) ?: ""

    var folderCount by mutableStateOf(0)
        private set
    var folderKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Word (or short phrase) that selects the current photo. */
    var keyword by mutableStateOf(prefs.getString(KEY_WORD, null) ?: DEFAULT_WORD)
        private set
    /** Speech-recognition language tag, e.g. "el-GR". */
    var language by mutableStateOf(prefs.getString(KEY_LANGUAGE, null) ?: DEFAULT_LANGUAGE)
        private set

    fun saveSettings(word: String, lang: String) {
        val w = word.trim()
        if (!KeywordMatcher(w).isValid) return
        keyword = w
        language = lang
        prefs.edit().putString(KEY_WORD, w).putString(KEY_LANGUAGE, lang).apply()
    }

    val gridState = LazyGridState()
    private var photosLoadedFor: String? = null

    private val _events = MutableSharedFlow<Feedback>(extraBufferCapacity = 8)
    val events: SharedFlow<Feedback> = _events

    /** Serializes add/remove so quick repeated commands can't race each other. */
    private val addMutex = Mutex()
    private var pendingRemove: Photo? = null
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleFolderRefresh()
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun loadAlbums() {
        viewModelScope.launch {
            albumsLoading = true
            albums = runCatching { repo.listAlbums() }.getOrDefault(emptyList())
            albumsLoading = false
        }
    }

    fun chooseFolder(relPath: String) {
        folder = relPath
        prefs.edit().putString(KEY_FOLDER, relPath).apply()
        scheduleFolderRefresh(immediate = true)
        if (photosLoadedFor != relPath) loadPhotos(relPath)
        screen = Screen.Grid
    }

    private fun loadPhotos(excludeRelPath: String) {
        viewModelScope.launch {
            photosLoading = true
            photos = runCatching { repo.loadAllImages(excludeRelPath) }.getOrDefault(emptyList())
            photosLoadedFor = excludeRelPath
            photosLoading = false
            gridState.scrollToItem(0)
        }
    }

    fun openViewer(index: Int) {
        screen = Screen.Viewer(index)
    }

    /** Called when leaving the viewer; keeps the grid scrolled near the last photo viewed. */
    fun closeViewer(lastIndex: Int) {
        screen = Screen.Grid
        viewModelScope.launch {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == lastIndex }
            if (!shown) gridState.scrollToItem(lastIndex)
        }
    }

    fun backToFolders() {
        loadAlbums()
        screen = Screen.Folders
    }

    fun addPhoto(photo: Photo) {
        val target = folder ?: return
        viewModelScope.launch {
            // One copy at a time, so two quick "Αυτή" on the same photo can't both get through.
            val result = addMutex.withLock {
                if (photo.key in folderKeys) {
                    CopyResult.AlreadyThere
                } else {
                    repo.copyToFolder(photo, target).also {
                        if (it is CopyResult.Added) folderKeys = folderKeys + photo.key
                    }
                }
            }
            _events.emit(
                when (result) {
                    CopyResult.Added -> Feedback.Added
                    CopyResult.AlreadyThere -> Feedback.AlreadyThere
                    is CopyResult.Failed -> Feedback.Failed(result.reason)
                },
            )
            scheduleFolderRefresh(immediate = true)
        }
    }

    /** Deletes the copy of [photo] from the working folder (the original stays where it is). */
    fun removePhoto(photo: Photo) {
        val target = folder ?: return
        viewModelScope.launch {
            val result = addMutex.withLock {
                repo.removeFromFolder(photo, target).also {
                    if (it is RemoveResult.Removed || it is RemoveResult.NotInFolder) {
                        folderKeys = folderKeys - photo.key
                    }
                }
            }
            _events.emit(
                when (result) {
                    RemoveResult.Removed -> Feedback.Removed
                    RemoveResult.NotInFolder -> Feedback.NotInFolder
                    is RemoveResult.Failed -> Feedback.Failed(result.reason)
                    is RemoveResult.NeedsConfirmation -> {
                        pendingRemove = photo
                        Feedback.ConfirmDelete(result.request)
                    }
                },
            )
            scheduleFolderRefresh(immediate = true)
        }
    }

    /** Result of the system "Allow deletion?" dialog. */
    fun onDeleteConfirmed(approved: Boolean) {
        val photo = pendingRemove ?: return
        pendingRemove = null
        if (!approved) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: the system already deleted the file.
            folderKeys = folderKeys - photo.key
            viewModelScope.launch { _events.emit(Feedback.Removed) }
            scheduleFolderRefresh(immediate = true)
        } else {
            // Android 10: access was granted, delete again.
            removePhoto(photo)
        }
    }

    private fun scheduleFolderRefresh(immediate: Boolean = false) {
        val target = folder ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!immediate) delay(300)
            val contents = runCatching { repo.folderContents(target) }.getOrNull() ?: return@launch
            if (target == folder) {
                folderCount = contents.count
                folderKeys = contents.keys
            }
        }
    }

    companion object {
        private const val KEY_FOLDER = "folder"
        private const val KEY_WORD = "keyword"
        private const val KEY_LANGUAGE = "language"
        const val DEFAULT_WORD = "Αυτή"
        const val DEFAULT_LANGUAGE = "el-GR"
    }
}
