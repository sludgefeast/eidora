package de.sebastian.eidora

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
import de.sebastian.eidora.ui.fullscreen.FullscreenPhotoScreen
import de.sebastian.eidora.ui.fullscreen.FullscreenViewModel
import de.sebastian.eidora.ui.persondetail.PersonDetailScreen
import de.sebastian.eidora.ui.persondetail.PersonDetailViewModel
import de.sebastian.eidora.ui.persons.VIRTUAL_IGNORED
import de.sebastian.eidora.ui.persons.VIRTUAL_UNKNOWN
import de.sebastian.eidora.ui.persons.PersonsScreen
import de.sebastian.eidora.ui.persons.PersonsViewModel
import de.sebastian.eidora.ui.photos.PhotosScreen
import de.sebastian.eidora.ui.photos.PhotosViewModel
import de.sebastian.eidora.ui.theme.EidoraTheme
import de.sebastian.eidora.worker.SyncPipeline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            EidoraTheme {
                EidoraApp()
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
fun EidoraApp() {
    val context = LocalContext.current
    val permission = remember { requiredMediaPermission() }

    var hasMedia by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var hasFiles by remember { mutableStateOf(hasAllFilesAccess()) }
    var hasNotifications by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMedia = it }
    val filesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasFiles = hasAllFilesAccess()
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasNotifications = it
    }

    val hasAllPermissions = hasMedia && hasFiles && hasNotifications

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
                Text(
                    "Faces needs the following permissions to work:",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                if (!hasMedia) {
                    Button(onClick = { mediaLauncher.launch(permission) }, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Grant photo access")
                    }
                }
                if (!hasFiles) {
                    Button(onClick = {
                        filesLauncher.launch(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        ))
                    }, modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("Grant file access")
                    }
                }
                if (!hasNotifications) {
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }) {
                        Text("Grant notification access")
                    }
                }
            }
        }
        return
    }

    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(
        modifier = Modifier.imePadding(),
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
                PersonDetailScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onFaceClick = { faceId, photoId ->
                        navController.navigate("fullscreen/$photoId?faceId=$faceId")
                    }
                )
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
                val vm: PhotosViewModel = viewModel()
                PhotosScreen(
                    viewModel = vm,
                    onPhotoClick = { photoId ->
                        navController.navigate("fullscreen/$photoId")
                    }
                )
            }
        }
    }
}
