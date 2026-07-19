package org.eidora

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import org.eidora.data.db.DatabaseProvider
import org.eidora.data.repository.FaceRepository
import org.eidora.ui.fullscreen.FullscreenPhotoScreen
import org.eidora.ui.fullscreen.FullscreenViewModel
import org.eidora.ui.persondetail.PersonDetailScreen
import org.eidora.ui.persondetail.PersonDetailViewModel
import org.eidora.ui.persons.PersonsScreen
import org.eidora.ui.persons.PersonsViewModel
import org.eidora.ui.persons.VIRTUAL_IGNORED
import org.eidora.ui.persons.VIRTUAL_UNKNOWN
import org.eidora.ui.photos.PhotosScreen
import org.eidora.ui.photos.PhotosViewModel
import org.eidora.ui.theme.EidoraTheme
import org.eidora.worker.SyncPipeline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat
            .setDecorFitsSystemWindows(window, false)
        setContent {
            EidoraTheme {
                EidoraApp()
            }
        }
    }
}

private fun requiredMediaPermission() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

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
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Re-check all permissions whenever the app returns to the foreground.
    // This catches the case where the user grants/revokes a permission in
    // the Android settings and comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasMedia = ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
                hasFiles = hasAllFilesAccess()
                hasNotifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMedia = it }
    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            hasFiles = hasAllFilesAccess()
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasNotifications = it
        }

    // Notification permission is optional – the app works without it,
    // only the progress notifications won't show. Media + file access are required.
    val hasRequiredPermissions = hasMedia && hasFiles

    // Enqueue the pipeline only once per app start, even if permissions
    // change multiple times or the composition recomposes.
    var pipelineEnqueued by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasRequiredPermissions, pipelineEnqueued) {
        if (hasRequiredPermissions && !pipelineEnqueued) {
            try {
                SyncPipeline.enqueue(context)
                pipelineEnqueued = true
            } catch (t: Throwable) {
                android.util.Log.e("FACES", "SyncPipeline failed", t)
            }
        }
    }

    // Once required permissions are in place, ask for the optional
    // notification permission a single time (Android shows its own dialog;
    // if the user denies, we never nag again from here).
    var notificationAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasRequiredPermissions, hasNotifications) {
        if (hasRequiredPermissions && !hasNotifications && !notificationAsked) {
            notificationAsked = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Track whether we've already asked for media permission, to detect
    // permanent denial (asked before + still not granted + system won't show dialog).
    val activity = context as? android.app.Activity
    var mediaAsked by rememberSaveable { mutableStateOf(false) }

    if (!hasRequiredPermissions) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    stringResource(R.string.permission_intro_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    stringResource(R.string.permission_intro_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp),
                )

                if (!hasMedia) {
                    Text(
                        stringResource(R.string.permission_photo_rationale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // Detect permanent denial: we asked, it's still denied, and the
                    // system no longer offers the rationale dialog.
                    val permanentlyDenied = mediaAsked && activity != null &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                    Button(
                        onClick = {
                            if (permanentlyDenied) {
                                // Send the user to the app settings to grant manually
                                filesLauncher.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            } else {
                                mediaAsked = true
                                mediaLauncher.launch(permission)
                            }
                        },
                        modifier = Modifier.padding(bottom = 16.dp),
                    ) {
                        Text(
                            if (permanentlyDenied) {
                                stringResource(R.string.permission_open_settings)
                            } else {
                                stringResource(R.string.permission_photo)
                            },
                        )
                    }
                }

                if (!hasFiles) {
                    Text(
                        stringResource(R.string.permission_files_rationale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Button(
                        onClick = {
                            filesLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(stringResource(R.string.permission_files))
                    }
                }
            }
        }
        return
    }

    val navController = rememberNavController()
    val currentRoute =
        navController
            .currentBackStackEntryAsState()
            .value
            ?.destination
            ?.route

    val personsVm: PersonsViewModel = viewModel()
    var menuExpanded by remember { mutableStateOf(false) }
    var showRejectAllConfirm by remember { mutableStateOf(false) }
    var showReanalyseAllConfirm by remember { mutableStateOf(false) }
    val reanalyseScope = rememberCoroutineScope()

    if (showRejectAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRejectAllConfirm = false },
            title = { Text(stringResource(R.string.action_reject_all_suggestions)) },
            text = { Text(stringResource(R.string.reject_all_suggestions_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    personsVm.rejectAllSuggestions()
                    showRejectAllConfirm = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showRejectAllConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showReanalyseAllConfirm) {
        AlertDialog(
            onDismissRequest = { showReanalyseAllConfirm = false },
            title = { Text(stringResource(R.string.action_reanalyse_all)) },
            text = { Text(stringResource(R.string.reanalyse_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showReanalyseAllConfirm = false
                    reanalyseScope.launch {
                        val repo =
                            org.eidora.data.repository.FaceRepository(
                                context,
                                DatabaseProvider.getInstance(context),
                            )
                        repo.resetAllFaces()
                        SyncPipeline.enqueueForce(context)
                    }
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showReanalyseAllConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    var showClusteringDialog by remember { mutableStateOf(false) }
    var clusteringRejectSuggestions by remember { mutableStateOf(false) }
    var clusteringRemoveUnconfirmed by remember { mutableStateOf(false) }

    if (showClusteringDialog) {
        AlertDialog(
            onDismissRequest = { showClusteringDialog = false },
            title = { Text(stringResource(R.string.action_start_clustering)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.clustering_dialog_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = clusteringRejectSuggestions,
                            onCheckedChange = { clusteringRejectSuggestions = it },
                        )
                        Text(
                            stringResource(R.string.clustering_option_reject_suggestions),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = clusteringRemoveUnconfirmed,
                            onCheckedChange = { clusteringRemoveUnconfirmed = it },
                        )
                        Text(
                            stringResource(R.string.clustering_option_remove_unconfirmed),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClusteringDialog = false
                    SyncPipeline.enqueueClustering(
                        context,
                        rejectSuggestions = clusteringRejectSuggestions,
                        removeUnconfirmed = clusteringRemoveUnconfirmed,
                    )
                }) { Text(stringResource(R.string.action_start)) }
            },
            dismissButton = {
                TextButton(onClick = { showClusteringDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            when (currentRoute) {
                "photos" -> {
                    // Photos screen: only Settings
                    var menuExpanded2 by remember { mutableStateOf(false) }
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = {
                            IconButton(onClick = { menuExpanded2 = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = menuExpanded2,
                                onDismissRequest = { menuExpanded2 = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_title)) },
                                    onClick = {
                                        menuExpanded2 = false
                                        navController.navigate("settings")
                                    },
                                )
                            }
                        },
                    )
                }
                "persons" -> {
                    // Persons screen: Clustering + destructive ops + Settings (last)
                    TopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        actions = {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_start_clustering)) },
                                    onClick = {
                                        menuExpanded = false
                                        clusteringRejectSuggestions = false
                                        clusteringRemoveUnconfirmed = false
                                        showClusteringDialog = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.action_reject_all_suggestions),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showRejectAllConfirm = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.action_reanalyse_all),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        showReanalyseAllConfirm = true
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings_title)) },
                                    onClick = {
                                        menuExpanded = false
                                        navController.navigate("settings")
                                    },
                                )
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == "persons",
                    onClick = { navController.navigate("persons") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.People, null) },
                    label = { Text(stringResource(R.string.nav_persons)) },
                )
                NavigationBarItem(
                    selected = currentRoute == "photos",
                    onClick = { navController.navigate("photos") { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Photo, null) },
                    label = { Text(stringResource(R.string.nav_photos)) },
                )
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "persons", modifier = Modifier.padding(padding)) {
            composable("persons") {
                PersonsScreen(
                    viewModel = personsVm,
                    onPersonClick = { navController.navigate("person_detail/$it") },
                    onPersonLongClick = { },
                )
            }
            composable(
                "person_detail/{personId}",
                listOf(navArgument("personId") { type = NavType.StringType }),
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
                    onNavigateToPerson = { targetId ->
                        navController.navigate("person_detail/$targetId") {
                            popUpTo("persons")
                        }
                    },
                    onShowPhotos = { pid -> navController.navigate("person_photos/$pid") },
                    onFaceClick = { faceId, photoId ->
                        navController.navigate("fullscreen/$photoId?faceId=$faceId")
                    },
                )
            }
            composable(
                "fullscreen/{photoId}?faceId={faceId}",
                listOf(
                    navArgument("photoId") { type = NavType.StringType },
                    navArgument("faceId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
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
                    onPhotoClick = { photoId, _ ->
                        navController.navigate("fullscreen/$photoId")
                    },
                )
            }
            composable("settings") {
                val vm: org.eidora.ui.settings.SettingsViewModel = viewModel()
                org.eidora.ui.settings.SettingsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                "person_photos/{personId}",
                listOf(navArgument("personId") { type = NavType.StringType }),
            ) { back ->
                val personId = back.arguments?.getString("personId") ?: return@composable
                val vm: PhotosViewModel = viewModel()
                LaunchedEffect(personId) { vm.loadForPerson(personId) }
                PhotosScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onPhotoClick = { photoId, faceId ->
                        if (faceId != null) {
                            navController.navigate("fullscreen/$photoId?faceId=$faceId")
                        } else {
                            navController.navigate("fullscreen/$photoId")
                        }
                    },
                )
            }
        }
    }
}
