package com.example.myapplication.pw5.composables

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.pw5.data.FacilityCatalog
import com.example.myapplication.pw5.data.OperatorSession
import com.example.myapplication.pw5.data.Shift
import com.example.myapplication.pw5.navigation.Screen
import com.example.myapplication.pw5.screens.FacilityDetailsScreen
import com.example.myapplication.pw5.screens.FacilityListScreen
import com.example.myapplication.pw5.screens.MainMenuScreen
import com.example.myapplication.pw5.screens.OperatorProfileScreen
import com.example.myapplication.pw5.screens.ReadinessSummaryScreen

/** Аргумент «режим розрахунку» (необов'язковий, за замовчуванням — усе навантаження). */
private val criticalOnlyArgument = navArgument(Screen.ARG_CRITICAL_ONLY) {
    type = NavType.BoolType
    defaultValue = false
}

private fun NavBackStackEntry.criticalOnly(): Boolean =
    arguments?.getBoolean(Screen.ARG_CRITICAL_ONLY) ?: false

/**
 * ПЗ 5, просунутий рівень: головне меню та навігація між екранами (Navigation Compose).
 *
 * Навігаційний граф:
 *  - MainMenu → FacilityList → FacilityDetails/{facilityId} (дані передаються аргументами маршруту);
 *  - MainMenu → ReadinessSummary → FacilityDetails/{facilityId};
 *  - MainMenu → OperatorProfile → результат повертається в MainMenu через savedStateHandle,
 *    і головне меню оновлюється автоматично (getStateFlow + collectAsStateWithLifecycle).
 *
 * Екрани не залежать від NavController — вони отримують дані та лямбди переходів,
 * а вся навігаційна логіка зосереджена тут.
 */
@Composable
fun EmergencyNavApp(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Screen.MainMenu.route,
        modifier = Modifier.fillMaxSize()
    ) {
        // Головне меню: стан оператора читається з власного savedStateHandle
        composable(Screen.MainMenu.route) { entry ->
            val handle = entry.savedStateHandle
            val name by handle.getStateFlow(Screen.RESULT_NAME, "").collectAsStateWithLifecycle()
            val shift by handle.getStateFlow(Screen.RESULT_SHIFT, Shift.DAY.name).collectAsStateWithLifecycle()
            val criticalOnly by handle.getStateFlow(Screen.RESULT_CRITICAL_ONLY, false).collectAsStateWithLifecycle()
            val session = OperatorSession(name, Shift.valueOf(shift), criticalOnly)

            MainMenuScreen(
                session = session,
                onOpenFacilities = { navController.navigate(Screen.FacilityList.createRoute(session.criticalOnly)) },
                onOpenSummary = { navController.navigate(Screen.ReadinessSummary.createRoute(session.criticalOnly)) },
                onOpenProfile = { navController.navigate(Screen.OperatorProfile.createRoute(session)) },
            )
        }

        composable(Screen.FacilityList.route, arguments = listOf(criticalOnlyArgument)) { entry ->
            val criticalOnly = entry.criticalOnly()
            FacilityListScreen(
                criticalOnly = criticalOnly,
                onOpenFacility = { id -> navController.navigate(Screen.FacilityDetails.createRoute(id, criticalOnly)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Screen.FacilityDetails.route,
            arguments = listOf(
                navArgument(Screen.ARG_FACILITY_ID) { type = NavType.IntType },
                criticalOnlyArgument,
            )
        ) { entry ->
            val facilityId = entry.arguments?.getInt(Screen.ARG_FACILITY_ID) ?: -1
            FacilityDetailsScreen(
                facility = FacilityCatalog.find(facilityId),
                criticalOnly = entry.criticalOnly(),
                onBack = { navController.popBackStack() },
                // Повернення до меню: знімаємо зі стеку всі екрани над головним меню
                onBackToMenu = { navController.popBackStack(Screen.MainMenu.route, inclusive = false) },
            )
        }

        composable(Screen.ReadinessSummary.route, arguments = listOf(criticalOnlyArgument)) { entry ->
            val criticalOnly = entry.criticalOnly()
            ReadinessSummaryScreen(
                criticalOnly = criticalOnly,
                onOpenFacility = { id -> navController.navigate(Screen.FacilityDetails.createRoute(id, criticalOnly)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            Screen.OperatorProfile.route,
            arguments = listOf(
                navArgument(Screen.ARG_NAME) { type = NavType.StringType; defaultValue = "" },
                navArgument(Screen.ARG_SHIFT) { type = NavType.StringType; defaultValue = Shift.DAY.name },
                criticalOnlyArgument,
            )
        ) { entry ->
            val args = entry.arguments
            val initial = OperatorSession(
                name = args?.getString(Screen.ARG_NAME).orEmpty(),
                shift = Shift.valueOf(args?.getString(Screen.ARG_SHIFT) ?: Shift.DAY.name),
                criticalOnly = entry.criticalOnly(),
            )
            OperatorProfileScreen(
                initial = initial,
                onSave = { session ->
                    // Повертаємо результат попередньому екрану (головному меню) і закриваємо профіль
                    navController.previousBackStackEntry?.savedStateHandle?.apply {
                        set(Screen.RESULT_NAME, session.name)
                        set(Screen.RESULT_SHIFT, session.shift.name)
                        set(Screen.RESULT_CRITICAL_ONLY, session.criticalOnly)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
