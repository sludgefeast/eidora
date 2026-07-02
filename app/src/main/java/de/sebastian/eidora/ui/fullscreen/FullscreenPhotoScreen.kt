package de.sebastian.eidora.ui.fullscreen

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import de.sebastian.eidora.R
import de.sebastian.eidora.domain.model.FaceRegionCoords
import de.sebastian.eidora.util.toFaceRegionCoords
import java.io.File
import kotlin.math.min

@Composable
fun FullscreenPhotoScreen(
    viewModel: FullscreenViewModel,
    currentFaceRegionId: String?,
    onRedetect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    // Container size (full composable)
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // Intrinsic image size (actual pixels of the photo)
    var intrinsicSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = state.photoPath?.let { File(it) },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            onSuccess = { result ->
                val d = result.painter.intrinsicSize
                intrinsicSize = IntSize(d.width.toInt(), d.height.toInt())
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it }
        )

        // Draw face overlays only when we know both sizes
        if (containerSize != IntSize.Zero && intrinsicSize != IntSize.Zero) {
            // Calculate the actual rendered image rect within the container (ContentScale.Fit)
            val imageRect = fitRect(intrinsicSize, containerSize)

            Canvas(modifier = Modifier.fillMaxSize()) {
                state.faceRegions.forEach { face ->
                    val coords = face.regionJson.toFaceRegionCoords()
                    val color = when {
                        face.id == currentFaceRegionId -> Color.Magenta
                        face.ignored -> Color.Gray
                        else -> Color.Green
                    }
                    drawFaceRect(coords, color, imageRect)
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

/**
 * Calculates the actual rendered image rect within a container
 * when ContentScale.Fit is used (letterboxing).
 */
private fun fitRect(intrinsic: IntSize, container: IntSize): Rect {
    val scaleX = container.width.toFloat() / intrinsic.width.toFloat()
    val scaleY = container.height.toFloat() / intrinsic.height.toFloat()
    val scale = min(scaleX, scaleY)

    val renderedW = intrinsic.width * scale
    val renderedH = intrinsic.height * scale

    // Center within container
    val offsetX = (container.width - renderedW) / 2f
    val offsetY = (container.height - renderedH) / 2f

    return Rect(offsetX, offsetY, offsetX + renderedW, offsetY + renderedH)
}

/**
 * Draws a face region rect using normalized MWG coords mapped onto the actual image rect.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaceRect(
    coords: FaceRegionCoords,
    color: Color,
    imageRect: Rect
) {
    val imgW = imageRect.width
    val imgH = imageRect.height

    val cx = imageRect.left + coords.x * imgW
    val cy = imageRect.top + coords.y * imgH
    val halfW = (coords.w * imgW) / 2f
    val halfH = (coords.h * imgH) / 2f

    // Clamp to image rect to never draw outside the photo
    val left = (cx - halfW).coerceAtLeast(imageRect.left)
    val top = (cy - halfH).coerceAtLeast(imageRect.top)
    val right = (cx + halfW).coerceAtMost(imageRect.right)
    val bottom = (cy + halfH).coerceAtMost(imageRect.bottom)

    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        style = Stroke(width = 3.dp.toPx())
    )
}
