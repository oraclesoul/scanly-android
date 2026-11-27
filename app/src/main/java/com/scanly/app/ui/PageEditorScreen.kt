package com.scanly.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scanly.app.ImageProcessor
import com.scanly.app.PageFilter
import com.scanly.app.ScanPage
import com.scanly.app.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageEditorScreen(docId: String, startIndex: Int, onBack: () -> Unit, onCrop: (String) -> Unit) {
    val context = LocalContext.current
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val doc by remember(docId) { repo.docs.map { list -> list.firstOrNull { it.id == docId } } }
        .collectAsStateWithLifecycle(initialValue = repo.doc(docId))

    val current = doc
    LaunchedEffect(current == null) { if (current == null) onBack() }
    if (current == null) return

    val pages = current.pages
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) { pages.size }
    val page = pages.getOrNull(pagerState.currentPage)
    var confirmDelete by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page ${pagerState.currentPage + 1} of ${pages.size}") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete page") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.navigationBarsPadding().padding(bottom = 4.dp)) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(PageFilter.entries) { f ->
                            FilterChip(
                                selected = page?.filter == f,
                                onClick = { page?.let { p -> scope.launch { repo.editPage(docId, p.id) { it.copy(filter = f) } } } },
                                label = { Text(f.label) }
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToolButton(Icons.Outlined.ChevronLeft, "Move earlier", enabled = pagerState.currentPage > 0) {
                            page?.let {
                                repo.movePage(docId, it.id, -1)
                                scope.launch { pagerState.scrollToPage(pagerState.currentPage - 1) }
                            }
                        }
                        ToolButton(Icons.Outlined.RotateLeft, "Rotate left", enabled = !rotating) {
                            page?.let { p -> scope.launch { rotating = true; repo.rotatePage(docId, p.id, false); rotating = false } }
                        }
                        ToolButton(Icons.Outlined.Crop, "Crop") { page?.let { onCrop(it.id) } }
                        ToolButton(Icons.Outlined.RotateRight, "Rotate right", enabled = !rotating) {
                            page?.let { p -> scope.launch { rotating = true; repo.rotatePage(docId, p.id, true); rotating = false } }
                        }
                        ToolButton(Icons.Outlined.ChevronRight, "Move later", enabled = pagerState.currentPage < pages.size - 1) {
                            page?.let {
                                repo.movePage(docId, it.id, 1)
                                scope.launch { pagerState.scrollToPage(pagerState.currentPage + 1) }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            key = { pages.getOrNull(it)?.id ?: it },
            modifier = Modifier.fillMaxSize().padding(padding),
            pageSpacing = 12.dp
        ) { index ->
            val p = pages.getOrNull(index)
            if (p != null) PagePreview(docId, p)
        }
    }

    if (confirmDelete && page != null) {
        ConfirmDialog("Delete page?", "This page will be removed from the document.", "Delete",
            onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            repo.deletePage(docId, page.id)
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
        Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PagePreview(docId: String, page: ScanPage) {
    val repo = LocalContext.current.repo
    var bitmap by remember(page.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.id, page.version) {
        bitmap = withContext(Dispatchers.Default) {
            ImageProcessor.decodeFile(repo.pageFile(docId, page.id), 1600)?.let { src ->
                val out = ImageProcessor.render(src, page)
                if (out !== src) src.recycle()
                out
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(16.dp), Alignment.Center) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator()
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
