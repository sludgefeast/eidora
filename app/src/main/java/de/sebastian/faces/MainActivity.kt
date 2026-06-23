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

private fun requiredMediaPermission() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasAllFilesAccess() =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesApp() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMedia by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasFiles by remember { mutableStateOf(hasAllFilesAccess()) }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasMedia = it
    }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasFiles = hasAllFilesAccess()
    }

    val hasAllPermissions = hasMedia && hasFiles

    LaunchedEffect(hasAllPermissions) {
        if (hasAllPermissions) {
            try { SyncPipeline.enqueue(context) } catch (t: Throwable) {
                android.util.Log.e("FACES", "SyncPipeline.enqueue failed", t)
            }
        }
    }

    if (!hasAllPermissions) {
        PermissionRequestScreen(
            needsMedia = !hasMedia,
            needsFiles = !hasFiles,
            onRequestMedia = { mediaLauncher.launch(permission) },
            onRequestFiles = {
                filesLauncher.launch(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ))
            }
        )
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
                    label = { Text(stringResource(R.string.nav_persons)) }
                )
                NavigationBarItem(
                    selected = currentRoute == "photos",
                    onClick = { navController.navigate("photos") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Photo, null) },
                    label = { Text(stringResource(R.string.nav_photos)) }
                )
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "persons", modifier = Modifier.padding(padding)) {
            composable("persons") {
                val vm: PersonsViewModel = viewModel()
                PersonsScreen(
                    viewModel = vm,
                    onPersonClick = { navController.navigate("person_detail/$it") },
                    onPersonLongClick = { }
                )
            }
            composable(
                "person_detail/{personId}",
                listOf(navArgument("personId") { type = NavType.StringType })
            ) { back ->
                val personId = back.arguments?.getString("personId") ?: return@composable
                val vm: PersonDetailViewModel = viewModel()
                LaunchedEffect(personId) {
                    when (personId) {
                        VIRTUAL_UNKNOWN -> vm.loadUnknown()
                        VIRTUAL_IGNORED -> vm.loadIgnored()
                        else -> vm.load(personId)
                    }
                }
                PersonDetailScreen(vm) { faceId, photoId ->
                    navController.navigate("fullscreen/$photoId?faceId=$faceId")
                }
            }
            composable(
                "fullscreen/{photoId}?faceId={faceId}",
                listOf(
                    navArgument("photoId") { type = NavType.StringType },
                    navArgument("faceId") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) { back ->
                val photoId = back.arguments?.getString("photoId") ?: return@composable
                val faceId = back.arguments?.getString("faceId")
                val vm: FullscreenViewModel = viewModel()
                LaunchedEffect(photoId) { vm.load(photoId) }
                FullscreenPhotoScreen(vm, faceId) {
                    vm.redetectFaces(photoId)
                    SyncPipeline.enqueueReSyncPhoto(navController.context, photoId)
                }
            }
            composable("photos") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Photos coming soon")
                }
            }
        }
    }
}

@Composable
private fun PermissionRequestScreen(
    needsMedia: Boolean,
    needsFiles: Boolean,
    onRequestMedia: () -> Unit,
    onRequestFiles: () -> Unit
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(
                "Faces needs access to your photos and files.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (needsMedia) {
                Button(onClick = onRequestMedia, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text("Grant photo access")
                }
            }
            if (needsFiles) {
                Button(onClick = onRequestFiles) {
                    Text("Grant file access")
                }
            }
        }
    }
}
