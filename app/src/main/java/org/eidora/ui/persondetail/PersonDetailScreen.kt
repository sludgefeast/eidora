package org.eidora.ui.persondetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.eidora.R
import org.eidora.data.db.FaceRegionWithPhoto
import org.eidora.ui.common.CircleThumbnail
import org.eidora.ui.common.MergeConfirmDialog
import org.eidora.util.ThumbnailHelper
import org.eidora.ui.common.LazyGridScrollbar

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    viewModel: PersonDetailViewModel,
    onBack: () -> Unit,
    onNavigateToPerson: (personId: String) -> Unit,
    onFaceClick: (faceRegionId: String, photoId: String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    var isEditingName by remember { mutableStateOf(false) }
    var editedName by remember(state.personName) { mutableStateOf(state.personName) }
    var showDeletePersonConfirm by remember { mutableStateOf(false) }
    var showRemoveUnconfirmedConfirm by remember { mutableStateOf(false) }

    if (showRemoveUnconfirmedConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveUnconfirmedConfirm = false },
            title = { Text(stringResource(R.string.action_remove_unconfirmed)) },
            text = { Text(stringResource(R.string.remove_unconfirmed_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveUnconfirmedConfirm = false
                    viewModel.removeUnconfirmedFaces()
                }) { Text(stringResource(R.string.action_remove_unconfirmed_short)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveUnconfirmedConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDeletePersonConfirm) {
        AlertDialog(
            onDismissRequest = { showDeletePersonConfirm = false },
            title = { Text(stringResource(R.string.action_delete_person)) },
            text = { Text(stringResource(R.string.delete_person_confirm_message, state.personName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeletePersonConfirm = false
                    viewModel.deleteCurrentPerson { onBack() }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePersonConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Auto-start editing when opening a suggestion; leave editing when it becomes a named person
    LaunchedEffect(state.viewMode) {
        when (state.viewMode) {
            PersonDetailViewMode.SUGGESTION -> {
                isEditingName = true
                editedName = ""
            }
            PersonDetailViewMode.NORMAL -> {
                isEditingName = false
            }
            else -> { /* leave as-is for virtual persons */ }
        }
    }

    val canEdit = state.viewMode == PersonDetailViewMode.NORMAL ||
                  state.viewMode == PersonDetailViewMode.SUGGESTION

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingName && canEdit) {
                        val focusRequester = remember { FocusRequester() }
                        OutlinedTextField(
                            value = editedName,
                            onValueChange = { editedName = it },
                            singleLine = true,
                            placeholder = {
                                if (state.viewMode == PersonDetailViewMode.SUGGESTION) {
                                    Text(stringResource(R.string.hint_enter_name))
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (editedName.isNotBlank() && editedName != state.personName) {
                                    viewModel.renameCurrentPerson(editedName.trim())
                                }
                                if (state.viewMode != PersonDetailViewMode.SUGGESTION) {
                                    isEditingName = false
                                }
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                    } else {
                        Text(
                            text = state.personName,
                            fontStyle = if (state.viewMode != PersonDetailViewMode.NORMAL)
                                androidx.compose.ui.text.font.FontStyle.Italic
                            else
                                androidx.compose.ui.text.font.FontStyle.Normal
                        )
                    }
                },
                actions = {
                    when {
                        state.viewMode == PersonDetailViewMode.SUGGESTION -> {
                            IconButton(onClick = {
                                viewModel.rejectCurrentSuggestion()
                                onBack()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_reject_suggestion)
                                )
                            }
                            IconButton(onClick = {
                                if (editedName.isNotBlank()) {
                                    viewModel.renameCurrentPerson(editedName.trim())
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.action_confirm)
                                )
                            }
                        }
                        state.viewMode == PersonDetailViewMode.NORMAL -> {
                            if (isEditingName) {
                                IconButton(onClick = {
                                    isEditingName = false
                                    editedName = state.personName
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                                }
                                IconButton(onClick = {
                                    if (editedName.isNotBlank() && editedName != state.personName) {
                                        viewModel.renameCurrentPerson(editedName.trim())
                                    }
                                    isEditingName = false
                                }) {
                                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.action_edit_name))
                                }
                            } else {
                                var overflowExpanded by remember { mutableStateOf(false) }
                                IconButton(onClick = { showDeletePersonConfirm = true }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.action_delete_person),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                IconButton(onClick = { editedName = state.personName; isEditingName = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit_name))
                                }
                                IconButton(onClick = { overflowExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                }
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.action_remove_unconfirmed),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            overflowExpanded = false
                                            showRemoveUnconfirmedConfirm = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            )
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
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()

        Box(modifier = Modifier.padding(padding)) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 8.dp,
                start = 8.dp,
                end = 34.dp   // room for scrollbar
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.viewMode == PersonDetailViewMode.SUGGESTION) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        text = stringResource(R.string.suggestion_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp)
                    )
                }
            }
            if (state.unconfirmedFaces.isNotEmpty()) {
                faceItemsWithMonthHeaders(
                    faces = state.unconfirmedFaces,
                    isSelected = { state.selectedFaceIds.contains(it) },
                    borderColorFor = { if (state.viewMode == PersonDetailViewMode.NORMAL) Color(0xFF4CAF50) else null },
                    onTap = { id ->
                        if (state.isMultiSelectActive) viewModel.toggleFaceSelection(id)
                        else viewModel.showFaceActions(id)
                    },
                    onLongPress = { id ->
                        if (state.isMultiSelectActive) viewModel.rangeSelectFace(id)
                        else viewModel.toggleFaceSelection(id)
                    },
                    onImageTap = { faceId, photoId -> onFaceClick(faceId, photoId) }
                )
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
                faceItemsWithMonthHeaders(
                    faces = state.confirmedFaces,
                    isSelected = { state.selectedFaceIds.contains(it) },
                    borderColorFor = { null },
                    onTap = { id ->
                        if (state.isMultiSelectActive) viewModel.toggleFaceSelection(id)
                        else viewModel.showFaceActions(id)
                    },
                    onLongPress = { id ->
                        if (state.isMultiSelectActive) viewModel.rangeSelectFace(id)
                        else viewModel.toggleFaceSelection(id)
                    },
                    onImageTap = { faceId, photoId -> onFaceClick(faceId, photoId) }
                )
            }
        }
        // Drag scrollbar
        LazyGridScrollbar(
            state = gridState,
            scope = scope,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
        } // Box
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
            onPermanentlyDelete = { viewModel.permanentlyDeleteFace(faceId) },
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
    state.mergeConflict?.let { conflict ->
        val context = LocalContext.current
        val thumbnail = conflict.targetRepresentativeFaceId?.let {
            ThumbnailHelper.thumbnailFile(context, it)
        }
        MergeConfirmDialog(
            existingPersonName = conflict.targetPersonName,
            existingRepresentativeThumbnail = thumbnail,
            onConfirmMerge = {
                viewModel.confirmMergeConflict { targetId ->
                    isEditingName = false
                    onNavigateToPerson(targetId)
                }
            },
            onCancel = { viewModel.cancelMergeConflict() }
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
    onPermanentlyDelete: () -> Unit,
    onRedetect: () -> Unit,
    onAssign: () -> Unit,
    onDismiss: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.action_delete_face)) },
            text = { Text(stringResource(R.string.delete_face_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onPermanentlyDelete()
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

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
                    PersonDetailViewMode.NORMAL,
                    PersonDetailViewMode.SUGGESTION ->
                        add(stringResource(R.string.action_remove_from_person) to onRemove)
                    PersonDetailViewMode.IGNORED ->
                        add(stringResource(R.string.action_unignore) to onRemove)
                    PersonDetailViewMode.UNKNOWN -> { /* no remove/unignore */ }
                }

                add(stringResource(R.string.action_redetect) to onRedetect)
                add(stringResource(R.string.action_assign_to_person) to onAssign)
                // Permanently delete – always available, shown last with destructive colour
                add(stringResource(R.string.action_delete_face) to { showDeleteConfirm = true })
            }

            actions.forEachIndexed { index, (label, action) ->
                val isDestructive = index == actions.lastIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = label,
                            color = if (isDestructive)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    },
                    modifier = Modifier.combinedClickable(onClick = { action() ; if (!isDestructive) onDismiss() })
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


private fun LazyGridScope.faceItemsWithMonthHeaders(
    faces: List<org.eidora.data.db.FaceRegionWithPhoto>,
    isSelected: (String) -> Boolean,
    borderColorFor: (String) -> androidx.compose.ui.graphics.Color?,
    onTap: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onImageTap: (faceId: String, photoId: String) -> Unit
) {
    val formatter = java.time.format.DateTimeFormatter.ofPattern(
        "MMMM yyyy", java.util.Locale.getDefault()
    )
    var lastMonthKey = ""

    faces.forEach { faceWithPhoto ->
        val takenAt = faceWithPhoto.photoTakenAt
        val monthKey = if (takenAt != null && takenAt > 0L) {
            val date = java.time.Instant.ofEpochMilli(takenAt)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            "${date.year}-${date.monthValue}"
        } else "unknown"

        if (monthKey != lastMonthKey) {
            lastMonthKey = monthKey
            val label = if (takenAt != null && takenAt > 0L) {
                val date = java.time.Instant.ofEpochMilli(takenAt)
                    .atZone(java.time.ZoneId.systemDefault())
                formatter.format(date)
            } else "–"
            item(key = "month_${monthKey}_${faceWithPhoto.faceRegion.id}", span = { GridItemSpan(3) }) {
                androidx.compose.material3.Text(
                    text = label,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = androidx.compose.ui.Modifier.padding(
                        start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp
                    )
                )
            }
        }

        item(key = faceWithPhoto.faceRegion.id) {
            FaceGridItem(
                face = faceWithPhoto,
                isSelected = isSelected(faceWithPhoto.faceRegion.id),
                borderColor = borderColorFor(faceWithPhoto.faceRegion.id),
                onTap = { onTap(faceWithPhoto.faceRegion.id) },
                onLongPress = { onLongPress(faceWithPhoto.faceRegion.id) },
                onImageTap = { onImageTap(faceWithPhoto.faceRegion.id, faceWithPhoto.faceRegion.photoId) }
            )
        }
    }
}
