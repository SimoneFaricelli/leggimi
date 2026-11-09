package it.faricelli.leggimi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import it.faricelli.leggimi.AppContainer
import it.faricelli.leggimi.LeggimiApp
import it.faricelli.leggimi.ui.library.LibraryScreen
import it.faricelli.leggimi.ui.picker.ModelPickerScreen
import it.faricelli.leggimi.ui.picker.VoicePickerScreen
import it.faricelli.leggimi.ui.reader.ReaderScreen
import it.faricelli.leggimi.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable object LibraryRoute
@Serializable data class ReaderRoute(val docId: String)
@Serializable object SettingsRoute
/** [docId] null = voce predefinita; altrimenti voce del singolo documento. */
@Serializable data class VoicePickerRoute(val docId: String? = null)
@Serializable data class ModelPickerRoute(val docId: String? = null)

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as LeggimiApp).container

@Composable
fun AppNavigation(mainViewModel: MainViewModel) {
    val nav = rememberNavController()

    LaunchedEffect(Unit) {
        mainViewModel.openDocument.collect { id ->
            nav.navigate(ReaderRoute(id)) { popUpTo(LibraryRoute) }
        }
    }

    NavHost(navController = nav, startDestination = LibraryRoute) {
        composable<LibraryRoute> {
            LibraryScreen(
                mainViewModel = mainViewModel,
                onOpen = { nav.navigate(ReaderRoute(it)) },
                onSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ReaderRoute> { entry ->
            val docId = entry.toRoute<ReaderRoute>().docId
            ReaderScreen(
                docId = docId,
                onBack = { nav.popBackStack() },
                onSettings = { nav.navigate(SettingsRoute) },
                onPickVoice = { nav.navigate(VoicePickerRoute(docId)) },
                onPickModel = { nav.navigate(ModelPickerRoute(docId)) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onPickVoice = { nav.navigate(VoicePickerRoute()) },
                onPickModel = { nav.navigate(ModelPickerRoute()) },
            )
        }
        composable<VoicePickerRoute> { entry ->
            VoicePickerScreen(docId = entry.toRoute<VoicePickerRoute>().docId, onDone = { nav.popBackStack() })
        }
        composable<ModelPickerRoute> { entry ->
            ModelPickerScreen(docId = entry.toRoute<ModelPickerRoute>().docId, onDone = { nav.popBackStack() })
        }
    }
}
