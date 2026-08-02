// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eidora.data.settings.ClusteringConfig
import org.eidora.data.settings.PowerConfig
import org.eidora.data.settings.SettingsProvider
import org.eidora.data.settings.SettingsRepository

data class SettingsUiState(
    val clusteringConfig: ClusteringConfig =
        ClusteringConfig(
            // Placeholder until the real (model-dependent) config flow emits.
            edgeThreshold = org.eidora.ml.EmbeddingModelSpec.DEFAULT.defaultThresholds.edge,
            clusterMatchThreshold = org.eidora.ml.EmbeddingModelSpec.DEFAULT.defaultThresholds.clusterMatch,
            individualMatchThreshold = org.eidora.ml.EmbeddingModelSpec.DEFAULT.defaultThresholds.individualMatch,
            minClusterSize = SettingsRepository.DEFAULT_MIN_CLUSTER_SIZE,
            timeWeight = SettingsRepository.DEFAULT_TIME_WEIGHT,
        ),
    val powerConfig: PowerConfig =
        PowerConfig(
            minBatteryPercent = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
            maxBatteryTempCelsius = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
            resumeBatteryPercent = SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
            resumeBatteryTempCelsius = SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP,
        ),
    val availableFolders: List<String> = emptyList(),
    val folderWhitelist: Set<String> = SettingsRepository.DEFAULT_FOLDER_WHITELIST,
    val confirmOnAssign: Boolean = SettingsRepository.DEFAULT_CONFIRM_ON_ASSIGN,
    val confirmOnNameSuggestion: Boolean = SettingsRepository.DEFAULT_CONFIRM_ON_NAME_SUGGESTION,
    val confirmOnMergeSuggestion: Boolean = SettingsRepository.DEFAULT_CONFIRM_ON_MERGE_SUGGESTION,
    val embeddingModelId: String = org.eidora.ml.EmbeddingModelSpec.DEFAULT.id,
    val detectionModelId: String = org.eidora.ml.DetectionModelSpec.DEFAULT.id,
)

class SettingsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val repo = SettingsProvider.get(app)
    private val faceRepo =
        org.eidora.data.repository
            .FaceRepository(app, org.eidora.data.db.DatabaseProvider.getInstance(app))

    /**
     * Removes all photos/faces/persons outside the current folder whitelist
     * from the database. Reports the number of removed photos via [onDone].
     */
    fun cleanupExcludedFolders(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val folders = repo.getFolderWhitelist().toList()
            val removed =
                try {
                    faceRepo.cleanupFoldersNotIn(folders)
                } catch (t: Throwable) {
                    0
                }
            onDone(removed)
        }
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.clusteringConfig.collect { config ->
                _uiState.update { it.copy(clusteringConfig = config) }
            }
        }
        viewModelScope.launch {
            repo.powerConfig.collect { config ->
                _uiState.update { it.copy(powerConfig = config) }
            }
        }
        viewModelScope.launch {
            repo.folderWhitelist.collect { wl ->
                _uiState.update { it.copy(folderWhitelist = wl) }
            }
        }
        viewModelScope.launch {
            repo.embeddingModelId.collect { id ->
                _uiState.update {
                    it.copy(embeddingModelId = id ?: org.eidora.ml.EmbeddingModelSpec.DEFAULT.id)
                }
            }
        }
        viewModelScope.launch {
            repo.detectionModelId.collect { id ->
                _uiState.update {
                    it.copy(detectionModelId = id ?: org.eidora.ml.DetectionModelSpec.DEFAULT.id)
                }
            }
        }
        viewModelScope.launch {
            repo.confirmOnAssign.collect { v -> _uiState.update { it.copy(confirmOnAssign = v) } }
        }
        viewModelScope.launch {
            repo.confirmOnNameSuggestion.collect { v -> _uiState.update { it.copy(confirmOnNameSuggestion = v) } }
        }
        viewModelScope.launch {
            repo.confirmOnMergeSuggestion.collect { v -> _uiState.update { it.copy(confirmOnMergeSuggestion = v) } }
        }
    }

    fun setConfirmOnAssign(value: Boolean) {
        viewModelScope.launch { repo.setConfirmOnAssign(value) }
    }

    fun setConfirmOnNameSuggestion(value: Boolean) {
        viewModelScope.launch { repo.setConfirmOnNameSuggestion(value) }
    }

    fun setConfirmOnMergeSuggestion(value: Boolean) {
        viewModelScope.launch { repo.setConfirmOnMergeSuggestion(value) }
    }

    fun setClusteringConfig(config: ClusteringConfig) {
        viewModelScope.launch { repo.setClusteringConfig(config) }
    }

    /**
     * Switches the detection model.
     *
     * Detection only produces face *regions* (bounding boxes). Existing regions
     * — whether from an earlier run or imported from XMP — are not wrong just
     * because the model changed, so re-detecting everything is optional:
     *
     *  - redetect = false: keep existing faces; the new model is used only for
     *    photos scanned from now on. Fast, no recompute.
     *  - redetect = true: re-scan all photos with the new detector, replacing
     *    existing regions and rebuilding embeddings/clusters.
     *
     * Either way confirmed names survive (they live on the faces and in XMP).
     * No-op if the chosen model is already active.
     */
    fun setPowerConfig(config: PowerConfig) {
        viewModelScope.launch { repo.setPowerConfig(config) }
    }

    /**
     * Activates a model by (containerId, modelId), dispatching to the detection
     * or embedding path based on the model's task read from its manifest.
     */
    fun selectModel(
        containerId: String,
        modelId: String,
        detectionStrategy: org.eidora.data.repository.FaceRepository.DetectionChangeStrategy?,
    ) {
        viewModelScope.launch {
            val task =
                withContext(Dispatchers.IO) {
                    org.eidora.ml.container.ContainerStore
                        .listContainers(getApplication())
                        .firstOrNull { it.id == containerId }
                        ?.manifest?.models?.firstOrNull { it.id == modelId }
                        ?.task
                } ?: return@launch
            if (task == org.eidora.ml.container.ContainerManifest.TASK_DETECTION) {
                selectDetectionModel(
                    containerId,
                    modelId,
                    detectionStrategy
                        ?: org.eidora.data.repository.FaceRepository
                            .DetectionChangeStrategy.KEEP_ALL,
                )
            } else {
                selectEmbeddingModel(containerId, modelId)
            }
        }
    }

    /**
     * Selects a detection model from a container as the active detector. A
     * detector change re-scans everything (new boxes → new crops → new
     * embeddings), so it clears derived data and restarts sync. No-op if it's
     * already the active selection.
     */
    private fun selectDetectionModel(
        containerId: String,
        modelId: String,
        strategy: org.eidora.data.repository.FaceRepository.DetectionChangeStrategy,
    ) {
        viewModelScope.launch {
            val current = repo.getSelectedDetection()
            if (current?.containerId == containerId && current.modelId == modelId) return@launch
            repo.setSelectedDetection(containerId, modelId)
            try {
                faceRepo.resetForDetectionModelChange(strategy)
            } catch (t: Throwable) {
                // best-effort; the pipeline still re-runs below where needed
            }
            when (strategy) {
                // Keep everything: no reset, no rescan — the new detector
                // applies to future scans only.
                org.eidora.data.repository.FaceRepository.DetectionChangeStrategy.KEEP_ALL -> {}
                // Only unconfirmed photos were reset (analyzed=false); a normal
                // sync picks those up without touching the confirmed ones.
                org.eidora.data.repository.FaceRepository.DetectionChangeStrategy.KEEP_CONFIRMED ->
                    org.eidora.worker.SyncPipeline.enqueue(getApplication())
                // Everything was cleared; force a full rescan.
                org.eidora.data.repository.FaceRepository.DetectionChangeStrategy.REDETECT_ALL ->
                    org.eidora.worker.SyncPipeline.restartAfterFolderChange(getApplication())
            }
        }
    }

    /**
     * Selects an embedding model from a container as the active embedder.
     * Embeddings from different models aren't comparable, so this clears all
     * embeddings and clustered persons, resets the clustering thresholds to the
     * model's manifest values, and restarts the pipeline. Confirmed names
     * survive (they re-import from XMP). No-op if already active.
     */
    private fun selectEmbeddingModel(containerId: String, modelId: String) {
        viewModelScope.launch {
            val current = repo.getSelectedEmbedding()
            if (current?.containerId == containerId && current.modelId == modelId) return@launch
            repo.setSelectedEmbedding(containerId, modelId)

            // Reset clustering thresholds to the manifest's values for this
            // model, if it declares them — thresholds tuned for the previous
            // model live in a different embedding space.
            val clustering =
                withContext(Dispatchers.IO) {
                    org.eidora.ml.container.ContainerStore
                        .listContainers(getApplication())
                        .firstOrNull { it.id == containerId }
                        ?.manifest?.models?.firstOrNull { it.id == modelId }
                        ?.clustering
                }
            if (clustering != null) {
                val cfg = repo.clusteringConfig.first()
                repo.setClusteringConfig(
                    cfg.copy(
                        edgeThreshold = clustering.edge,
                        clusterMatchThreshold = clustering.clusterMatch,
                        individualMatchThreshold = clustering.individualMatch,
                    ),
                )
            }

            try {
                faceRepo.resetForEmbeddingModelChange()
            } catch (t: Throwable) {
                // best-effort; the pipeline still re-runs below
            }
            org.eidora.worker.SyncPipeline.restartAfterFolderChange(getApplication())
        }
    }

    fun setFolderWhitelist(folders: Set<String>) {
        viewModelScope.launch {
            val previous = repo.getFolderWhitelist()
            if (previous == folders) return@launch // no real change

            repo.setFolderWhitelist(folders)
            // Keep persons homogeneous: split any that now mix visible and
            // hidden faces, recomputing centroids for the affected persons.
            try {
                faceRepo.splitPersonsByVisibility(folders.toList())
            } catch (t: Throwable) {
                // best-effort; UI stays consistent via folder-filtered queries
            }
            // A running sync/clustering pass is working on the old folder set –
            // stop it and start over. XMP writing is a separate chain and keeps
            // running so confirmed names still reach the photo files.
            org.eidora.worker.SyncPipeline
                .restartAfterFolderChange(getApplication())
        }
    }

    fun loadAvailableFolders(context: android.content.Context) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val folders = mutableSetOf<String>()
            val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val raw = cursor.getString(col) ?: continue
                    // Normalize: strip trailing slash, e.g. "DCIM/Camera/" → "DCIM/Camera"
                    folders.add(raw.trimEnd('/'))
                }
            }
            _uiState.update { it.copy(availableFolders = folders.sorted()) }
        }
    }
}
