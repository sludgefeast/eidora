package de.sebastian.faces.ui.persons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.sebastian.faces.R
import de.sebastian.faces.data.db.PersonWithCount
import de.sebastian.faces.util.ThumbnailHelper
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PersonsScreen(
    viewModel: PersonsViewModel,
    onPersonClick: (String) -> Unit,
    onPersonLongClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        // Confirmed persons grid
        item {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                userScrollEnabled = false,
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                            viewModel.toggleSelection(personWithCount.person.id)
                            onPersonLongClick(personWithCount.person.id)
                        },
                        onNameClick = { viewModel.startRename(personWithCount.person.id) }
                    )
                }

                // Virtual persons at end of grid
                item {
                    VirtualPersonItem(
                        label = stringResource(R.string.virtual_person_unknown),
                        color = Color(0xFF9E9E9E),
                        count = state.unknownCount,
                        onClick = { onPersonClick(VIRTUAL_UNKNOWN) }
                    )
                }
                item {
                    VirtualPersonItem(
                        label = stringResource(R.string.virtual_person_ignored),
                        color = Color(0xFF616161),
                        count = state.ignoredCount,
                        onClick = { onPersonClick(VIRTUAL_IGNORED) }
                    )
                }
            }
        }

        // Suggestions section
        if (state.suggestions.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.section_suggestions),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(state.suggestions.size) { index ->
                val suggestion = state.suggestions[index]
                SuggestionRow(
                    suggestion = suggestion,
                    onThumbnailClick = { onPersonClick(suggestion.personId) },
                    onNameConfirmed = { name -> viewModel.confirmSuggestion(suggestion.personId, name) }
                )
            }
        }
    }

    // Multiselect action bar
    if (state.isMultiSelectActive && state.selectedPersonIds.size >= 2) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(onClick = { viewModel.startMerge() }) {
                        Text(stringResource(R.string.action_merge))
                    }
                }
            }
        }
    }

    // Rename dialog
    state.renamingPersonId?.let { personId ->
        RenamePersonSheet(
            currentName = state.confirmedPersons.find { it.person.id == personId }?.person?.name ?: "",
            onConfirm = { newName -> viewModel.renamePerson(personId, newName) },
            onDismiss = { viewModel.cancelRename() }
        )
    }

    // Merge sheet
    if (state.showMergeSheet) {
        MergePersonsSheet(
            persons = state.confirmedPersons.filter { state.selectedPersonIds.contains(it.person.id) },
            onConfirm = { winnerId -> viewModel.mergePersons(winnerId) },
            onDismiss = { viewModel.cancelMerge() }
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
    onNameClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbnailFile = personWithCount.person.representativeFaceId?.let {
        ThumbnailHelper.thumbnailFile(context, it)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.size(88.dp)) {
            AsyncImage(
                model = thumbnailFile,
                contentDescription = personWithCount.person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = personWithCount.person.name ?: "",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onNameClick)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VirtualPersonItem(
    label: String,
    color: Color,
    count: Int,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .combinedClickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionRow(
    suggestion: PersonSuggestionUi,
    onThumbnailClick: () -> Unit,
    onNameConfirmed: (String) -> Unit
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        val thumbnailFile = suggestion.representativeFaceId?.let {
            ThumbnailHelper.thumbnailFile(context, it)
        }
        AsyncImage(
            model = thumbnailFile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .combinedClickable(onClick = onThumbnailClick)
        )
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.hint_enter_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onNameConfirmed(text.trim())
            }),
            modifier = Modifier.weight(1f)
        )
    }
}

const val VIRTUAL_UNKNOWN = "virtual_unknown"
const val VIRTUAL_IGNORED = "virtual_ignored"
