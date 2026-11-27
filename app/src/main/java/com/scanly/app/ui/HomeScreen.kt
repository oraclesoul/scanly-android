package com.scanly.app.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.scanly.app.ScanDoc
import com.scanly.app.repo
import kotlinx.coroutines.launch

suspend fun importImages(context: Context, docId: String, uris: List<Uri>): Int {
    val repo = context.repo
    var ok = 0
    uris.forEach { uri ->
        if (repo.addPage(docId) { context.contentResolver.openInputStream(uri)!! }) ok++
    }
    if (ok < uris.size) {
        Toast.makeText(context, "Some images could not be imported", Toast.LENGTH_SHORT).show()
    }
    return ok
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onScan: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val docs by repo.docs.collectAsStateWithLifecycle()
    var importing by remember { mutableStateOf(false) }
    var renameDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var deleteDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var exportDoc by remember { mutableStateOf<ScanDoc?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                importing = true
                val doc = repo.createDoc()
                val ok = importImages(context, doc.id, uris)
                importing = false
                if (ok > 0) onOpen(doc.id) else repo.deleteDoc(doc.id)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanly", fontWeight = FontWeight.Medium) },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Import images") }
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = null) },
                    text = { Text("Scan") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (docs.isEmpty()) {
                EmptyState(Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(docs, key = { it.id }) { doc ->
                        DocRow(
                            doc = doc,
                            onClick = { onOpen(doc.id) },
                            onRename = { renameDoc = doc },
                            onExport = { exportDoc = doc },
                            onDelete = { deleteDoc = doc }
                        )
                    }
                }
            }
            if (importing) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    renameDoc?.let { d ->
        RenameDialog(d.name, onDismiss = { renameDoc = null }) {
            repo.rename(d.id, it); renameDoc = null
        }
    }
    deleteDoc?.let { d ->
        ConfirmDialog("Delete document?", "“${d.name}” and all its pages will be deleted.", "Delete",
            onDismiss = { deleteDoc = null }) {
            repo.deleteDoc(d.id); deleteDoc = null
        }
    }
    exportDoc?.let { d -> ExportDialog(d, onDismiss = { exportDoc = null }) }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Outlined.DocumentScanner, contentDescription = null,
            modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("No scans yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap Scan to capture a document, or import photos from your gallery.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DocRow(doc: ScanDoc, onClick: () -> Unit, onRename: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val first = doc.pages.firstOrNull()
            Box(
                Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (first != null) {
                    AsyncImage(
                        model = context.repo.thumbFile(doc.id, first),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · ${formatDate(doc.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Create PDF") },
                        leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, null) },
                        onClick = { menu = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}
