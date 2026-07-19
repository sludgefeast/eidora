package org.eidora.ui.persons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.db.PersonEntity
import org.eidora.data.db.PersonWithCount
import org.eidora.data.repository.FaceRepository
import org.eidora.ui.common.MultiSelectState

data class PersonSuggestionUi(
    val personId: String,
    val representativeFaceId: String?,
    val firstFaceId: String?,
    val faceCount: Int,
)

data class PersonsUiState(
    val confirmedPersons: List<PersonWithCount> = emptyList(),
    val suggestions: List<PersonSuggestionUi> = emptyList(),
    val unknownCount: Int = 0,
    val ignoredCount: Int = 0,
    val multiSelect: MultiSelectState<String> = MultiSelectState(),
    val renamingPersonId: String? = null,
    val showMergeSheet: Boolean = false,
) {
    val selectedPersonIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
}

class PersonsViewModel(
    app: Application,
) : AndroidViewModel(app) {
    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val settingsRepo = org.eidora.data.settings.SettingsProvider.get(app)
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
                faceDao.observeIgnoredCount(),
            ) {
                    confirmed: List<PersonWithCount>,
                    suggestions: List<PersonEntity>,
                    unknownCount: Int,
                    ignoredCount: Int,
                ->

                val suggestionUis =
                    suggestions.map { person ->
                        val faces = faceDao.findByPersonId(person.id)
                        PersonSuggestionUi(
                            personId = person.id,
                            representativeFaceId = person.representativeFaceId,
                            firstFaceId = faces.firstOrNull()?.id,
                            faceCount = faces.size,
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

    fun renamePerson(
        personId: String,
        newName: String,
    ) {
        viewModelScope.launch {
            repo.renamePerson(personId, newName)
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
                    showMergeSheet = false,
                )
            }
        }
    }

    fun confirmSuggestion(
        personId: String,
        name: String,
    ) {
        viewModelScope.launch {
            val existing = personDao.findByName(name)
            if (existing != null && existing.id != personId) {
                // Naming matches an existing person → merge suggestion into it
                val confirm = settingsRepo.getConfirmOnMergeSuggestion()
                repo.mergePersons(listOf(personId, existing.id), existing.id, confirmFaces = confirm)
            } else {
                // Plain naming of a suggestion
                val confirm = settingsRepo.getConfirmOnNameSuggestion()
                repo.nameSuggestion(personId, name, confirm = confirm)
            }
        }
    }

    fun rejectSuggestion(personId: String) {
        viewModelScope.launch {
            repo.rejectSuggestion(personId)
        }
    }

    fun rejectAllSuggestions() {
        viewModelScope.launch {
            repo.rejectAllSuggestions()
            org.eidora.worker.SyncPipeline
                .enqueueClustering(getApplication())
        }
    }
}
