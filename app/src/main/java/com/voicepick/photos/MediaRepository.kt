package com.voicepick.photos

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long?,
    /** When the photo was taken (falls back to when it was added to the phone), epoch millis. */
    val dateMillis: Long,
) {
    /** Identifies "the same picture" across folders (a copy keeps name and size). */
    val key: String get() = "$name|$size"
}

data class Album(
    val name: String,
    /** MediaStore RELATIVE_PATH, e.g. "Pictures/Favourites/". */
    val relPath: String,
    val count: Int,
)

data class FolderContents(val count: Int, val keys: Set<String>)

sealed interface CopyResult {
    data object Added : CopyResult
    data object AlreadyThere : CopyResult
    data class Failed(val reason: String) : CopyResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data object NotInFolder : RemoveResult
    /** Android wants the user to confirm (the file wasn't created by this app). */
    data class NeedsConfirmation(val request: IntentSender) : RemoveResult
    data class Failed(val reason: String) : RemoveResult
}

class MediaRepository(context: Context) {
    private val resolver = context.contentResolver
    private val readUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val writeUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Images from [source], newest first, except those already in [excludeRelPath]. */
    suspend fun loadImages(source: PhotoSource, excludeRelPath: String?): List<Photo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val rel = MediaStore.Images.Media.RELATIVE_PATH
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val added = MediaStore.Images.Media.DATE_ADDED // seconds, unlike DATE_TAKEN (millis)
        val clauses = ArrayList<String>()
        val args = ArrayList<String>()
        if (excludeRelPath != null) {
            clauses += "$rel != ?"
            args += excludeRelPath
        }
        when (source) {
            PhotoSource.All -> Unit
            is PhotoSource.Folder -> {
                clauses += "$rel = ?"
                args += source.relPath
            }
            is PhotoSource.Dates -> {
                val from = DateFormats.startMillis(source.start)
                val until = DateFormats.startMillis(source.end.plusDays(1))
                clauses += "(($taken > 0 AND $taken >= ? AND $taken < ?) OR " +
                    "(($taken IS NULL OR $taken <= 0) AND $added >= ? AND $added < ?))"
                args += listOf(from, until, from / 1000, until / 1000).map(Long::toString)
            }
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val out = ArrayList<Photo>()
        resolver.query(readUri, projection, selection, args.toTypedArray(), null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val takenMs = if (c.isNull(takenCol)) null else c.getLong(takenCol).takeIf { it > 0 }
                out += Photo(
                    id = id,
                    uri = ContentUris.withAppendedId(readUri, id),
                    name = c.getString(nameCol) ?: "photo_$id.jpg",
                    mime = c.getString(mimeCol) ?: "image/jpeg",
                    size = c.getLong(sizeCol),
                    dateTaken = takenMs,
                    dateMillis = takenMs ?: (c.getLong(addedCol) * 1000),
                )
            }
        }
        out.sortedByDescending { it.dateMillis }
    }

    /**
     * Albums on the phone. With [writableOnly], only those this app may add images to
     * (under Pictures/ or DCIM/).
     */
    suspend fun listAlbums(writableOnly: Boolean): List<Album> = withContext(Dispatchers.IO) {
        val counts = HashMap<String, Int>()
        resolver.query(
            readUri,
            arrayOf(MediaStore.Images.Media.RELATIVE_PATH),
            null, null, null,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val path = c.getString(col) ?: continue
                counts[path] = (counts[path] ?: 0) + 1
            }
        }
        counts.filterKeys { !writableOnly || isWritable(it) }
            .map { (path, n) -> Album(folderName(path), path, n) }
            .sortedByDescending { it.count }
    }

    /** Count and identity keys of the images currently in [relPath]. */
    suspend fun folderContents(relPath: String): FolderContents = withContext(Dispatchers.IO) {
        val keys = HashSet<String>()
        var count = 0
        resolver.query(
            readUri,
            arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(relPath),
            null,
        )?.use { c ->
            count = c.count
            while (c.moveToNext()) {
                keys += "${c.getString(0)}|${c.getLong(1)}"
            }
        }
        FolderContents(count, keys)
    }

    /** Copies [photo] into [relPath]. Skips it if an identical file (same name and size) is already there. */
    suspend fun copyToFolder(photo: Photo, relPath: String): CopyResult = withContext(Dispatchers.IO) {
        if (photo.key in folderContents(relPath).keys) return@withContext CopyResult.AlreadyThere

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, photo.name)
            put(MediaStore.Images.Media.MIME_TYPE, photo.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
            photo.dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
        }
        val dest = try {
            resolver.insert(writeUri, values)
        } catch (e: Exception) {
            Log.e(TAG, "insert failed", e)
            null
        } ?: return@withContext CopyResult.Failed("Could not create file")

        try {
            resolver.openInputStream(photo.uri).use { input ->
                resolver.openOutputStream(dest).use { output ->
                    requireNotNull(input) { "Cannot read source" }
                    requireNotNull(output) { "Cannot write destination" }
                    input.copyTo(output)
                }
            }
            resolver.update(
                dest,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null, null,
            )
            CopyResult.Added
        } catch (e: Exception) {
            Log.e(TAG, "copy failed", e)
            runCatching { resolver.delete(dest, null, null) }
            CopyResult.Failed(e.message ?: "Copy failed")
        }
    }

    /** Deletes the copy of [photo] that is in [relPath] (matched by name and size). The original is untouched. */
    suspend fun removeFromFolder(photo: Photo, relPath: String): RemoveResult = withContext(Dispatchers.IO) {
        val uris = ArrayList<Uri>()
        resolver.query(
            readUri,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.SIZE} = ?",
            arrayOf(relPath, photo.name, photo.size.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) uris += ContentUris.withAppendedId(readUri, c.getLong(0))
        }
        if (uris.isEmpty()) return@withContext RemoveResult.NotInFolder

        try {
            uris.forEach { resolver.delete(it, null, null) }
            RemoveResult.Removed
        } catch (e: SecurityException) {
            Log.i(TAG, "delete needs user confirmation", e)
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    RemoveResult.NeedsConfirmation(MediaStore.createDeleteRequest(resolver, uris).intentSender)
                e is RecoverableSecurityException ->
                    RemoveResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
                else -> RemoveResult.Failed("Not allowed to delete")
            }
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
            RemoveResult.Failed(e.message ?: "Delete failed")
        }
    }

    companion object {
        private const val TAG = "MediaRepository"

        fun isWritable(relPath: String) =
            relPath.startsWith("Pictures/") || relPath.startsWith("DCIM/")

        fun folderName(relPath: String) =
            relPath.trimEnd('/').substringAfterLast('/').ifEmpty { relPath }

        /** Turns a user-typed folder name into a RELATIVE_PATH under Pictures/. */
        fun relPathForNewFolder(name: String): String? {
            val clean = name.trim()
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .trim('.', ' ')
            return if (clean.isEmpty()) null else "Pictures/$clean/"
        }
    }
}
