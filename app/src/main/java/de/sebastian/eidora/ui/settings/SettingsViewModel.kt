package de.sebastian.eidora.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.eidora.data.settings.ClusteringConfig
import de.sebastian.eidora.data.settings.PowerConfig
import de.sebastian.eidora.data.settings.SettingsProvider
import de.sebastian.eidora.data.settings.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SettingsUiState(
    val clusteringConfig: ClusteringConfig = ClusteringConfig(
        edgeThreshold = SettingsRepository.DEFAULT_EDGE_THRESHOLD,
        clusterMatchThreshold = SettingsRepository.DEFAULT_CLUSTER_MATCH_THRESHOLD,
        individualMatchThreshold = SettingsRepository.DEFAULT_INDIVIDUAL_MATCH_THRESHOLD,
        minClusterSize = SettingsRepository.DEFAULT_MIN_CLUSTER_SIZE,
        timeWeight = SettingsRepository.DEFAULT_TIME_WEIGHT
    ),
    val powerConfig: PowerConfig = PowerConfig(
        minBatteryPercent = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
        maxBatteryTempCelsius = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP
    ),
    val availableFolders: List<String> = emptyList(),
    val folderWhitelist: Set<String> = SettingsRepository.DEFAULT_FOLDER_WHITELIST
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsProvider.get(app)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {

        }
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
    }



    fun setClusteringConfig(config: ClusteringConfig) {
        viewModelScope.launch { repo.setClusteringConfig(config) }
    }

    fun setPowerConfig(config: PowerConfig) {
        viewModelScope.launch { repo.setPowerConfig(config) }
    }

    fun setFolderWhitelist(folders: Set<String>) {
        viewModelScope.launch { repo.setFolderWhitelist(folders) }
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
