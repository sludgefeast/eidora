// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.eidora.R

/**
 * Standalone folder-selection screen. Split out of SettingsScreen because the
 * available-folders list is read asynchronously on open, which made the main
 * settings list visibly jump as the list appeared. On its own page the loading
 * happens here, leaving the main settings calm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadAvailableFolders(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_folders_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            )

            // With no folder selected nothing is analyzed (empty whitelist = none).
            // Unlike the setup wizard, settings allows an empty selection, so warn
            // the user rather than silently analyzing nothing.
            if (state.folderWhitelist.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_folders_empty_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (state.availableFolders.isEmpty()) {
                Text(
                    stringResource(R.string.settings_folders_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val categories =
                    listOf(
                        org.eidora.data.settings.FolderCategory.CAMERA to
                            stringResource(R.string.folder_category_camera),
                        org.eidora.data.settings.FolderCategory.COMMON to
                            stringResource(R.string.folder_category_common),
                        org.eidora.data.settings.FolderCategory.APPS to stringResource(R.string.folder_category_apps),
                        org.eidora.data.settings.FolderCategory.OTHER to stringResource(R.string.folder_category_other),
                    )
                val grouped =
                    state.availableFolders.groupBy {
                        org.eidora.data.settings.SettingsRepository
                            .categorize(it)
                    }
                // "Sonstiges" starts collapsed; all others start expanded
                val collapsedByDefault = setOf(org.eidora.data.settings.FolderCategory.OTHER)
                val expandedCategories =
                    remember {
                        androidx.compose.runtime
                            .mutableStateMapOf<org.eidora.data.settings.FolderCategory, Boolean>()
                            .apply {
                                categories.forEach { (cat, _) -> put(cat, cat !in collapsedByDefault) }
                            }
                    }
                categories.forEach { (category, label) ->
                    val folders = grouped[category] ?: return@forEach
                    val isExpanded = expandedCategories[category] == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 2.dp)
                                .clickable { expandedCategories[category] = !isExpanded },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (isExpanded) {
                        folders.forEach { folder ->
                            val coveredByAncestor =
                                org.eidora.data.settings.FolderHierarchy
                                    .isCoveredByAncestor(folder, state.folderWhitelist)
                            val isIncluded =
                                folder in state.folderWhitelist || coveredByAncestor
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                            ) {
                                Checkbox(
                                    checked = isIncluded,
                                    // A child covered by a selected ancestor is
                                    // locked on: it's already included, and the
                                    // user manages it via the parent.
                                    enabled = !coveredByAncestor,
                                    onCheckedChange = { included ->
                                        val newWl =
                                            if (included) {
                                                org.eidora.data.settings.FolderHierarchy
                                                    .select(folder, state.folderWhitelist)
                                            } else {
                                                org.eidora.data.settings.FolderHierarchy
                                                    .deselect(folder, state.folderWhitelist)
                                            }
                                        viewModel.setFolderWhitelist(newWl)
                                    },
                                )
                                Text(
                                    text = folder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color =
                                        if (coveredByAncestor) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            val cleanupContext = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
                onClick = {
                    viewModel.cleanupExcludedFolders { removed ->
                        android.widget.Toast
                            .makeText(
                                cleanupContext,
                                cleanupContext.getString(R.string.settings_folders_cleanup_done, removed),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.settings_folders_cleanup))
            }
            Text(
                text = stringResource(R.string.settings_folders_cleanup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
        }
    }
}
