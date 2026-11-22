package com.voicepick.photos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.DateFormats
import com.voicepick.photos.Period
import com.voicepick.photos.PeriodParser
import com.voicepick.photos.PhotoSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class Mode { None, Dates, Folder, Search }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadSourceAlbums() }

    val previous = vm.source
    var mode by rememberSaveable {
        mutableStateOf(
            when (previous) {
                is PhotoSource.Dates -> Mode.Dates
                is PhotoSource.Folder -> Mode.Folder
                is PhotoSource.Search -> Mode.Search
                PhotoSource.All -> Mode.None
            },
        )
    }
    var period by remember {
        mutableStateOf((previous as? PhotoSource.Dates)?.let { Period(it.label ?: "", it.start, it.end) })
    }
    var periodText by rememberSaveable { mutableStateOf(period?.label ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }

    fun tryParse(candidates: List<String>) {
        val parsed = candidates.firstNotNullOfOrNull { PeriodParser.parse(it) }
        if (parsed != null) {
            period = parsed
            periodText = parsed.label
            error = null
        } else {
            period = null
            error = "Couldn't understand «${candidates.firstOrNull().orEmpty()}». " +
                "Try e.g. «Πάσχα 2024», «Καλοκαίρι 2026», «Μάιος 2025» or «2023»."
        }
    }

    val listen = rememberSpeechInput(
        language = vm.language,
        prompt = "Say a period, e.g. «Πάσχα 2024»",
        onError = { error = it },
    ) { heard ->
        periodText = heard[0]
        tryParse(heard)
    }

    if (showCalendar) {
        CalendarDialog(
            initial = period,
            onDismiss = { showCalendar = false },
            onPicked = { start, end ->
                showCalendar = false
                val p = period
                // Keep the spoken name only if the dates weren't changed.
                period = if (p != null && p.start == start && p.end == end) p else Period("", start, end)
                periodText = period?.label.orEmpty()
                error = null
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What do you want to browse?") },
                actions = { TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 20.sp) } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 1. All photos
            item {
                OptionCard(
                    emoji = "🖼",
                    title = "All photos",
                    subtitle = "Everything on the phone, newest first",
                    onClick = { vm.chooseSource(PhotoSource.All) },
                )
            }

            // 2. A time period
            item {
                OptionCard(
                    emoji = "📅",
                    title = "A time period",
                    subtitle = "Say or type a period, or pick two dates",
                    selected = mode == Mode.Dates,
                    onClick = { mode = if (mode == Mode.Dates) Mode.None else Mode.Dates },
                ) {
                    if (mode == Mode.Dates) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = periodText,
                                    onValueChange = { periodText = it; error = null },
                                    placeholder = { Text("e.g. Πάσχα 2024, Καλοκαίρι 2026") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { tryParse(listOf(periodText)) }),
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = listen, contentPadding = PaddingValues(12.dp)) {
                                    Text("🎤", fontSize = 20.sp)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    enabled = periodText.isNotBlank(),
                                    onClick = { tryParse(listOf(periodText)) },
                                ) { Text("Find dates") }
                                OutlinedButton(onClick = { showCalendar = true }) { Text("Pick on calendar") }
                            }
                            error?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            period?.let { p ->
                                HorizontalDivider()
                                if (p.label.isNotEmpty()) Text(p.label, fontWeight = FontWeight.SemiBold)
                                Text(DateFormats.range(p.start, p.end), style = MaterialTheme.typography.bodyLarge)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { showCalendar = true }) { Text("Change dates") }
                                    Button(onClick = {
                                        vm.chooseSource(PhotoSource.Dates(p.start, p.end, p.label.ifEmpty { null }))
                                    }) { Text("Continue →") }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Search by content
            item {
                OptionCard(
                    emoji = "🔍",
                    title = "Search by content",
                    subtitle = "Passports, IDs, receipts, cards, screenshots, text…",
                    selected = mode == Mode.Search,
                    onClick = { mode = if (mode == Mode.Search) Mode.None else Mode.Search },
                ) {
                    if (mode == Mode.Search) {
                        SearchSection(vm, initialQuery = (previous as? PhotoSource.Search)?.query.orEmpty())
                    }
                }
            }

            // 4. A folder
            item {
                OptionCard(
                    emoji = "📁",
                    title = "A folder",
                    subtitle = "Only the photos in one album",
                    selected = mode == Mode.Folder,
                    onClick = { mode = if (mode == Mode.Folder) Mode.None else Mode.Folder },
                )
            }
            if (mode == Mode.Folder) {
                if (vm.sourceAlbums.isEmpty()) {
                    item { Text("Loading albums…", Modifier.padding(start = 16.dp)) }
                }
                items(vm.sourceAlbums, key = { "src_" + it.relPath }) { album ->
                    val selected = (previous as? PhotoSource.Folder)?.relPath == album.relPath
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .clickable { vm.chooseSource(PhotoSource.Folder(album.relPath)) },
                        colors = if (selected) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            CardDefaults.cardColors()
                        },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(album.name, fontWeight = FontWeight.SemiBold)
                                Text(album.relPath, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${album.count}", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCard(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(emoji, fontSize = 28.sp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            extra()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarDialog(initial: Period?, onDismiss: () -> Unit, onPicked: (LocalDate, LocalDate) -> Unit) {
    // The Material date pickers work in UTC midnight millis.
    fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.utcMillis(),
        initialSelectedEndDateMillis = initial?.end?.utcMillis(),
        initialDisplayedMonthMillis = (initial?.start ?: LocalDate.now()).utcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    val start = state.selectedStartDateMillis?.utcDate() ?: return@TextButton
                    val end = state.selectedEndDateMillis?.utcDate() ?: start
                    onPicked(start, end)
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier.height(500.dp),
            title = { Text("From – to", Modifier.padding(start = 24.dp, top = 16.dp)) },
        )
    }
}
