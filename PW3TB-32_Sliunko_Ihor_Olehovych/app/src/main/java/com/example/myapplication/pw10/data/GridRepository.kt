package com.example.myapplication.pw10.data

import android.content.Context
import com.example.myapplication.net.MockHttp
import com.example.myapplication.net.buildRetrofit
import com.example.myapplication.pw10.data.remote.GridApi
import com.example.myapplication.pw10.data.remote.MockGridServer
import com.example.myapplication.pw10.domain.GridMapper
import com.example.myapplication.pw10.domain.GridSnapshot
import com.google.gson.JsonParseException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/** Звідки отримано дані. */
enum class DataOrigin(val label: String) {
    NETWORK("REST API"),
    MEMORY("Кеш у пам'яті"),
    OFFLINE("Офлайн-кеш (файл)"),
}

data class LoadResult(
    val snapshot: GridSnapshot,
    val origin: DataOrigin,
    /** Скільки повторних спроб знадобилося під час цього завантаження. */
    val retries: Int,
    /** Причина, через яку показано офлайн-кеш. */
    val warning: String? = null,
)

/** Офлайн-кеш: останній успішний знімок зберігається у файлі як JSON (Gson). */
interface SnapshotCache {
    fun read(): GridSnapshot?
    fun write(snapshot: GridSnapshot)
}

class JsonFileCache(private val file: File) : SnapshotCache {
    override fun read(): GridSnapshot? = try {
        if (file.exists()) MockHttp.gson.fromJson(file.readText(), GridSnapshot::class.java) else null
    } catch (e: JsonParseException) {
        null // пошкоджений кеш — ігноруємо, а не падаємо
    }

    override fun write(snapshot: GridSnapshot) {
        file.parentFile?.mkdirs()
        file.writeText(MockHttp.gson.toJson(snapshot))
    }
}

/**
 * Повторне виконання запиту при тимчасових збоях: IOException та HTTP 5xx.
 * Затримка росте експоненційно: 300 мс → 600 мс → 1,2 с. Помилки 4xx не повторюються —
 * повтор некоректного запиту дасть той самий результат.
 */
suspend fun <T> withRetry(
    times: Int = 3,
    initialDelayMs: Long = 300,
    onRetry: () -> Unit = {},
    block: suspend () -> T,
): T {
    var delayMs = initialDelayMs
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            val transient = e is IOException || (e is HttpException && e.code() >= 500)
            if (!transient) throw e
            onRetry()
            delay(delayMs)
            delayMs *= 2
        }
    }
    return block() // остання спроба: помилка передається далі
}

/**
 * Repository для ПЗ 10: об'єднує три REST-ресурси в один знімок [GridSnapshot].
 *
 * Взаємодія Retrofit — Gson — ViewModel:
 *  - Retrofit виконує HTTP-запити, Gson-конвертер розбирає JSON у DTO;
 *  - Repository перевіряє DTO ([GridMapper]), кешує результат і повертає доменні моделі;
 *  - ViewModel викликає [load] у viewModelScope і публікує стан у StateFlow для Compose.
 *
 * Асинхронність: список об'єктів і графік відключень запитуються паралельно (async),
 * потім телеметрія всіх об'єктів — теж паралельно; кожен запит має власні повторні спроби.
 * Кешування: знімок у пам'яті живе [cacheTtlMs]; при відсутності мережі — офлайн-кеш з файлу.
 */
class GridRepository(
    private val api: GridApi,
    private val cache: SnapshotCache,
    private val now: () -> Long = System::currentTimeMillis,
    private val cacheTtlMs: Long = 60_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var memory: GridSnapshot? = null

    suspend fun load(force: Boolean = false): Result<LoadResult> = withContext(io) {
        val cached = memory
        if (!force && cached != null && now() - cached.fetchedAt < cacheTtlMs) {
            return@withContext Result.success(LoadResult(cached, DataOrigin.MEMORY, retries = 0))
        }
        val retries = AtomicInteger()
        try {
            val snapshot = fetchAll { retries.incrementAndGet() }
            memory = snapshot
            cache.write(snapshot)
            Result.success(LoadResult(snapshot, DataOrigin.NETWORK, retries.get()))
        } catch (e: Exception) {
            if (e !is IOException && e !is HttpException && e !is JsonParseException) throw e
            val reason = describe(e)
            val offline = memory ?: cache.read()
            if (offline != null) Result.success(LoadResult(offline, DataOrigin.OFFLINE, retries.get(), warning = reason))
            else Result.failure(IOException(reason, e))
        }
    }

    private suspend fun fetchAll(onRetry: () -> Unit): GridSnapshot = coroutineScope {
        val date = dateFormat.format(Date(now()))
        val facilitiesCall = async { withRetry(onRetry = onRetry) { api.facilities() } }
        val scheduleCall = async { withRetry(onRetry = onRetry) { api.schedule(date) } }
        val facilities = GridMapper.facilities(facilitiesCall.await())
        val schedule = GridMapper.schedule(scheduleCall.await())
        val telemetry = facilities.items
            .map { f -> async { withRetry(onRetry = onRetry) { api.telemetry(f.id) } } }
            .awaitAll()
            .mapNotNull(GridMapper::telemetry)
        GridSnapshot(
            fetchedAt = now(),
            date = date,
            facilities = facilities.items,
            telemetry = telemetry,
            queues = schedule.items,
            rejected = facilities.rejected + schedule.rejected,
        )
    }

    private fun describe(e: Exception) = when (e) {
        is HttpException -> "Сервер відповів HTTP ${e.code()} після повторних спроб"
        is JsonParseException -> "Некоректний формат відповіді сервера"
        else -> e.message ?: "Немає з'єднання з сервером"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    companion object {
        @Volatile
        private var instance: Pair<GridRepository, MockGridServer>? = null

        fun create(context: Context): Pair<GridRepository, MockGridServer> =
            instance ?: synchronized(this) {
                instance ?: run {
                    val server = MockGridServer()
                    val api = buildRetrofit(server).create(GridApi::class.java)
                    val cache = JsonFileCache(File(context.filesDir, "pw10/grid_snapshot.json"))
                    GridRepository(api, cache) to server
                }.also { instance = it }
            }
    }
}
