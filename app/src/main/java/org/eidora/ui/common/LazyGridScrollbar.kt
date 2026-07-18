package org.eidora.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A drag-to-scroll indicator for [LazyVerticalGrid].
 *
 * Usage – wrap your grid in a [Box] and overlay this at [Alignment.CenterEnd]:
 *
 * ```kotlin
 * val gridState = rememberLazyGridState()
 * val scope = rememberCoroutineScope()
 *
 * Box(Modifier.fillMaxSize()) {
 *     LazyVerticalGrid(
 *         state = gridState,
 *         contentPadding = PaddingValues(end = 34.dp) // room for bar
 *     ) { … }
 *     LazyGridScrollbar(
 *         state = gridState,
 *         scope = scope,
 *         modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
 *     )
 * }
 * ```
 */
@Composable
fun LazyGridScrollbar(
    state: LazyGridState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    val isScrolling by remember { derivedStateOf { state.isScrollInProgress } }
    val alpha by animateFloatAsState(
        targetValue = if (isScrolling || isDragging) 0.7f else 0f,
        animationSpec = tween(durationMillis = if (isScrolling || isDragging) 100 else 800),
        label = "scrollbar-alpha",
    )
    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visibleItems by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    val firstVisible by remember { derivedStateOf { state.firstVisibleItemIndex } }

    if (totalItems == 0 || visibleItems >= totalItems) return

    BoxWithConstraints(modifier = modifier.alpha(alpha)) {
        val density = LocalDensity.current
        val heightPx = with(density) { maxHeight.toPx() }
        val handleFraction = (visibleItems.toFloat() / totalItems.toFloat()).coerceAtLeast(0.05f)
        val positionFraction =
            firstVisible.toFloat() /
                (totalItems - visibleItems).toFloat().coerceAtLeast(1f)
        val handleHeight = maxHeight * handleFraction
        val handleHeightPx = heightPx * handleFraction
        val handleOffset = (maxHeight - handleHeight) * positionFraction

        Box(
            modifier =
                Modifier
                    .offset(y = handleOffset)
                    .width(24.dp)
                    .height(handleHeight)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state =
                            rememberDraggableState { dragAmount ->
                                val trackPx = heightPx - handleHeightPx
                                if (trackPx <= 0f) return@rememberDraggableState
                                val currentPos =
                                    firstVisible.toFloat() +
                                        (dragAmount / trackPx) * (totalItems - visibleItems).toFloat()
                                val targetIndex = currentPos.toInt().coerceIn(0, totalItems - 1)
                                scope.launch { state.scrollToItem(targetIndex) }
                            },
                        onDragStarted = { isDragging = true },
                        onDragStopped = { isDragging = false },
                    ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(
                            Color.White.copy(alpha = 0.6f),
                            shape = MaterialTheme.shapes.small,
                        ),
            )
        }
    }
}
