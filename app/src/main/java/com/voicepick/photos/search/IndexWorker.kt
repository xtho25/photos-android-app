package com.voicepick.photos.search

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.voicepick.photos.KeywordMatcher
import com.voicepick.photos.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/** Reads the text in every new or changed photo (on-device OCR) and stores it in [SearchIndex]. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var foregroundAllowed = true

    override suspend fun doWork(): Result {
        // Periodic and "Index now" runs share one worker; only one may run at a time.
        if (!running.tryLock()) return Result.success()
        return try {
            index()
        } finally {
            running.unlock()
        }
    }

    /** Automatic (periodic) runs only work while plugged in; "Index now" runs always. */
    private val automatic get() = inputData.getBoolean(KEY_AUTOMATIC, false)

    private suspend fun index(): Result {
        val ctx = applicationContext
        // Never read photos unless the user agreed (also guards schedules left from older versions).
        if (!isAllowed(ctx)) return Result.success()
        if (automatic && !isPluggedIn(ctx)) return Result.success()
        val readPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(ctx, readPerm) != PackageManager.PERMISSION_GRANTED) return Result.success()

        val index = SearchIndex.get(ctx)
        val items = MediaRepository(ctx).listForIndex()
        val todo = withContext(Dispatchers.IO) {
            index.retainOnly(items.mapTo(HashSet()) { it.id })
            val known = index.indexedVersions()
            items.filter { known[it.id] != it.modified }
        }
        if (todo.isEmpty()) return Result.success()
        Log.i(TAG, "indexing ${todo.size} photos")

        updateProgress(0, todo.size)
        val startedAt = System.currentTimeMillis()
        var processed = 0
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            for ((i, item) in todo.withIndex()) {
                if (isStopped || !isAllowed(ctx)) break
                if (automatic && i % 10 == 0 && !isPluggedIn(ctx)) {
                    Log.i(TAG, "unplugged, pausing")
                    break
                }
                val text = recognize(recognizer, item.uri).orEmpty()
                val categories = DocumentClassifier.classify(text, item.relPath, item.name)
                withContext(Dispatchers.IO) {
                    index.put(item.id, item.modified, categories, KeywordMatcher.normalize(text))
                }
                processed = i + 1
                if (processed % 10 == 0 || i == todo.lastIndex) updateProgress(processed, todo.size)
                if (processed % 50 == 0) Log.i(TAG, "progress $processed/${todo.size}")
            }
        } finally {
            recognizer.close()
            val secs = (System.currentTimeMillis() - startedAt) / 1000.0
            Log.i(TAG, "processed $processed/${todo.size} in ${"%.1f".format(secs)}s")
        }
        return Result.success()
    }

    private suspend fun recognize(recognizer: TextRecognizer, uri: Uri): String? = try {
        val bitmap = withContext(Dispatchers.IO) { decode(uri) }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $uri", e)
        null
    }

    /** Decodes downscaled (longest side ≤ [MAX_SIDE]) to keep memory and time low; EXIF rotation is applied. */
    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(applicationContext.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > MAX_SIDE) {
                val s = MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private suspend fun updateProgress(done: Int, total: Int) {
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        if (!foregroundAllowed) return
        try {
            setForeground(foregroundInfo(done, total))
        } catch (e: Exception) {
            // Android may refuse a foreground job started from the background; continue without it.
            Log.i(TAG, "running without foreground notification", e)
            foregroundAllowed = false
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Photo search indexing", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Preparing photo search")
            .setContentText("$done / $total photos")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Pause",
                WorkManager.getInstance(ctx).createCancelPendingIntent(id),
            )
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val TAG = "IndexWorker"
        private const val CHANNEL = "indexing"
        private const val NOTIFICATION_ID = 42
        private const val MAX_SIDE = 1600
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val WORK_TAG = "search-index"
        private const val KEY_AUTOMATIC = "automatic"
        private const val WORK_NOW = "search-index-now"
        private const val WORK_PERIODIC = "search-index-periodic"

        private val running = Mutex()

        /** SharedPreferences file and key holding the user's answer to "index my photos?". */
        const val PREFS = "settings"
        const val KEY_CONSENT = "index_consent"

        /** null = not asked yet. */
        fun consent(context: Context): Boolean? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (prefs.contains(KEY_CONSENT)) prefs.getBoolean(KEY_CONSENT, false) else null
        }

        fun isAllowed(context: Context) = consent(context) == true

        /** Turns indexing off completely: no automatic runs until the user allows it again. */
        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        }

        /**
         * Checks hourly and indexes new photos only while the phone is plugged in.
         * (WorkManager's own "requires charging" constraint misreads the charging state on HyperOS,
         * so the worker checks the battery itself.)
         */
        fun schedulePeriodic(context: Context, initialDelayHours: Long = 0) {
            val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(initialDelayHours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(KEY_AUTOMATIC to true))
                .addTag(WORK_TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun isPluggedIn(context: Context): Boolean {
            val bm = context.getSystemService(BatteryManager::class.java)
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
            if (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL) return true
            if (bm?.isCharging == true) return true
            val sticky: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            Log.d(TAG, "battery status=$status plugged=$plugged")
            return plugged != 0
        }

        /** "Index now": runs right away, also on battery. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<IndexWorker>().addTag(WORK_TAG).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
        }

        /** Stops any running indexing. The automatic while-charging schedule resumes in a few hours. */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            schedulePeriodic(context, initialDelayHours = 6)
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
