package de.sebastian.eidora.ui.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.sebastian.eidora.R
import java.io.File
import kotlinx.coroutines.launch
import de.sebastian.eidora.ui.common.LazyGridScrollbar

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onPhotoClick: (photoId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    val firstVisibleIndex by remember { derivedStateOf { gridState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex) {
        val item = state.items.getOrNull(firstVisibleIndex)
        if (item is PhotosListItem.Photo) {
            viewModel.updateCurrentYear(item.entity.id)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 26.dp)
        ) {
            state.items.forEach { item ->
                when (item) {
                    is PhotosListItem.MonthHeader -> {
                        item(span = { GridItemSpan(2) }, key = item.key) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                    is PhotosListItem.Photo -> {
                        item(key = item.entity.id) {
                            PhotoGridItem(
                                photo = item,
                                isSelected = state.selectedPhotoIds.contains(item.entity.id),
                                onTap = {
                                    if (state.isMultiSelectActive)
                                        viewModel.toggleSelection(item.entity.id)
                                    else
                                        onPhotoClick(item.entity.id)
                                },
                                onLongPress = {
                                    if (state.isMultiSelectActive)
                                        viewModel.rangeSelect(item.entity.id)
                                    else
                                        viewModel.toggleSelection(item.entity.id)
                                }
                            )
                        }
                    }
                }
            }
        }

        VerticalScrollbar(
            state = gridState,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(24.dp)
        )

        if (state.currentYear.isNotBlank()) {
            Text(
                text = state.currentYear,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (state.isMultiSelectActive && state.selectedPhotoIds.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(onClick = { viewModel.redetectSelected() }) {
                            Text(stringResource(R.string.action_redetect))
                        }
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGridItem(
    photo: PhotosListItem.Photo,
    isSelected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(2.dp)
            .aspectRatio(1f)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        AsyncImage(
            model = File(photo.entity.path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x660D47A1))
            )
        }
    }
}

/**
 * Simple vertical scrollbar that appears while scrolling and fades out when idle.
 */
@Composable
private fun VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    var isDragging by remember { mutableStateOf(false) }
    val isScrolling by remember { derivedStateOf { state.isScrollInProgress } }
    val alpha by animateFloatAsState(
        targetValue = if (isScrolling || isDragging) 0.7f else 0f,
        animationSpec = tween(durationMillis = if (isScrolling || isDragging) 100 else 800),
        label = "scrollbar-alpha"
    )

    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val firstVisible by remember { derivedStateOf { state.firstVisibleItemIndex } }

    if (totalItems == 0 || visibleItems >= totalItems) return

    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier = modifier.alpha(alpha)) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val handleFraction = (visibleItems.toFloat() / totalItems.toFloat()).coerceAtLeast(0.05f)
        val positionFraction = firstVisible.toFloat() / (totalItems - visibleItems).toFloat().coerceAtLeast(1f)

        val handleHeight = maxHeight * handleFraction
        val handleHeightPx = heightPx * handleFraction
        val handleOffset = (maxHeight - handleHeight) * positionFraction

        Box(
            modifier = Modifier
                .offset(y = handleOffset)
                .width(24.dp)  // Bigger hit target than visible bar
                .height(handleHeight)
                .draggable(
                    orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                    state = androidx.compose.foundation.gestures.rememberDraggableState { dragAmount ->
                        val trackPx = heightPx - handleHeightPx
                        if (trackPx <= 0f) return@rememberDraggableState
                        val currentPos = firstVisible.toFloat() +
                            (dragAmount / trackPx) * (totalItems - visibleItems).toFloat()
                        val targetIndex = currentPos.toInt().coerceIn(0, totalItems - 1)
                        scope.launch { state.scrollToItem(targetIndex) }
                    },
                    onDragStarted = { isDragging = true },
                    onDragStopped = { isDragging = false }
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.6f), shape = MaterialTheme.shapes.small)
            )
        }
    }
}
