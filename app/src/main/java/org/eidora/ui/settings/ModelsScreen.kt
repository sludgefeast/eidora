// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import org.eidora.R
import org.eidora.ml.container.ContainerManifest
import org.eidora.ml.container.ContainerStore

/**
 * Models settings screen. Lists every installed container (free one first) with
 * its models, offers an import, and allows deleting non-protected containers or
 * individual models. Activating/choosing which model is used comes later.
 */
@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    onTestModel: (String, String) -> Unit = { _, _ -> },
    onActivateModel: (String, String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var containers by remember {
        mutableStateOf(ContainerStore.listContainers(context))
    }
    var message by remember { mutableStateOf<String?>(null) }

    // Currently selected models (container id + model id), to mark them active.
    var selectedDetection by remember {
        mutableStateOf<org.eidora.data.settings.SettingsRepository.SelectedModel?>(null)
    }
    var selectedEmbedding by remember {
        mutableStateOf<org.eidora.data.settings.SettingsRepository.SelectedModel?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val settings = org.eidora.data.settings.SettingsProvider.get(context)
        selectedDetection =
            withContext(Dispatchers.IO) { settings.getSelectedDetection() }
        selectedEmbedding =
            withContext(Dispatchers.IO) { settings.getSelectedEmbedding() }
    }

    // Pending import that clashed with an existing id; user must choose.
    var duplicateUri by remember { mutableStateOf<Uri?>(null) }
    // Pending delete confirmation.
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    // Pending activation confirmation (switching a model triggers a re-sync).
    var pendingActivate by remember { mutableStateOf<PendingActivate?>(null) }

    fun refresh() {
        containers = ContainerStore.listContainers(context)
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            ContainerStore.importContainer(context, it)
                        }
                    }
                when (result) {
                    is ContainerStore.ImportResult.Success -> {
                        message = context.getString(R.string.models_import_success)
                        refresh()
                    }
                    is ContainerStore.ImportResult.Invalid ->
                        message = context.getString(R.string.models_import_invalid)
                    is ContainerStore.ImportResult.Duplicate ->
                        duplicateUri = uri
                    null ->
                        message = context.getString(R.string.models_import_invalid)
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.models_screen_title),
            onBack = onBack,
        )
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.models_import_button))
            }
            Spacer(Modifier.height(12.dp))

            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (containers.isEmpty()) {
                Text(
                    stringResource(R.string.models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(containers, key = { it.id }) { container ->
                        ContainerCard(
                            container = container,
                            selectedDetection = selectedDetection,
                            selectedEmbedding = selectedEmbedding,
                            onDeleteContainer = {
                                pendingDelete = PendingDelete.Container(container.id)
                            },
                            onDeleteModel = { modelId ->
                                pendingDelete = PendingDelete.Model(container.id, modelId)
                            },
                            onTestModel = { modelId -> onTestModel(container.id, modelId) },
                            onActivateModel = { modelId, task ->
                                pendingActivate = PendingActivate(container.id, modelId, task)
                            },
                        )
                    }
                }
            }
        }
    }

    // Duplicate-on-import dialog: replace or keep both (keep both = cancel here,
    // since two containers can't share an id; we simply don't import).
    duplicateUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { duplicateUri = null },
            title = { Text(stringResource(R.string.models_delete_confirm_title)) },
            text = { Text(stringResource(R.string.models_import_duplicate)) },
            confirmButton = {
                TextButton(onClick = {
                    duplicateUri = null
                    scope.launch {
                        val result =
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use {
                                    ContainerStore.importContainer(context, it, replaceExisting = true)
                                }
                            }
                        if (result is ContainerStore.ImportResult.Success) {
                            message = context.getString(R.string.models_import_success)
                            refresh()
                        }
                    }
                }) { Text(stringResource(R.string.models_import_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { duplicateUri = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Delete confirmation dialog.
    pendingDelete?.let { pd ->
        val confirmText =
            when (pd) {
                is PendingDelete.Container -> stringResource(R.string.models_delete_container_confirm)
                is PendingDelete.Model -> stringResource(R.string.models_delete_model_confirm)
            }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.models_delete_confirm_title)) },
            text = { Text(confirmText) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (pd) {
                                is PendingDelete.Container ->
                                    ContainerStore.deleteContainer(context, pd.containerId)
                                is PendingDelete.Model ->
                                    ContainerStore.deleteModel(context, pd.containerId, pd.modelId)
                            }
                        }
                        pendingDelete = null
                        refresh()
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // Activation confirmation dialog (switching a model triggers a re-sync).
    pendingActivate?.let { pa ->
        val msg =
            if (pa.task == ContainerManifest.TASK_DETECTION) {
                stringResource(R.string.models_activate_detection_msg)
            } else {
                stringResource(R.string.models_activate_embedding_msg)
            }
        AlertDialog(
            onDismissRequest = { pendingActivate = null },
            title = { Text(stringResource(R.string.models_activate_title)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    onActivateModel(pa.containerId, pa.modelId)
                    pendingActivate = null
                    // Reflect the new selection immediately.
                    if (pa.task == ContainerManifest.TASK_DETECTION) {
                        selectedDetection =
                            org.eidora.data.settings.SettingsRepository
                                .SelectedModel(pa.containerId, pa.modelId)
                    } else {
                        selectedEmbedding =
                            org.eidora.data.settings.SettingsRepository
                                .SelectedModel(pa.containerId, pa.modelId)
                    }
                }) { Text(stringResource(R.string.models_activate_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingActivate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private sealed interface PendingDelete {
    data class Container(val containerId: String) : PendingDelete
    data class Model(val containerId: String, val modelId: String) : PendingDelete
}

private data class PendingActivate(
    val containerId: String,
    val modelId: String,
    val task: String,
)

@Composable
private fun ContainerCard(
    container: ContainerStore.InstalledContainer,
    selectedDetection: org.eidora.data.settings.SettingsRepository.SelectedModel?,
    selectedEmbedding: org.eidora.data.settings.SettingsRepository.SelectedModel?,
    onDeleteContainer: () -> Unit,
    onDeleteModel: (String) -> Unit,
    onTestModel: (String) -> Unit,
    onActivateModel: (String, String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        container.manifest.container.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    container.manifest.container.description?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (container.isProtected) {
                    Text(
                        stringResource(R.string.models_free_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    IconButton(onClick = onDeleteContainer) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.models_delete_container),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            container.manifest.models.forEach { model ->
                val active =
                    when (model.task) {
                        ContainerManifest.TASK_DETECTION ->
                            selectedDetection?.containerId == container.id &&
                                selectedDetection.modelId == model.id
                        else ->
                            selectedEmbedding?.containerId == container.id &&
                                selectedEmbedding.modelId == model.id
                    }
                ModelRow(
                    model = model,
                    protected = container.isProtected,
                    active = active,
                    onDelete = { onDeleteModel(model.id) },
                    onTest = { onTestModel(model.id) },
                    onActivate = { onActivateModel(model.id, model.task) },
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ContainerManifest.ModelEntry,
    protected: Boolean,
    active: Boolean,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name ?: model.id,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.models_active_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val taskLabel =
                if (model.task == ContainerManifest.TASK_DETECTION) {
                    stringResource(R.string.models_detection_label)
                } else {
                    stringResource(R.string.models_embedding_label)
                }
            val license = model.license?.name
            Text(
                text = if (license != null) "$taskLabel · $license" else taskLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!active) {
                TextButton(
                    onClick = onActivate,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(stringResource(R.string.models_activate))
                }
            }
        }
        TextButton(onClick = onTest) {
            Text(stringResource(R.string.selftest_action))
        }
        if (!protected) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.models_delete_model),
                )
            }
        }
    }
}

/**
 * A simple screen header (back arrow + title) built from stable APIs only,
 * avoiding the experimental Material3 TopAppBar.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}
