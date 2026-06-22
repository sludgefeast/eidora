package de.sebastian.faces

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import de.sebastian.faces.data.db.DatabaseProvider
import de.sebastian.faces.ui.theme.FacesTheme

// DIAGNOSTIC STEP 4: add Room database init
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                Step4Screen()
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
fun Step4Screen() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMedia by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasFiles by remember { mutableStateOf(hasAllFilesAccess()) }
    var dbStatus by remember { mutableStateOf("not initialized") }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMedia = it
    }

    LaunchedEffect(hasMedia, hasFiles) {
        if (hasMedia && hasFiles) {
            dbStatus = try {
                val db = DatabaseProvider.getInstance(context)
                val count = db.photoDao().getAllPaths().size
                "OK - ${count} photos in DB"
            } catch (t: Throwable) {
                Log.e("FACESDIAG", "DB init failed", t)
                "FAILED: ${t.message}"
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Text("Step 4: Room DB")
                Spacer(Modifier.height(8.dp))
                Text("Media: $hasMedia  |  Files: $hasFiles")
                Text("DB: $dbStatus")
                Spacer(Modifier.height(16.dp))
                if (!hasMedia) {
                    Button(onClick = { mediaLauncher.launch(permission) }) { Text("Grant photo access") }
                }
            }
        }
    }
}
