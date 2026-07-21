// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

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
    val namesakeConflict: NamesakeConflict? = null,
) {
    val selectedPersonIds get() = multiSelect.selectedIds
    val isMultiSelectActive get() = multiSelect.isActive
}

/**
 * Raised when a suggestion is named to match an existing person who is
 * currently hidden by the folder filter, so the merge would be invisible.
 */
data class NamesakeConflict(
    val suggestionId: String,
    val existingPersonId: String,
    val name: String,
)

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
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            settingsRepo.folderWhitelist.flatMapLatest { wl ->
            val folders = wl.toList()
            combine(
                repo.observePersonsWithCount(folders),
                personDao.observeSuggestions(folders),
                faceDao.observeUnknownCount(folders),
                faceDao.observeIgnoredCount(folders),
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
            }
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
                // A person with this name already exists. If that person is
                // currently hidden by the folder filter, the merge would happen
                // invisibly – ask the user first. If they are visible, the merge
                // is self-evident and proceeds directly.
                val folders = settingsRepo.getFolderWhitelist().toList()
                val visibleFaces = personDao.countFacesInFolders(existing.id, folders)
                if (visibleFaces == 0) {
                    _uiState.update {
                        it.copy(
                            namesakeConflict =
                                NamesakeConflict(
                                    suggestionId = personId,
                                    existingPersonId = existing.id,
                                    name = name,
                                ),
                        )
                    }
                    return@launch
                }
                val confirm = settingsRepo.getConfirmOnMergeSuggestion()
                repo.mergePersons(listOf(personId, existing.id), existing.id, confirmFaces = confirm)
            } else {
                val confirm = settingsRepo.getConfirmOnNameSuggestion()
                repo.nameSuggestion(personId, name, confirm = confirm)
            }
        }
    }

    /** User chose to merge the suggestion into the hidden namesake person. */
    fun confirmNamesakeMerge() {
        val conflict = _uiState.value.namesakeConflict ?: return
        _uiState.update { it.copy(namesakeConflict = null) }
        viewModelScope.launch {
            val confirm = settingsRepo.getConfirmOnMergeSuggestion()
            repo.mergePersons(
                listOf(conflict.suggestionId, conflict.existingPersonId),
                conflict.existingPersonId,
                confirmFaces = confirm,
            )
        }
    }

    /** User chose to keep the suggestion as a separate, new person. */
    fun keepNamesakeSeparate() {
        val conflict = _uiState.value.namesakeConflict ?: return
        _uiState.update { it.copy(namesakeConflict = null) }
        viewModelScope.launch {
            // Name the suggestion without merging. The name index is non-unique,
            // so two people may legitimately share a name.
            val confirm = settingsRepo.getConfirmOnNameSuggestion()
            repo.nameSuggestion(conflict.suggestionId, conflict.name, confirm = confirm)
        }
    }

    fun dismissNamesakeConflict() {
        _uiState.update { it.copy(namesakeConflict = null) }
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
