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
private val KEY_MIN_BATTERY_PERCENT = intPreferencesKey("min_battery_percent")
private val KEY_MAX_BATTERY_TEMP = floatPreferencesKey("max_battery_temp_celsius")
private val KEY_FOLDER_WHITELIST = stringPreferencesKey("folder_whitelist")
private val KEY_CONFIRM_ON_ASSIGN =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_assign")
private val KEY_CONFIRM_ON_NAME_SUGGESTION =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_name_suggestion")
private val KEY_CONFIRM_ON_MERGE_SUGGESTION =
    androidx.datastore.preferences.core.booleanPreferencesKey("confirm_on_merge_suggestion")

data class ClusteringConfig(
    val edgeThreshold: Float,
    val clusterMatchThreshold: Float,
    val individualMatchThreshold: Float,
    val minClusterSize: Int,
    val timeWeight: Float,
)

data class PowerConfig(
    val minBatteryPercent: Int,
    val maxBatteryTempCelsius: Float,
)

enum class FolderCategory { CAMERA, COMMON, APPS, OTHER }

class SettingsRepository(
    private val context: Context,
) {
    // ---- Filename patterns -------------------------------------------------

    // ---- Clustering thresholds --------------------------------------------

    val clusteringConfig: Flow<ClusteringConfig> =
        context.dataStore.data.map { prefs ->
            ClusteringConfig(
                edgeThreshold = prefs[KEY_CLUSTER_EDGE_THRESHOLD] ?: DEFAULT_EDGE_THRESHOLD,
                clusterMatchThreshold = prefs[KEY_CLUSTER_MATCH_THRESHOLD] ?: DEFAULT_CLUSTER_MATCH_THRESHOLD,
                individualMatchThreshold = prefs[KEY_INDIVIDUAL_MATCH_THRESHOLD] ?: DEFAULT_INDIVIDUAL_MATCH_THRESHOLD,
                minClusterSize = prefs[KEY_MIN_CLUSTER_SIZE] ?: DEFAULT_MIN_CLUSTER_SIZE,
                timeWeight = prefs[KEY_TIME_WEIGHT] ?: DEFAULT_TIME_WEIGHT,
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
        }
    }

    // ---- Power gate --------------------------------------------------------

    val powerConfig: Flow<PowerConfig> =
        context.dataStore.data.map { prefs ->
            PowerConfig(
                minBatteryPercent = prefs[KEY_MIN_BATTERY_PERCENT] ?: DEFAULT_MIN_BATTERY_PERCENT,
                maxBatteryTempCelsius = prefs[KEY_MAX_BATTERY_TEMP] ?: DEFAULT_MAX_BATTERY_TEMP,
            )
        }

    suspend fun getPowerConfig(): PowerConfig = powerConfig.first()

    suspend fun setPowerConfig(config: PowerConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MIN_BATTERY_PERCENT] = config.minBatteryPercent
            prefs[KEY_MAX_BATTERY_TEMP] = config.maxBatteryTempCelsius
        }
    }

    // ---- Folder whitelist --------------------------------------------------

    /**
     * Folders (MediaStore RELATIVE_PATH) included in syncing.
     * Stored as newline-separated list.
     * null / empty = only DEFAULT_FOLDER_WHITELIST (first-run default).
     */
    val folderWhitelist: Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_FOLDER_WHITELIST]?.split("\n")?.filter { it.isNotBlank() }?.toSet()
                ?: DEFAULT_FOLDER_WHITELIST
        }

    suspend fun getFolderWhitelist(): Set<String> = folderWhitelist.first()

    suspend fun setFolderWhitelist(folders: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FOLDER_WHITELIST] = folders.joinToString("\n")
        }
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

    suspend fun getConfirmOnAssign(): Boolean = confirmOnAssign.first()

    suspend fun getConfirmOnNameSuggestion(): Boolean = confirmOnNameSuggestion.first()

    suspend fun getConfirmOnMergeSuggestion(): Boolean = confirmOnMergeSuggestion.first()

    suspend fun setConfirmOnAssign(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_ASSIGN] = value }
    }

    suspend fun setConfirmOnNameSuggestion(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_NAME_SUGGESTION] = value }
    }

    suspend fun setConfirmOnMergeSuggestion(value: Boolean) {
        context.dataStore.edit { it[KEY_CONFIRM_ON_MERGE_SUGGESTION] = value }
    }

    companion object {
        const val DEFAULT_EDGE_THRESHOLD = 0.50f
        const val DEFAULT_CLUSTER_MATCH_THRESHOLD = 0.55f
        const val DEFAULT_INDIVIDUAL_MATCH_THRESHOLD = 0.50f
        const val DEFAULT_MIN_CLUSTER_SIZE = 5
        const val DEFAULT_TIME_WEIGHT = 1.0f

        // Manual assignment confirms faces by default; naming a suggestion
        // does not auto-confirm all its faces (they stay suggestions);
        // merging a suggestion into a person confirms by default.
        const val DEFAULT_CONFIRM_ON_ASSIGN = true
        const val DEFAULT_CONFIRM_ON_NAME_SUGGESTION = false
        const val DEFAULT_CONFIRM_ON_MERGE_SUGGESTION = true

        // Only Camera is selected by default
        val DEFAULT_FOLDER_WHITELIST: Set<String> = setOf("DCIM/Camera")

        // Category patterns for grouping folders in the settings UI
        val CAMERA_PATTERNS = listOf("DCIM/")
        val APPS_PATTERNS = listOf("Android/media/", "Android/data/")
        val COMMON_PATTERNS = listOf("Pictures/", "Download/", "Downloads/")

        fun categorize(relativePath: String): FolderCategory =
            when {
                APPS_PATTERNS.any { relativePath.startsWith(it) } -> FolderCategory.APPS
                CAMERA_PATTERNS.any { relativePath.startsWith(it) } -> FolderCategory.CAMERA
                COMMON_PATTERNS.any { relativePath.startsWith(it) } -> FolderCategory.COMMON
                else -> FolderCategory.OTHER
            }

        const val DEFAULT_MIN_BATTERY_PERCENT = 20
        const val DEFAULT_MAX_BATTERY_TEMP = 40.0f

        fun patternToRegex(pattern: String): Regex {
            val escaped = pattern.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex("^$escaped$", RegexOption.IGNORE_CASE)
        }

        fun matchesAnyPattern(
            filename: String,
            patterns: List<String>,
        ): Boolean {
            if (patterns.isEmpty()) return true
            return patterns.any { patternToRegex(it).matches(filename) }
        }
    }
}
