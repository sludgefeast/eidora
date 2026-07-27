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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.eidora.R
import org.eidora.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenModels: () -> Unit = {},
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
            Text(
                text = stringResource(R.string.settings_folders_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) { viewModel.loadAvailableFolders(context) }

            if (state.availableFolders.isEmpty()) {
                Text(
                    stringResource(R.string.settings_folders_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val categories =
                    listOf(
                        org.eidora.data.settings.FolderCategory.CAMERA to
                            stringResource(R.string.folder_category_camera),
                        org.eidora.data.settings.FolderCategory.COMMON to
                            stringResource(R.string.folder_category_common),
                        org.eidora.data.settings.FolderCategory.APPS to stringResource(R.string.folder_category_apps),
                        org.eidora.data.settings.FolderCategory.OTHER to stringResource(R.string.folder_category_other),
                    )
                val grouped =
                    state.availableFolders.groupBy {
                        org.eidora.data.settings.SettingsRepository
                            .categorize(it)
                    }
                // "Sonstiges" starts collapsed; all others start expanded
                val collapsedByDefault = setOf(org.eidora.data.settings.FolderCategory.OTHER)
                val expandedCategories =
                    remember {
                        androidx.compose.runtime
                            .mutableStateMapOf<org.eidora.data.settings.FolderCategory, Boolean>()
                            .apply {
                                categories.forEach { (cat, _) -> put(cat, cat !in collapsedByDefault) }
                            }
                    }
                categories.forEach { (category, label) ->
                    val folders = grouped[category] ?: return@forEach
                    val isExpanded = expandedCategories[category] == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp, bottom = 2.dp)
                                .clickable { expandedCategories[category] = !isExpanded },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector =
                                if (isExpanded) {
                                    Icons.Default.ExpandLess
                                } else {
                                    Icons.Default.ExpandMore
                                },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (isExpanded) {
                        folders.forEach { folder ->
                            val isIncluded = folder in state.folderWhitelist
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                            ) {
                                Checkbox(
                                    checked = isIncluded,
                                    onCheckedChange = { included ->
                                        val newWl =
                                            if (included) {
                                                state.folderWhitelist + folder
                                            } else {
                                                state.folderWhitelist - folder
                                            }
                                        viewModel.setFolderWhitelist(newWl)
                                    },
                                )
                                Text(
                                    text = folder,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            val cleanupContext = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
                onClick = {
                    viewModel.cleanupExcludedFolders { removed ->
                        android.widget.Toast
                            .makeText(
                                cleanupContext,
                                cleanupContext.getString(R.string.settings_folders_cleanup_done, removed),
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                Text(stringResource(R.string.settings_folders_cleanup))
            }
            Text(
                text = stringResource(R.string.settings_folders_cleanup_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

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

            SectionHeader(stringResource(R.string.settings_detection_title))
            SectionDescription(stringResource(R.string.settings_detection_description))
            DetectionSetting(
                currentId = state.detectionModelId,
                onSelect = { spec, redetect -> viewModel.switchDetectionModel(spec, redetect) },
            )

            SectionHeader(stringResource(R.string.settings_model_title))
            SectionDescription(stringResource(R.string.settings_model_description))
            ModelSetting(
                currentId = state.embeddingModelId,
                onSelect = { spec -> viewModel.switchEmbeddingModel(spec) },
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
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(
    text: String,
    first: Boolean = false,
) {
    // A divider above each section (except the first) plus generous top space
    // gives clear visual grouping without boxing everything in cards.
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

/** Explanatory line under a section header. Keeps sections visually consistent. */
@Composable
private fun SectionDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

@Composable
private fun SwitchSetting(
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

@Composable
private fun FloatSetting(
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
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.setting_reset_to_default))
            }
        }
    }
}

@Composable
private fun IntSetting(
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
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
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
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.setting_reset_to_default))
            }
        }
    }
}

/**
 * Embedding-model picker. Selecting a different model shows a confirmation
 * dialog first, because switching recomputes every face embedding. The choice
 * is applied only after the user confirms.
 */
/**
 * Shows a model's effective license clearly: the license name, and one line on
 * why. Restricted (non-free) models are visually flagged and carry an extra
 * note that they are not in F-Droid builds. This is the single place the app
 * explains licensing, so the user decides with full information.
 */
@Composable
private fun ModelLicenseRow(license: org.eidora.ml.ModelLicense) {
    val accent =
        if (license.isFree) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        }
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Text(
            text =
                stringResource(R.string.model_license_label) +
                    ": " + stringResource(license.effectiveNameRes),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(license.reasonRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!license.isFree) {
            Text(
                text = stringResource(R.string.license_restricted_note),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
            )
        }
    }
}

@Composable
private fun ModelSetting(
    currentId: String,
    onSelect: (org.eidora.ml.EmbeddingModelSpec) -> Unit,
) {
    val specs = org.eidora.ml.EmbeddingModelSpec.ALL

    // The model the user tapped but hasn't confirmed yet.
    var pending by remember { mutableStateOf<org.eidora.ml.EmbeddingModelSpec?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            val name =
                when (spec.id) {
                    org.eidora.ml.EmbeddingModelSpec.ARCFACE.id -> stringResource(R.string.model_arcface_name)
                    else -> stringResource(R.string.model_sface_name)
                }
            val desc =
                when (spec.id) {
                    org.eidora.ml.EmbeddingModelSpec.ARCFACE.id -> stringResource(R.string.model_arcface_desc)
                    else -> stringResource(R.string.model_sface_desc)
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (spec.id != currentId) pending = spec
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = spec.id == currentId,
                    onClick = {
                        if (spec.id != currentId) pending = spec
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelLicenseRow(spec.license)
                }
            }
        }
    }

    val target = pending
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.model_switch_warning_title)) },
            text = { Text(stringResource(R.string.model_switch_warning_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onSelect(target)
                }) {
                    Text(stringResource(R.string.model_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Detection-model picker. Mirrors [ModelSetting]: selecting a different model
 * shows a confirmation dialog, because switching re-detects all photos.
 */
@Composable
private fun DetectionSetting(
    currentId: String,
    onSelect: (org.eidora.ml.DetectionModelSpec, Boolean) -> Unit,
) {
    val specs = org.eidora.ml.DetectionModelSpec.ALL

    var pending by remember { mutableStateOf<org.eidora.ml.DetectionModelSpec?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        specs.forEach { spec ->
            val name =
                when (spec.id) {
                    org.eidora.ml.DetectionModelSpec.SCRFD.id -> stringResource(R.string.detection_scrfd_name)
                    else -> stringResource(R.string.detection_yunet_name)
                }
            val desc =
                when (spec.id) {
                    org.eidora.ml.DetectionModelSpec.SCRFD.id -> stringResource(R.string.detection_scrfd_desc)
                    else -> stringResource(R.string.detection_yunet_desc)
                }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (spec.id != currentId) pending = spec
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = spec.id == currentId,
                    onClick = {
                        if (spec.id != currentId) pending = spec
                    },
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ModelLicenseRow(spec.license)
                }
            }
        }
    }

    val target = pending
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.detection_switch_title)) },
            text = { Text(stringResource(R.string.detection_switch_body)) },
            // Two actions stacked: keep existing faces, or re-scan all photos.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        pending = null
                        onSelect(target, false) // keep existing faces
                    }) {
                        Text(stringResource(R.string.detection_switch_keep))
                    }
                    TextButton(onClick = {
                        pending = null
                        onSelect(target, true) // re-detect all photos
                    }) {
                        Text(stringResource(R.string.detection_switch_redetect))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
