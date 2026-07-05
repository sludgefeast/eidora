package de.sebastian.eidora.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.eidora.data.settings.ClusteringConfig
import de.sebastian.eidora.data.settings.SettingsProvider
import de.sebastian.eidora.data.settings.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val filenamePatterns: List<String> = emptyList(),
    val clusteringConfig: ClusteringConfig = ClusteringConfig(
        edgeThreshold = SettingsRepository.DEFAULT_EDGE_THRESHOLD,
        clusterMatchThreshold = SettingsRepository.DEFAULT_CLUSTER_MATCH_THRESHOLD,
        individualMatchThreshold = SettingsRepository.DEFAULT_INDIVIDUAL_MATCH_THRESHOLD,
        minClusterSize = SettingsRepository.DEFAULT_MIN_CLUSTER_SIZE
    )
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsProvider.get(app)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.filenamePatterns.collect { patterns ->
                _uiState.update { it.copy(filenamePatterns = patterns) }
            }
        }
        viewModelScope.launch {
            repo.clusteringConfig.collect { config ->
                _uiState.update { it.copy(clusteringConfig = config) }
            }
        }
    }

    fun setPatterns(patterns: List<String>) {
        viewModelScope.launch {
            repo.setFilenamePatterns(patterns.map { it.trim() }.filter { it.isNotBlank() })
        }
    }

    fun setClusteringConfig(config: ClusteringConfig) {
        viewModelScope.launch { repo.setClusteringConfig(config) }
    }
}
