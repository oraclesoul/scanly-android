package com.scanly.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.scanly.app.PageFilter
import com.scanly.app.ScanPage
import com.scanly.app.repo
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    docId: String,
    onBack: () -> Unit,
    onScanMore: () -> Unit,
    onOpenPage: (Int) -> Unit
) {
    val context = LocalContext.current
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val doc by remember(docId) { repo.docs.map { list -> list.firstOrNull { it.id == docId } } }
        .collectAsStateWithLifecycle(initialValue = repo.doc(docId))

    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showDeletePages by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            importImages(context, docId, uris)
            busy = false
        }
    }

    val current = doc
    LaunchedEffect(current == null) { if (current == null) onBack() }
    if (current == null) return

    // only pages that still exist count as selected
    val sel = remember(current.pages, selected) { current.pages.filter { it.id in selected } }
    val selecting = sel.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${sel.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
                    },
                    actions = {
                        IconButton(onClick = { selected = current.pages.map { it.id }.toSet() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            current.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showRename = true }
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select all pages") },
                                    leadingIcon = { Icon(Icons.Outlined.SelectAll, null) },
                                    onClick = { menu = false; selected = current.pages.map { it.id }.toSet() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                    onClick = { menu = false; showRename = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete document") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menu = false; showDelete = true }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        },
        bottomBar = {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.navigationBarsPadding().padding(bottom = 4.dp)) {
                        Text(
                            "Apply to ${sel.size} selected",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val common = sel.map { it.filter }.distinct().singleOrNull()
                            LazyRow(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(PageFilter.entries) { f ->
                                    FilterChip(
                                        selected = common == f,
                                        onClick = {
                                            val ids = sel.map { it.id }
                                            scope.launch { busy = true; repo.setFilter(docId, ids, f); busy = false }
                                        },
                                        label = { Text(f.label) }
                                    )
                                }
                            }
                            IconButton(onClick = { showDeletePages = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete selected pages")
                            }
                        }
                    }
                }
            } else {
                BottomAppBar(
                    actions = {
                        IconButton(onClick = onScanMore) { Icon(Icons.Outlined.DocumentScanner, contentDescription = "Scan more pages") }
                        IconButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Import images") }
                    },
                    floatingActionButton = {
                        FloatingActionButton(
                            onClick = { showExport = true },
                            elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()
                        ) { Icon(Icons.Outlined.PictureAsPdf, contentDescription = "Create PDF") }
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (selecting) "Tap pages to select or deselect them"
                else "Tap the circle on a page to select it · Hold and drag a page to reorder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Box(Modifier.fillMaxSize()) {
                PageGrid(
                    docId = docId,
                    pages = current.pages,
                    selected = selected,
                    selecting = selecting,
                    onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
                    onOpen = onOpenPage,
                    onReordered = { repo.setOrder(docId, it) }
                )
                if (busy) {
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
                        Alignment.Center
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(current.name, onDismiss = { showRename = false }) {
            repo.rename(docId, it); showRename = false
        }
    }
    if (showDelete) {
        ConfirmDialog("Delete document?", "“${current.name}” and all its pages will be deleted.", "Delete",
            onDismiss = { showDelete = false }) {
            showDelete = false
            repo.deleteDoc(docId)
        }
    }
    if (showDeletePages && selecting) {
        val all = sel.size == current.pages.size
        ConfirmDialog(
            title = if (sel.size == 1) "Delete page?" else "Delete ${sel.size} pages?",
            message = if (all) "These are all the pages, so the whole document will be deleted."
            else "The selected pages will be removed from this document.",
            confirmLabel = "Delete",
            onDismiss = { showDeletePages = false }
        ) {
            showDeletePages = false
            val ids = sel.map { it.id }.toSet()
            selected = emptySet()
            repo.deletePages(docId, ids)
        }
    }
    if (showExport) ExportDialog(current, onDismiss = { showExport = false })
}

@Composable
private fun PageGrid(
    docId: String,
    pages: List<ScanPage>,
    selected: Set<String>,
    selecting: Boolean,
    onToggle: (String) -> Unit,
    onOpen: (Int) -> Unit,
    onReordered: (List<String>) -> Unit
) {
    val repo = LocalContext.current.repo
    var local by remember(pages) { mutableStateOf(pages) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(local, key = { it.id }) { page ->
            ReorderableItem(reorderState, key = page.id, enabled = !selecting) { isDragging ->
                val index = local.indexOf(page)
                val isSelected = page.id in selected
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .shadow(if (isDragging) 12.dp else 0.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            if (selecting) onToggle(page.id)
                            else onOpen(pages.indexOfFirst { it.id == page.id }.coerceAtLeast(0))
                        }
                        .longPressDraggableHandle(
                            enabled = !selecting,
                            onDragStopped = { onReordered(local.map { it.id }) }
                        )
                ) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            AsyncImage(
                                model = repo.thumbFile(docId, page),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp).clip(CircleShape)
                                    .background(if (isSelected) Color.White else Color.Black.copy(alpha = 0.35f))
                                    .clickable { onToggle(page.id) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    contentDescription = if (isSelected) "Deselect page ${index + 1}" else "Select page ${index + 1}",
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                            Text(
                                page.filter.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
