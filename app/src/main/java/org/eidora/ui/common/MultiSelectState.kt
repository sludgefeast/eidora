// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

/**
 * Generic multiselect state that can be embedded in any ViewModel's UiState.
 *
 * @param T the type of item ID (typically String/UUID)
 * @param allItemIds ordered list of all visible item IDs – used for range selection.
 *                   Must be provided by the ViewModel at the time of range selection.
 */
data class MultiSelectState<T>(
    val selectedIds: Set<T> = emptySet(),
    val lastSelectedId: T? = null,
    val isActive: Boolean = false,
) {
    /**
     * Toggles a single item. Activates multiselect on first selection.
     */
    fun toggle(id: T): MultiSelectState<T> {
        val newSelected = selectedIds.toMutableSet()
        if (newSelected.contains(id)) {
            newSelected.remove(id)
        } else {
            newSelected.add(id)
        }
        return copy(
            selectedIds = newSelected,
            isActive = newSelected.isNotEmpty(),
            lastSelectedId = if (newSelected.isNotEmpty()) id else null,
        )
    }

    /**
     * Selects all items between lastSelectedId and the given id (inclusive).
     * Falls back to toggle if no previous selection exists.
     *
     * @param id the item that was long-pressed
     * @param orderedIds the full ordered list of visible item IDs
     */
    fun rangeSelect(
        id: T,
        orderedIds: List<T>,
    ): MultiSelectState<T> {
        val lastId = lastSelectedId
        if (lastId == null || lastId == id) {
            return toggle(id)
        }

        val lastIndex = orderedIds.indexOf(lastId)
        val currentIndex = orderedIds.indexOf(id)

        if (lastIndex == -1 || currentIndex == -1) {
            return toggle(id)
        }

        val range =
            if (lastIndex < currentIndex) {
                orderedIds.subList(lastIndex, currentIndex + 1)
            } else {
                orderedIds.subList(currentIndex, lastIndex + 1)
            }

        val newSelected = selectedIds.toMutableSet().also { it.addAll(range) }
        return copy(
            selectedIds = newSelected,
            isActive = true,
            lastSelectedId = id,
        )
    }

    /**
     * Clears all selections and deactivates multiselect.
     */
    fun clear(): MultiSelectState<T> = MultiSelectState()
}
