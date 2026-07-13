package de.sebastian.eidora.ui.fullscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import coil.compose.AsyncImage
import de.sebastian.eidora.R
import de.sebastian.eidora.domain.model.FaceRegionCoords
import de.sebastian.eidora.util.toFaceRegionCoords
import java.io.File
import kotlin.math.min

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

@Composable
fun FullscreenPhotoScreen(
    viewModel: FullscreenViewModel,
    currentFaceRegionId: String?,
    onRedetect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val photoFile = remember(state.photoPath) { state.photoPath?.let { File(it) } }
    val fileExists = remember(photoFile) { photoFile?.exists() == true }

    if (!fileExists) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.photo_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }
    var intrinsicSize by remember { mutableStateOf(IntSize.Zero) }

    // Zoom/pan state
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
                .pointerInput(Unit) {
                    // Double-tap: reset or zoom in
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.01f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        scale = newScale
                        if (newScale > 1.01f) {
                            offsetX += pan.x
                            offsetY += pan.y
                            // Clamp pan so image doesn't scroll off-screen
                            val maxOffsetX = (containerSize.width * (newScale - 1f)) / 2f
                            val maxOffsetY = (containerSize.height * (newScale - 1f)) / 2f
                            offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                            offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            AsyncImage(
                model = state.photoPath?.let { File(it) },
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { result ->
                    val d = result.painter.intrinsicSize
                    intrinsicSize = IntSize(d.width.toInt(), d.height.toInt())
                },
                modifier = Modifier.fillMaxSize()
            )

            if (containerSize != IntSize.Zero && intrinsicSize != IntSize.Zero) {
                val imageRect = fitRect(intrinsicSize, containerSize)

                Canvas(modifier = Modifier.fillMaxSize()) {
                    state.faceRegions.forEach { face ->
                        val coords = face.regionJson.toFaceRegionCoords()
                        val color = when {
                            face.id == currentFaceRegionId -> Color.Magenta
                            face.ignored -> Color.Gray
                            else -> Color.Green
                        }
                        // Stroke width inversely scaled so it stays visually constant while zooming
                        drawFaceRect(coords, color, imageRect, strokeScale = 1f / scale)
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Button(
                onClick = onRedetect,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Text(stringResource(R.string.action_redetect_faces))
            }
        }
    }
}

private fun fitRect(intrinsic: IntSize, container: IntSize): Rect {
    val scaleX = container.width.toFloat() / intrinsic.width.toFloat()
    val scaleY = container.height.toFloat() / intrinsic.height.toFloat()
    val scale = min(scaleX, scaleY)

    val renderedW = intrinsic.width * scale
    val renderedH = intrinsic.height * scale

    val offsetX = (container.width - renderedW) / 2f
    val offsetY = (container.height - renderedH) / 2f

    return Rect(offsetX, offsetY, offsetX + renderedW, offsetY + renderedH)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaceRect(
    coords: FaceRegionCoords,
    color: Color,
    imageRect: Rect,
    strokeScale: Float
) {
    val imgW = imageRect.width
    val imgH = imageRect.height

    val cx = imageRect.left + coords.x * imgW
    val cy = imageRect.top + coords.y * imgH
    val halfW = (coords.w * imgW) / 2f
    val halfH = (coords.h * imgH) / 2f

    val left = (cx - halfW).coerceAtLeast(imageRect.left)
    val top = (cy - halfH).coerceAtLeast(imageRect.top)
    val right = (cx + halfW).coerceAtMost(imageRect.right)
    val bottom = (cy + halfH).coerceAtMost(imageRect.bottom)

    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(width = 3.dp.toPx() * strokeScale)
    )
}
