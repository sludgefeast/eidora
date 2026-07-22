// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.photos

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eidora.R
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.PhotoEntity
import org.eidora.data.repository.FaceRepository
import org.eidora.ui.common.MultiSelectState
import org.eidora.worker.SyncPipeline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class PhotosListItem {
    data class MonthHeader(
        val label: String,
        val key: String,
    ) : PhotosListItem()

    data class Photo(
        val entity: PhotoEntity,
    ) : PhotosListItem()
}

data class PhotosUiState(
    val items: List<PhotosListItem> = emptyList(),
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val currentYear: String = "",
    /** Non-null when showing photos for a specific person. */
    val personName: String? = null,
    /** Maps photoId → faceRegionId for the person's confirmed face (person mode only). */
    val confirmedFaceByPhoto: Map<String, String> = emptyMap(),
) {
    val selectedPhotoIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
    val isPersonMode get() = personName != null
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PhotosViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = DatabaseProvider.getInstance(app)
    private val settingsRepo = org.eidora.data.settings.SettingsProvider.get(app)
    private val repo = FaceRepository(app, db)
    private val context: Context get() = getApplication()

    private val _uiState = MutableStateFlow(PhotosUiState())
    val uiState: StateFlow<PhotosUiState> = _uiState.asStateFlow()

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val yearFormatter = DateTimeFormatter.ofPattern("yyyy", Locale.getDefault())

    init {
        viewModelScope.launch {
            settingsRepo.folderWhitelist
                .flatMapLatest { folders -> db.photoDao().observeAllSortedByDate(folders.toList()) }
                .collect { photos ->
                _uiState.update { it.copy(items = buildListItems(photos)) }
            }
        }
    }

    /** Switch to person-filtered mode. Call after creation when showing a person's photos. */
    fun loadForPerson(personId: String) {
        viewModelScope.launch {
            val person = db.personDao().findById(personId)
            _uiState.update { it.copy(personName = person?.name ?: "") }
            settingsRepo.folderWhitelist
                .flatMapLatest { folders ->
                    db.faceRegionDao().observeConfirmedPhotosForPerson(personId, folders.toList())
                }.collect { photos ->
                val faceMap =
                    db
                        .faceRegionDao()
                        .findByPersonId(personId)
                        .filter { it.name != null && !it.ignored }
                        .associate { it.photoId to it.id }
                _uiState.update {
                    it.copy(
                        items = buildListItems(photos),
                        confirmedFaceByPhoto = faceMap,
                    )
                }
            }
        }
    }

    private fun buildListItems(photos: List<PhotoEntity>): List<PhotosListItem> {
        val result = mutableListOf<PhotosListItem>()
        var lastMonthKey: String? = null

        photos.forEach { photo ->
            val monthKey =
                photo.takenAt?.let {
                    val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                    "${date.year}-${date.monthValue}"
                } ?: "no-date"

            if (monthKey != lastMonthKey) {
                val label =
                    photo.takenAt?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                        monthFormatter.format(date)
                    } ?: context.getString(R.string.label_no_date)
                result.add(PhotosListItem.MonthHeader(label = label, key = monthKey))
                lastMonthKey = monthKey
            }
            result.add(PhotosListItem.Photo(photo))
        }
        return result
    }

    fun updateCurrentYear(firstVisiblePhotoId: String?) {
        val photo = firstVisiblePhotoId ?: return
        val items = _uiState.value.items
        val photoItem =
            items
                .filterIsInstance<PhotosListItem.Photo>()
                .find { it.entity.id == photo } ?: return
        val year =
            photoItem.entity.takenAt?.let {
                val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                yearFormatter.format(date)
            } ?: ""
        _uiState.update { it.copy(currentYear = year) }
    }

    fun toggleSelection(photoId: String) {
        _uiState.update { it.copy(multiSelect = it.multiSelect.toggle(photoId)) }
    }

    fun rangeSelect(photoId: String) {
        _uiState.update { state ->
            val orderedIds =
                state.items
                    .filterIsInstance<PhotosListItem.Photo>()
                    .map { it.entity.id }
            state.copy(multiSelect = state.multiSelect.rangeSelect(photoId, orderedIds))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelect = it.multiSelect.clear()) }
    }

    fun redetectSelected() {
        val ids = _uiState.value.selectedPhotoIds.toList()
        viewModelScope.launch {
            ids.forEach { photoId ->
                repo.resetPhotoFaces(photoId)
                SyncPipeline.enqueueReSyncPhoto(getApplication(), photoId)
            }
            clearSelection()
        }
    }
}
