package de.sebastian.faces.ui.persons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.data.db.PersonEntity
import de.sebastian.faces.data.db.PersonWithCount
import de.sebastian.faces.data.repository.FaceRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PersonSuggestionUi(
    val personId: String,
    val representativeFaceId: String?,
    val faceCount: Int
)

data class PersonsUiState(
    val confirmedPersons: List<PersonWithCount> = emptyList(),
    val suggestions: List<PersonSuggestionUi> = emptyList(),
    val unknownCount: Int = 0,
    val ignoredCount: Int = 0,
    val selectedPersonIds: Set<String> = emptySet(),
    val isMultiSelectActive: Boolean = false,
    val renamingPersonId: String? = null,
    val showMergeSheet: Boolean = false
)

class PersonsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = DatabaseProvider.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val faceDao = db.faceRegionDao()
    private val personDao = db.personDao()

    private val _uiState = MutableStateFlow(PersonsUiState())
    val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()

    init {
        // Fix 2: use reactive count flows instead of suspend calls inside collect
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
                    PersonSuggestionUi(
                        personId = person.id,
                        representativeFaceId = person.representativeFaceId,
                        faceCount = 0 // loaded lazily in UI if needed
                    )
                }

                PersonsUiState(
                    confirmedPersons = confirmed,
                    suggestions = suggestionUis,
                    unknownCount = unknownCount,
                    ignoredCount = ignoredCount,
                    selectedPersonIds = _uiState.value.selectedPersonIds,
                    isMultiSelectActive = _uiState.value.isMultiSelectActive,
                    renamingPersonId = _uiState.value.renamingPersonId,
                    showMergeSheet = _uiState.value.showMergeSheet
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun toggleSelection(personId: String) {
        _uiState.update { state ->
            val newSelected = state.selectedPersonIds.toMutableSet()
            if (newSelected.contains(personId)) newSelected.remove(personId)
            else newSelected.add(personId)
            state.copy(
                selectedPersonIds = newSelected,
                isMultiSelectActive = newSelected.isNotEmpty()
            )
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
                    showMergeSheet = false,
                    selectedPersonIds = emptySet(),
                    isMultiSelectActive = false
                )
            }
        }
    }

    fun confirmSuggestion(personId: String, name: String) {
        viewModelScope.launch {
            // Check if a person with this name already exists
            val existing = personDao.findByName(name)
            if (existing != null && existing.id != personId) {
                // Merge suggestion into existing named person
                repo.mergePersons(listOf(personId, existing.id), existing.id)
            } else {
                // Name the suggestion person and confirm all its faces
                personDao.updateName(personId, name)
                val faces = faceDao.findByPersonId(personId)
                faces.filter { it.name == null }.forEach { face ->
                    repo.confirmFace(face.id, personId)
                }
            }
        }
    }
}
