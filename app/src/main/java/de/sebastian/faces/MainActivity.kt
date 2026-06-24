package de.sebastian.faces

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import de.sebastian.faces.ui.persons.PersonsScreen
import de.sebastian.faces.ui.persons.PersonsViewModel
import de.sebastian.faces.ui.theme.FacesTheme
import de.sebastian.faces.worker.SyncPipeline

// DIAGNOSTIC STEP 6c: add PersonsScreen
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                Step6cApp()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Step6cApp() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMedia by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasFiles by remember { mutableStateOf(hasAllFilesAccess()) }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMedia = it }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasFiles = hasAllFilesAccess()
    }

    val hasAllPermissions = hasMedia && hasFiles

    LaunchedEffect(hasAllPermissions) {
        if (hasAllPermissions) {
            try { SyncPipeline.enqueue(context) } catch (t: Throwable) {
                android.util.Log.e("FACES", "SyncPipeline failed", t)
            }
        }
    }

    if (!hasAllPermissions) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                if (!hasMedia) Button(onClick = { mediaLauncher.launch(permission) }) { Text("Grant photo access") }
                if (!hasFiles) Button(onClick = {
                    filesLauncher.launch(Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ))
                }) { Text("Grant file access") }
            }
        }
        return
    }

    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == "persons",
                    onClick = { navController.navigate("persons") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.People, null) },
                    label = { Text("People") }
                )
                NavigationBarItem(
                    selected = currentRoute == "photos",
                    onClick = { navController.navigate("photos") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Photo, null) },
                    label = { Text("Photos") }
                )
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "persons", modifier = Modifier.padding(padding)) {
            composable("persons") {
                val vm: PersonsViewModel = viewModel()
                PersonsScreen(
                    viewModel = vm,
                    onPersonClick = { },
                    onPersonLongClick = { }
                )
            }
            composable("photos") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Photos tab")
                }
            }
        }
    }
}
