package org.eidora.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.eidora.R
import java.io.File

/**
 * Dialog shown when a user's chosen name conflicts with an existing named person.
 * Offers to merge into the existing person, showing that person's representative face.
 */
@Composable
fun MergeConfirmDialog(
    existingPersonName: String,
    existingRepresentativeThumbnail: File?,
    onConfirmMerge: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.merge_conflict_title)) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(R.string.merge_conflict_message, existingPersonName),
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                CircleThumbnail(
                    file = existingRepresentativeThumbnail,
                    contentDescription = existingPersonName,
                    modifier = Modifier.size(96.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = existingPersonName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmMerge) {
                Text(stringResource(R.string.action_merge_into_existing))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
