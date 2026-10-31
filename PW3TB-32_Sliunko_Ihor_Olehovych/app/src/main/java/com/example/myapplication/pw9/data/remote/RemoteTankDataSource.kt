package com.example.myapplication.pw9.data.remote

import com.example.myapplication.pw9.domain.FuelTank
import com.google.gson.annotations.SerializedName
import java.io.IOException
import retrofit2.HttpException
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path

/** JSON-представлення резервуара на сервері диспетчерської (snake_case → camelCase через @SerializedName). */
data class TankDto(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("facility") val facility: String?,
    @SerializedName("capacity_l") val capacityL: Double?,
    @SerializedName("level_l") val levelL: Double?,
    @SerializedName("rated_power_kw") val ratedPowerKw: Double?,
    @SerializedName("load_kw") val loadKw: Double?,
    @SerializedName("full_load_lph") val fullLoadLph: Double?,
    @SerializedName("updated_at") val updatedAt: Long?,
) {
    /** Записи з відсутніми полями відкидаються — Gson не гарантує non-null для Kotlin-полів. */
    fun toDomainOrNull(): FuelTank? {
        return FuelTank(
            id = id ?: return null,
            name = name ?: return null,
            facility = facility ?: "",
            capacityL = capacityL?.takeIf { it > 0 } ?: return null,
            levelL = levelL ?: return null,
            ratedPowerKw = ratedPowerKw?.takeIf { it > 0 } ?: return null,
            loadKw = loadKw ?: 0.0,
            fullLoadLph = fullLoadLph?.takeIf { it > 0 } ?: return null,
            updatedAt = updatedAt ?: 0L,
        )
    }

    companion object {
        fun of(t: FuelTank) = TankDto(t.id, t.name, t.facility, t.capacityL, t.levelL, t.ratedPowerKw, t.loadKw, t.fullLoadLph, t.updatedAt)
    }
}

/** REST API сервера диспетчерської. */
interface TankApi {
    @GET("tanks")
    suspend fun getTanks(): List<TankDto>

    @PUT("tanks/{id}")
    suspend fun putTank(@Path("id") id: String, @Body tank: TankDto): TankDto

    @DELETE("tanks/{id}")
    suspend fun deleteTank(@Path("id") id: String): Response<Unit>
}

/**
 * Віддалене джерело даних. Помилки мережі (IOException) та HTTP (HttpException)
 * не перехоплюються тут — їх обробляє Repository, який вирішує, чи показати кеш.
 */
interface RemoteTankDataSource {
    suspend fun fetchAll(): List<FuelTank>
    suspend fun upsert(tank: FuelTank)
    suspend fun delete(id: String)
}

class RetrofitTankDataSource(private val api: TankApi) : RemoteTankDataSource {
    override suspend fun fetchAll() = api.getTanks().mapNotNull(TankDto::toDomainOrNull)

    override suspend fun upsert(tank: FuelTank) {
        api.putTank(tank.id, TankDto.of(tank))
    }

    override suspend fun delete(id: String) {
        val response = api.deleteTank(id)
        // 404 — запис уже видалено на сервері, для синхронізації це успіх
        if (!response.isSuccessful && response.code() != 404) throw HttpException(response)
    }
}

/** Опис помилки віддаленого джерела для користувача. */
fun describeRemoteError(e: Throwable): String = when (e) {
    is HttpException -> "Сервер диспетчерської відповів помилкою HTTP ${e.code()}"
    is IOException -> e.message ?: "Немає зв'язку з сервером диспетчерської"
    else -> e.message ?: e::class.simpleName.orEmpty()
}
