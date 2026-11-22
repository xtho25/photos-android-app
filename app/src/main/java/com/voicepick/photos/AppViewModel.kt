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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.voicepick.photos.search.IndexWorker
import com.voicepick.photos.search.SearchIndex
import com.voicepick.photos.search.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

sealed interface Screen {
    /** Step 1: what to browse (all / dates / a folder). */
    data object Source : Screen
    /** Step 2: the working folder that selected photos are copied to. */
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

/** The grid is a list of day headers, each followed by that day's photos. */
sealed interface GridEntry {
    data class Header(val day: LocalDate, val count: Int) : GridEntry
    data class Cell(val photoIndex: Int) : GridEntry
}

/** Where a photo sits within its day: [position] of [count] photos taken on [day]. */
data class PhotoDay(val day: LocalDate, val position: Int, val count: Int)

/** Content-search index progress: [indexed] of [total] photos; [running] with [runDone]/[runTotal] this run. */
data class IndexStatus(
    val indexed: Int,
    val total: Int,
    val running: Boolean,
    val runDone: Int = 0,
    val runTotal: Int = 0,
)

/** Languages offered for speech recognition: tag → label. */
val SPEECH_LANGUAGES = linkedMapOf(
    "el-GR" to "Ελληνικά",
    "en-US" to "English",
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Source)
        private set

    var source by mutableStateOf<PhotoSource>(PhotoSource.All)
        private set

    /** Every album on the phone, for choosing what to browse. */
    var sourceAlbums by mutableStateOf<List<Album>>(emptyList())
        private set

    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var albumsLoading by mutableStateOf(false)
        private set

    var photos by mutableStateOf<List<Photo>>(emptyList())
        private set
    var photosLoading by mutableStateOf(false)
        private set

    /** Day headers + photo cells for the grid. */
    var gridEntries by mutableStateOf<List<GridEntry>>(emptyList())
        private set
    /** For each photo index: its index in [gridEntries]. */
    private var entryIndexOfPhoto = IntArray(0)
    /** For each photo index: its day and position within that day. */
    var photoDays by mutableStateOf<List<PhotoDay>>(emptyList())
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

    /** When off, the viewer doesn't listen; photos are added only with the ＋ Add button. */
    var voiceEnabled by mutableStateOf(prefs.getBoolean(KEY_VOICE, true))
        private set

    fun saveSettings(word: String, lang: String, voice: Boolean) {
        val w = word.trim()
        if (!KeywordMatcher(w).isValid) return
        keyword = w
        language = lang
        voiceEnabled = voice
        prefs.edit().putString(KEY_WORD, w).putString(KEY_LANGUAGE, lang).putBoolean(KEY_VOICE, voice).apply()
    }

    /** Photo order in the grid and viewer: newest first (default) or oldest first. */
    var newestFirst by mutableStateOf(prefs.getBoolean(KEY_NEWEST_FIRST, true))
        private set

    fun setSortNewestFirst(newest: Boolean) {
        if (newest == newestFirst) return
        newestFirst = newest
        prefs.edit().putBoolean(KEY_NEWEST_FIRST, newest).apply()
        viewModelScope.launch {
            applyPhotos(photos.asReversed().toList())
            gridState.scrollToItem(0)
        }
    }

    val gridState = LazyGridState()
    private var photosLoadedFor: Pair<PhotoSource, String>? = null

    private val _events = MutableSharedFlow<Feedback>(extraBufferCapacity = 8)
    val events: SharedFlow<Feedback> = _events

