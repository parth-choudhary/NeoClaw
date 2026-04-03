package com.parth.neoclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.parth.neoclaw.engine.AgentOrchestrator
import com.parth.neoclaw.ui.ChatScreen
import com.parth.neoclaw.ui.OnboardingScreen
import com.parth.neoclaw.ui.SettingsScreen
import com.parth.neoclaw.ui.SkillBrowserScreen
import com.parth.neoclaw.ui.BrowserLoginScreen
import com.parth.neoclaw.ui.theme.NeoClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NeoClawTheme {
                val orchestrator: AgentOrchestrator = viewModel()
                val navController = rememberNavController()

                val prefs = getSharedPreferences("neoclaw_prefs", MODE_PRIVATE)
                val onboardingComplete = prefs.getBoolean("onboarding_complete", false)
                val startDest = if (onboardingComplete) "chat" else "onboarding"

                NavHost(navController = navController, startDestination = startDest) {
                    composable("onboarding") {
                        OnboardingScreen(
                            orchestrator = orchestrator,
                            onComplete = {
                                orchestrator.triggerBootstrapSequence()
                                navController.navigate("chat") {
                                    popUpTo("onboarding") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("chat") {
                        ChatScreen(
                            orchestrator = orchestrator,
                            onOpenSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            orchestrator = orchestrator,
                            onBack = { navController.popBackStack() },
                            onOpenSkills = { navController.navigate("skills") },
                            onAddSkill = { navController.navigate("skills?showInstall=true") },
                            onOpenBrowserLogin = { navController.navigate("browser_login") }
                        )
                    }
                    composable("browser_login") {
                        BrowserLoginScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable(
                        route = "skills?showInstall={showInstall}",
                        arguments = listOf(navArgument("showInstall") { defaultValue = false })
                    ) { backStackEntry ->
                        val showInstall = backStackEntry.arguments?.getBoolean("showInstall") ?: false
                        SkillBrowserScreen(
                            orchestrator = orchestrator,
                            onBack = { navController.popBackStack() },
                            initialShowInstallSheet = showInstall
                        )
                    }
                }
            }
        }
    }
}
