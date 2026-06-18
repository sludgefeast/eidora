package de.sebastian.faces.ui.persondetail

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
import de.sebastian.faces.R
import de.sebastian.faces.data.db.FaceRegionWithPhoto
import de.sebastian.faces.util.ThumbnailHelper

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
                        borderColor = Color(0xFF4CAF50),
                        onTap = {
                            if (state.isMultiSelectActive)
                                viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id)
                            else
                                viewModel.showFaceActions(faceWithPhoto.faceRegion.id)
                        },
                        onLongPress = { viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id) },
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
                        onLongPress = { viewModel.toggleFaceSelection(faceWithPhoto.faceRegion.id) },
                        onImageTap = {
                            onFaceClick(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId)
                        }
                    )
                }
            }
        }
    }

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
            .size(108.dp)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
    ) {
        val imageModifier = if (borderColor != null) {
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(borderColor, CircleShape)
                .padding(3.dp)
                .clip(CircleShape)
        } else {
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
        }

        AsyncImage(
            model = thumbnailFile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = imageModifier.combinedClickable(onClick = onImageTap)
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
