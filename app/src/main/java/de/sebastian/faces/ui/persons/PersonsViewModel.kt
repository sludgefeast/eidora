package de.sebastian.faces.ui.persons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.PersonEntity
import de.sebastian.faces.data.db.PersonWithCount
import de.sebastian.faces.data.repository.FaceRepository
import de.sebastian.faces.ui.common.MultiSelectState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PersonSuggestionUi(
    val personId: String,
    val representativeFaceId: String?,
    val firstFaceId: String?,
    val faceCount: Int
)

data class PersonsUiState(
    val confirmedPersons: List<PersonWithCount> = emptyList(),
    val suggestions: List<PersonSuggestionUi> = emptyList(),
    val unknownCount: Int = 0,
    val ignoredCount: Int = 0,
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val renamingPersonId: String? = null,
    val showMergeSheet: Boolean = false,
    val mergeConflict: MergeConflict? = null
) {
    val selectedPersonIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
}

/**
 * Represents a pending merge decision when the user picked a name that already exists.
 */
data class MergeConflict(
    val sourcePersonId: String,
    val targetPersonId: String,
    val targetPersonName: String,
    val targetRepresentativeFaceId: String?
)

class PersonsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val faceDao = db.faceRegionDao()
    private val personDao = db.personDao()

    private val _uiState = MutableStateFlow(PersonsUiState())
    val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repo.observePersonsWithCount(),
                personDao.observeSuggestions(),
                faceDao.observeUnknownCount(),
                faceDao.observeIgnoredCount()
            ) { confirmed: List<PersonWithCount>,
                suggestions: List<PersonEntity>,
                unknownCount: Int,
                ignoredCount: Int ->

                val suggestionUis = suggestions.map { person ->
                    val faces = faceDao.findByPersonId(person.id)
                    PersonSuggestionUi(
                        personId = person.id,
                        representativeFaceId = person.representativeFaceId,
                        firstFaceId = faces.firstOrNull()?.id,
                        faceCount = faces.size
                    )
                }

                PersonsUiState(
                    confirmedPersons = confirmed,
                    suggestions = suggestionUis,
                    unknownCount = unknownCount,
                    ignoredCount = ignoredCount,
                    multiSelect = _uiState.value.multiSelect,
                    renamingPersonId = _uiState.value.renamingPersonId,
                    showMergeSheet = _uiState.value.showMergeSheet,
                    mergeConflict = _uiState.value.mergeConflict
                )
            }.collect { newState -> _uiState.value = newState }
        }
    }

    fun toggleSelection(personId: String) {
        _uiState.update { it.copy(multiSelect = it.multiSelect.toggle(personId)) }
    }

    fun rangeSelectPerson(personId: String) {
        _uiState.update { state ->
            val orderedIds = state.confirmedPersons.map { it.person.id }
            state.copy(multiSelect = state.multiSelect.rangeSelect(personId, orderedIds))
        }
    }

    fun startRename(personId: String) {
        _uiState.update { it.copy(renamingPersonId = personId) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(renamingPersonId = null) }
    }

    fun renamePerson(personId: String, newName: String) {
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                _uiState.update { it.copy(renamingPersonId = null) }
                return@launch
            }
            val existing = personDao.findByName(trimmed)
            if (existing != null && existing.id != personId) {
                _uiState.update {
                    it.copy(
                        renamingPersonId = null,
                        mergeConflict = MergeConflict(
                            sourcePersonId = personId,
                            targetPersonId = existing.id,
                            targetPersonName = trimmed,
                            targetRepresentativeFaceId = existing.representativeFaceId
                        )
                    )
                }
                return@launch
            }
            repo.renamePerson(personId, trimmed)
            _uiState.update { it.copy(renamingPersonId = null) }
        }
    }

    fun startMerge() {
        _uiState.update { it.copy(showMergeSheet = true) }
    }

    fun cancelMerge() {
        _uiState.update { it.copy(showMergeSheet = false) }
    }

    fun mergePersons(winnerId: String) {
        viewModelScope.launch {
            val sourceIds = _uiState.value.selectedPersonIds.toList()
            repo.mergePersons(sourceIds, winnerId)
            _uiState.update {
                it.copy(
                    multiSelect = it.multiSelect.clear(),
                    showMergeSheet = false
                )
            }
        }
    }

    fun confirmSuggestion(personId: String, name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isBlank()) return@launch
            val existing = personDao.findByName(trimmed)
            if (existing != null && existing.id != personId) {
                _uiState.update {
                    it.copy(
                        mergeConflict = MergeConflict(
                            sourcePersonId = personId,
                            targetPersonId = existing.id,
                            targetPersonName = trimmed,
                            targetRepresentativeFaceId = existing.representativeFaceId
                        )
                    )
                }
                return@launch
            }
            personDao.updateName(personId, trimmed)
            val faces = faceDao.findByPersonId(personId)
            faces.filter { it.name == null }.forEach { face ->
                repo.confirmFace(face.id, personId)
            }
        }
    }

    fun confirmMergeConflict() {
        val conflict = _uiState.value.mergeConflict ?: return
        viewModelScope.launch {
            repo.mergePersons(
                listOf(conflict.sourcePersonId, conflict.targetPersonId),
                conflict.targetPersonId
            )
            _uiState.update { it.copy(mergeConflict = null) }
        }
    }

    fun cancelMergeConflict() {
        _uiState.update { it.copy(mergeConflict = null) }
    }
}
