// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "eidora_settings")

private val KEY_CLUSTER_EDGE_THRESHOLD = floatPreferencesKey("cluster_edge_threshold")
private val KEY_CLUSTER_MATCH_THRESHOLD = floatPreferencesKey("cluster_match_threshold")
private val KEY_INDIVIDUAL_MATCH_THRESHOLD = floatPreferencesKey("individual_match_threshold")
private val KEY_MIN_CLUSTER_SIZE = intPreferencesKey("min_cluster_size")
private val KEY_TIME_WEIGHT = floatPreferencesKey("clustering_time_weight")
private val KEY_SUGGEST_MARGIN = floatPreferencesKey("clustering_suggest_margin")
private val KEY_MIN_BATTERY_PERCENT = intPreferencesKey("min_battery_percent")
private val KEY_MAX_BATTERY_TEMP = floatPreferencesKey("max_battery_temp_celsius")
private val KEY_RESUME_BATTERY_PERCENT = intPreferencesKey("resume_battery_percent")
private val KEY_RESUME_BATTERY_TEMP = floatPreferencesKey("resume_battery_temp_celsius")
private val KEY_FOLDER_WHITELIST = stringPreferencesKey("folder_whitelist")
private val KEY_EMBEDDING_MODEL_ID = stringPreferencesKey("embedding_model_id")
private val KEY_DETECTION_MODEL_ID = stringPreferencesKey("detection_model_id")
private val KEY_EMBEDDING_CONTAINER_ID = stringPreferencesKey("embedding_container_id")
private val KEY_DETECTION_CONTAINER_ID = stringPreferencesKey("detection_container_id")
private val KEY_FOLDER_WIZARD_DONE =
    androidx.datastore.preferences.core.booleanPreferencesKey("folder_wizard_done")
private val KEY_METADATA_WIZARD_DONE =
    androidx.datastore.preferences.core.booleanPreferencesKey("metadata_wizard_done")
private val KEY_CONFIRM_ON_ASSIGN =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_assign")
private val KEY_CONFIRM_ON_NAME_SUGGESTION =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_name_suggestion")
private val KEY_CONFIRM_ON_MERGE_SUGGESTION =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_merge_suggestion")
private val KEY_FILL_MISSING_DATE =
    androidx.datastore.preferences.core.booleanPreferencesKey("fill_missing_date")

data class ClusteringConfig(
    val edgeThreshold: Float,
    val clusterMatchThreshold: Float,
    val individualMatchThreshold: Float,
    val minClusterSize: Int,
    val timeWeight: Float,
    val suggestMargin: Float,
)

/**
 * Power thresholds with hysteresis.
 *
 * Processing pauses once the battery drops below [minBatteryPercent] or the
 * battery temperature rises above [maxBatteryTempCelsius]. It only resumes once
 * the battery is back at [resumeBatteryPercent] or the temperature has fallen
 * to [resumeBatteryTempCelsius].
 *
 * The gap matters: without it the device would resume the moment it crosses
 * back over the pause threshold, heat up again within seconds, and oscillate.
 */
data class PowerConfig(
    val minBatteryPercent: Int,
    val maxBatteryTempCelsius: Float,
    val resumeBatteryPercent: Int = minBatteryPercent,
    val resumeBatteryTempCelsius: Float = maxBatteryTempCelsius,
)

enum class FolderCategory { CAMERA, COMMON, APPS, OTHER }

