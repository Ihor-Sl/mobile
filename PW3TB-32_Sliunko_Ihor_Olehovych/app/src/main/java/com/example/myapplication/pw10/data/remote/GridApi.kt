package com.example.myapplication.pw10.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * DTO — точне відображення JSON-відповідей сервера.
 * Усі поля nullable: Gson створює об'єкти без виклику конструктора, тому поле, якого немає в JSON,
 * стане null навіть для non-null типу Kotlin. Перевірка й перетворення в доменні моделі — у GridMapper.
 * @SerializedName зв'язує snake_case імена JSON із camelCase полями Kotlin.
 */

/** GET /facilities — об'єкти з вкладеними адресою, навантаженням і масивом джерел. */
data class FacilitiesResponse(
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("facilities") val facilities: List<FacilityDto>?,
)

data class FacilityDto(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("category") val category: Int?,
    @SerializedName("address") val address: AddressDto?,
    @SerializedName("load") val load: LoadDto?,
    @SerializedName("sources") val sources: List<SourceDto>?,
)

data class AddressDto(
    @SerializedName("city") val city: String?,
    @SerializedName("street") val street: String?,
)

data class LoadDto(
    @SerializedName("total_kw") val totalKw: Double?,
    @SerializedName("critical_kw") val criticalKw: Double?,
)

/** Джерело резерву: type = "DGU" (дизель-генератор) або "UPS" (ДБЖ з акумуляторами). */
data class SourceDto(
    @SerializedName("type") val type: String?,
    @SerializedName("rated_kw") val ratedKw: Double?,
    @SerializedName("tank_l") val tankL: Double? = null,
    @SerializedName("full_load_lph") val fullLoadLph: Double? = null,
    @SerializedName("battery_kwh") val batteryKwh: Double? = null,
)

/** GET /outages/schedule?date=… — черги планових відключень з масивами вікон. */
data class ScheduleResponse(
    @SerializedName("date") val date: String?,
    @SerializedName("queues") val queues: List<QueueDto>?,
)

data class QueueDto(
    @SerializedName("queue") val queue: String?,
    @SerializedName("facility_ids") val facilityIds: List<Int>?,
    @SerializedName("windows") val windows: List<WindowDto>?,
)

data class WindowDto(
    @SerializedName("from_h") val fromH: Int?,
    @SerializedName("to_h") val toH: Int?,
)

/** GET /facilities/{id}/telemetry — поточні показники об'єкта з вкладеним станом мережі. */
data class TelemetryDto(
    @SerializedName("facility_id") val facilityId: Int?,
    @SerializedName("measured_at") val measuredAt: Long?,
    @SerializedName("fuel_l") val fuelL: Double?,
    @SerializedName("battery_soc") val batterySoc: Double?,
    @SerializedName("grid") val grid: GridStateDto?,
)

data class GridStateDto(
    @SerializedName("online") val online: Boolean?,
    @SerializedName("voltage_v") val voltageV: Double?,
)

/**
 * Опис REST API для Retrofit. Реалізацію інтерфейсу Retrofit генерує сам (Retrofit.create),
 * suspend-функції виконуються асинхронно без колбеків, а помилкові HTTP-коди
 * перетворюються на retrofit2.HttpException.
 */
interface GridApi {
    @GET("facilities")
    suspend fun facilities(): FacilitiesResponse

    @GET("outages/schedule")
    suspend fun schedule(@Query("date") date: String): ScheduleResponse

    @GET("facilities/{id}/telemetry")
    suspend fun telemetry(@Path("id") id: Int): TelemetryDto
}
