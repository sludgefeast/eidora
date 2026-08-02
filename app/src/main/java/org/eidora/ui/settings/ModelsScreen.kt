// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import java.io.File
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
private const val MODELS_README_URL =
    "https://github.com/sludgefeast/eidora/blob/main/scripts/README.md"

@Composable
fun ModelsScreen(
    onBack: () -> Unit,
    onTestModel: (String, String) -> Unit = { _, _ -> },
    onActivateModel: (
        String,
        String,
        org.eidora.data.repository.FaceRepository.DetectionChangeStrategy?,
        org.eidora.data.repository.FaceRepository.EmbeddingChangeStrategy?,
    ) -> Unit = { _, _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var containers by remember {
        mutableStateOf(ContainerStore.listContainers(context))
    }
    val toast: (Int) -> Unit = { resId ->
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

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
                        toast(R.string.models_import_success)
                        refresh()
                    }
                    is ContainerStore.ImportResult.Invalid ->
                        toast(R.string.models_import_invalid)
                    is ContainerStore.ImportResult.Duplicate ->
                        duplicateUri = uri
                    null ->
                        toast(R.string.models_import_invalid)
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
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.models_import_button))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.models_import_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.models_import_learn_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.clickable {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(MODELS_README_URL)),
                            )
                        } catch (t: Throwable) {
                            toast(R.string.about_no_browser)
                        }
                    },
            )
            Spacer(Modifier.height(12.dp))

            if (containers.isEmpty()) {
                Text(
                    stringResource(R.string.models_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                containers.forEachIndexed { index, container ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                    }
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
                Spacer(Modifier.height(16.dp))
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
                            toast(R.string.models_import_success)
                            refresh()
                            // A reimport swaps the model files. If the replaced
                            // container holds the active detection or embedding
                            // model, that's effectively a reactivation with
                            // possibly different weights — ask the same question
                            // the activation flow does. Inactive containers just
                            // get replaced silently.
                            val c = result.container
                            // Copy the delegated state into locals so the
                            // null-checks below smart-cast (a `by remember`
                            // property can't be smart-cast directly).
                            val activeDet = selectedDetection
                            val activeEmb = selectedEmbedding
                            val activeModel =
                                c.manifest.models.firstOrNull { m ->
                                    when (m.task) {
                                        ContainerManifest.TASK_DETECTION ->
                                            activeDet?.containerId == c.id &&
                                                activeDet.modelId == m.id
                                        else ->
                                            activeEmb?.containerId == c.id &&
                                                activeEmb.modelId == m.id
                                    }
                                }
                            if (activeModel != null) {
                                pendingActivate =
                                    PendingActivate(
                                        c.id,
                                        activeModel.id,
                                        activeModel.task,
                                        isReimport = true,
                                    )
                            }
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
        val isDetection = pa.task == ContainerManifest.TASK_DETECTION

        fun activate(
            detectionStrategy: org.eidora.data.repository.FaceRepository.DetectionChangeStrategy?,
            embeddingStrategy: org.eidora.data.repository.FaceRepository.EmbeddingChangeStrategy?,
        ) {
            onActivateModel(pa.containerId, pa.modelId, detectionStrategy, embeddingStrategy)
            pendingActivate = null
            if (isDetection) {
                selectedDetection =
                    org.eidora.data.settings.SettingsRepository
                        .SelectedModel(pa.containerId, pa.modelId)
            } else {
                selectedEmbedding =
                    org.eidora.data.settings.SettingsRepository
                        .SelectedModel(pa.containerId, pa.modelId)
            }
        }

        if (isDetection) {
            // Detection change: let the user choose what happens to existing
            // faces. The three options differ sharply in what they preserve.
            // All actions (incl. cancel) go in one vertical column so the long
            // labels stack cleanly instead of overlapping the dismiss slot.
            AlertDialog(
                onDismissRequest = { pendingActivate = null },
                title = { Text(stringResource(R.string.models_activate_title)) },
                text = { Text(stringResource(R.string.models_detection_change_msg)) },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        DetectionStrategyButton(
                            label = stringResource(R.string.models_detection_keep_all),
                            onClick = {
                                activate(
                                    org.eidora.data.repository.FaceRepository
                                        .DetectionChangeStrategy.KEEP_ALL,
                                    null,
                                )
                            },
                        )
                        DetectionStrategyButton(
                            label = stringResource(R.string.models_detection_keep_confirmed),
                            onClick = {
                                activate(
                                    org.eidora.data.repository.FaceRepository
                                        .DetectionChangeStrategy.KEEP_CONFIRMED,
                                    null,
                                )
                            },
                        )
                        DetectionStrategyButton(
                            label = stringResource(R.string.models_detection_redetect_all),
                            onClick = {
                                activate(
                                    org.eidora.data.repository.FaceRepository
                                        .DetectionChangeStrategy.REDETECT_ALL,
                                    null,
                                )
                            },
                        )
                        DetectionStrategyButton(
                            label = stringResource(R.string.action_cancel),
                            onClick = { pendingActivate = null },
                        )
                    }
                },
            )
        } else {
            // Embedding change: recompute everything, or — for a same-model
            // reimport where the vector space is unchanged — keep the existing
            // embeddings and clusters.
            AlertDialog(
                onDismissRequest = { pendingActivate = null },
                title = { Text(stringResource(R.string.models_activate_title)) },
                text = {
                    Text(
                        if (pa.isReimport) {
                            stringResource(R.string.models_embedding_change_msg)
                        } else {
                            stringResource(R.string.models_activate_embedding_msg)
                        },
                    )
                },
                confirmButton = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Keeping embeddings is only safe for a same-model
                        // reimport (unchanged vector space). Switching to a
                        // different model must recompute, so hide it there.
                        if (pa.isReimport) {
                            DetectionStrategyButton(
                                label = stringResource(R.string.models_embedding_keep),
                                onClick = {
                                    activate(
                                        null,
                                        org.eidora.data.repository.FaceRepository
                                            .EmbeddingChangeStrategy.KEEP_EMBEDDINGS,
                                    )
                                },
                            )
                        }
                        DetectionStrategyButton(
                            label = stringResource(R.string.models_embedding_recompute),
                            onClick = {
                                activate(
                                    null,
                                    org.eidora.data.repository.FaceRepository
                                        .EmbeddingChangeStrategy.RECOMPUTE_ALL,
                                )
                            },
                        )
                        DetectionStrategyButton(
                            label = stringResource(R.string.action_cancel),
                            onClick = { pendingActivate = null },
                        )
                    }
                },
            )
        }
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
    // True when this came from re-importing an already-active model (same vector
    // space), which is the only case where keeping embeddings is safe.
    val isReimport: Boolean = false,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        // A container that holds the active detection or embedding model must
        // not be deletable — removing it would pull the model out from under a
        // running configuration.
        val containerHasActiveModel =
            container.manifest.models.any { model ->
                when (model.task) {
                    ContainerManifest.TASK_DETECTION ->
                        selectedDetection?.containerId == container.id &&
                            selectedDetection.modelId == model.id
                    else ->
                        selectedEmbedding?.containerId == container.id &&
                            selectedEmbedding.modelId == model.id
                }
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    container.manifest.container.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
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
                IconButton(
                    onClick = onDeleteContainer,
                    enabled = !containerHasActiveModel,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.models_delete_container),
                        tint =
                            if (containerHasActiveModel) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                LocalContentColor.current
                            },
                    )
                }
            }
        }

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
            val sizeBytes =
                runCatching { File(container.dir, model.file).length() }.getOrDefault(0L)
            ModelRow(
                model = model,
                sizeBytes = sizeBytes,
                protected = container.isProtected,
                active = active,
                onDelete = { onDeleteModel(model.id) },
                onTest = { onTestModel(model.id) },
                onActivate = { onActivateModel(model.id, model.task) },
            )
        }
    }
}