class SettingsRepository(
    private val context: Context,
) {
    // ---- Filename patterns -------------------------------------------------

    // ---- Clustering thresholds --------------------------------------------

    val clusteringConfig: Flow<ClusteringConfig> =
        context.dataStore.data.map { prefs ->
            // Fall back to the selected model's tuned thresholds, not fixed
            // constants: an unset threshold should default to a value that fits
            // whichever embedding model is active.
            val spec =
                org.eidora.ml.EmbeddingModelSpec.byId(prefs[KEY_EMBEDDING_MODEL_ID])
            val t = spec.defaultThresholds
            ClusteringConfig(
                edgeThreshold = prefs[KEY_CLUSTER_EDGE_THRESHOLD] ?: t.edge,
                clusterMatchThreshold = prefs[KEY_CLUSTER_MATCH_THRESHOLD] ?: t.clusterMatch,
                individualMatchThreshold = prefs[KEY_INDIVIDUAL_MATCH_THRESHOLD] ?: t.individualMatch,
                minClusterSize = prefs[KEY_MIN_CLUSTER_SIZE] ?: DEFAULT_MIN_CLUSTER_SIZE,
                timeWeight = prefs[KEY_TIME_WEIGHT] ?: DEFAULT_TIME_WEIGHT,
                suggestMargin = prefs[KEY_SUGGEST_MARGIN] ?: DEFAULT_SUGGEST_MARGIN,
            )
        }

    suspend fun getClusteringConfig(): ClusteringConfig = clusteringConfig.first()

    suspend fun setClusteringConfig(config: ClusteringConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLUSTER_EDGE_THRESHOLD] = config.edgeThreshold
            prefs[KEY_CLUSTER_MATCH_THRESHOLD] = config.clusterMatchThreshold
            prefs[KEY_INDIVIDUAL_MATCH_THRESHOLD] = config.individualMatchThreshold
            prefs[KEY_MIN_CLUSTER_SIZE] = config.minClusterSize
            prefs[KEY_TIME_WEIGHT] = config.timeWeight
            prefs[KEY_SUGGEST_MARGIN] = config.suggestMargin
        }
    }

    // ---- Power gate --------------------------------------------------------

    val powerConfig: Flow<PowerConfig> =
        context.dataStore.data.map { prefs ->
            PowerConfig(
                minBatteryPercent = prefs[KEY_MIN_BATTERY_PERCENT] ?: DEFAULT_MIN_BATTERY_PERCENT,
                maxBatteryTempCelsius = prefs[KEY_MAX_BATTERY_TEMP] ?: DEFAULT_MAX_BATTERY_TEMP,
                resumeBatteryPercent = prefs[KEY_RESUME_BATTERY_PERCENT] ?: DEFAULT_RESUME_BATTERY_PERCENT,
                resumeBatteryTempCelsius = prefs[KEY_RESUME_BATTERY_TEMP] ?: DEFAULT_RESUME_BATTERY_TEMP,
            )
        }

    suspend fun getPowerConfig(): PowerConfig = powerConfig.first()

    suspend fun setPowerConfig(config: PowerConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MIN_BATTERY_PERCENT] = config.minBatteryPercent
            prefs[KEY_MAX_BATTERY_TEMP] = config.maxBatteryTempCelsius
            // Keep the hysteresis meaningful: resume must be no worse than pause.
            prefs[KEY_RESUME_BATTERY_PERCENT] =
                config.resumeBatteryPercent.coerceAtLeast(config.minBatteryPercent)
            prefs[KEY_RESUME_BATTERY_TEMP] =
                config.resumeBatteryTempCelsius.coerceAtMost(config.maxBatteryTempCelsius)
        }
    }

    // ---- Folder whitelist --------------------------------------------------

    /**
     * Folders (MediaStore RELATIVE_PATH) included in syncing.
     * Stored as newline-separated list.
     * KEY absent (never set) = DEFAULT_FOLDER_WHITELIST (first-run default).
     * KEY present but empty = explicitly no folders → nothing is analyzed.
     */
    val folderWhitelist: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            val raw = prefs[KEY_FOLDER_WHITELIST]
            if (raw == null) {
                // Never set: first-run default.
                DEFAULT_FOLDER_WHITELIST
            } else {
                // Explicitly set (possibly to empty = deselected everything).
                raw.split("\n").filter { it.isNotBlank() }.toSet()
            }
        }

    suspend fun getFolderWhitelist(): Set<String> = folderWhitelist.first()

    suspend fun setFolderWhitelist(folders: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FOLDER_WHITELIST] = folders.joinToString("\n")
        }
    }

    /** True once the first-run folder selection wizard has been completed. */
    val folderWizardDone: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FOLDER_WIZARD_DONE] ?: false }

    suspend fun getFolderWizardDone(): Boolean = folderWizardDone.first()

    suspend fun setFolderWizardDone(done: Boolean) {
        context.dataStore.edit { it[KEY_FOLDER_WIZARD_DONE] = done }
    }

    /** True once the first-run metadata (capture-date) step has been completed. */
    val metadataWizardDone: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_METADATA_WIZARD_DONE] ?: false }

    suspend fun getMetadataWizardDone(): Boolean = metadataWizardDone.first()

    suspend fun setMetadataWizardDone(done: Boolean) {
        context.dataStore.edit { it[KEY_METADATA_WIZARD_DONE] = done }
    }

    // ---- Embedding model choice ---------------------------------------------

    /**
     * The chosen embedding model's id (see EmbeddingModelSpec). Null until the
     * user picks one, in which case the caller uses EmbeddingModelSpec.DEFAULT.
     */
    val embeddingModelId: Flow<String?> =
        context.dataStore.data.map { it[KEY_EMBEDDING_MODEL_ID] }

    suspend fun getEmbeddingModelId(): String? = embeddingModelId.first()

    suspend fun setEmbeddingModelId(id: String) {
        context.dataStore.edit { it[KEY_EMBEDDING_MODEL_ID] = id }
    }

    // ---- Container-aware selected models ------------------------------------
    // Identity of a chosen model is the pair (containerId, modelId), since the
    // same modelId can exist in different containers. These supersede the plain
    // *_MODEL_ID keys above for the container world; the old keys are kept for
    // compatibility with the pre-container code paths still in the tree.

    /** A model selected for use, identified within its container. */
    data class SelectedModel(val containerId: String, val modelId: String)

    val selectedDetection: Flow<SelectedModel?> =
        context.dataStore.data.map { prefs ->
            val c = prefs[KEY_DETECTION_CONTAINER_ID]
            val m = prefs[KEY_DETECTION_MODEL_ID]
            if (c != null && m != null) SelectedModel(c, m) else null
        }

    val selectedEmbedding: Flow<SelectedModel?> =
        context.dataStore.data.map { prefs ->
            val c = prefs[KEY_EMBEDDING_CONTAINER_ID]
            val m = prefs[KEY_EMBEDDING_MODEL_ID]
            if (c != null && m != null) SelectedModel(c, m) else null
        }

    suspend fun getSelectedDetection(): SelectedModel? = selectedDetection.first()

    suspend fun getSelectedEmbedding(): SelectedModel? = selectedEmbedding.first()

    suspend fun setSelectedDetection(containerId: String, modelId: String) {
        context.dataStore.edit {
            it[KEY_DETECTION_CONTAINER_ID] = containerId
            it[KEY_DETECTION_MODEL_ID] = modelId
        }
    }

    suspend fun setSelectedEmbedding(containerId: String, modelId: String) {
        context.dataStore.edit {
            it[KEY_EMBEDDING_CONTAINER_ID] = containerId
            it[KEY_EMBEDDING_MODEL_ID] = modelId
        }
    }

    // ---- Detection model choice ---------------------------------------------

    /** The chosen detection model's id (see DetectionModelSpec). */
    val detectionModelId: Flow<String?> =
        context.dataStore.data.map { it[KEY_DETECTION_MODEL_ID] }

    suspend fun getDetectionModelId(): String? = detectionModelId.first()

    suspend fun setDetectionModelId(id: String) {
        context.dataStore.edit { it[KEY_DETECTION_MODEL_ID] = id }
    }

    // ---- Model download over mobile network ---------------------------------

    // ---- Confirmation behaviour for manual face assignment ------------------
    // Whether each manual operation marks the affected faces as confirmed
    // (name written) or leaves them unconfirmed (suggestion only).

    val confirmOnAssign: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CONFIRM_ON_ASSIGN] ?: DEFAULT_CONFIRM_ON_ASSIGN }

    val confirmOnNameSuggestion: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CONFIRM_ON_NAME_SUGGESTION] ?: DEFAULT_CONFIRM_ON_NAME_SUGGESTION }

    val confirmOnMergeSuggestion: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_CONFIRM_ON_MERGE_SUGGESTION] ?: DEFAULT_CONFIRM_ON_MERGE_SUGGESTION }

    val fillMissingDate: Flow<Boolean> =
        context.dataStore.data.map { it[KEY_FILL_MISSING_DATE] ?: DEFAULT_FILL_MISSING_DATE }

    suspend fun getConfirmOnAssign(): Boolean = confirmOnAssign.first()

    suspend fun getConfirmOnNameSuggestion(): Boolean = confirmOnNameSuggestion.first()

    suspend fun getConfirmOnMergeSuggestion(): Boolean = confirmOnMergeSuggestion.first()

    suspend fun getFillMissingDate(): Boolean = fillMissingDate.first()

    suspend fun setConfirmOnAssign(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_ASSIGN] = value }
    }

    suspend fun setConfirmOnNameSuggestion(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_NAME_SUGGESTION] = value }
    }

    suspend fun setConfirmOnMergeSuggestion(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_MERGE_SUGGESTION] = value }
    }

    suspend fun setFillMissingDate(value: Boolean) {
        context.dataStore.edit { it[KEY_FILL_MISSING_DATE] = value }
    }

    companion object {
        // Clustering threshold defaults are model-dependent and live in
        // EmbeddingModelSpec.defaultThresholds (see clusteringConfig above).
        const val DEFAULT_MIN_CLUSTER_SIZE = 2
        const val DEFAULT_TIME_WEIGHT = 1.0f

        // Suggest-threshold margin over the model's auto (individual-match)
        // threshold: suggest = auto × (1 + margin). Keeps suggestions just past
        // the confident band; higher recovers more borderline faces but mixes
        // more. See ClusteringWorker for the rationale behind the 0.10 default.
        const val DEFAULT_SUGGEST_MARGIN = 0.10f

        // Manual assignment confirms faces by default; naming a suggestion
        // does not auto-confirm all its faces (they stay suggestions);
        // merging a suggestion into a person confirms by default.
        const val DEFAULT_CONFIRM_ON_ASSIGN = true
        const val DEFAULT_CONFIRM_ON_NAME_SUGGESTION = false
        const val DEFAULT_CONFIRM_ON_MERGE_SUGGESTION = true

        // Like Aves: when a photo has no capture date in its metadata, write one
        // (derived from the file's modification time) before editing, so its
        // chronological position survives later timestamp changes. On by default.
        const val DEFAULT_FILL_MISSING_DATE = true

        // Only Camera is selected by default
        val DEFAULT_FOLDER_WHITELIST: Set<String> = setOf("DCIM/Camera")

        // Category patterns for grouping folders in the settings UI
        val CAMERA_PATTERNS = listOf("DCIM/")
        val APPS_PATTERNS = listOf("Android/media/", "Android/data/")
        val COMMON_PATTERNS = listOf("Pictures/", "Download/", "Downloads/")

        fun categorize(relativePath: String): FolderCategory {
            // Match a pattern like "Pictures/" against both the folder itself
            // ("Pictures") and anything beneath it ("Pictures/ChatGPT"). Without
            // this, the bare parent folder fell through to OTHER while its
            // subfolders were correctly categorized — e.g. "Pictures" showed up
            // under "Sonstiges" even though "Pictures/ChatGPT" was under COMMON.
            fun matches(patterns: List<String>) =
                patterns.any { p ->
                    val bare = p.trimEnd('/')
                    relativePath == bare || relativePath.startsWith("$bare/")
                }
            return when {
                matches(APPS_PATTERNS) -> FolderCategory.APPS
                matches(CAMERA_PATTERNS) -> FolderCategory.CAMERA
                matches(COMMON_PATTERNS) -> FolderCategory.COMMON
                else -> FolderCategory.OTHER
            }
        }

        const val DEFAULT_MIN_BATTERY_PERCENT = 20
        const val DEFAULT_MAX_BATTERY_TEMP = 40.0f

        // Resume thresholds: 5 % above / 5 K below the pause thresholds, so the
        // device recovers noticeably before work continues.
        const val DEFAULT_RESUME_BATTERY_PERCENT = 25
        const val DEFAULT_RESUME_BATTERY_TEMP = 35.0f
    }
}
