package de.sebastian.faces.ui.fullscreen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.FaceRegionEntity
import de.sebastian.faces.data.repository.FaceRepository
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

    fun load(photoId: String) {
        viewModelScope.launch {
            val photo = photoDao.findById(photoId) ?: return@launch
            val faces: List<FaceRegionEntity> = faceDao.findByPhotoId(photoId)
            _uiState.update { it.copy(photoPath = photo.path, faceRegions = faces) }
        }
    }

    fun redetectFaces(photoId: String) {
        viewModelScope.launch {
            repo.resetPhotoFaces(photoId)
        }
    }
}
