package com.parth.mobileclaw

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
import com.parth.mobileclaw.engine.AgentOrchestrator
import com.parth.mobileclaw.ui.ChatScreen
import com.parth.mobileclaw.ui.OnboardingScreen
import com.parth.mobileclaw.ui.SettingsScreen
import com.parth.mobileclaw.ui.SkillBrowserScreen
import com.parth.mobileclaw.ui.theme.MobileClawTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MobileClawTheme {
                val orchestrator: AgentOrchestrator = viewModel()
                val navController = rememberNavController()

                val prefs = getSharedPreferences("mobileclaw_prefs", MODE_PRIVATE)
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
                            onAddSkill = { navController.navigate("skills?showInstall=true") }
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
