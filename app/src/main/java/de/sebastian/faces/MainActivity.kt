package de.sebastian.faces

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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

        // Trigger sync pipeline on every app start
        SyncPipeline.enqueue(this)

        setContent {
            FacesTheme {
                FacesApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesApp() {
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
                // Photos tab placeholder
                Text("Photos coming soon")
            }
        }
    }
}
