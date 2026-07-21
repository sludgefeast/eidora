package org.eidora.ui.persondetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.FaceRegionWithPhoto
import org.eidora.data.db.PersonWithCount
import org.eidora.data.repository.FaceRepository
import org.eidora.ui.common.MultiSelectState
import org.eidora.worker.SyncPipeline

enum class PersonDetailViewMode { NORMAL, UNKNOWN, IGNORED, SUGGESTION }

data class PersonDetailUiState(
    val personName: String = "",
    val viewMode: PersonDetailViewMode = PersonDetailViewMode.NORMAL,
    val unconfirmedFaces: List<FaceRegionWithPhoto> = emptyList(),
    val confirmedFaces: List<FaceRegionWithPhoto> = emptyList(),
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val actionFaceId: String? = null,
    val showAssignSheet: Boolean = false,
    val assignTargetFaceIds: Set<String> = emptySet(),
    val allPersons: List<PersonWithCount> = emptyList(),
    val personSearchQuery: String = "",
    val isReassigning: Boolean = false,
    val mergeConflict: MergeConflict? = null,
) {
    val selectedFaceIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
}

data class MergeConflict(
    val sourcePersonId: String,
    val targetPersonId: String,
    val targetPersonName: String,
    val targetRepresentativeFaceId: String?,
)

class PersonDetailViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val settingsRepo = org.eidora.data.settings.SettingsProvider.get(app)
    private val faceDao = db.faceRegionDao()
    private val personDao = db.personDao()

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    internal var currentPersonId: String? = null

    init {
        // Observe all named persons independently of load mode,
        // so the "Assign to person" sheet always has options.
        viewModelScope.launch {
            val folders = settingsRepo.getFolderWhitelist().toList()
            repo.observePersonsWithCount(folders).collect { persons: List<PersonWithCount> ->
                _uiState.update { it.copy(allPersons = persons) }
            }
        }
    }

    fun load(personId: String) {
        currentPersonId = personId
        viewModelScope.launch {
            val person = personDao.findById(personId)
            val isSuggestion = person != null && person.name == null
            _uiState.update {
                it.copy(
                    personName = person?.name ?: "",
                    viewMode =
                        if (isSuggestion) {
                            PersonDetailViewMode.SUGGESTION
                        } else {
                            PersonDetailViewMode.NORMAL
                        },
                )
            }

            val folders = settingsRepo.getFolderWhitelist().toList()
            faceDao.observeByPersonId(personId, folders).collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        unconfirmedFaces =
                            faces.filter { f ->
                                f.faceRegion.name == null && !f.faceRegion.ignored
                            },
                        confirmedFaces =
                            faces.filter { f ->
                                f.faceRegion.name != null && !f.faceRegion.ignored
                            },
                    )
                }
            }
        }
    }

    fun loadUnknown() {
        viewModelScope.launch {
            val folders = settingsRepo.getFolderWhitelist().toList()
            repo.observeUnknownFaces(folders).collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        personName =
                            getApplication<Application>()
                                .getString(org.eidora.R.string.virtual_person_unknown),
                        viewMode = PersonDetailViewMode.UNKNOWN,
                        unconfirmedFaces = faces,
                        confirmedFaces = emptyList(),
                    )
                }
            }
        }
    }

    fun loadIgnored() {
        viewModelScope.launch {
            val folders = settingsRepo.getFolderWhitelist().toList()
            repo.observeIgnoredFaces(folders).collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        personName =
                            getApplication<Application>()
                                .getString(org.eidora.R.string.virtual_person_ignored),
                        viewMode = PersonDetailViewMode.IGNORED,
                        unconfirmedFaces = faces,
                        confirmedFaces = emptyList(),
                    )
                }
            }
        }
    }

    fun toggleFaceSelection(faceId: String) {
        _uiState.update { it.copy(multiSelect = it.multiSelect.toggle(faceId)) }
    }

    fun rangeSelectFace(faceId: String) {
        _uiState.update { state ->
            val orderedIds =
                (state.unconfirmedFaces + state.confirmedFaces)
                    .map { it.faceRegion.id }
            state.copy(multiSelect = state.multiSelect.rangeSelect(faceId, orderedIds))
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelect = it.multiSelect.clear()) }
    }

    fun showFaceActions(faceId: String) {
        _uiState.update { it.copy(actionFaceId = faceId) }
    }

    fun dismissActions() {
        _uiState.update { it.copy(actionFaceId = null) }
    }

    fun confirmFace(faceId: String) {
        val personId = currentPersonId ?: return
        viewModelScope.launch { repo.confirmFace(faceId, personId) }
    }

    fun ignoreFace(faceId: String) {
        viewModelScope.launch { repo.ignoreFace(faceId) }
    }

    fun removeFace(faceId: String) {
        viewModelScope.launch { repo.removeFaceFromPerson(faceId) }
    }

    fun permanentlyDeleteFace(faceId: String) {
        viewModelScope.launch { repo.permanentlyDeleteFace(faceId) }
    }

    fun unignoreFace(faceId: String) {
        viewModelScope.launch { repo.unignoreFace(faceId) }
    }

    fun rejectCurrentSuggestion() {
        val personId = currentPersonId ?: return
        viewModelScope.launch { repo.rejectSuggestion(personId) }
    }

    fun deleteCurrentPerson(onDeleted: () -> Unit) {
        val personId = currentPersonId ?: return
        viewModelScope.launch {
            repo.deletePerson(personId)
            onDeleted()
        }
    }

    fun removeUnconfirmedFaces() {
        val personId = currentPersonId ?: return
        viewModelScope.launch { repo.removeUnconfirmedFaces(personId) }
    }

    fun renameCurrentPerson(newName: String) {
        val personId = currentPersonId ?: return
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) return@launch
            val existing = personDao.findByName(trimmed)
            if (existing != null && existing.id != personId) {
                _uiState.update {
                    it.copy(
                        mergeConflict =
                            MergeConflict(
                                sourcePersonId = personId,
                                targetPersonId = existing.id,
                                targetPersonName = trimmed,
                                targetRepresentativeFaceId = existing.representativeFaceId,
                            ),
                    )
                }
                return@launch
            }
            repo.renamePerson(personId, trimmed)
            _uiState.update {
                it.copy(
                    personName = trimmed,
                    viewMode = PersonDetailViewMode.NORMAL,
                )
            }
        }
    }

    fun confirmMergeConflict(onMerged: (String) -> Unit = {}) {
        val conflict = _uiState.value.mergeConflict ?: return
        // Dismiss dialog and navigate immediately – merge continues in background
        _uiState.update { it.copy(mergeConflict = null) }
        onMerged(conflict.targetPersonId)
        viewModelScope.launch {
            val confirm = settingsRepo.getConfirmOnMergeSuggestion()
            repo.mergePersons(
                listOf(conflict.sourcePersonId, conflict.targetPersonId),
                conflict.targetPersonId,
                confirmFaces = confirm,
            )
        }
    }

    fun cancelMergeConflict() {
        _uiState.update { it.copy(mergeConflict = null) }
    }

    /**
     * Runs the clustering worker to reassign unknown faces. The UI updates
     * automatically as faces leave the "Unknown" flow.
     */
    fun reassignUnknownFaces() {
        val app = getApplication<Application>()
        _uiState.update { it.copy(isReassigning = true) }
        org.eidora.worker.SyncPipeline
            .enqueueClustering(app)
        // Reset spinner after a short delay - the flow will already have
        // updated the visible faces as they leave "Unknown"
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(isReassigning = false) }
        }
    }

    fun redetectFace(faceId: String) {
        viewModelScope.launch {
            val face = faceDao.findById(faceId) ?: return@launch
            repo.resetPhotoFaces(face.photoId)
            SyncPipeline.enqueueReSyncPhoto(getApplication(), face.photoId)
        }
    }

    fun confirmSelected() {
        val personId = currentPersonId ?: return
        val ids = _uiState.value.selectedFaceIds.toList()
        viewModelScope.launch {
            ids.forEach { repo.confirmFace(it, personId) }
            clearSelection()
        }
    }

    fun ignoreSelected() {
        val ids = _uiState.value.selectedFaceIds.toList()
        viewModelScope.launch {
            ids.forEach { repo.ignoreFace(it) }
            clearSelection()
        }
    }

    fun removeSelected() {
        val ids = _uiState.value.selectedFaceIds.toList()
        viewModelScope.launch {
            ids.forEach { repo.removeFaceFromPerson(it) }
            clearSelection()
        }
    }

    fun redetectSelected() {
        val ids = _uiState.value.selectedFaceIds.toList()
        viewModelScope.launch {
            val photoIds = ids.mapNotNull { faceDao.findById(it)?.photoId }.distinct()
            photoIds.forEach { photoId ->
                repo.resetPhotoFaces(photoId)
                SyncPipeline.enqueueReSyncPhoto(getApplication(), photoId)
            }
            clearSelection()
        }
    }

    fun showAssignSheet(faceId: String? = null) {
        val targets = faceId?.let { setOf(it) } ?: _uiState.value.selectedFaceIds
        _uiState.update { it.copy(showAssignSheet = true, assignTargetFaceIds = targets) }
    }

    fun dismissAssignSheet() {
        _uiState.update { it.copy(showAssignSheet = false, assignTargetFaceIds = emptySet()) }
    }


    fun assignToExistingPerson(personId: String) {
        val faceIds = _uiState.value.assignTargetFaceIds.toList()
        viewModelScope.launch {
            val confirm = settingsRepo.getConfirmOnAssign()
            faceIds.forEach { repo.assignFaceToPerson(it, personId, confirm = confirm) }
            clearSelection()
            dismissAssignSheet()
        }
    }

    fun assignToNewPerson(name: String) {
        val faceIds = _uiState.value.assignTargetFaceIds.toList()
        viewModelScope.launch {
            val confirm = settingsRepo.getConfirmOnAssign()
            val person = repo.assignFaceToNewPerson(faceIds.first(), name, confirm = confirm)
            faceIds.drop(1).forEach { repo.assignFaceToPerson(it, person.id, confirm = confirm) }
            clearSelection()
            dismissAssignSheet()
        }
    }
}
