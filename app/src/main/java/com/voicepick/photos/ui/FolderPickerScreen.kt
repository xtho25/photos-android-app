package com.voicepick.photos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicepick.photos.Album
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.MediaRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { vm.loadAlbums() }
    var newName by rememberSaveable { mutableStateOf("") }
    val newPath = MediaRepository.relPathForNewFolder(newName)
    val current = vm.folder
    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) SettingsDialog(vm) { showSettings = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose working folder") },
                navigationIcon = {
                    TextButton(onClick = vm::backToSource) { Text("←", fontSize = 20.sp) }
                },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("⚙", fontSize = 20.sp) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Browsing: ${vm.sourceLabel}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "Photos you pick by saying «${vm.keyword}» are copied into this folder. " +
                        "Tap ⚙ to change the word.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (current != null) {
                item {
                    Button(onClick = { vm.chooseFolder(current) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continue with “${MediaRepository.folderName(current)}”")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            item {
                Text("New folder", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("e.g. Επιλογές") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { newPath?.let(vm::chooseFolder) }),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = newPath != null, onClick = { newPath?.let(vm::chooseFolder) }) {
                        Text("Use")
                    }
                }
                if (newPath != null) {
                    Text(
                        "Will be saved in $newPath",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Existing albums", style = MaterialTheme.typography.titleMedium)
            }

            if (vm.albumsLoading && vm.albums.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(vm.albums, key = { it.relPath }) { album ->
                AlbumRow(album, selected = album.relPath == current) { vm.chooseFolder(album.relPath) }
            }
        }
    }
}

@Composable
private fun AlbumRow(album: Album, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📁 ${album.name}", fontWeight = FontWeight.SemiBold)
                Text(album.relPath, style = MaterialTheme.typography.bodySmall)
            }
            Text("${album.count}", style = MaterialTheme.typography.titleMedium)
        }
    }
}
