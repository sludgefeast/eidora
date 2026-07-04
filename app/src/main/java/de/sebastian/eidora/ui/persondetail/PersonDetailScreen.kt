package de.sebastian.eidora.ui.persondetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
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
import de.sebastian.eidora.R
import de.sebastian.eidora.data.db.FaceRegionWithPhoto
import de.sebastian.eidora.ui.common.CircleThumbnail
import de.sebastian.eidora.util.ThumbnailHelper

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    viewModel: PersonDetailViewModel,
    onFaceClick: (faceRegionId: String, photoId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    text = state.personName,
                    fontStyle = if (state.viewMode != PersonDetailViewMode.NORMAL)
                        androidx.compose.ui.text.font.FontStyle.Italic
                    else
                        androidx.compose.ui.text.font.FontStyle.Normal
                )
            })
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
            } else if (state.viewMode == PersonDetailViewMode.UNKNOWN &&
                       state.unconfirmedFaces.isNotEmpty()) {
                Surface(shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Button(
                            onClick = { viewModel.reassignUnknownFaces() },
                            enabled = !state.isReassigning
                        ) {
                            if (state.isReassigning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.action_assign_faces))
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 8.dp,
                start = 8.dp,
                end = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.unconfirmedFaces.isNotEmpty()) {
                items(state.unconfirmedFaces, key = { it.faceRegion.id }) { faceWithPhoto ->
                    FaceGridItem(
                        face = faceWithPhoto,
                        isSelected = state.selectedFaceIds.contains(faceWithPhoto.faceRegion.id),
                        borderColor = if (state.viewMode == PersonDetailViewMode.NORMAL)
                            Color(0xFF4CAF50)
                        else null,
                        onTap = {
                            if (state.isMultiSelectActive)
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                            else
                                viewModel.showFaceActions(faceWithPhoto.faceRegion.id)
                        },
                        onLongPress = {
                            if (state.isMultiSelectActive)
                                viewModel.rangeSelectFace(faceWithPhoto.faceRegion.id)
                            else
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                        },
                        onImageTap = {
                            onFaceClick(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId)
                        }
                    )
                }
            }

            if (state.confirmedFaces.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = stringResource(R.string.section_confirmed),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                    }
                }
                items(state.confirmedFaces, key = { it.faceRegion.id }) { faceWithPhoto ->
                    FaceGridItem(
                        face = faceWithPhoto,
                        isSelected = state.selectedFaceIds.contains(faceWithPhoto.faceRegion.id),
                        borderColor = null,
                        onTap = {
                            if (state.isMultiSelectActive)
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                            else
                                viewModel.showFaceActions(faceWithPhoto.faceRegion.id)
                        },
                        onLongPress = {
                            if (state.isMultiSelectActive)
                                viewModel.rangeSelectFace(faceWithPhoto.faceRegion.id)
                            else
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                        },
                        onImageTap = {
                            onFaceClick(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId)
                        }
                    )
                }
            }
        }
    }

    state.actionFaceId?.let { faceId ->
        val face = state.unconfirmedFaces.find { it.faceRegion.id == faceId }
            ?: state.confirmedFaces.find { it.faceRegion.id == faceId }
        FaceActionsSheet(
            viewMode = state.viewMode,
            onOpenPhoto = {
                face?.let { onFaceClick(faceId, it.faceRegion.photoId) }
            },
            onConfirm = { viewModel.confirmFace(faceId) },
            onIgnore = { viewModel.ignoreFace(faceId) },
            onRemove = {
                if (state.viewMode == PersonDetailViewMode.IGNORED)
                    viewModel.unignoreFace(faceId)
                else
                    viewModel.removeFace(faceId)
            },
            onRedetect = { viewModel.redetectFace(faceId) },
            onAssign = { viewModel.showAssignSheet(faceId) },
            onDismiss = { viewModel.dismissActions() }
        )
    }

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
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onImageTap: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailFile = ThumbnailHelper.thumbnailFile(context, face.faceRegion.id)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress
            )
    ) {
        CircleThumbnail(
            file = thumbnailFile,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            borderColor = borderColor
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x660D47A1))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FaceActionsSheet(
    viewMode: PersonDetailViewMode,
    onOpenPhoto: () -> Unit,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
    onRemove: () -> Unit,
    onRedetect: () -> Unit,
    onAssign: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            val actions = buildList<Pair<String, () -> Unit>> {
                add(stringResource(R.string.action_open_photo) to onOpenPhoto)

                // Confirm: only in NORMAL mode (Unknown/Ignored have no person to confirm to)
                if (viewMode == PersonDetailViewMode.NORMAL) {
                    add(stringResource(R.string.action_confirm) to onConfirm)
                }

                // Ignore: not in IGNORED mode (already ignored)
                if (viewMode != PersonDetailViewMode.IGNORED) {
                    add(stringResource(R.string.action_ignore) to onIgnore)
                }

                // Remove or Unignore depending on mode
                when (viewMode) {
                    PersonDetailViewMode.NORMAL ->
                        add(stringResource(R.string.action_remove_from_person) to onRemove)
                    PersonDetailViewMode.IGNORED ->
                        add(stringResource(R.string.action_unignore) to onRemove)
                    PersonDetailViewMode.UNKNOWN -> { /* no remove/unignore */ }
                }

                add(stringResource(R.string.action_redetect) to onRedetect)
                add(stringResource(R.string.action_assign_to_person) to onAssign)
            }

            actions.forEach { (label, action) ->
                ListItem(
                    headlineContent = { Text(label) },
                    modifier = Modifier.combinedClickable(onClick = { action(); onDismiss() })
                )
            }
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
