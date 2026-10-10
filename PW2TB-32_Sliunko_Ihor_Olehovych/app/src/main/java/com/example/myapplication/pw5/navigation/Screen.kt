package com.example.myapplication.pw5.navigation

import android.net.Uri
import com.example.myapplication.pw5.data.OperatorSession

/**
 * Маршрути навігаційного графа ПЗ 5.
 * Усі рядки маршрутів та імена аргументів зібрані в одному sealed class, тому перехід
 * на неіснуючий екран чи помилка в імені аргументу виявляються ще на етапі компіляції.
 */
sealed class Screen(val route: String) {

    /** Головне меню (стартовий екран графа). */
    data object MainMenu : Screen("main_menu")

    /** Список об'єктів; необов'язковий аргумент — режим розрахунку. */
    data object FacilityList : Screen("facility_list?$ARG_CRITICAL_ONLY={$ARG_CRITICAL_ONLY}") {
        fun createRoute(criticalOnly: Boolean) = "facility_list?$ARG_CRITICAL_ONLY=$criticalOnly"
    }

    /** Деталі об'єкта; обов'язковий аргумент — id об'єкта, необов'язковий — режим розрахунку. */
    data object FacilityDetails :
        Screen("facility_details/{$ARG_FACILITY_ID}?$ARG_CRITICAL_ONLY={$ARG_CRITICAL_ONLY}") {
        fun createRoute(facilityId: Int, criticalOnly: Boolean) =
            "facility_details/$facilityId?$ARG_CRITICAL_ONLY=$criticalOnly"
    }

    /** Зведення готовності по всіх об'єктах. */
    data object ReadinessSummary : Screen("readiness_summary?$ARG_CRITICAL_ONLY={$ARG_CRITICAL_ONLY}") {
        fun createRoute(criticalOnly: Boolean) = "readiness_summary?$ARG_CRITICAL_ONLY=$criticalOnly"
    }

    /** Профіль оператора; поточні значення передаються як аргументи для попереднього заповнення форми. */
    data object OperatorProfile :
        Screen("operator_profile?$ARG_NAME={$ARG_NAME}&$ARG_SHIFT={$ARG_SHIFT}&$ARG_CRITICAL_ONLY={$ARG_CRITICAL_ONLY}") {
        fun createRoute(session: OperatorSession) =
            // Ім'я може містити пробіли та кирилицю — кодуємо його для URI маршруту
            "operator_profile?$ARG_NAME=${Uri.encode(session.name)}" +
                "&$ARG_SHIFT=${session.shift.name}&$ARG_CRITICAL_ONLY=${session.criticalOnly}"
    }

    companion object {
        const val ARG_FACILITY_ID = "facilityId"
        const val ARG_CRITICAL_ONLY = "criticalOnly"
        const val ARG_NAME = "name"
        const val ARG_SHIFT = "shift"

        /** Ключі результату, який екран профілю повертає в головне меню через savedStateHandle. */
        const val RESULT_NAME = "result_operator_name"
        const val RESULT_SHIFT = "result_operator_shift"
        const val RESULT_CRITICAL_ONLY = "result_critical_only"
    }
}
