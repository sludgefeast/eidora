// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("MultiSelectState")
class MultiSelectStateTest {
    private val items = listOf("a", "b", "c", "d", "e")

    @Nested
    @DisplayName("toggle")
    inner class Toggle {
        @Test
        fun `first toggle activates and selects`() {
            val s = MultiSelectState<String>().toggle("b")
            assertTrue(s.isActive)
            assertEquals(setOf("b"), s.selectedIds)
            assertEquals("b", s.lastSelectedId)
        }

        @Test
        fun `toggling same item twice deselects and deactivates`() {
            val s = MultiSelectState<String>().toggle("b").toggle("b")
            assertFalse(s.isActive)
            assertTrue(s.selectedIds.isEmpty())
            assertEquals(null, s.lastSelectedId)
        }

        @Test
        fun `toggling multiple accumulates selection`() {
            val s = MultiSelectState<String>().toggle("a").toggle("c")
            assertEquals(setOf("a", "c"), s.selectedIds)
            assertEquals("c", s.lastSelectedId)
        }

        @Test
        fun `deselecting one of several stays active`() {
            val s = MultiSelectState<String>().toggle("a").toggle("b").toggle("a")
            assertTrue(s.isActive)
            assertEquals(setOf("b"), s.selectedIds)
        }
    }

    @Nested
    @DisplayName("rangeSelect")
    inner class RangeSelect {
        @Test
        fun `without previous selection behaves like toggle`() {
            val s = MultiSelectState<String>().rangeSelect("c", items)
            assertEquals(setOf("c"), s.selectedIds)
        }

        @Test
        fun `selects forward range inclusive`() {
            val s = MultiSelectState<String>().toggle("b").rangeSelect("d", items)
            assertEquals(setOf("b", "c", "d"), s.selectedIds)
            assertEquals("d", s.lastSelectedId)
        }

        @Test
        fun `selects backward range inclusive`() {
            val s = MultiSelectState<String>().toggle("d").rangeSelect("b", items)
            assertEquals(setOf("b", "c", "d"), s.selectedIds)
            assertEquals("b", s.lastSelectedId)
        }

        @Test
        fun `range merges with existing selection`() {
            val s =
                MultiSelectState<String>()
                    .toggle("a")
                    .toggle("c")
                    .rangeSelect("e", items)
            // a stays, then range c..e added
            assertEquals(setOf("a", "c", "d", "e"), s.selectedIds)
        }

        @Test
        fun `same id as last falls back to toggle`() {
            val s = MultiSelectState<String>().toggle("c").rangeSelect("c", items)
            // toggling c off
            assertTrue(s.selectedIds.isEmpty())
        }

        @Test
        fun `unknown id falls back to toggle`() {
            val s = MultiSelectState<String>().toggle("b").rangeSelect("zzz", items)
            assertEquals(setOf("b", "zzz"), s.selectedIds)
        }
    }

    @Test
    @DisplayName("clear resets to empty inactive state")
    fun clear() {
        val s = MultiSelectState<String>().toggle("a").toggle("b").clear()
        assertFalse(s.isActive)
        assertTrue(s.selectedIds.isEmpty())
        assertEquals(null, s.lastSelectedId)
    }
}