@Composable
private fun DetectionStrategyButton(
    label: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
        )
    }
}

@Composable
private fun ModelRow(
    model: ContainerManifest.ModelEntry,
    sizeBytes: Long,
    protected: Boolean,
    active: Boolean,
    onDelete: () -> Unit,
    onTest: () -> Unit,
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val isDetection = model.task == ContainerManifest.TASK_DETECTION
        Column(modifier = Modifier.weight(1f)) {
            // Icon + name on one vertically-centered row, so the icon sits level
            // with the name regardless of the description height below.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isDetection) Icons.Default.Face else Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
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
            // Description and activate button are indented to line up under the
            // name (past the icon), so the left column reads as one block.
            Column(modifier = Modifier.padding(start = 36.dp)) {
                val taskLabel =
                    if (isDetection) {
                        stringResource(R.string.models_detection_label)
                    } else {
                        stringResource(R.string.models_embedding_label)
                    }
                val license = model.license?.name
                val sizeLabel = formatBytes(sizeBytes)
                val base = if (license != null) "$taskLabel · $license" else taskLabel
                Text(
                    text = if (sizeBytes > 0) "$base · $sizeLabel" else base,
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
        }
        // Fixed action column on the right: the Test button, then a trash slot
        // that's always the same width (an empty spacer when the model can't be
        // deleted), so the Test buttons line up across every container.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest) {
                Text(stringResource(R.string.selftest_action))
            }
            if (!protected) {
                IconButton(
                    onClick = onDelete,
                    // An active model can't be deleted — it's in use by the
                    // current detection/recognition configuration.
                    enabled = !active,
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.models_delete_model),
                        tint =
                            if (active) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                LocalContentColor.current
                            },
                    )
                }
            } else {
                // Reserve the trash's footprint so Test aligns with rows that
                // do show a trash icon.
                Spacer(Modifier.width(48.dp))
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

/** Formats a byte count as a short human-readable size (e.g. "3.3 MB"). */
private fun formatBytes(bytes: Long): String = org.eidora.util.formatBytes(bytes)
