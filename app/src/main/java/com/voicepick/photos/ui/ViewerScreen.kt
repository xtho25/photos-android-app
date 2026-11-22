package com.voicepick.photos.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.Feedback
import com.voicepick.photos.KeywordListener
import com.voicepick.photos.KeywordMatcher
import com.voicepick.photos.ListenStatus
import com.voicepick.photos.Photo
import kotlinx.coroutines.delay

private data class Flash(val id: Int, val text: String, val color: Color)

@Composable
fun ViewerScreen(vm: AppViewModel, startIndex: Int) {
    val photos = vm.photos
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { vm.closeViewer(0) }
        return
    }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val currentPhotos by rememberUpdatedState(photos)
    val currentPage by rememberUpdatedState(pager.currentPage)

    var status by remember { mutableStateOf(ListenStatus.Off) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var flashCounter by remember { mutableIntStateOf(0) }
    var zoomed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }

    // System "Allow deletion?" dialog, for files this app didn't create.
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        vm.onDeleteConfirmed(it.resultCode == android.app.Activity.RESULT_OK)
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMic = it
    }

    fun addCurrent() {
        currentPhotos.getOrNull(currentPage)?.let(vm::addPhoto)
    }

    // Voice listening: only while this screen is visible, the app is in the foreground
    // and the settings dialog is closed. Restarts when the word or language changes.
    val keyword = vm.keyword
    val language = vm.language
    DisposableEffect(lifecycleOwner, hasMic, keyword, language, showSettings) {
        val listener = KeywordListener(
            context,
            matcher = KeywordMatcher(keyword),
            language = language,
            onKeyword = { addCurrent() },
            onStatus = { status = it },
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> when {
                    !hasMic -> status = ListenStatus.NoPermission
                    !showSettings -> listener.start()
                }
                Lifecycle.Event.ON_PAUSE -> listener.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        view.keepScreenOn = true
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            listener.stop()
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            if (event is Feedback.ConfirmDelete) {
                deleteLauncher.launch(IntentSenderRequest.Builder(event.request).build())
                return@collect
            }
            flashCounter++
            flash = when (event) {
                Feedback.Added -> {
                    vibrate(context, 70)
                    Flash(flashCounter, "✓ Added", Color(0xE62E7D32))
                }
                Feedback.AlreadyThere -> {
                    vibrate(context, 25)
                    Flash(flashCounter, "Already in folder", Color(0xE6F57F17))
                }
                Feedback.Removed -> {
                    vibrate(context, 40)
                    Flash(flashCounter, "✕ Removed", Color(0xE6455A64))
                }
                Feedback.NotInFolder -> Flash(flashCounter, "Not in folder", Color(0xE6455A64))
                is Feedback.Failed -> Flash(flashCounter, "Failed: ${event.reason}", Color(0xE6C62828))
                is Feedback.ConfirmDelete -> return@collect
            }
        }
    }
    LaunchedEffect(flash?.id) {
        if (flash != null) {
            delay(1300)
            flash = null
        }
    }

    BackHandler { vm.closeViewer(pager.currentPage) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pager,
            key = { photos[it].id },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                photo = photos[page],
                isCurrent = page == pager.currentPage,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
            )
        }

        // Top bar: back + position on the left, counter in the top-right corner.
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(onClick = { vm.closeViewer(pager.currentPage) }) { Text("←", color = Color.White, fontSize = 18.sp) }
            Pill(modifier = Modifier.padding(start = 8.dp)) {
                Text("${pager.currentPage + 1} / ${photos.size}", color = Color.White, fontSize = 13.sp)
            }
            Box(Modifier.weight(1f))
            CounterBadge(vm.folderName, vm.folderCount)
        }

        // Mic status under the top bar.
        MicIndicator(
            status = status,
            keyword = keyword,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 8.dp, top = 56.dp),
            onClick = {
                if (!hasMic) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else showSettings = true
            },
        )

        // Feedback flash in the middle.
        AnimatedVisibility(
            visible = flash != null,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val f = flash
            if (f != null) {
                Text(
                    f.text,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(f.color, RoundedCornerShape(20.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                )
            }
        }

        // Bottom: in-folder marker + remove button on the left, manual add button on the right.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val current = photos.getOrNull(pager.currentPage)
            if (current != null && current.key in vm.folderKeys) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(color = Color(0xCC2E7D32)) { Text("✓ In folder", color = Color.White, fontSize = 13.sp) }
                    Pill(
                        modifier = Modifier.padding(start = 8.dp),
                        color = Color(0xCCB71C1C),
                        onClick = { vm.removePhoto(current) },
                    ) {
                        Text("✕ Remove", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Box(Modifier.size(1.dp))
            }
            Pill(color = Color(0xCC1A237E), onClick = { addCurrent() }) {
                Text("＋ Add", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Pill(
    modifier: Modifier = Modifier,
    color: Color = Color(0x99000000),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(color, RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MicIndicator(status: ListenStatus, keyword: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (dot, label) = when (status) {
        ListenStatus.Listening -> Color(0xFF66BB6A) to "Listening for «$keyword»"
        ListenStatus.Hearing -> Color(0xFFFFEE58) to "Hearing…"
        ListenStatus.Starting -> Color(0xFF9E9E9E) to "Mic starting…"
        ListenStatus.Off -> Color(0xFF616161) to "Mic off"
        ListenStatus.Error -> Color(0xFFFF7043) to "Mic retrying…"
        ListenStatus.Unavailable -> Color(0xFFE53935) to "Speech recognition unavailable for this language"
        ListenStatus.NoPermission -> Color(0xFFE53935) to "Tap to allow microphone"
    }
    Row(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(dot, CircleShape),
        )
        Text("  🎤 $label  ⚙", color = Color.White, fontSize = 12.sp)
    }
}

/** Full-screen photo with pinch-zoom and double-tap zoom. Single-finger swipes go to the pager unless zoomed. */
@Composable
private fun ZoomableImage(photo: Photo, isCurrent: Boolean, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomCallback by rememberUpdatedState(onZoomChanged)

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun set(s: Float, o: Offset) {
        val wasZoomed = scale > 1f
        scale = s
        offset = if (s <= 1f) Offset.Zero else clamp(o, s)
        if (wasZoomed != (s > 1f)) zoomCallback(s > 1f)
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent && scale != 1f) set(1f, Offset.Zero)
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                size = this.size
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            set(1f, Offset.Zero)
                        } else {
                            val s = 2.5f
                            val center = Offset(this.size.width / 2f, this.size.height / 2f)
                            set(s, (center - tap) * (s - 1f))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                size = this.size
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            set(newScale, offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = photo.uri,
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

private fun vibrate(context: Context, ms: Long) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
