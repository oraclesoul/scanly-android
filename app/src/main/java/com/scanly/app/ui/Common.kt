package com.scanly.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanly.app.PdfExporter
import com.scanly.app.PdfPageSize
import com.scanly.app.PdfQuality
import com.scanly.app.SavedPdf
import com.scanly.app.ScanDoc
import com.scanly.app.appSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

fun formatDate(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ExportDialog(doc: ScanDoc, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(doc.name) }
    var size by remember { mutableStateOf(PdfPageSize.A4) }
    var quality by remember { mutableStateOf(PdfQuality.BALANCED) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<SavedPdf?>(null) }
    val settings = context.appSettings
    val tree by settings.saveTree.collectAsStateWithLifecycle()
    val pickFolder = rememberFolderPicker()

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (result != null) "PDF saved" else "Create PDF") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val saved = result
                when {
                    saved != null -> {
                        Text(saved.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Saved in ${saved.location}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    working -> {
                        Text("Creating PDF…")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("PDF name") },
                            suffix = { Text(".pdf") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Save to", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    settings.locationLabel(tree),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = pickFolder) { Text("Change") }
                        }
                        Text("Page size", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PdfPageSize.entries.forEach {
                                FilterChip(selected = size == it, onClick = { size = it }, label = { Text(it.label) })
                            }
                        }
                        Text("Quality", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PdfQuality.entries.forEach {
                                FilterChip(selected = quality == it, onClick = { quality = it }, label = { Text(it.label) })
                            }
                        }
                        Text(
                            "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            val saved = result
            if (saved != null) {
                Row {
                    TextButton(onClick = { openPdf(context, saved.uri) }) { Text("Open") }
                    TextButton(onClick = { sharePdf(context, saved.uri) }) { Text("Share") }
                }
            } else if (!working) {
                TextButton(onClick = {
                    working = true
                    scope.launch {
                        try {
                            result = PdfExporter.export(context, doc, name, size, quality) { done, total ->
                                progress = if (total == 0) 0f else done.toFloat() / total
                            }
                        } catch (e: Exception) {
                            val hint = if (tree != null) " Check the save location." else ""
                            Toast.makeText(context, "Could not create PDF.$hint", Toast.LENGTH_LONG).show()
                        }
                        working = false
                    }
                }, enabled = name.isNotBlank() && doc.pages.isNotEmpty()) { Text("Create") }
            }
        },
        dismissButton = {
            if (!working) TextButton(onClick = onDismiss) { Text(if (result != null) "Done" else "Cancel") }
        }
    )
}

private fun openPdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
    }
}

private fun sharePdf(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share PDF"))
}
