package de.sebastian.eidora.ui.fullscreen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.eidora.data.db.DatabaseProvider
import de.sebastian.eidora.data.db.FaceRegionEntity
import de.sebastian.eidora.data.repository.FaceRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FullscreenUiState(
    val photoPath: String? = null,
    val faceRegions: List<FaceRegionEntity> = emptyList()
)

class FullscreenViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val faceDao = db.faceRegionDao()
    private val photoDao = db.photoDao()

    private val _uiState = MutableStateFlow(FullscreenUiState())
    val uiState: StateFlow<FullscreenUiState> = _uiState.asStateFlow()

    // Fix 5: reactive Flow so UI updates after re-detect
    fun load(photoId: String) {
        viewModelScope.launch {
            val photo = photoDao.findById(photoId) ?: return@launch
            _uiState.update { it.copy(photoPath = photo.path) }

            // observeByPersonId watches all faces for this photo reactively
            // We use a custom flow that watches all face regions for a photo
            faceDao.observeByPhotoId(photoId).collect { faces: List<FaceRegionEntity> ->
                _uiState.update { it.copy(faceRegions = faces) }
            }
        }
    }

    fun redetectFaces(photoId: String) {
        viewModelScope.launch {
            repo.resetPhotoFaces(photoId)
        }
    }
}
