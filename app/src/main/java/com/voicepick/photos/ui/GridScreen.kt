package com.voicepick.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.DateFormats
import com.voicepick.photos.GridEntry
import com.voicepick.photos.PhotoSource
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }
    val entries = vm.gridEntries
    val photos = vm.photos

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${vm.sourceLabel} (${photos.size})",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
            if (vm.photosLoading && photos.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (photos.isEmpty()) {
                val why = when (val s = vm.source) {
                    is PhotoSource.Dates -> "No photos between ${DateFormats.range(s.start, s.end)}"
                    is PhotoSource.Folder ->
                        if (s.relPath == vm.folder) "This album is also the working folder.\nPick a different one."
                        else "No photos in this album"
                    is PhotoSource.Search -> "No photos match «${s.query}»"
                    PhotoSource.All -> "No photos found"
                }
                Text(why, Modifier.align(Alignment.Center).padding(24.dp))
            }

            LazyVerticalGrid(
                state = vm.gridState,
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(
                    entries,
                    key = { e ->
                        when (e) {
                            is GridEntry.Header -> "d" + e.day.toEpochDay()
                            is GridEntry.Cell -> photos[e.photoIndex].id
                        }
                    },
                    span = { e -> if (e is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    contentType = { it::class },
                ) { e ->
                    when (e) {
                        is GridEntry.Header -> DayHeader(e.day, e.count)
                        is GridEntry.Cell -> {
                            val photo = photos[e.photoIndex]
                            Box(
                                Modifier
                                    .padding(1.dp)
                                    .aspectRatio(1f)
                                    .background(Color(0xFF222222))
                                    .clickable { vm.openViewer(e.photoIndex) },
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
                }
            }

            if (photos.size > 40) {
                FastScroller(
                    state = vm.gridState,
                    itemCount = entries.size,
                    labelFor = { index -> dayAt(entries, index)?.let(DateFormats::monthYear) },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 28.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            DateFormats.dayHeader(day),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

/** The day of the grid entry at [index] (a header, or the header above a photo). */
private fun dayAt(entries: List<GridEntry>, index: Int): LocalDate? {
    var i = index.coerceIn(0, entries.lastIndex.coerceAtLeast(0))
    while (i >= 0) {
        val e = entries.getOrNull(i) ?: return null
        if (e is GridEntry.Header) return e.day
        i--
    }
    return null
}

/** A draggable handle on the right edge for jumping through thousands of photos; shows the month while dragging. */
@Composable
private fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    labelFor: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    val fraction by remember(itemCount) {
        derivedStateOf { state.firstVisibleItemIndex.toFloat() / (itemCount - 1).coerceAtLeast(1) }
    }
    val thumbHeight = 56.dp

    BoxWithConstraints(modifier) {
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val thumbY = (fraction * trackPx).roundToInt()

        fun jumpTo(y: Float) {
            val f = (y / trackPx).coerceIn(0f, 1f)
            scope.launch { state.scrollToItem((f * (itemCount - 1)).roundToInt()) }
        }

        // Touch strip + handle.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(24.dp)
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
                    .offset { IntOffset(0, thumbY) }
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp)
                    .size(width = if (dragging) 10.dp else 6.dp, height = thumbHeight)
                    .background(
                        if (dragging) Color(0xFF5C6BC0) else Color(0x995C6BC0),
                        RoundedCornerShape(50),
                    ),
            )
        }

        // Month bubble next to the handle while dragging.
        val label = if (dragging) labelFor(state.firstVisibleItemIndex) else null
        if (label != null) {
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-32.dp.roundToPx(), thumbY + 8.dp.roundToPx()) }
                    .background(Color(0xE61A237E), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
