package com.scanly.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scanly.app.pdfStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Thin wrapper around [PdfRenderer]; it can only render one page at a time, hence the mutex. */
private class PdfDoc(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val mutex = Mutex()
    private var closed = false
    val pageCount: Int = renderer.pageCount

    suspend fun aspectRatios(): List<Float> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) emptyList()
            else (0 until pageCount).map { i -> renderer.openPage(i).use { it.width.toFloat() / it.height } }
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(AndroidColor.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): PdfDoc? = runCatching {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            try {
                PdfDoc(pfd, PdfRenderer(pfd))
            } catch (e: Exception) {
                pfd.close()
                null
            }
        }.getOrNull()
    }
}

private val ZOOM_LEVELS = listOf(1f, 1.5f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(pdfId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val record = remember(pdfId) { context.pdfStore.get(pdfId) }
    if (record == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val uri = remember(record.uri) { Uri.parse(record.uri) }
    val doc = remember(record.uri) { PdfDoc.open(context, uri) }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    var zoomIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
    val ratios by produceState<List<Float>>(emptyList(), doc) { value = doc?.aspectRatios() ?: emptyList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(record.name.removeSuffix(".pdf"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (doc != null) {
                            Text(
                                "Page ${currentPage.coerceAtMost(doc.pageCount)} of ${doc.pageCount}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (doc != null) {
                        IconButton(onClick = { zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.size }) {
                            Icon(
                                if (zoomIndex == ZOOM_LEVELS.lastIndex) Icons.Outlined.ZoomOut else Icons.Outlined.ZoomIn,
                                contentDescription = "Zoom"
                            )
                        }
                    }
                    IconButton(onClick = { sharePdf(context, uri) }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                    IconButton(onClick = { openPdf(context, uri) }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in another app") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (doc == null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't open this PDF. It may have been moved or deleted.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { openPdf(context, uri) }) { Text("Try another app") }
                }
            } else if (ratios.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val zoom = ZOOM_LEVELS[zoomIndex]
                    val density = LocalDensity.current
                    val viewportWidth = maxWidth
                    // render sharper when zoomed, but cap the bitmap size to keep memory in check
                    val renderWidth = with(density) { (viewportWidth * zoom).roundToPx() }.coerceAtMost(1800)
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .width(viewportWidth * zoom)
                                .fillMaxHeight()
                                .pointerInput(Unit) {
                                    detectTapGestures(onDoubleTap = { zoomIndex = (zoomIndex + 1) % ZOOM_LEVELS.size })
                                },
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(ratios) { index, ratio ->
                                val bitmap by produceState<Bitmap?>(null, index, renderWidth) {
                                    value = doc.render(index, renderWidth)
                                }
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(ratio).shadow(2.dp).background(Color.White)
                                ) {
                                    bitmap?.let {
                                        Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            contentScale = ContentScale.FillBounds,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
