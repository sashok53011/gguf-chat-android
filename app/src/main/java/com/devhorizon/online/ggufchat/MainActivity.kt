package com.devhorizon.online.ggufchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devhorizon.online.ggufchat.ui.components.DrawerContent
import com.devhorizon.online.ggufchat.ui.screens.ChatScreen
import com.devhorizon.online.ggufchat.ui.screens.ModelPickerScreen
import com.devhorizon.online.ggufchat.ui.screens.SettingsScreen
import com.devhorizon.online.ggufchat.ui.theme.AppTheme
import com.devhorizon.online.ggufchat.ui.theme.GGUFChatTemplateTheme
import com.devhorizon.online.ggufchat.ui.theme.Localization
import com.devhorizon.online.ggufchat.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppNavigation()
        }
    }
}

@Composable
fun AppNavigation() {
    val viewModel: ChatViewModel = viewModel()
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val lang by viewModel.appLanguage.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val l = { key: String -> Localization.getString(key, lang) }

    val theme = when (appTheme) {
        "dark" -> AppTheme.DARK
        "green" -> AppTheme.GREEN
        "orange" -> AppTheme.ORANGE
        "light_blue" -> AppTheme.LIGHT_BLUE
        "yellow" -> AppTheme.YELLOW
        else -> AppTheme.LIGHT
    }

    GGUFChatTemplateTheme(appTheme = theme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppContent(viewModel, navController, drawerState, scope, l)
        }
    }
}

@Composable
private fun AppContent(
    viewModel: ChatViewModel,
    navController: androidx.navigation.NavHostController,
    drawerState: androidx.compose.material3.DrawerState,
    scope: kotlinx.coroutines.CoroutineScope,
    l: (String) -> String
) {
    val sessions by viewModel.chatRepository.sessions.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    sessions = sessions,
                    currentSessionId = viewModel.chatRepository.currentSessionId.collectAsState().value,
                    l = l,
                    onNewChat = {
                        viewModel.createNewSession()
                        scope.launch { drawerState.close() }
                    },
                    onSelectSession = { id ->
                        viewModel.selectSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSession = { id ->
                        viewModel.deleteSession(id)
                    },
                    onOpenModels = {
                        scope.launch { drawerState.close() }
                        navController.navigate("models")
                    },
                    onOpenSettings = {
                        scope.launch { drawerState.close() }
                        navController.navigate("settings")
                    }
                )
            }
        }
    ) {
        NavHost(navController = navController, startDestination = "chat") {
            composable("chat") {
                ChatScreen(
                    viewModel = viewModel,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { navController.navigate("settings") }
                )
            }
            composable("models") {
                ModelPickerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
