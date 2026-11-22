package com.voicepick.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.voicepick.photos.AppViewModel
import com.voicepick.photos.KeywordMatcher
import com.voicepick.photos.SPEECH_LANGUAGES

/** Lets the user pick the selection word and the speech-recognition language. */
@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var word by rememberSaveable { mutableStateOf(vm.keyword) }
    var lang by rememberSaveable { mutableStateOf(vm.language) }
    var langTouched by rememberSaveable { mutableStateOf(false) }
    val valid = KeywordMatcher(word).isValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Say this word while viewing a photo to add it to the folder.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = word,
                    onValueChange = {
                        word = it
                        // Follow the alphabet of the word unless the user picked a language by hand.
                        if (!langTouched) guessLanguage(it)?.let { g -> lang = g }
                    },
                    label = { Text("Selection word") },
                    singleLine = true,
                    isError = !valid,
                    supportingText = { if (!valid) Text("Enter a word") },
                )
                Text("Speech language", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SPEECH_LANGUAGES.forEach { (tag, label) ->
                        FilterChip(
                            selected = lang == tag,
                            onClick = { lang = tag; langTouched = true },
                            label = { Text(label) },
                        )
                    }
                }
                TextButton(onClick = {
                    word = AppViewModel.DEFAULT_WORD
                    lang = AppViewModel.DEFAULT_LANGUAGE
                }) { Text("Reset to «${AppViewModel.DEFAULT_WORD}»") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                vm.saveSettings(word, lang)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun guessLanguage(word: String): String? = when {
    word.any { it in 'Ͱ'..'Ͽ' || it in 'ἀ'..'῿' } -> "el-GR"
    word.any { it in 'a'..'z' || it in 'A'..'Z' } -> "en-US"
    else -> null
}
