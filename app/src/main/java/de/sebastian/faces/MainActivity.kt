package de.sebastian.faces

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import de.sebastian.faces.ui.theme.FacesTheme
import de.sebastian.faces.worker.SyncPipeline

// DIAGNOSTIC STEP 5: add WorkManager / SyncPipeline
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                Step5Screen()
            }
        }
    }
}

private fun requiredMediaPermission() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAllFilesAccess() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

@Composable
fun Step5Screen() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMedia by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasFiles by remember { mutableStateOf(hasAllFilesAccess()) }
    var syncStatus by remember { mutableStateOf("not started") }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMedia = it
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasFiles = hasAllFilesAccess()
    }

    LaunchedEffect(hasMedia, hasFiles) {
        if (hasMedia && hasFiles) {
            syncStatus = try {
                SyncPipeline.enqueue(context)
                "enqueued OK"
            } catch (t: Throwable) {
                Log.e("FACESDIAG", "SyncPipeline failed", t)
                "FAILED: ${t.message}"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("Step 5: WorkManager")
                Spacer(Modifier.height(8.dp))
                Text("Media: $hasMedia  |  Files: $hasFiles")
                Text("Sync: $syncStatus")
                Spacer(Modifier.height(16.dp))
                if (!hasMedia) {
                    Button(onClick = { mediaLauncher.launch(permission) }) {
                        Text("Grant photo access")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (!hasFiles) {
                    Button(onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        filesLauncher.launch(intent)
                    }) {
                        Text("Grant file access")
                    }
                }
            }
        }
    }
}
