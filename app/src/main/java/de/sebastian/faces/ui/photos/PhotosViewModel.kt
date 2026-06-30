package de.sebastian.faces.ui.photos

import android.content.Context
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.R
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.PhotoEntity
import de.sebastian.faces.data.repository.FaceRepository
import de.sebastian.faces.ui.common.MultiSelectState
import de.sebastian.faces.worker.SyncPipeline
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class PhotosListItem {
    data class MonthHeader(val label: String, val key: String) : PhotosListItem()
    data class Photo(val entity: PhotoEntity) : PhotosListItem()
}

data class PhotosUiState(
    val items: List<PhotosListItem> = emptyList(),
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val currentYear: String = ""
) {
    val selectedPhotoIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
}

class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val context: Context get() = getApplication()

    private val _uiState = MutableStateFlow(PhotosUiState())
    val uiState: StateFlow<PhotosUiState> = _uiState.asStateFlow()

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val yearFormatter = DateTimeFormatter.ofPattern("yyyy", Locale.getDefault())

    init {
        viewModelScope.launch {
            db.photoDao().observeAllSortedByDate().collect { photos ->
                _uiState.update { it.copy(items = buildListItems(photos)) }
            }
        }
    }

    private fun buildListItems(photos: List<PhotoEntity>): List<PhotosListItem> {
        val result = mutableListOf<PhotosListItem>()
        var lastMonthKey: String? = null

        photos.forEach { photo ->
            val monthKey = photo.takenAt?.let {
                val date = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                "${date.year}-${date.monthValue}"
            } ?: "no-date"

            if (monthKey != lastMonthKey) {
                val label = photo.takenAt?.let {
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
        val photoItem = items.filterIsInstance<PhotosListItem.Photo>()
            .find { it.entity.id == photo } ?: return
        val year = photoItem.entity.takenAt?.let {
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
            val orderedIds = state.items
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
