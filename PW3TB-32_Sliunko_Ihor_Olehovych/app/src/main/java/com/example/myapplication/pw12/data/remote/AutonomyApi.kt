package com.example.myapplication.pw12.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class ForecastPointDto(
    @SerializedName("hour") val hour: Int?,
    @SerializedName("fuel_l") val fuelL: Double?,
)

data class AutonomyDto(
    @SerializedName("facility_code") val facilityCode: String?,
    @SerializedName("facility_name") val facilityName: String?,
    @SerializedName("horizon_h") val horizonH: Int?,
    @SerializedName("autonomy_h") val autonomyH: Double?,
    @SerializedName("consumption_lph") val consumptionLph: Double?,
    @SerializedName("points") val points: List<ForecastPointDto>?,
)

/** GET /autonomy/{code}?hours=N — прогноз автономності об'єкта на N годин. */
interface AutonomyApi {
    @GET("autonomy/{code}")
    suspend fun forecast(@Path("code") code: String, @Query("hours") hours: Int): AutonomyDto
}
