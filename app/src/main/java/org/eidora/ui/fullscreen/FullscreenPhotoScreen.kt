package org.eidora.ui.fullscreen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eidora.R
import org.eidora.data.db.DatabaseProvider
import org.eidora.domain.model.FaceRegionCoords
import org.eidora.util.ThumbnailHelper
import org.eidora.util.toFaceRegionCoords
import org.eidora.util.toJson
import java.io.File
import kotlin.math.min

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f

@Composable
fun FullscreenPhotoScreen(
    viewModel: FullscreenViewModel,
    currentFaceRegionId: String?,
    onRedetect: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val photoFile = remember(state.photoPath) { state.photoPath?.let { File(it) } }
    val fileExists = remember(photoFile) { photoFile?.exists() == true }

    if (!fileExists) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.BrokenImage,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.photo_not_found),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var intrinsicSize by remember { mutableStateOf(IntSize.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // Zoom/pan state
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // Rotation state (0, 90, 180, 270) – visual only until saved
    var displayRotation by remember { mutableStateOf(0f) }
    // Key to force Coil to reload after write
    var imageKey by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun rotate(delta: Float) {
        val newRotation = (displayRotation + delta + 360f) % 360f
        displayRotation = newRotation
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val file = photoFile ?: return@withContext
                    // 1. Write EXIF orientation
                    val exif = ExifInterface(file.absolutePath)
                    val currentOrientation =
                        exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL,
                        )
                    val newOrientation = rotateExifOrientation(currentOrientation, delta.toInt())
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, newOrientation.toString())
                    exif.saveAttributes()
                    // Notify MediaStore so other apps see the new orientation
                    android.media.MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf("image/jpeg"),
                        null,
                    )

                    // 2. Transform face region coords + regenerate thumbnails
                    val db = DatabaseProvider.getInstance(context)
                    val faceDao = db.faceRegionDao()
                    val photoDao = db.photoDao()
                    val photo = photoDao.findByPath(file.absolutePath) ?: return@withContext
                    val faces = faceDao.findByPhotoId(photo.id)
                    faces.forEach { face ->
                        val oldCoords = face.regionJson.toFaceRegionCoords()
                        val newCoords = oldCoords.rotate(delta.toInt())
                        faceDao.updateRegionJson(face.id, newCoords.toJson())
                        ThumbnailHelper.createThumbnail(context, file, newCoords, face.id)
                    }

                    // 3. Invalidate Coil cache so next recompose shows rotated image
                    context.imageLoader.diskCache?.remove(file.absolutePath)
                    context.imageLoader.memoryCache?.remove(
                        coil.memory.MemoryCache.Key(file.absolutePath),
                    )
                } catch (t: Throwable) {
                    android.util.Log.e("FullscreenPhoto", "Failed to save rotation", t)
                }
            }
            displayRotation = 0f
            imageKey++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { containerSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.01f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }.pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            scale = newScale
                            if (newScale > 1.01f) {
                                offsetX += pan.x
                                offsetY += pan.y
                                val maxOffsetX = (containerSize.width * (newScale - 1f)) / 2f
                                val maxOffsetY = (containerSize.height * (newScale - 1f)) / 2f
                                offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                        rotationZ = displayRotation,
                    ),
        ) {
            AsyncImage(
                model =
                    ImageRequest
                        .Builder(context)
                        .data(state.photoPath?.let { File(it) })
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { result ->
                    val d = result.painter.intrinsicSize
                    intrinsicSize = IntSize(d.width.toInt(), d.height.toInt())
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (containerSize != IntSize.Zero && intrinsicSize != IntSize.Zero) {
                val imageRect = fitRect(intrinsicSize, containerSize)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    state.faceRegions.forEach { face ->
                        val coords = face.regionJson.toFaceRegionCoords()
                        val color =
                            when {
                                face.id == currentFaceRegionId -> Color.Magenta
                                face.ignored -> Color.Gray
                                else -> Color.Green
                            }
                        drawFaceRect(coords, color, imageRect, strokeScale = 1f / scale)
                    }
                }
            }
        }

        // Bottom bar: rotate left | redetect | rotate right
        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = { rotate(-90f) }) {
                Icon(
                    Icons.Default.RotateLeft,
                    contentDescription = stringResource(R.string.action_rotate_left),
                )
            }
            Button(onClick = onRedetect) {
                Text(stringResource(R.string.action_redetect_faces))
            }
            FilledTonalIconButton(onClick = { rotate(90f) }) {
                Icon(
                    Icons.Default.RotateRight,
                    contentDescription = stringResource(R.string.action_rotate_right),
                )
            }
        }
    }
}

/**
 * Computes the new EXIF orientation after rotating by [deltaDegrees] (90 or -90).
 */
private fun rotateExifOrientation(
    current: Int,
    deltaDegrees: Int,
): Int {
    // Normalize current orientation to degrees
    val currentDeg =
        when (current) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    val newDeg = ((currentDeg + deltaDegrees) + 360) % 360
    return when (newDeg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }
}

private fun fitRect(
    intrinsic: IntSize,
    container: IntSize,
): Rect {
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
    strokeScale: Float,
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
        style = Stroke(width = 3.dp.toPx() * strokeScale),
    )
}
