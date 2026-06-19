package de.sebastian.faces.ui.persondetail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.FaceRegionWithPhoto
import de.sebastian.faces.data.db.PersonWithCount
import de.sebastian.faces.data.repository.FaceRepository
import de.sebastian.faces.worker.SyncPipeline
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PersonDetailUiState(
    val personName: String = "",
    val unconfirmedFaces: List<FaceRegionWithPhoto> = emptyList(),
    val confirmedFaces: List<FaceRegionWithPhoto> = emptyList(),
    val selectedFaceIds: Set<String> = emptySet(),
    val isMultiSelectActive: Boolean = false,
    val actionFaceId: String? = null,
    val showAssignSheet: Boolean = false,
    val assignTargetFaceIds: Set<String> = emptySet(),
    val allPersons: List<PersonWithCount> = emptyList(),
    val personSearchQuery: String = ""
)

class PersonDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val faceDao = db.faceRegionDao()
    private val personDao = db.personDao()

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    private var currentPersonId: String? = null

    fun load(personId: String) {
        currentPersonId = personId
        viewModelScope.launch {
            val person = personDao.findById(personId)
            _uiState.update { it.copy(personName = person?.name ?: "") }

            faceDao.observeByPersonId(personId).collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        unconfirmedFaces = faces.filter { f ->
                            f.faceRegion.name == null && !f.faceRegion.ignored
                        },
                        confirmedFaces = faces.filter { f ->
                            f.faceRegion.name != null && !f.faceRegion.ignored
                        }
                    )
                }
            }
        }
        viewModelScope.launch {
            repo.observePersonsWithCount().collect { persons: List<PersonWithCount> ->
                _uiState.update { it.copy(allPersons = persons) }
            }
        }
    }

    fun loadUnknown() {
        viewModelScope.launch {
            repo.observeUnknownFaces().collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        personName = getApplication<Application>()
                            .getString(de.sebastian.faces.R.string.virtual_person_unknown),
                        unconfirmedFaces = faces,
                        confirmedFaces = emptyList()
                    )
                }
            }
        }
    }

    fun loadIgnored() {
        viewModelScope.launch {
            repo.observeIgnoredFaces().collect { faces: List<FaceRegionWithPhoto> ->
                _uiState.update {
                    it.copy(
                        personName = getApplication<Application>()
                            .getString(de.sebastian.faces.R.string.virtual_person_ignored),
                        unconfirmedFaces = faces,
                        confirmedFaces = emptyList()
                    )
                }
            }
        }
    }

    fun toggleFaceSelection(faceId: String) {
        _uiState.update { state ->
            val newSelected = state.selectedFaceIds.toMutableSet()
            if (newSelected.contains(faceId)) newSelected.remove(faceId)
            else newSelected.add(faceId)
            state.copy(
                selectedFaceIds = newSelected,
                isMultiSelectActive = newSelected.isNotEmpty()
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedFaceIds = emptySet(), isMultiSelectActive = false) }
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

    fun filteredPersons(): List<PersonWithCount> {
        val q = _uiState.value.personSearchQuery.trim().lowercase()
        return if (q.isEmpty()) _uiState.value.allPersons
        else _uiState.value.allPersons.filter { it.person.name?.lowercase()?.contains(q) == true }
    }

    fun assignToExistingPerson(personId: String) {
        val faceIds = _uiState.value.assignTargetFaceIds.toList()
        viewModelScope.launch {
            faceIds.forEach { repo.assignFaceToPerson(it, personId) }
            clearSelection()
            dismissAssignSheet()
        }
    }

    fun assignToNewPerson(name: String) {
        val faceIds = _uiState.value.assignTargetFaceIds.toList()
        viewModelScope.launch {
            val person = repo.assignFaceToNewPerson(faceIds.first(), name)
            faceIds.drop(1).forEach { repo.assignFaceToPerson(it, person.id) }
            clearSelection()
            dismissAssignSheet()
        }
    }
}
