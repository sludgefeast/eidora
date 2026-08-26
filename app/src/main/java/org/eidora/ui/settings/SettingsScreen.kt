// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eidora.R
import org.eidora.data.settings.SettingsRepository
import org.eidora.ui.settings.components.FloatSetting
import org.eidora.ui.settings.components.IntSetting
import org.eidora.ui.settings.components.SectionHeader
import org.eidora.ui.settings.components.SwitchSetting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit = {},
    onOpenFolders: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // Navigable entry: Models management screen
            NavigationEntry(
                title = stringResource(R.string.models_settings_entry),
                description = stringResource(R.string.models_settings_entry_desc),
                onClick = onOpenModels,
            )
            Spacer(Modifier.height(8.dp))

            // Section: folder filter (top)
            SectionHeader(stringResource(R.string.settings_folders_title), first = true)

            // Single clickable entry that opens the folder selection page (on its
            // own page so its async folder scan doesn't make this list jump on
            // open). Keeps the detailed description; the previous standalone
            // description text plus a second, terser entry were merged into this
            // one to avoid two confusing "Folders" rows.
            NavigationEntry(
                title = stringResource(R.string.settings_folders_entry),
                description = stringResource(R.string.settings_folders_description),
                onClick = onOpenFolders,
            )
            if (state.folderWhitelist.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_folders_empty_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            // Section: clustering
            SectionHeader(stringResource(R.string.settings_clustering_title))
            Text(
                text = stringResource(R.string.settings_clustering_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            val cfg = state.clusteringConfig
            // Reset-to-default on the sliders should target the active model's
            // tuned thresholds, not fixed constants.
            val modelThresholds =
                org.eidora.ml.EmbeddingModelSpec
                    .byId(state.embeddingModelId)
                    .defaultThresholds

            FloatSetting(
                label = stringResource(R.string.setting_edge_threshold),
                description = stringResource(R.string.setting_edge_threshold_description),
                hint = stringResource(R.string.setting_edge_threshold_hint),
                value = cfg.edgeThreshold,
                default = modelThresholds.edge,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(edgeThreshold = it)) },
            )
            FloatSetting(
                label = stringResource(R.string.setting_cluster_match_threshold),
                description = stringResource(R.string.setting_cluster_match_threshold_description),
                hint = stringResource(R.string.setting_cluster_match_threshold_hint),
                value = cfg.clusterMatchThreshold,
                default = modelThresholds.clusterMatch,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(clusterMatchThreshold = it)) },
            )
            FloatSetting(
                label = stringResource(R.string.setting_individual_match_threshold),
                description = stringResource(R.string.setting_individual_match_threshold_description),
                hint = stringResource(R.string.setting_individual_match_threshold_hint),
                value = cfg.individualMatchThreshold,
                default = modelThresholds.individualMatch,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(individualMatchThreshold = it)) },
            )
            IntSetting(
                label = stringResource(R.string.setting_min_cluster_size),
                description = stringResource(R.string.setting_min_cluster_size_description),
                hint = stringResource(R.string.setting_min_cluster_size_hint),
                value = cfg.minClusterSize,
                default = SettingsRepository.DEFAULT_MIN_CLUSTER_SIZE,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(minClusterSize = it)) },
            )
            FloatSetting(
                label = stringResource(R.string.setting_time_weight),
                description = stringResource(R.string.setting_time_weight_description),
                hint = stringResource(R.string.setting_time_weight_hint),
                value = cfg.timeWeight,
                default = SettingsRepository.DEFAULT_TIME_WEIGHT,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(timeWeight = it)) },
            )
            FloatSetting(
                label = stringResource(R.string.setting_suggest_margin),
                description = stringResource(R.string.setting_suggest_margin_description),
                hint = stringResource(R.string.setting_suggest_margin_hint),
                value = cfg.suggestMargin,
                default = SettingsRepository.DEFAULT_SUGGEST_MARGIN,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(suggestMargin = it)) },
            )

            SwitchSetting(
                label = stringResource(R.string.setting_limit_suggestions),
                description = stringResource(R.string.setting_limit_suggestions_description),
                checked = cfg.limitSuggestions,
                onCheckedChange = { viewModel.setClusteringConfig(cfg.copy(limitSuggestions = it)) },
            )
            if (cfg.limitSuggestions) {
                IntSetting(
                    label = stringResource(R.string.setting_max_suggestions),
                    description = stringResource(R.string.setting_max_suggestions_description),
                    hint = stringResource(R.string.setting_max_suggestions_hint),
                    value = cfg.maxSuggestions,
                    default = SettingsRepository.DEFAULT_MAX_SUGGESTIONS,
                    onValueChange = { viewModel.setClusteringConfig(cfg.copy(maxSuggestions = it)) },
                )
            }

            // Section: confirmation behaviour
            SectionHeader(stringResource(R.string.settings_confirm_title))
            Text(
                text = stringResource(R.string.settings_confirm_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SwitchSetting(
                label = stringResource(R.string.setting_confirm_on_assign),
                description = stringResource(R.string.setting_confirm_on_assign_description),
                checked = state.confirmOnAssign,
                onCheckedChange = { viewModel.setConfirmOnAssign(it) },
            )
            SwitchSetting(
                label = stringResource(R.string.setting_confirm_on_name_suggestion),
                description = stringResource(R.string.setting_confirm_on_name_suggestion_description),
                checked = state.confirmOnNameSuggestion,
                onCheckedChange = { viewModel.setConfirmOnNameSuggestion(it) },
            )
            SwitchSetting(
                label = stringResource(R.string.setting_confirm_on_merge_suggestion),
                description = stringResource(R.string.setting_confirm_on_merge_suggestion_description),
                checked = state.confirmOnMergeSuggestion,
                onCheckedChange = { viewModel.setConfirmOnMergeSuggestion(it) },
            )
            SwitchSetting(
                label = stringResource(R.string.setting_auto_confirm_cluster),
                description = stringResource(R.string.setting_auto_confirm_cluster_description),
                checked = state.autoConfirmClusterMatches,
                onCheckedChange = { viewModel.setAutoConfirmClusterMatches(it) },
            )

            // Section: metadata
            SectionHeader(stringResource(R.string.settings_metadata_title))
            Text(
                text = stringResource(R.string.settings_metadata_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SwitchSetting(
                label = stringResource(R.string.setting_fill_missing_date),
                description = stringResource(R.string.setting_fill_missing_date_description),
                checked = state.fillMissingDate,
                onCheckedChange = { viewModel.setFillMissingDate(it) },
            )

            // Section: power
            SectionHeader(stringResource(R.string.settings_power_title))
            Text(
                text = stringResource(R.string.settings_power_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            val pwr = state.powerConfig
            val tempInFahrenheit =
                org.eidora.util.TemperatureUnit
                    .useFahrenheit(androidx.compose.ui.platform.LocalContext.current)
            val tempUnitLabel =
                stringResource(
                    if (tempInFahrenheit) R.string.unit_fahrenheit else R.string.unit_celsius,
                )

            // Battery thresholds together (pause, then resume) so the
            // hysteresis reads at a glance…
            IntSetting(
                label = stringResource(R.string.setting_min_battery_percent),
                description = stringResource(R.string.setting_min_battery_percent_description),
                value = pwr.minBatteryPercent,
                default = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                onValueChange = { viewModel.setPowerConfig(pwr.copy(minBatteryPercent = it)) },
            )
            IntSetting(
                label = stringResource(R.string.setting_resume_battery_percent),
                description = stringResource(R.string.setting_resume_battery_percent_description),
                value = pwr.resumeBatteryPercent,
                default = SettingsRepository.DEFAULT_RESUME_BATTERY_PERCENT,
                onValueChange = { viewModel.setPowerConfig(pwr.copy(resumeBatteryPercent = it)) },
            )

            // …then the temperature thresholds together (pause, then resume).
            FloatSetting(
                label = stringResource(R.string.setting_max_battery_temp, tempUnitLabel),
                description = stringResource(R.string.setting_max_battery_temp_description),
                value =
                    org.eidora.util.TemperatureUnit
                        .forDisplay(pwr.maxBatteryTempCelsius, tempInFahrenheit),
                default =
                    org.eidora.util.TemperatureUnit
                        .forDisplay(SettingsRepository.DEFAULT_MAX_BATTERY_TEMP, tempInFahrenheit),
                decimals = if (tempInFahrenheit) 0 else 1,
                onValueChange = { entered ->
                    val celsius =
                        org.eidora.util.TemperatureUnit
                            .fromInput(entered, tempInFahrenheit)
                    viewModel.setPowerConfig(pwr.copy(maxBatteryTempCelsius = celsius))
                },
            )
            FloatSetting(
                label = stringResource(R.string.setting_resume_battery_temp, tempUnitLabel),
                description = stringResource(R.string.setting_resume_battery_temp_description),
                value =
                    org.eidora.util.TemperatureUnit
                        .forDisplay(pwr.resumeBatteryTempCelsius, tempInFahrenheit),
                default =
                    org.eidora.util.TemperatureUnit
                        .forDisplay(SettingsRepository.DEFAULT_RESUME_BATTERY_TEMP, tempInFahrenheit),
                decimals = if (tempInFahrenheit) 0 else 1,
                onValueChange = { entered ->
                    val celsius =
                        org.eidora.util.TemperatureUnit
                            .fromInput(entered, tempInFahrenheit)
                    viewModel.setPowerConfig(pwr.copy(resumeBatteryTempCelsius = celsius))
                },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NavigationEntry(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
