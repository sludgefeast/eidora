// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("FolderHierarchy")
class FolderHierarchyTest {
    @Nested
    @DisplayName("isDescendant")
    inner class Descendant {
        @Test
        fun directChild() {
            assertTrue(FolderHierarchy.isDescendant("Pictures/ChatGPT", "Pictures"))
        }

        @Test
        fun deepChild() {
            assertTrue(
                FolderHierarchy.isDescendant("Pictures/Kleinanzeigen/verkauft", "Pictures"),
            )
        }

        @Test
        fun notADescendantOfItself() {
            assertFalse(FolderHierarchy.isDescendant("Pictures", "Pictures"))
        }

        @Test
        fun siblingIsNotDescendant() {
            assertFalse(FolderHierarchy.isDescendant("PicturesX", "Pictures"))
        }

        @Test
        fun unrelated() {
            assertFalse(FolderHierarchy.isDescendant("DCIM/Camera", "Pictures"))
        }
    }

    @Nested
    @DisplayName("isCoveredByAncestor")
    inner class Covered {
        @Test
        @DisplayName("direct parent selected covers the child")
        fun parentCovers() {
            assertTrue(
                FolderHierarchy.isCoveredByAncestor("Pictures/ChatGPT", setOf("Pictures")),
            )
        }

        @Test
        @DisplayName("grandparent selected covers a deep child")
        fun grandparentCovers() {
            assertTrue(
                FolderHierarchy.isCoveredByAncestor(
                    "Pictures/Kleinanzeigen/verkauft",
                    setOf("Pictures"),
                ),
            )
        }

        @Test
        @DisplayName("a folder is not covered by itself")
        fun notSelf() {
            assertFalse(
                FolderHierarchy.isCoveredByAncestor("Pictures", setOf("Pictures")),
            )
        }

        @Test
        @DisplayName("not covered when only a sibling is selected")
        fun siblingDoesNotCover() {
            assertFalse(
                FolderHierarchy.isCoveredByAncestor(
                    "Pictures/ChatGPT",
                    setOf("Pictures/Screenshots"),
                ),
            )
        }
    }

    @Nested
    @DisplayName("select")
    inner class Select {
        @Test
        @DisplayName("selecting a parent drops now-redundant descendants")
        fun dropsRedundant() {
            val wl = setOf("Pictures/ChatGPT", "Pictures/Kleinanzeigen/verkauft", "DCIM/Camera")
            val result = FolderHierarchy.select("Pictures", wl)
            assertEquals(setOf("Pictures", "DCIM/Camera"), result)
        }

        @Test
        @DisplayName("selecting a leaf just adds it")
        fun addsLeaf() {
            val result = FolderHierarchy.select("Pictures/ChatGPT", setOf("DCIM/Camera"))
            assertEquals(setOf("Pictures/ChatGPT", "DCIM/Camera"), result)
        }

        @Test
        @DisplayName("selecting multi-level parent drops deep descendants")
        fun dropsDeep() {
            val wl = setOf("Pictures/Kleinanzeigen/verkauft", "Pictures/Kleinanzeigen/offen")
            val result = FolderHierarchy.select("Pictures/Kleinanzeigen", wl)
            assertEquals(setOf("Pictures/Kleinanzeigen"), result)
        }
    }

    @Nested
    @DisplayName("minimize")
    inner class Minimize {
        @Test
        @DisplayName("drops a child already covered by its parent")
        fun dropsCoveredChild() {
            assertEquals(
                setOf("DCIM/Camera"),
                FolderHierarchy.minimize(setOf("DCIM/Camera", "DCIM/Camera/Sub")),
            )
        }

        @Test
        @DisplayName("keeps independent folders")
        fun keepsIndependent() {
            val input = setOf("DCIM/Camera", "DCIM/PlantNet")
            assertEquals(input, FolderHierarchy.minimize(input))
        }

        @Test
        @DisplayName("collapses a deep chain to the topmost folder")
        fun collapsesChain() {
            assertEquals(
                setOf("Pictures"),
                FolderHierarchy.minimize(setOf("Pictures", "Pictures/A", "Pictures/A/B")),
            )
        }

        @Test
        @DisplayName("is order-independent (child listed before parent)")
        fun orderIndependent() {
            assertEquals(
                setOf("Pictures"),
                FolderHierarchy.minimize(setOf("Pictures/A/B", "Pictures/A", "Pictures")),
            )
        }

        @Test
        @DisplayName("empty stays empty")
        fun empty() {
            assertEquals(emptySet<String>(), FolderHierarchy.minimize(emptySet()))
        }
    }
}
