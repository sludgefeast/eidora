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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import de.sebastian.faces.ui.fullscreen.FullscreenPhotoScreen
import de.sebastian.faces.ui.fullscreen.FullscreenViewModel
import de.sebastian.faces.ui.persondetail.PersonDetailScreen
import de.sebastian.faces.ui.persondetail.PersonDetailViewModel
import de.sebastian.faces.ui.persons.VIRTUAL_IGNORED
import de.sebastian.faces.ui.persons.VIRTUAL_UNKNOWN
import de.sebastian.faces.ui.persons.PersonsScreen
import de.sebastian.faces.ui.persons.PersonsViewModel
import de.sebastian.faces.ui.theme.FacesTheme
import de.sebastian.faces.worker.SyncPipeline
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                FacesApp()
            }
        }
    }
}

private fun requiredMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesApp() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMediaPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasFilesAccess by remember { mutableStateOf(hasAllFilesAccess()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMediaPermission = granted }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasFilesAccess = hasAllFilesAccess() }

    val hasAllPermissions = hasMediaPermission && hasFilesAccess

    LaunchedEffect(hasAllPermissions) {
        if (hasAllPermissions) {
            try {
                Log.d("FACESDIAG", "Enqueueing SyncPipeline...")
                SyncPipeline.enqueue(context)
                Log.d("FACESDIAG", "SyncPipeline enqueued successfully")
            } catch (t: Throwable) {
                Log.e("FACESDIAG", "SyncPipeline.enqueue failed", t)
                try {
                    File(context.filesDir, "diag_sync_crash.txt")
                        .writeText("SyncPipeline.enqueue failed:\n${t.stackTraceToString()}")
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    if (!hasAllPermissions) {
        PermissionRequestScreen(
            needsMediaPermission = !hasMediaPermission,
            needsFilesAccess = !hasFilesAccess,
            onRequestMediaPermission = { permissionLauncher.launch(permission) },
            onRequestFilesAccess = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                manageStorageLauncher.launch(intent)
            }
        )
        return
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == "persons",
                    onClick = { navController.navigate("persons") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_persons)) }
                )
                NavigationBarItem(
                    selected = currentRoute == "photos",
                    onClick = { navController.navigate("photos") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Photo, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_photos)) }
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "persons",
            modifier = Modifier.padding(padding)
        ) {
            composable("persons") {
                val vm: PersonsViewModel = viewModel()
                PersonsScreen(
                    viewModel = vm,
                    onPersonClick = { navController.navigate("person_detail/$it") },
                    onPersonLongClick = { }
                )
            }
            composable(
                route = "person_detail/{personId}",
                arguments = listOf(navArgument("personId") { type = NavType.StringType })
            ) { backStackEntry ->
                val personId = backStackEntry.arguments?.getString("personId") ?: return@composable
                val vm: PersonDetailViewModel = viewModel()
                LaunchedEffect(personId) {
                    when (personId) {
                        VIRTUAL_UNKNOWN -> vm.loadUnknown()
                        VIRTUAL_IGNORED -> vm.loadIgnored()
                        else -> vm.load(personId)
                    }
                }
                PersonDetailScreen(
                    viewModel = vm,
                    onFaceClick = { faceRegionId, photoId ->
                        navController.navigate("fullscreen/$photoId?faceId=$faceRegionId")
                    }
                )
            }
            composable(
                route = "fullscreen/{photoId}?faceId={faceId}",
                arguments = listOf(
                    navArgument("photoId") { type = NavType.StringType },
                    navArgument("faceId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { backStackEntry ->
                val photoId = backStackEntry.arguments?.getString("photoId") ?: return@composable
                val faceId = backStackEntry.arguments?.getString("faceId")
                val vm: FullscreenViewModel = viewModel()
                LaunchedEffect(photoId) { vm.load(photoId) }
                FullscreenPhotoScreen(
                    viewModel = vm,
                    currentFaceRegionId = faceId,
                    onRedetect = {
                        vm.redetectFaces(photoId)
                        SyncPipeline.enqueueReSyncPhoto(navController.context, photoId)
                    }
                )
            }
            composable("photos") {
                Text("Photos coming soon")
            }
        }
    }
}

@Composable
private fun PermissionRequestScreen(
    needsMediaPermission: Boolean,
    needsFilesAccess: Boolean,
    onRequestMediaPermission: () -> Unit,
    onRequestFilesAccess: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Faces needs access to your photos and files to detect and organize faces.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (needsMediaPermission) {
                Button(
                    onClick = onRequestMediaPermission,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) { Text("Grant photo access") }
            }
            if (needsFilesAccess) {
                Button(onClick = onRequestFilesAccess) {
                    Text("Grant file access")
                }
            }
        }
    }
}
