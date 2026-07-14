package de.sebastian.eidora.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.sebastian.eidora.R
import de.sebastian.eidora.data.settings.ClusteringConfig
import de.sebastian.eidora.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var editedPatterns by remember(state.filenamePatterns) {
        mutableStateOf(state.filenamePatterns)
    }
    var newPattern by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.setPatterns(editedPatterns)
                        onBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // Section: filename patterns
            SectionHeader(stringResource(R.string.settings_filename_patterns_title))
            Text(
                text = stringResource(R.string.settings_filename_patterns_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            editedPatterns.forEachIndexed { index, pattern ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { new ->
                            editedPatterns = editedPatterns.toMutableList().apply {
                                set(index, new)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        editedPatterns = editedPatterns.toMutableList().apply { removeAt(index) }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = newPattern,
                    onValueChange = { newPattern = it },
                    placeholder = { Text("IMG_*") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    if (newPattern.isNotBlank()) {
                        editedPatterns = editedPatterns + newPattern.trim()
                        newPattern = ""
                    }
                }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
                }
            }

            Spacer(Modifier.height(24.dp))

            // Section: clustering
            SectionHeader(stringResource(R.string.settings_clustering_title))
            Text(
                text = stringResource(R.string.settings_clustering_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val cfg = state.clusteringConfig

            FloatSetting(
                label = stringResource(R.string.setting_edge_threshold),
                description = stringResource(R.string.setting_edge_threshold_description),
                hint = stringResource(R.string.setting_edge_threshold_hint),
                value = cfg.edgeThreshold,
                default = SettingsRepository.DEFAULT_EDGE_THRESHOLD,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(edgeThreshold = it)) }
            )
            FloatSetting(
                label = stringResource(R.string.setting_cluster_match_threshold),
                description = stringResource(R.string.setting_cluster_match_threshold_description),
                hint = stringResource(R.string.setting_cluster_match_threshold_hint),
                value = cfg.clusterMatchThreshold,
                default = SettingsRepository.DEFAULT_CLUSTER_MATCH_THRESHOLD,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(clusterMatchThreshold = it)) }
            )
            FloatSetting(
                label = stringResource(R.string.setting_individual_match_threshold),
                description = stringResource(R.string.setting_individual_match_threshold_description),
                hint = stringResource(R.string.setting_individual_match_threshold_hint),
                value = cfg.individualMatchThreshold,
                default = SettingsRepository.DEFAULT_INDIVIDUAL_MATCH_THRESHOLD,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(individualMatchThreshold = it)) }
            )
            IntSetting(
                label = stringResource(R.string.setting_min_cluster_size),
                description = stringResource(R.string.setting_min_cluster_size_description),
                hint = stringResource(R.string.setting_min_cluster_size_hint),
                value = cfg.minClusterSize,
                default = SettingsRepository.DEFAULT_MIN_CLUSTER_SIZE,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(minClusterSize = it)) }
            )
            FloatSetting(
                label = stringResource(R.string.setting_time_weight),
                description = stringResource(R.string.setting_time_weight_description),
                hint = stringResource(R.string.setting_time_weight_hint),
                value = cfg.timeWeight,
                default = SettingsRepository.DEFAULT_TIME_WEIGHT,
                onValueChange = { viewModel.setClusteringConfig(cfg.copy(timeWeight = it)) }
            )

            Spacer(Modifier.height(24.dp))

            // Section: power
            SectionHeader(stringResource(R.string.settings_power_title))
            Text(
                text = stringResource(R.string.settings_power_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val pwr = state.powerConfig
            IntSetting(
                label = stringResource(R.string.setting_min_battery_percent),
                description = stringResource(R.string.setting_min_battery_percent_description),
                value = pwr.minBatteryPercent,
                default = SettingsRepository.DEFAULT_MIN_BATTERY_PERCENT,
                onValueChange = { viewModel.setPowerConfig(pwr.copy(minBatteryPercent = it)) }
            )
            FloatSetting(
                label = stringResource(R.string.setting_max_battery_temp),
                description = stringResource(R.string.setting_max_battery_temp_description),
                value = pwr.maxBatteryTempCelsius,
                default = SettingsRepository.DEFAULT_MAX_BATTERY_TEMP,
                onValueChange = { viewModel.setPowerConfig(pwr.copy(maxBatteryTempCelsius = it)) }
            )

            Spacer(Modifier.height(24.dp))

            // Section: folder filter
            SectionHeader(stringResource(R.string.settings_folders_title))
            Text(
                text = stringResource(R.string.settings_folders_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            val context = androidx.compose.ui.platform.LocalContext.current
            LaunchedEffect(Unit) { viewModel.loadAvailableFolders(context) }

            if (state.availableFolders.isEmpty()) {
                Text(
                    stringResource(R.string.settings_folders_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val categories = listOf(
                    de.sebastian.eidora.data.settings.FolderCategory.CAMERA to stringResource(R.string.folder_category_camera),
                    de.sebastian.eidora.data.settings.FolderCategory.COMMON to stringResource(R.string.folder_category_common),
                    de.sebastian.eidora.data.settings.FolderCategory.APPS to stringResource(R.string.folder_category_apps),
                    de.sebastian.eidora.data.settings.FolderCategory.OTHER to stringResource(R.string.folder_category_other),
                )
                val grouped = state.availableFolders.groupBy {
                    de.sebastian.eidora.data.settings.SettingsRepository.categorize(it)
                }
                categories.forEach { (category, label) ->
                    val folders = grouped[category] ?: return@forEach
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    folders.forEach { folder ->
                        val isIncluded = folder in state.folderWhitelist
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = isIncluded,
                                onCheckedChange = { included ->
                                    val newWl = if (included)
                                        state.folderWhitelist + folder
                                    else
                                        state.folderWhitelist - folder
                                    viewModel.setFolderWhitelist(newWl)
                                }
                            )
                            Text(
                                text = folder,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun FloatSetting(
    label: String,
    description: String,
    value: Float,
    default: Float,
    hint: String? = null,
    onValueChange: (Float) -> Unit
) {
    var text by remember(value) { mutableStateOf("%.2f".format(value)) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
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
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.setting_default_hint, "%.2f".format(default)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
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
    onValueChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp)
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
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.setting_default_hint, default.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
            IconButton(onClick = { onValueChange(default) }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.setting_reset_to_default))
            }
        }
    }
}
