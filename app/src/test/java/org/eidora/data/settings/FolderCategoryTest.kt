// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.data.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@DisplayName("SettingsRepository.categorize")
class FolderCategoryTest {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "DCIM/, CAMERA",
        "DCIM/Camera, CAMERA",
        "DCIM/Screenshots, CAMERA",
        "Pictures/, COMMON",
        "Pictures/Vacation, COMMON",
        "Download/, COMMON",
        "Downloads/, COMMON",
        "Android/media/com.whatsapp, APPS",
        "Android/data/com.foo, APPS",
        "Movies/, OTHER",
        "Music/, OTHER",
        "SomeRandomFolder/, OTHER",
    )
    fun categorizes(
        path: String,
        expected: String,
    ) {
        assertEquals(FolderCategory.valueOf(expected), SettingsRepository.categorize(path))
    }

    @Test
    @DisplayName("APPS takes precedence over other matches")
    fun appsPrecedence() {
        // A path that could only match APPS – ensures the when-order is APPS first
        assertEquals(FolderCategory.APPS, SettingsRepository.categorize("Android/media/x"))
    }

    @Test
    @DisplayName("empty path is OTHER")
    fun emptyPath() {
        assertEquals(FolderCategory.OTHER, SettingsRepository.categorize(""))
    }

    @Test
    @DisplayName("matching is case-sensitive on the known prefixes")
    fun caseSensitive() {
        // lowercase 'dcim' does not match 'DCIM/' → OTHER
        assertEquals(FolderCategory.OTHER, SettingsRepository.categorize("dcim/camera"))
    }
}
