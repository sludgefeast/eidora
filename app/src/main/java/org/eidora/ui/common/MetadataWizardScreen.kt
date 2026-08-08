// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.eidora.R
import org.eidora.data.settings.SettingsProvider
import org.eidora.data.settings.SettingsRepository

/**
 * First-run step that lets the user decide, before any analysis writes to their
 * files, whether Eidora should add a capture date to photos that don't have one.
 *
 * There's room here to explain the trade-off fully (unlike the compact settings
 * toggle): why undated photos lose their place in the timeline once a file is
 * touched, and how writing a date fixes that. The choice is stored in the same
 * `fillMissingDate` setting the settings screen uses, so it stays adjustable
 * later. Completing the step sets `metadataWizardDone`.
 */
@Composable
fun MetadataWizardScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Start from the current setting value (default on), so re-entry is coherent.
    var enabled by remember {
        mutableStateOf(SettingsRepository.DEFAULT_FILL_MISSING_DATE)
    }
    var saving by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                // Keep content clear of the status bar, navigation bar and any
                // display cutout (e.g. an in-display front camera), so the title
                // never sits underneath the camera. safeDrawing reports these
                // per-device, so we don't guess the cutout position.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.metadata_wizard_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.metadata_wizard_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))

        // The toggle row, echoing the settings-screen switch so it feels familiar.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.setting_fill_missing_date),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.metadata_wizard_toggle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { enabled = it },
                modifier = Modifier.padding(start = 12.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (saving) return@Button
                saving = true
                scope.launch {
                    val repo = SettingsProvider.get(context)
                    repo.setFillMissingDate(enabled)
                    repo.setMetadataWizardDone(true)
                    onDone()
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.metadata_wizard_continue))
        }
    }
}
