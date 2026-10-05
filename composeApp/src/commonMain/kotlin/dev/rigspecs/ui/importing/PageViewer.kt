package dev.rigspecs.ui.importing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rigspecs.data.importing.DocumentPages
import org.koin.compose.koinInject

/** One page of a document, full screen: pinch (or the buttons) to zoom, drag to pan. */
@Composable
fun PageViewerDialog(path: String, page: Int, onDismiss: () -> Unit) {
    val pages = koinInject<DocumentPages>()
    val image by produceState<Result<ImageBitmap>?>(null, path, page) {
        value = runCatching { decodeImage(pages.renderJpeg(path, page, 2400)) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val r = image) {
                null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> r.fold(
                    onSuccess = { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Page $page",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().transformable(transform).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                        )
                    },
                    onFailure = {
                        Text(
                            "Couldn't show page $page: ${it.message}",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    },
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); if (scale == 1f) offset = Offset.Zero }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out", tint = Color.White)
                }
                OutlinedButton(onClick = { scale = (scale * 1.5f).coerceAtMost(6f) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in", tint = Color.White)
                }
                Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
            }
            Text(
                "Page $page",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)
                    .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
