package de.sebastian.faces.ui.fullscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.sebastian.faces.R
import de.sebastian.faces.data.db.FaceRegionEntity
import de.sebastian.faces.domain.model.FaceRegionCoords
import de.sebastian.faces.util.toFaceRegionCoords
import java.io.File

@Composable
fun FullscreenPhotoScreen(
    viewModel: FullscreenViewModel,
    currentFaceRegionId: String?,
    onRedetect: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Photo
        AsyncImage(
            model = state.photoPath?.let { File(it) },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { imageSize = it }
        )

        // Face overlays
        if (imageSize != IntSize.Zero) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                state.faceRegions.forEach { face ->
                    val coords = face.regionJson.toFaceRegionCoords()
                    val color = when {
                        face.id == currentFaceRegionId -> Color.Magenta
                        face.ignored -> Color.Gray
                        else -> Color.Green
                    }
                    drawFaceRect(coords, color, imageSize)
                }
            }
        }

        // Re-detect button
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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFaceRect(
    coords: FaceRegionCoords,
    color: Color,
    imageSize: IntSize
) {
    val w = imageSize.width.toFloat()
    val h = imageSize.height.toFloat()

    val cx = coords.x * w
    val cy = coords.y * h
    val halfW = (coords.w * w) / 2f
    val halfH = (coords.h * h) / 2f

    drawRect(
        color = color,
        topLeft = Offset(cx - halfW, cy - halfH),
        size = Size(halfW * 2f, halfH * 2f),
        style = Stroke(width = 3.dp.toPx())
    )
}
