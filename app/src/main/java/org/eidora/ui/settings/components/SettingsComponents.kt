// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eidora.R

/*
 * Shared building blocks for settings-style screens.
 *
 * These were originally private to SettingsScreen; they're extracted here so
 * other screens (e.g. the models screen) can reuse the exact same look instead
 * of re-implementing rows. Kept deliberately small — four proven pieces, no
 * generic "Setting" abstraction. Each carries the project-specific behavior we
 * actually rely on (text-entry numeric fields with a reset-to-default button,
 * locale-tolerant parsing), which a generic library couldn't provide.
 *
 * Visibility is `internal` so any screen in this module can use them, but they
 * don't leak outside the app module.
 */

/**
 * Section title with a divider above it (except the first), giving clear visual
 * grouping without boxing everything in cards.
 */
@Composable
internal fun SectionHeader(
    text: String,
    first: Boolean = false,
) {
    if (!first) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 28.dp, bottom = 0.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (first) 8.dp else 20.dp, bottom = 8.dp),
    )
}

/** A labelled on/off row with a description line under the label. */
@Composable
internal fun SwitchSetting(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * A numeric (decimal) setting entered as text, with a default hint and a
 * reset-to-default button. Uses a text field rather than a slider so values
 * aren't range-limited, and parses locale-tolerantly (accepts ',' or '.').
 */
@Composable
internal fun FloatSetting(
    label: String,
    description: String,
    value: Float,
    default: Float,
    hint: String? = null,
    decimals: Int = 2,
    onValueChange: (Float) -> Unit,
) {
    val fmt = "%.${decimals}f"
    var text by remember(value) { mutableStateOf(fmt.format(value)) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(
                text = hint,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    new.replace(',', '.').toFloatOrNull()?.let { onValueChange(it) }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.setting_default_hint, fmt.format(default)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = { onValueChange(default) }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.setting_reset_to_default),
                )
            }
        }
    }
}

/**
 * An integer setting entered as text, with a default hint and a reset-to-default
 * button. Same rationale as [FloatSetting] but for whole numbers.
 */
@Composable
internal fun IntSetting(
    label: String,
    description: String,
    value: Int,
    default: Int,
    hint: String? = null,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hint != null) {
            Text(
                text = hint,
                style =
                    MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    new.toIntOrNull()?.let { onValueChange(it) }
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.setting_default_hint, default.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = { onValueChange(default) }) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.setting_reset_to_default),
                )
            }
        }
    }
}
