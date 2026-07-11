package de.sebastian.eidora.ui.persons

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.sebastian.eidora.R
import de.sebastian.eidora.data.db.PersonWithCount
import de.sebastian.eidora.ui.common.CircleThumbnail
import de.sebastian.eidora.util.ThumbnailHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenamePersonSheet(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.sheet_rename_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank()) { onConfirm(text.trim()); onDismiss() }
                }),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (text.isNotBlank()) { onConfirm(text.trim()); onDismiss() }
                }) { Text(stringResource(R.string.action_confirm)) }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MergePersonsSheet(
    persons: List<PersonWithCount>,
    onConfirm: (winnerId: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = stringResource(R.string.sheet_merge_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            LazyColumn {
                items(persons, key = { it.person.id }) { personWithCount ->
                    val thumbnailFile = personWithCount.person.representativeFaceId?.let {
                        ThumbnailHelper.thumbnailFile(context, it)
                    }
                    ListItem(
                        leadingContent = {
                            CircleThumbnail(
                                file = thumbnailFile,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp)
                            )
                        },
                        headlineContent = { Text(personWithCount.person.name ?: "") },
                        supportingContent = { Text(stringResource(R.string.person_face_count_confirmed, personWithCount.confirmedCount)) },
                        modifier = Modifier.combinedClickable(onClick = {
                            onConfirm(personWithCount.person.id)
                            onDismiss()
                        })
                    )
                }
            }
        }
    }
}
