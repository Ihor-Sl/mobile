package com.example.myapplication.pw12.data

import android.content.Context
import android.util.Log
import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw12.data.remote.AutonomyApi
import com.example.myapplication.pw12.data.remote.AutonomyDto
import com.example.myapplication.pw12.data.remote.FaultyAutonomyServer
import com.example.myapplication.pw12.errors.AppError
import com.example.myapplication.pw12.errors.AppException
import com.example.myapplication.pw12.errors.ErrorHandler
import com.example.myapplication.pw12.errors.ErrorLogger
import com.example.myapplication.pw12.errors.FileErrorLogger
import com.example.myapplication.pw12.errors.RetryInfo
import com.example.myapplication.pw12.errors.RetryPolicy
import java.io.File

/** Перевірений прогноз автономності. */
data class AutonomyForecast(
    val code: String,
    val name: String,
    val horizonH: Int,
    val autonomyH: Double,
    val consumptionLph: Double,
    val fuelByHour: List<Double>,
) {
    val enough get() = autonomyH >= horizonH
}

/** Перевірка введення (базовий рівень: некоректний ввід не надсилається на сервер). */
object ForecastInput {
    private val CODE = Regex("^OBJ-\\d{2}$")

    fun codeError(code: String) = when {
        code.isBlank() -> "Вкажіть код об'єкта"
        !CODE.matches(code.trim().uppercase()) -> "Формат: OBJ-01"
        else -> null
    }

    fun hoursError(hours: String): String? {
        val h = hours.trim().toIntOrNull() ?: return "Ціле число годин"
        return if (h !in 1..72) "Від 1 до 72 год" else null
    }
}

/**
 * Repository ПЗ 12. Порядок обробки запиту:
 *  1. перевірка мережі ([NetworkMonitor]) — без підключення запит не надсилається (NoInternet);
 *  2. виконання через [RetryPolicy] — тимчасові помилки повторюються з паузою;
 *  3. кожна помилка класифікується й реєструється [ErrorHandler]-ом (Logcat + файл);
 *  4. відповідь перевіряється: відсутні або некоректні поля → помилка Parse.
 * Назовні виходить або [AutonomyForecast], або [AppException] з готовим повідомленням для UI.
 */
class AutonomyRepository(
    private val api: AutonomyApi,
    val network: NetworkMonitor,
    val handler: ErrorHandler,
    val logger: ErrorLogger,
    val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    suspend fun forecast(
        code: String,
        hours: Int,
        onAttempt: (Int) -> Unit = {},
        onRetry: (RetryInfo) -> Unit = {},
        onCountdown: (Int) -> Unit = {},
    ): AutonomyForecast {
        val request = "GET /autonomy/$code?hours=$hours"
        if (!network.isOnline()) {
            handler.logClassified(AppError.NoInternet, request, 1, 1, null)
            throw AppException(AppError.NoInternet)
        }
        val dto = retryPolicy.execute(request, handler, onRetry, onAttempt, onCountdown) { api.forecast(code, hours) }
        return validate(dto) ?: run {
            val error = AppError.Parse("відсутні обов'язкові поля")
            handler.logClassified(error, request, 1, 1, null)
            throw AppException(error)
        }
    }

    private fun validate(dto: AutonomyDto): AutonomyForecast? {
        val code = dto.facilityCode ?: return null
        val autonomy = dto.autonomyH?.takeIf { it >= 0 } ?: return null
        val points = dto.points?.mapNotNull { it.fuelL } ?: return null
        if (points.isEmpty()) return null
        return AutonomyForecast(code, dto.facilityName ?: code, dto.horizonH ?: points.size - 1, autonomy, dto.consumptionLph ?: 0.0, points)
    }

    companion object {
        @Volatile
        private var instance: Pair<AutonomyRepository, FaultyAutonomyServer>? = null

        fun create(context: Context): Pair<AutonomyRepository, FaultyAutonomyServer> =
            instance ?: synchronized(this) {
                instance ?: run {
                    val server = FaultyAutonomyServer()
                    val api = buildRetrofit(server, timeoutSec = 10).create(AutonomyApi::class.java)
                    val logger = FileErrorLogger(
                        File(context.filesDir, "pw12/network_errors.log"),
                        logcat = { Log.w("PW12-Network", it) },
                    )
                    AutonomyRepository(api, AndroidNetworkMonitor(context.applicationContext), ErrorHandler(logger), logger) to server
                }.also { instance = it }
            }
    }
}
