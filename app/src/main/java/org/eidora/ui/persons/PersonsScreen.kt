// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.persons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.eidora.R
import org.eidora.data.db.PersonWithCount
import org.eidora.ui.common.CircleColorLabel
import org.eidora.ui.common.CircleThumbnail
import org.eidora.ui.common.LazyGridScrollbar
import org.eidora.util.ThumbnailHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonsScreen(
    viewModel: PersonsViewModel,
    onPersonClick: (String) -> Unit,
    onPersonLongClick: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Use LazyVerticalGrid as the single scrollable container.
    // Suggestions and virtual persons are added as full-width span items.
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 34.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Confirmed persons
            items(state.confirmedPersons, key = { it.person.id }) { personWithCount ->
                PersonGridItem(
                    personWithCount = personWithCount,
                    isSelected = state.selectedPersonIds.contains(personWithCount.person.id),
                    onClick = {
                        if (state.isMultiSelectActive) {
                            viewModel.toggleSelection(personWithCount.person.id)
                        } else {
                            onPersonClick(personWithCount.person.id)
                        }
                    },
                    onLongClick = {
                        if (state.isMultiSelectActive) {
                            viewModel.rangeSelectPerson(personWithCount.person.id)
                        } else {
                            viewModel.toggleSelection(personWithCount.person.id)
                            onPersonLongClick(personWithCount.person.id)
                        }
                    },
                    onNameClick = { viewModel.startRename(personWithCount.person.id) },
                )
            }

            // Virtual persons on their own row below the confirmed persons grid
            item(span = { GridItemSpan(3) }) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 4.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        VirtualPersonItem(
                            label = stringResource(R.string.virtual_person_unknown),
                            color = Color(0xFF9E9E9E),
                            count = state.unknownCount,
                            onClick = { onPersonClick(VIRTUAL_UNKNOWN) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        VirtualPersonItem(
                            label = stringResource(R.string.virtual_person_ignored),
                            color = Color(0xFF616161),
                            count = state.ignoredCount,
                            onClick = { onPersonClick(VIRTUAL_IGNORED) },
                        )
                    }
                    // Third slot empty for symmetry with 3-column grid
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            // Suggestions header
            if (state.suggestions.isNotEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        text = stringResource(R.string.section_suggestions),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                }
                items(state.suggestions, key = { it.personId }, span = { GridItemSpan(3) }) { suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        onThumbnailClick = { onPersonClick(suggestion.personId) },
                        onNameConfirmed = { name -> viewModel.confirmSuggestion(suggestion.personId, name) },
                        onReject = { viewModel.rejectSuggestion(suggestion.personId) },
                    )
                }
            }
        }

        // Multiselect action bar
        if (state.isMultiSelectActive && state.selectedPersonIds.size >= 2) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Surface(shadowElevation = 8.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Button(onClick = { viewModel.startMerge() }) {
                            Text(stringResource(R.string.action_merge))
                        }
                    }
                }
            }
        }

        // Rename sheet
        state.renamingPersonId?.let { personId ->
            RenamePersonSheet(
                currentName =
                    state.confirmedPersons
                        .find { it.person.id == personId }
                        ?.person
                        ?.name ?: "",
                onConfirm = { newName -> viewModel.renamePerson(personId, newName) },
                onDismiss = { viewModel.cancelRename() },
            )
        }
        LazyGridScrollbar(
            state = gridState,
            scope = scope,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    } // Box

    // Merge sheet
    if (state.showMergeSheet) {
        MergePersonsSheet(
            persons = state.confirmedPersons.filter { state.selectedPersonIds.contains(it.person.id) },
            onConfirm = { winnerId -> viewModel.mergePersons(winnerId) },
            onDismiss = { viewModel.cancelMerge() },
        )
    }

    // Namesake conflict: visible person(s) already use this name
    state.namesakeConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissNamesakeConflict() },
            title = { Text(stringResource(R.string.namesake_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.namesake_message, conflict.name))
                    Spacer(Modifier.height(12.dp))
                    conflict.candidates.forEach { candidate ->
                        TextButton(
                            onClick = { viewModel.mergeIntoNamesake(candidate.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.namesake_merge_into, candidate.name ?: conflict.name),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissNamesakeConflict() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonGridItem(
    personWithCount: PersonWithCount,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onNameClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnailFile =
        personWithCount.person.representativeFaceId?.let {
            ThumbnailHelper.thumbnailFile(context, it)
        }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(4.dp),
    ) {
        CircleThumbnail(
            file = thumbnailFile,
            contentDescription = personWithCount.person.name,
            modifier = Modifier.fillMaxWidth(),
            borderColor = if (personWithCount.unconfirmedCount > 0) Color(0xFF4CAF50) else null,
        ) {
            if (isSelected) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(Color(0x660D47A1)),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = personWithCount.person.name ?: "",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNameClick),
        )
    }
}

@Composable
private fun VirtualPersonItem(
    label: String,
    color: Color,
    count: Int,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .clickable(onClick = onClick)
                .padding(4.dp),
    ) {
        CircleColorLabel(
            color = color,
            label = count.toString(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SuggestionRow(
    suggestion: PersonSuggestionUi,
    onThumbnailClick: () -> Unit,
    onNameConfirmed: (String) -> Unit,
    onReject: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val thumbnailId = suggestion.representativeFaceId ?: suggestion.firstFaceId
    val thumbnailFile = thumbnailId?.let { ThumbnailHelper.thumbnailFile(context, it) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        CircleThumbnail(
            file = thumbnailFile,
            contentDescription = null,
            modifier =
                Modifier
                    .size(56.dp)
                    .clickable(onClick = onThumbnailClick),
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.hint_enter_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions =
                KeyboardActions(onDone = {
                    if (text.isNotBlank()) onNameConfirmed(text.trim())
                }),
            modifier =
                Modifier
                    .weight(1f)
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            scope.launch { bringIntoViewRequester.bringIntoView() }
                        }
                    },
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onReject) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.action_reject_suggestion),
            )
        }
    }
}

const val VIRTUAL_UNKNOWN = "virtual_unknown"
const val VIRTUAL_IGNORED = "virtual_ignored"
