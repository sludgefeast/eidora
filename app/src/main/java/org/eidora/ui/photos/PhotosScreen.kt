// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.photos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import org.eidora.R
import org.eidora.ui.common.LazyGridScrollbar
import java.io.File

@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel,
    onPhotoClick: (photoId: String, faceId: String?) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

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
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(end = 26.dp),
        ) {
            state.items.forEach { item ->
                when (item) {
                    is PhotosListItem.MonthHeader -> {
                        item(span = { GridItemSpan(2) }, key = item.key) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    is PhotosListItem.Photo -> {
                        item(key = item.entity.id) {
                            PhotoGridItem(
                                photo = item,
                                isSelected = state.selectedPhotoIds.contains(item.entity.id),
                                onTap = {
                                    if (state.isMultiSelectActive) {
                                        viewModel.toggleSelection(item.entity.id)
                                    } else {
                                        val faceId = state.confirmedFaceByPhoto[item.entity.id]
                                        onPhotoClick(item.entity.id, faceId)
                                    }
                                },
                                onLongPress = {
                                    if (state.isMultiSelectActive) {
                                        viewModel.rangeSelect(item.entity.id)
                                    } else {
                                        viewModel.toggleSelection(item.entity.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyGridScrollbar(
            state = gridState,
            scope = scope,
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight(),
        )

        if (state.currentYear.isNotBlank()) {
            Text(
                text = state.currentYear,
                fontSize = 13.sp,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 12.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = MaterialTheme.shapes.small,
                        ).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }

        if (state.isMultiSelectActive && state.selectedPhotoIds.isNotEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(shadowElevation = 8.dp) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
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
    onLongPress: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(2.dp)
                .aspectRatio(1f)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress),
    ) {
        AsyncImage(
            model = File(photo.entity.path),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isSelected) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x660D47A1)),
            )
        }
    }
}
