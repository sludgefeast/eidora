// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.util

/**
 * Formats a byte count as a short human-readable size (e.g. "3.3 MB").
 * Returns "—" for non-positive input. Uses binary steps (1024) and one decimal
 * place from KB upward; exact bytes below 1 KB are shown without a decimal.
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "$bytes B" else "%.1f %s".format(value, units[unit])
}
