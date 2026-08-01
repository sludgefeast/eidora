// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.settings

/**
 * Pure helpers for hierarchical folder selection.
 *
 * A selected folder implicitly includes everything beneath it (the scan matches
 * a photo when its path equals a selected folder OR starts with it + "/"). So a
 * child folder is already covered when any ancestor is selected — at any depth,
 * e.g. selecting "Pictures" covers "Pictures/Kleinanzeigen/verkauft".
 *
 * The UI uses this to show covered children as checked-and-disabled, and to keep
 * the stored whitelist minimal (a child isn't stored separately while a parent
 * covers it). Kept free of Android types so it is unit-testable.
 */
object FolderHierarchy {
    /** True if [folder] is a strict descendant of [ancestor] (any depth). */
    fun isDescendant(folder: String, ancestor: String): Boolean =
        folder.startsWith("$ancestor/")

    /**
     * True if any folder in [whitelist] is a strict ancestor of [folder]. When
     * true the folder is already included via that ancestor, so its own checkbox
     * should be checked and disabled.
     */
    fun isCoveredByAncestor(folder: String, whitelist: Set<String>): Boolean =
        whitelist.any { it != folder && isDescendant(folder, it) }

    /**
     * Adds [folder] to [whitelist], removing any entries it now covers. Selecting
     * a parent makes previously-stored descendants redundant, so they're dropped
     * to keep the set minimal (and to match the disabled UI state).
     */
    fun select(folder: String, whitelist: Set<String>): Set<String> =
        whitelist.filterNot { isDescendant(it, folder) }.toSet() + folder

    /**
     * Removes [folder] from [whitelist]. Descendants covered only via [folder]
     * were never stored separately, so plain removal is enough — after this the
     * children become unchecked and selectable again.
     */
    fun deselect(folder: String, whitelist: Set<String>): Set<String> =
        whitelist - folder
}
