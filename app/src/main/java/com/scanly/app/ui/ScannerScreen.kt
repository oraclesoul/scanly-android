package com.scanly.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.scanly.app.repo
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ScannerScreen(docId: String?, onFinished: (String?) -> Unit) {
    val context = LocalContext.current
    val repo = context.repo
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var currentDoc by rememberSaveable { mutableStateOf(docId) }
    var captured by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    var taking by remember { mutableStateOf(false) }
    var flash by rememberSaveable { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val docs by repo.docs.collectAsStateWithLifecycle()
    val lastPage = docs.firstOrNull { it.id == currentDoc }?.pages?.lastOrNull()

    val resolution = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolution)
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    DisposableEffect(hasPermission, lifecycleOwner) {
        if (!hasPermission) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().setResolutionSelector(resolution).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            provider.unbindAll()
            camera = try {
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(context, "Camera unavailable", Toast.LENGTH_SHORT).show()
                null
            }
        }, mainExecutor)
        onDispose { runCatching { future.get().unbindAll() } }
    }

    LaunchedEffect(flash) {
        imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    }

    fun ensureDoc(): String = currentDoc ?: repo.createDoc().id.also { currentDoc = it }

    fun capture() {
        if (taking || !hasPermission) return
        taking = true
        val file = File.createTempFile("cap", ".jpg", context.cacheDir)
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    taking = false
                    val id = ensureDoc()
                    pending++
                    scope.launch {
                        val ok = repo.addPage(id) { file.inputStream() }
                        file.delete()
                        pending--
                        if (ok) captured++ else Toast.makeText(context, "Could not save page", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    taking = false
                    file.delete()
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            val id = ensureDoc()
            pending++
            scope.launch {
                captured += importImages(context, id, uris)
                pending--
            }
        }
    }

    fun finish() {
        if (pending == 0) onFinished(currentDoc)
    }

    BackHandler { finish() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera permission is needed to scan documents.", color = Color.White)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Grant permission")
                }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("Import from gallery instead")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val overlay = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.4f), contentColor = Color.White)
            IconButton(onClick = { finish() }, colors = overlay) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(onClick = { flash = !flash }, colors = overlay) {
                    Icon(
                        if (flash) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = if (flash) "Flash on" else "Flash off"
                    )
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(Modifier.size(width = 120.dp, height = 64.dp), Alignment.CenterStart) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Import", tint = Color.White)
                }
            }

            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                    .clip(CircleShape).background(if (taking) Color.LightGray else Color.White)
                    .clickable(enabled = !taking) { capture() }
            )

            Box(Modifier.size(width = 120.dp, height = 64.dp), Alignment.CenterEnd) {
                if (captured > 0 || pending > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (lastPage != null && currentDoc != null) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                                    .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(8.dp))
                            ) {
                                AsyncImage(
                                    model = repo.thumbFile(currentDoc!!, lastPage),
                                    contentDescription = "Last page",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        if (pending > 0) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                        } else {
                            Button(onClick = { finish() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                                Icon(Icons.Outlined.Check, contentDescription = "Done", modifier = Modifier.size(18.dp))
                                Text("  $captured")
                            }
                        }
                    }
                }
            }
        }
    }
}
