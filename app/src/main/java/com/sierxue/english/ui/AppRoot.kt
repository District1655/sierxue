package com.sierxue.english.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sierxue.english.di.AppContainer
import com.sierxue.english.di.Initialization
import com.sierxue.english.ui.components.AssistantOverlay
import com.sierxue.english.ui.screens.CatalogScreen
import com.sierxue.english.ui.screens.ExamScreen
import com.sierxue.english.ui.screens.ExamSetupScreen
import com.sierxue.english.ui.screens.HomeScreen
import com.sierxue.english.ui.screens.LessonScreen
import com.sierxue.english.ui.screens.PracticeScreen
import com.sierxue.english.ui.screens.ReportScreen
import com.sierxue.english.ui.screens.ReviewScreen
import com.sierxue.english.ui.screens.SectionScreen
import com.sierxue.english.ui.screens.SettingsScreen
import com.sierxue.english.ui.screens.StatsScreen
import com.sierxue.english.ui.screens.WrongBookScreen
import com.sierxue.english.ui.viewmodel.AssistantViewModel

@Composable
fun SiErXueApp(container: AppContainer) {
    val initialization by container.initialization.collectAsStateWithLifecycle()
    when (val state = initialization) {
        Initialization.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Initialization.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface { Text("课程导入失败：${state.message}", color = MaterialTheme.colorScheme.error) }
        }
        Initialization.Ready -> ReadyApp(container)
    }
}

@Composable
private fun ReadyApp(container: AppContainer) {
    val nav = rememberNavController()
    val assistantVm: AssistantViewModel = viewModel(factory = container.viewModelFactory)
    val back: () -> Unit = { nav.popBackStack() }
    val home: () -> Unit = {
        nav.navigate("home") { popUpTo("home") { inclusive = true }; launchSingleTop = true }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
            composable("home") { HomeScreen(onNavigate = { nav.navigate(it) }) }
            composable("catalog") { CatalogScreen(onBack = back, onLesson = { nav.navigate("lesson/$it") }) }
            composable("lesson/{unitId}", arguments = listOf(navArgument("unitId") { type = NavType.StringType })) { entry ->
                LessonScreen(entry.arguments?.getString("unitId").orEmpty(), back, onNavigate = { nav.navigate(it) })
            }
            composable("section/{sectionId}", arguments = listOf(navArgument("sectionId") { type = NavType.StringType })) { entry ->
                SectionScreen(entry.arguments?.getString("sectionId").orEmpty(), back, onPractice = { nav.navigate("practice_section/$it") })
            }
            composable("practice/{unitId}", arguments = listOf(navArgument("unitId") { type = NavType.StringType })) { entry ->
                PracticeScreen(unitId = entry.arguments?.getString("unitId"), onBack = back)
            }
            composable("practice_section/{sectionId}", arguments = listOf(navArgument("sectionId") { type = NavType.StringType })) { entry ->
                PracticeScreen(sectionId = entry.arguments?.getString("sectionId"), onBack = back)
            }
            composable("wrong") { WrongBookScreen(back, onQuestion = { nav.navigate("wrong_review/$it") }) }
            composable("wrong_review/{questionId}", arguments = listOf(navArgument("questionId") { type = NavType.StringType })) { entry ->
                PracticeScreen(wrongQuestionId = entry.arguments?.getString("questionId"), onBack = back)
            }
            composable("review") { ReviewScreen(back) }
            composable("exam_setup") { ExamSetupScreen(back, onStart = { nav.navigate("exam/$it") }) }
            composable("exam/{examId}", arguments = listOf(navArgument("examId") { type = NavType.StringType })) { entry ->
                ExamScreen(entry.arguments?.getString("examId").orEmpty(), back, onReport = { id ->
                    nav.navigate("report/$id") { popUpTo("exam_setup") }
                })
            }
            composable("report/{examId}", arguments = listOf(navArgument("examId") { type = NavType.StringType })) { entry ->
                ReportScreen(entry.arguments?.getString("examId").orEmpty(), home)
            }
            composable("stats") { StatsScreen(back, onReport = { nav.navigate("report/$it") }) }
            composable("settings") { SettingsScreen(back) }
        }
        AssistantOverlay(assistantVm)
    }
}
