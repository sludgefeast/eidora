// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sebastian (Eidora contributors)

package org.eidora.ui.about

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eidora.BuildConfig
import org.eidora.R
import org.eidora.util.LogExporter

private const val REPO_URL = "https://github.com/sludgefeast/eidora"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showRangeDialog by remember { mutableStateOf(false) }
    var pendingRange by remember { mutableStateOf(LogExporter.Range.LAST_DAY) }

    // User picks the destination; we then write the collected log into it.
    val saveLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        try {
                            val text = LogExporter.collect(context, pendingRange)
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(text.toByteArray())
                            }
                            true
                        } catch (t: Throwable) {
                            false
                        }
                    }
                Toast
                    .makeText(
                        context,
                        context.getString(
                            if (ok) R.string.about_logs_saved else R.string.about_logs_failed,
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // The whole screen is one scrollable list: the header, the actions and
        // the library list share a single LazyColumn (via LibrariesContainer's
        // header slot), so everything scrolls together instead of only the
        // library list moving under a fixed header.
        LibrariesContainer(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            header = {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_eidora_logo),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(88.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.about_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.about_license_line),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.about_models_line),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    HorizontalDivider()

                    AboutAction(
                        icon = Icons.Default.Code,
                        title = stringResource(R.string.about_source_code),
                        subtitle = REPO_URL.removePrefix("https://"),
                        onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                            } catch (t: Throwable) {
                                Toast
                                    .makeText(context, R.string.about_no_browser, Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    )

                    AboutAction(
                        icon = Icons.Default.BugReport,
                        title = stringResource(R.string.about_export_logs),
                        subtitle = stringResource(R.string.about_export_logs_description),
                        onClick = { showRangeDialog = true },
                    )

                    HorizontalDivider()

                    Text(
                        stringResource(R.string.about_libraries_header),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            },
        )
    }

    if (showRangeDialog) {
        AlertDialog(
            onDismissRequest = { showRangeDialog = false },
            title = { Text(stringResource(R.string.about_export_logs)) },
            text = {
                Column {
                    Text(stringResource(R.string.about_logs_range_message))
                    Spacer(Modifier.height(12.dp))
                    LogExporter.Range.entries.forEach { range ->
                        TextButton(
                            onClick = {
                                pendingRange = range
                                showRangeDialog = false
                                saveLauncher.launch(LogExporter.suggestedFileName())
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    when (range) {
                                        LogExporter.Range.LAST_HOUR -> R.string.about_logs_range_hour
                                        LogExporter.Range.LAST_DAY -> R.string.about_logs_range_day
                                        LogExporter.Range.EVERYTHING -> R.string.about_logs_range_all
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRangeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AboutAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
