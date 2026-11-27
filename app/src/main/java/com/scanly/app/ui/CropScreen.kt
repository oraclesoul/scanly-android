package com.scanly.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.scanly.app.ImageProcessor
import com.scanly.app.PageFilter
import com.scanly.app.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(docId: String, pageId: String, onBack: () -> Unit) {
    val repo = LocalContext.current.repo
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val pts = remember { mutableStateListOf<Offset>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(docId, pageId) {
        val page = repo.doc(docId)?.pages?.firstOrNull { it.id == pageId }
        if (page == null) {
            onBack()
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.Default) { ImageProcessor.decodeFile(repo.pageFile(docId, pageId), 1600) }
        pts.clear()
        for (i in 0..3) pts.add(Offset(page.corners[i * 2], page.corners[i * 2 + 1]))
    }

    fun setPoints(c: List<Float>) {
        pts.clear()
        for (i in 0..3) pts.add(Offset(c[i * 2], c[i * 2 + 1]))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(enabled = bitmap != null && pts.size == 4 && !saving, onClick = {
                        saving = true
                        val ordered = ImageProcessor.orderPoints(pts.map { floatArrayOf(it.x, it.y) })
                        scope.launch {
                            repo.editPage(docId, pageId) {
                                it.copy(
                                    corners = ordered,
                                    filter = if (it.filter == PageFilter.ORIGINAL) PageFilter.CROPPED else it.filter
                                )
                            }
                            onBack()
                        }
                    }) { Text("Done") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(enabled = bitmap != null, onClick = {
                        val bmp = bitmap ?: return@TextButton
                        scope.launch {
                            val found = withContext(Dispatchers.Default) { ImageProcessor.detectCorners(bmp) }
                            setPoints(found ?: ImageProcessor.DEFAULT_CORNERS)
                        }
                    }) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Auto detect")
                    }
                    TextButton(onClick = { setPoints(listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)) }) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Full image")
                    }
                }
            }
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
            val bmp = bitmap
            if (bmp == null || pts.size != 4) {
                CircularProgressIndicator()
            } else {
                val ratio = bmp.width.toFloat() / bmp.height
                val boxW = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
                val boxH = if (maxWidth / maxHeight > ratio) maxHeight else maxWidth / ratio
                val primary = MaterialTheme.colorScheme.primary
                Box(Modifier.size(boxW, boxH)) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            var active = -1
                            val grab = 56.dp.toPx()
                            detectDragGestures(
                                onDragStart = { start ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    var bestDist = grab
                                    active = -1
                                    pts.forEachIndexed { i, p ->
                                        val d = (Offset(p.x * w, p.y * h) - start).getDistance()
                                        if (d < bestDist) { bestDist = d; active = i }
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (active >= 0) {
                                        change.consume()
                                        val p = pts[active]
                                        pts[active] = Offset(
                                            (p.x + delta.x / size.width).coerceIn(0f, 1f),
                                            (p.y + delta.y / size.height).coerceIn(0f, 1f)
                                        )
                                    }
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 }
                            )
                        }
                    ) {
                        val px = pts.map { Offset(it.x * size.width, it.y * size.height) }
                        val quad = Path().apply {
                            moveTo(px[0].x, px[0].y)
                            for (i in 1..3) lineTo(px[i].x, px[i].y)
                            close()
                        }
                        val shade = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                            addPath(quad)
                        }
                        drawPath(shade, Color.Black.copy(alpha = 0.5f))
                        drawPath(quad, primary, style = Stroke(width = 2.dp.toPx()))
                        px.forEach {
                            drawCircle(Color.White, radius = 11.dp.toPx(), center = it)
                            drawCircle(primary, radius = 11.dp.toPx(), center = it, style = Stroke(width = 3.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}
