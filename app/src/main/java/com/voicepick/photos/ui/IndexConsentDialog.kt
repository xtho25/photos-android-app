package com.voicepick.photos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Asks before any photo is read for content search. */
@Composable
fun IndexConsentDialog(onAllow: () -> Unit, onDeny: () -> Unit, onDismiss: () -> Unit = onDeny) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Make your photos searchable?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "To find passports, IDs, receipts, cards, screenshots and other text in your photos, " +
                        "the app needs to read every photo once and remember the text it finds.",
                )
                Text("• Everything happens on this phone. Nothing is uploaded.", style = MaterialTheme.typography.bodySmall)
                Text(
                    "• It runs only while the phone is charging (or when you tap Index now).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "• You can turn it off later in Search by content; the stored text is then deleted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Not now") } },
    )
}
