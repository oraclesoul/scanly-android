package com.scanly.app.ui

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.scanly.app.PdfRecord
import com.scanly.app.ScanDoc
import com.scanly.app.pdfStore
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
fun HomeScreen(
    onScan: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenPdf: (String) -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val repo = context.repo
    val store = context.pdfStore
    val scope = rememberCoroutineScope()
    val docs by repo.docs.collectAsStateWithLifecycle()
    val pdfs by store.pdfs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var renameDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var deleteDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var exportDoc by remember { mutableStateOf<ScanDoc?>(null) }
    var renamePdf by remember { mutableStateOf<PdfRecord?>(null) }
    var deletePdf by remember { mutableStateOf<PdfRecord?>(null) }

    // files can be renamed or deleted from other apps, so re-check whenever we come back
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { store.refresh() } }

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
            Column {
                TopAppBar(
                    title = { Text("Scanly", fontWeight = FontWeight.Medium) },
                    actions = {
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
                PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("PDFs") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Scans") })
                }
            }
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
            if (tab == 0) {
                if (pdfs.isEmpty()) {
                    EmptyState(
                        Icons.Outlined.PictureAsPdf,
                        "No PDFs yet",
                        "Scan a document or import photos, then create a PDF. It will show up here.",
                        Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(pdfs, key = { it.id }) { pdf ->
                            PdfRow(
                                pdf = pdf,
                                onView = { onOpenPdf(pdf.id) },
                                onShare = { sharePdf(context, Uri.parse(pdf.uri)) },
                                onRename = { renamePdf = pdf },
                                onDelete = { deletePdf = pdf }
                            )
                        }
                    }
                }
            } else {
                if (docs.isEmpty()) {
                    EmptyState(
                        Icons.Outlined.DocumentScanner,
                        "No scans yet",
                        "Tap Scan to capture a document, or import photos from your gallery.",
                        Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 180.dp),
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
        ConfirmDialog("Delete scan?", "“${d.name}” and all its pages will be deleted. PDFs you already created from it are not affected.", "Delete",
            onDismiss = { deleteDoc = null }) {
            repo.deleteDoc(d.id); deleteDoc = null
        }
    }
    exportDoc?.let { d -> ExportDialog(d, onDismiss = { exportDoc = null }) }

    renamePdf?.let { p ->
        RenameDialog(p.name.removeSuffix(".pdf"), onDismiss = { renamePdf = null }) { newName ->
            renamePdf = null
            scope.launch {
                store.rename(p, newName).onFailure {
                    Toast.makeText(context, "Couldn't rename. Another file may already have that name.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    deletePdf?.let { p ->
        ConfirmDialog("Delete PDF?", "“${p.name}” will be permanently deleted from ${p.location}.", "Delete",
            onDismiss = { deletePdf = null }) {
            deletePdf = null
            scope.launch {
                store.delete(p).onFailure {
                    Toast.makeText(context, "Couldn't delete this PDF", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PdfRow(pdf: PdfRecord, onView: () -> Unit, onShare: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onView)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 56.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                AsyncImage(
                    model = context.pdfStore.thumbFile(pdf.id),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(pdf.name.removeSuffix(".pdf"), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${pdf.pageCount} page${if (pdf.pageCount == 1) "" else "s"} · ${Formatter.formatShortFileSize(context, pdf.sizeBytes)} · ${formatShortDate(context, pdf.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    pdf.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More options for ${pdf.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("View") },
                        leadingIcon = { Icon(Icons.Outlined.Visibility, null) },
                        onClick = { menu = false; onView() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Outlined.Share, null) },
                        onClick = { menu = false; onShare() }
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
