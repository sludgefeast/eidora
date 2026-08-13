// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.fullscreen

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionEntity
import org.eidora.data.repository.FaceRepository

data class FullscreenUiState(
    val photoPath: String? = null,
    val faceRegions: List<FaceRegionEntity> = emptyList(),
)

class FullscreenViewModel(
    app: Application,
) : AndroidViewModel(app) {
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
            // Clear the photo's existing faces, then kick off re-detection.
            // Without the enqueue the old faces were removed but new ones were
            // never produced, so the photo ended up with no faces at all.
            repo.resetPhotoFaces(photoId)
            org.eidora.worker.SyncPipeline.enqueueReSyncPhoto(getApplication(), photoId)
        }
    }
}
