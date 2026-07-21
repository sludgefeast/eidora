// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.persondetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.eidora.R
import org.eidora.data.db.PersonWithCount
import org.eidora.ui.common.CircleThumbnail
import org.eidora.util.ThumbnailHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AssignToPersonSheet(
    viewModel: PersonDetailViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val filtered =
        remember(query, state.allPersons) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                state.allPersons
            } else {
                state.allPersons.filter {
                    it.person.name
                        ?.lowercase()
                        ?.contains(q) == true
                }
            }
        }

    val showCreate =
        query.isNotBlank() &&
            filtered.none {
                it.person.name?.equals(query.trim(), ignoreCase = true) == true
            }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.sheet_assign_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.hint_search_person)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.navigationBarsPadding()) {
                // Create new entry
                if (showCreate) {
                    item {
                        ListItem(
                            headlineContent = {
                                Text(stringResource(R.string.create_new_person, query.trim()))
                            },
                            modifier =
                                Modifier.combinedClickable(onClick = {
                                    viewModel.assignToNewPerson(query.trim())
                                    onDismiss()
                                }),
                        )
                        HorizontalDivider()
                    }
                }

                // Existing persons
                items(filtered, key = { it.person.id }) { personWithCount ->
                    PersonListItem(
                        personWithCount = personWithCount,
                        onClick = {
                            viewModel.assignToExistingPerson(personWithCount.person.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonListItem(
    personWithCount: PersonWithCount,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnailFile =
        personWithCount.person.representativeFaceId?.let {
            ThumbnailHelper.thumbnailFile(context, it)
        }
    ListItem(
        leadingContent = {
            CircleThumbnail(
                file = thumbnailFile,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
        },
        headlineContent = { Text(personWithCount.person.name ?: "") },
        supportingContent = { Text("${personWithCount.confirmedCount} confirmed") },
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}
