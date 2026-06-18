package de.sebastian.faces.ui.persons

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.sebastian.faces.data.db.FacesDatabase
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

    private val db = FacesDatabase.getInstance(app)
    private val repo = FaceRepository(app, db)
    private val faceDao = db.faceRegionDao()
    private val personDao = db.personDao()

    private val _uiState = MutableStateFlow(PersonsUiState())
    val uiState: StateFlow<PersonsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repo.observePersonsWithCount().collect { allPersons ->
                val confirmed = allPersons.filter { it.confirmedCount > 0 || it.person.representativeFaceId != null }
                // Suggestions: persons where no face has a confirmed name
                val suggestions = allPersons
                    .filter { pwc ->
                        val faces = faceDao.findByPersonId(pwc.person.id)
                        faces.isNotEmpty() && faces.all { it.name == null && !it.ignored }
                    }
                    .map { pwc ->
                        PersonSuggestionUi(
                            personId = pwc.person.id,
                            representativeFaceId = pwc.person.representativeFaceId,
                            faceCount = faceDao.findByPersonId(pwc.person.id).size
                        )
                    }

                val unknownCount = faceDao.findUnclusteredAndNotIgnored().size
                val ignoredCount = faceDao.observeIgnored().first().size

                _uiState.update {
                    it.copy(
                        confirmedPersons = confirmed,
                        suggestions = suggestions,
                        unknownCount = unknownCount,
                        ignoredCount = ignoredCount
                    )
                }
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
            val faces = faceDao.findByPersonId(personId)
            val existing = personDao.findByName(name)
            val targetPersonId = existing?.id ?: personId

            if (existing != null && existing.id != personId) {
                // Merge suggestion person into existing person
                repo.mergePersons(listOf(personId, existing.id), existing.id)
            }
            faces.forEach { face ->
                if (face.name == null) repo.confirmFace(face.id, targetPersonId)
            }
        }
    }
}
