package de.sebastian.eidora.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "eidora_settings")

private val KEY_FILENAME_PATTERNS = stringPreferencesKey("filename_patterns")
private val KEY_CLUSTER_EDGE_THRESHOLD = floatPreferencesKey("cluster_edge_threshold")
private val KEY_CLUSTER_MATCH_THRESHOLD = floatPreferencesKey("cluster_match_threshold")
private val KEY_INDIVIDUAL_MATCH_THRESHOLD = floatPreferencesKey("individual_match_threshold")
private val KEY_MIN_CLUSTER_SIZE = intPreferencesKey("min_cluster_size")

data class ClusteringConfig(
    val edgeThreshold: Float,
    val clusterMatchThreshold: Float,
    val individualMatchThreshold: Float,
    val minClusterSize: Int
)

class SettingsRepository(private val context: Context) {

    // ---- Filename patterns -------------------------------------------------

    val filenamePatterns: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_FILENAME_PATTERNS]?.split("\n")?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    suspend fun getFilenamePatterns(): List<String> = filenamePatterns.first()

    suspend fun setFilenamePatterns(patterns: List<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILENAME_PATTERNS] = patterns.joinToString("\n")
        }
    }

    // ---- Clustering thresholds --------------------------------------------

    val clusteringConfig: Flow<ClusteringConfig> = context.dataStore.data.map { prefs ->
        ClusteringConfig(
            edgeThreshold = prefs[KEY_CLUSTER_EDGE_THRESHOLD] ?: DEFAULT_EDGE_THRESHOLD,
            clusterMatchThreshold = prefs[KEY_CLUSTER_MATCH_THRESHOLD] ?: DEFAULT_CLUSTER_MATCH_THRESHOLD,
            individualMatchThreshold = prefs[KEY_INDIVIDUAL_MATCH_THRESHOLD] ?: DEFAULT_INDIVIDUAL_MATCH_THRESHOLD,
            minClusterSize = prefs[KEY_MIN_CLUSTER_SIZE] ?: DEFAULT_MIN_CLUSTER_SIZE
        )
    }

    suspend fun getClusteringConfig(): ClusteringConfig = clusteringConfig.first()

    suspend fun setClusteringConfig(config: ClusteringConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLUSTER_EDGE_THRESHOLD] = config.edgeThreshold
            prefs[KEY_CLUSTER_MATCH_THRESHOLD] = config.clusterMatchThreshold
            prefs[KEY_INDIVIDUAL_MATCH_THRESHOLD] = config.individualMatchThreshold
            prefs[KEY_MIN_CLUSTER_SIZE] = config.minClusterSize
        }
    }

    companion object {
        const val DEFAULT_EDGE_THRESHOLD = 0.30f
        const val DEFAULT_CLUSTER_MATCH_THRESHOLD = 0.30f
        const val DEFAULT_INDIVIDUAL_MATCH_THRESHOLD = 0.25f
        const val DEFAULT_MIN_CLUSTER_SIZE = 2

        fun patternToRegex(pattern: String): Regex {
            val escaped = pattern.split("*").joinToString(".*") { Regex.escape(it) }
            return Regex("^$escaped$", RegexOption.IGNORE_CASE)
        }

        fun matchesAnyPattern(filename: String, patterns: List<String>): Boolean {
            if (patterns.isEmpty()) return true
            return patterns.any { patternToRegex(it).matches(filename) }
        }
    }
}
