package de.sebastian.faces.ui.persondetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.sebastian.faces.R
import de.sebastian.faces.data.db.FaceRegionWithPhoto
import de.sebastian.faces.util.ThumbnailHelper

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonDetailScreen(
    viewModel: PersonDetailViewModel,
    onFaceClick: (faceRegionId: String, photoId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(state.personName) })
        },
        bottomBar = {
            if (state.isMultiSelectActive && state.selectedFaceIds.isNotEmpty()) {
                MultiSelectActionBar(
                    onConfirm = { viewModel.confirmSelected() },
                    onIgnore = { viewModel.ignoreSelected() },
                    onRemove = { viewModel.removeSelected() },
                    onRedetect = { viewModel.redetectSelected() },
                    onAssign = { viewModel.showAssignSheet() }
                )
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
                start = 8.dp, end = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Unconfirmed faces
            if (state.unconfirmedFaces.isNotEmpty()) {
                items(state.unconfirmedFaces, key = { it.faceRegion.id }) { faceWithPhoto ->
                    FaceGridItem(
                        face = faceWithPhoto,
                        isSelected = state.selectedFaceIds.contains(faceWithPhoto.faceRegion.id),
                        borderColor = Color(0xFF4CAF50),
                        isMultiSelectActive = state.isMultiSelectActive,
                        onTap = {
                            if (state.isMultiSelectActive) {
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                            } else {
                                viewModel.showFaceActions(faceWithPhoto.faceRegion.id)
                            }
                        },
                        onLongPress = { viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id) },
                        onImageTap = { onFaceClick(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId) }
                    )
                }
            }

            // Divider before confirmed faces
            if (state.confirmedFaces.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.section_confirmed),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
                items(state.confirmedFaces, key = { it.faceRegion.id }) { faceWithPhoto ->
                    FaceGridItem(
                        face = faceWithPhoto,
                        isSelected = state.selectedFaceIds.contains(faceWithPhoto.faceRegion.id),
                        borderColor = null,
                        isMultiSelectActive = state.isMultiSelectActive,
                        onTap = {
                            if (state.isMultiSelectActive) {
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                            } else {
                                viewModel.showFaceActions(faceWithPhoto.faceRegion.id)
                            }
                        },
                        onLongPress = { viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id) },
                        onImageTap = { onFaceClick(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId) }
                    )
                }
            }
        }
    }

    // Single face actions bottom sheet
    state.actionFaceId?.let { faceId ->
        FaceActionsSheet(
            onConfirm = { viewModel.confirmFace(faceId) },
            onIgnore = { viewModel.ignoreFace(faceId) },
            onRemove = { viewModel.removeFace(faceId) },
            onRedetect = { viewModel.redetectFace(faceId) },
            onAssign = { viewModel.showAssignSheet(faceId) },
            onDismiss = { viewModel.dismissActions() }
        )
    }

    // Assign to person sheet
    if (state.showAssignSheet) {
        AssignToPersonSheet(
            viewModel = viewModel,
            onDismiss = { viewModel.dismissAssignSheet() }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FaceGridItem(
    face: FaceRegionWithPhoto,
    isSelected: Boolean,
    borderColor: Color?,
    isMultiSelectActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onImageTap: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailFile = ThumbnailHelper.thumbnailFile(context, face.faceRegion.id)

    Box(
        modifier = Modifier
            .size(108.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        AsyncImage(
            model = thumbnailFile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .then(
                    if (borderColor != null) Modifier.background(borderColor, CircleShape)
                    else Modifier
                )
                .combinedClickable(onClick = onImageTap)
        )
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0x660D47A1))
            )
        }
    }
}

@Composable
private fun MultiSelectActionBar(
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onRemove: () -> Unit,
    onRedetect: () -> Unit,
    onAssign: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_confirm)) }
            TextButton(onClick = onIgnore) { Text(stringResource(R.string.action_ignore)) }
            TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove_from_person)) }
            TextButton(onClick = onRedetect) { Text(stringResource(R.string.action_redetect)) }
            TextButton(onClick = onAssign) { Text(stringResource(R.string.action_assign_to_person)) }
        }
    }
}

@Composable
private fun FaceActionsSheet(
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onRemove: () -> Unit,
    onRedetect: () -> Unit,
    onAssign: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            listOf(
                stringResource(R.string.action_confirm) to onConfirm,
                stringResource(R.string.action_ignore) to onIgnore,
                stringResource(R.string.action_remove_from_person) to onRemove,
                stringResource(R.string.action_redetect) to onRedetect,
                stringResource(R.string.action_assign_to_person) to onAssign
            ).forEach { (label, action) ->
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier.combinedClickable(onClick = { action(); onDismiss() })
                )
            }
        }
    }
}
