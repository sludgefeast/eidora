// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("formatBytes")
class ByteFormatTest {
    @Test
    @DisplayName("non-positive input shows a dash")
    fun nonPositive() {
        assertEquals("—", formatBytes(0))
        assertEquals("—", formatBytes(-5))
    }

    @Test
    @DisplayName("bytes below 1 KB are shown as plain bytes")
    fun bytes() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    @DisplayName("exactly 1024 rolls over to 1.0 KB")
    fun kilobyteBoundary() {
        assertEquals("1.0 KB", formatBytes(1024).replace(',', '.'))
    }

    @Test
    @DisplayName("megabyte-range values use MB with one decimal")
    fun megabytes() {
        // The decimal separator follows the default locale (',' in de, '.' in
        // en), so normalize before comparing — the point is the number + unit.
        val result = formatBytes((3.3 * 1024 * 1024).toLong()).replace(',', '.')
        assertEquals("3.3 MB", result)
    }

    @Test
    @DisplayName("gigabyte-range values use GB")
    fun gigabytes() {
        val result = formatBytes(2L * 1024 * 1024 * 1024).replace(',', '.')
        assertEquals("2.0 GB", result)
    }

    @Test
    @DisplayName("very large values stay in GB (largest unit)")
    fun staysInGb() {
        val result = formatBytes(5000L * 1024 * 1024 * 1024)
        assertEquals(true, result.endsWith("GB"), "expected GB, got $result")
    }
}
