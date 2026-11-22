package com.voicepick.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.voicepick.photos.AppViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All photos (${vm.photos.size})") },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 20.sp) }
                    CounterBadge(
                        vm.folderName,
                        vm.folderCount,
                        modifier = Modifier.padding(end = 8.dp),
                        onClick = vm::backToFolders,
                    )
                },
            )
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            if (vm.photosLoading && vm.photos.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (vm.photos.isEmpty()) {
                Text("No photos found", Modifier.align(Alignment.Center))
            }

            LazyVerticalGrid(
                state = vm.gridState,
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(vm.photos, key = { _, p -> p.id }) { index, photo ->
                    Box(
                        Modifier
                            .padding(1.dp)
                            .aspectRatio(1f)
                            .background(Color(0xFF222222))
                            .clickable { vm.openViewer(index) },
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(photo.uri)
                                .size(256)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (photo.key in vm.folderKeys) {
                            Text(
                                "✓",
                                color = Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .background(Color(0xFF2E7D32), CircleShape)
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }

            if (vm.photos.size > 40) {
                FastScroller(
                    state = vm.gridState,
                    itemCount = vm.photos.size,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** A draggable handle on the right edge for jumping through thousands of photos. */
@Composable
private fun FastScroller(state: LazyGridState, itemCount: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(itemCount) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (itemCount - 1).coerceAtLeast(1) }
    }
    val thumbHeight = 56.dp

    BoxWithConstraints(modifier.width(24.dp)) {
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)

        fun jumpTo(y: Float) {
            val f = (y / trackPx).coerceIn(0f, 1f)
            scope.launch { state.scrollToItem((f * (itemCount - 1)).roundToInt()) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(itemCount, trackPx) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jumpTo(it.y - thumbHeight.toPx() / 2) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            jumpTo(change.position.y - thumbHeight.toPx() / 2)
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(0, (fraction * trackPx).roundToInt()) }
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .size(width = if (dragging) 10.dp else 6.dp, height = thumbHeight)
                    .background(
                        if (dragging) Color(0xFF5C6BC0) else Color(0x995C6BC0),
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}