    /** Serializes add/remove so quick repeated commands can't race each other. */
    private val addMutex = Mutex()
    private var pendingRemove: Photo? = null
    private var refreshJob: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleFolderRefresh()
    }

    private val searchIndex = SearchIndex.get(app)

    var indexStatus by mutableStateOf<IndexStatus?>(null)
        private set

    /** The user's answer to "index all photos for content search?"; null = not asked yet. */
    var indexConsent by mutableStateOf(IndexWorker.consent(app))
        private set

    fun setIndexConsent(allow: Boolean) {
        val app = getApplication<Application>()
        prefs.edit().putBoolean(IndexWorker.KEY_CONSENT, allow).apply()
        indexConsent = allow
        if (allow) {
            IndexWorker.schedulePeriodic(app)
        } else {
            IndexWorker.disable(app)
            viewModelScope.launch(Dispatchers.IO) { runCatching { searchIndex.clear() } }
        }
        refreshIndexStatus()
    }

    init {
        app.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        if (indexConsent == true) IndexWorker.schedulePeriodic(app) else IndexWorker.disable(app)
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosByTagFlow(IndexWorker.WORK_TAG).collect { infos ->
                val run = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                val counts = indexCounts()
                indexStatus = IndexStatus(
                    indexed = counts.first,
                    total = counts.second,
                    running = run != null,
                    runDone = run?.progress?.getInt(IndexWorker.KEY_DONE, 0) ?: 0,
                    runTotal = run?.progress?.getInt(IndexWorker.KEY_TOTAL, 0) ?: 0,
                )
            }
        }
    }

    private suspend fun indexCounts(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        runCatching { searchIndex.count() to repo.countImages() }.getOrDefault(0 to 0)
    }

    fun refreshIndexStatus() {
        viewModelScope.launch {
            val (indexed, total) = indexCounts()
            indexStatus = (indexStatus ?: IndexStatus(0, 0, false)).copy(indexed = indexed, total = total)
        }
    }

    fun startIndexing() = IndexWorker.runNow(getApplication())

    fun pauseIndexing() = IndexWorker.stop(getApplication())

    /** How many indexed photos match [query] (for the preview before continuing). */
    suspend fun countMatches(query: String): Int = withContext(Dispatchers.IO) {
        runCatching { searchIndex.search(SearchQuery.parse(query)).size }.getOrDefault(0)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }

    fun loadAlbums() {
        viewModelScope.launch {
            albumsLoading = true
            albums = runCatching { repo.listAlbums(writableOnly = true) }.getOrDefault(emptyList())
            albumsLoading = false
        }
    }

    fun loadSourceAlbums() {
        viewModelScope.launch {
            sourceAlbums = runCatching { repo.listAlbums(writableOnly = false) }.getOrDefault(emptyList())
        }
    }

    /** Short description of what is being browsed, e.g. "All photos", "Πάσχα 2024", "📁 Camera". */
    val sourceLabel: String
        get() = when (val s = source) {
            PhotoSource.All -> "All photos"
            is PhotoSource.Folder -> "📁 " + MediaRepository.folderName(s.relPath)
            is PhotoSource.Dates -> s.label ?: DateFormats.range(s.start, s.end)
            is PhotoSource.Search -> "🔍 " + s.query
        }

    fun chooseSource(src: PhotoSource) {
        source = src
        loadAlbums()
        screen = Screen.Folders
    }

    fun backToSource() {
        screen = Screen.Source
    }

    fun chooseFolder(relPath: String) {
        folder = relPath
        prefs.edit().putString(KEY_FOLDER, relPath).apply()
        scheduleFolderRefresh(immediate = true)
        val key = source to relPath
        if (photosLoadedFor != key) loadPhotos(key)
        screen = Screen.Grid
    }

    private fun loadPhotos(key: Pair<PhotoSource, String>) {
        viewModelScope.launch {
            photosLoading = true
            val loaded = runCatching {
                val all = repo.loadImages(key.first, key.second)
                when (val s = key.first) {
                    is PhotoSource.Search -> {
                        val ids = withContext(Dispatchers.IO) { searchIndex.search(SearchQuery.parse(s.query)) }
                        all.filter { it.id in ids }
                    }
                    else -> all
                }
            }.getOrDefault(emptyList())
            // The repository returns newest first.
            applyPhotos(if (newestFirst) loaded else loaded.asReversed().toList())
            photosLoadedFor = key
            photosLoading = false
            gridState.scrollToItem(0)
        }
    }

    private suspend fun applyPhotos(list: List<Photo>) {
        val sections = withContext(Dispatchers.Default) { buildSections(list) }
        photos = list
        gridEntries = sections.entries
        entryIndexOfPhoto = sections.entryIndexOfPhoto
        photoDays = sections.days
    }

    private class Sections(val entries: List<GridEntry>, val entryIndexOfPhoto: IntArray, val days: List<PhotoDay>)

    /** Groups photos (already sorted by date, either direction) by the day they were taken. */
    private fun buildSections(photos: List<Photo>): Sections {
        val dayOf = photos.map { DateFormats.dayOf(it.dateMillis) }
        val entries = ArrayList<GridEntry>(photos.size + photos.size / 4)
        val entryIndex = IntArray(photos.size)
        val days = ArrayList<PhotoDay>(photos.size)
        var i = 0
        while (i < photos.size) {
            val day = dayOf[i]
            var j = i
            while (j < photos.size && dayOf[j] == day) j++
            entries += GridEntry.Header(day, j - i)
            for (k in i until j) {
                entryIndex[k] = entries.size
                entries += GridEntry.Cell(k)
                days += PhotoDay(day, k - i + 1, j - i)
            }
            i = j
        }
        return Sections(entries, entryIndex, days)
    }

    fun openViewer(index: Int) {
        screen = Screen.Viewer(index)
    }

    /** Called when leaving the viewer; keeps the grid scrolled near the last photo viewed. */
    fun closeViewer(lastIndex: Int) {
        screen = Screen.Grid
        val entry = entryIndexOfPhoto.getOrNull(lastIndex) ?: return
        viewModelScope.launch {
            val visible = gridState.layoutInfo.visibleItemsInfo
            val shown = visible.any { it.index == entry }
            // Scroll so the photo's day header is visible too, when possible.
            if (!shown) gridState.scrollToItem((entry - 1).coerceAtLeast(0))
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
        private const val KEY_VOICE = "voice_enabled"
        private const val KEY_NEWEST_FIRST = "newest_first"
        const val DEFAULT_WORD = "Αυτή"
        const val DEFAULT_LANGUAGE = "el-GR"
    }
}
