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

/**
 * Returns the correct media-read permission for the running Android version.
 * Android 13+ (API 33+) uses READ_MEDIA_IMAGES, older versions use READ_EXTERNAL_STORAGE.
 */
private fun requiredMediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

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
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMediaPermission = granted
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasFilesAccess = hasAllFilesAccess()
    }

    val hasAllPermissions = hasMediaPermission && hasFilesAccess

    LaunchedEffect(hasAllPermissions) {
        if (hasAllPermissions) {
            SyncPipeline.enqueue(context)
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
                    onPersonClick = { personId ->
                        navController.navigate("person_detail/$personId")
                    },
                    onPersonLongClick = { _ -> /* selection handled in VM */ }
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
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Faces needs access to your photos to detect and organize faces, " +
                    "and file access to write face data back into your photos.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (needsMediaPermission) {
                Button(
                    onClick = onRequestMediaPermission,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text("Grant photo access")
                }
            }
            if (needsFilesAccess) {
                Button(onClick = onRequestFilesAccess) {
                    Text("Grant file access")
                }
            }
        }
    }
}
