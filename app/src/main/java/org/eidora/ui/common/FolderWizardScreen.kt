// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rememberCoroutineScope
import kotlinx.coroutines.withContext
import org.eidora.R
import org.eidora.data.settings.SettingsProvider
import org.eidora.data.settings.SettingsRepository

/**
 * First-run wizard: lets the user pick which photo folders Eidora should
 * process. Camera folders are pre-selected. Only the chosen folders' photos,
 * faces and persons are shown in the app afterwards.
 */
@Composable
fun FolderWizardScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var folders by remember { mutableStateOf<List<String>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val found =
            withContext(Dispatchers.IO) {
                scanFolders(context)
            }
        folders = found
        // Pre-select camera folders (default whitelist matches)
        selected =
            found
                .filter { f -> SettingsRepository.DEFAULT_FOLDER_WHITELIST.any { f == it || f.startsWith("$it/") } }
                .toSet()
                .ifEmpty {
                    found.filter { it.contains("Camera", ignoreCase = true) }.toSet()
                }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.wizard_folders_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.wizard_folders_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        val list = folders
        when {
            list == null ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp)) }
            list.isEmpty() ->
                Text(
                    stringResource(R.string.wizard_folders_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                LazyColumn(Modifier.weight(1f)) {
                    items(list) { folder ->
                        FolderRow(
                            folder = folder,
                            checked = folder in selected,
                            onToggle = {
                                selected =
                                    if (folder in selected) selected - folder else selected + folder
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
        }

        Button(
            onClick = {
                if (saving) return@Button
                saving = true
                scope.launch {
                    val repo = SettingsProvider.get(context)
                    repo.setFolderWhitelist(selected)
                    repo.setFolderWizardDone(true)
                    onDone()
                }
            },
            enabled = selected.isNotEmpty() && folders != null,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.wizard_folders_continue), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FolderRow(
    folder: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggle,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(
                folder,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
            )
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

/** Lists distinct MediaStore RELATIVE_PATH folders that contain JPEGs. */
private fun scanFolders(context: android.content.Context): List<String> {
    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
    val sel = "${android.provider.MediaStore.Images.Media.MIME_TYPE} = ?"
    val folders = sortedSetOf<String>()
    context.contentResolver
        .query(uri, proj, sel, arrayOf("image/jpeg"), null)
        ?.use { cursor ->
            val col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                cursor.getString(col)?.trimEnd('/')?.takeIf { it.isNotBlank() }?.let { folders.add(it) }
            }
        }
    return folders.toList()
}
