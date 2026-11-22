package com.voicepick.photos.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.IndexStatus
import com.voicepick.photos.PhotoSource
import com.voicepick.photos.search.Category
import com.voicepick.photos.search.SearchQuery
import kotlinx.coroutines.launch
import java.text.NumberFormat

/** Body of the "Search by content" option: index status, query box, suggestions, match count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchSection(vm: AppViewModel, initialQuery: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var searched by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.refreshIndexStatus() }

    fun search(q: String) {
        query = q
        error = null
        if (q.isBlank()) return
        scope.launch {
            matches = vm.countMatches(q)
            searched = q
        }
    }

    val listen = rememberSpeechInput(
        language = vm.language,
        prompt = "What are you looking for? e.g. «διαβατήρια»",
        onError = { error = it },
    ) { heard -> search(heard[0]) }

    // Ask for notification permission (progress notification) before the first manual run.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startIndexing()
    }
    fun indexNow() {
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.startIndexing()
    }

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IndexStatusBlock(vm.indexStatus, onStart = ::indexNow, onPause = vm::pauseIndexing)
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; error = null },
                placeholder = { Text("e.g. διαβατήριο, receipts, Lidl") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = listen, contentPadding = PaddingValues(12.dp)) { Text("🎤", fontSize = 20.sp) }
        }
        OutlinedButton(enabled = query.isNotBlank(), onClick = { search(query) }) { Text("Search") }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Category.entries.forEach { cat ->
                SuggestionChip(onClick = { search(cat.label) }, label = { Text(cat.label) })
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        searched?.let { q ->
            HorizontalDivider()
            val parsed = SearchQuery.parse(q)
            Text(
                if (matches == 1) "1 photo matches «$q»" else "$matches photos match «$q»",
                fontWeight = FontWeight.SemiBold,
            )
            if (matches == 0 && parsed.greekTerms.isNotEmpty()) {
                Text(
                    "Text inside photos is read in Latin letters only, so Greek words written in photos " +
                        "can't be found yet. Try a category such as «διαβατήρια» or «αποδείξεις».",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            vm.indexStatus?.let { s ->
                if (s.indexed < s.total) {
                    Text(
                        "Only indexed photos are searched (${fmt(s.indexed)} of ${fmt(s.total)} so far).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(enabled = matches > 0, onClick = { vm.chooseSource(PhotoSource.Search(q)) }) {
                Text("Continue →")
            }
        }
    }
}

@Composable
private fun IndexStatusBlock(status: IndexStatus?, onStart: () -> Unit, onPause: () -> Unit) {
    if (status == null) {
        Text("Checking search index…", style = MaterialTheme.typography.bodySmall)
        return
    }
    val total = status.total.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Ready to search: ${fmt(status.indexed.coerceAtMost(status.total))} of ${fmt(status.total)} photos",
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { status.indexed.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
        )
        if (status.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reading photos… ${fmt(status.runDone)} / ${fmt(status.runTotal)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onPause) { Text("Pause") }
            }
        } else if (status.indexed < status.total) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Runs automatically while charging.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onStart) { Text("Index now") }
            }
        }
    }
}

private fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n)
