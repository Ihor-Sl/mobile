package com.example.myapplication.pw11.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    /** Бажаний термін дії токена (сервер обмежує 15…600 с). */
    @SerializedName("ttl_sec") val ttlSec: Int,
)

data class UserDto(
    @SerializedName("username") val username: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("role") val role: String?,
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("token_type") val tokenType: String?,
    @SerializedName("expires_in") val expiresIn: Int?,
    @SerializedName("user") val user: UserDto?,
)

data class UnitStatusDto(
    @SerializedName("name") val name: String?,
    @SerializedName("state") val state: String?,
    @SerializedName("fuel_percent") val fuelPercent: Int?,
)

data class ReserveStatusDto(
    @SerializedName("grid_online") val gridOnline: Boolean?,
    @SerializedName("units") val units: List<UnitStatusDto>?,
)

data class AtsTestDto(
    @SerializedName("started_by") val startedBy: String?,
    @SerializedName("result") val result: String?,
    @SerializedName("transfer_ms") val transferMs: Int?,
)

data class AuditEntryDto(
    @SerializedName("time") val time: Long?,
    @SerializedName("user") val user: String?,
    @SerializedName("action") val action: String?,
    @SerializedName("success") val success: Boolean?,
)

/**
 * API пульта. Заголовок Authorization додає [AuthInterceptor] автоматично;
 * службовий заголовок «No-Auth: true» вимикає підстановку токена (логін, демонстрація запиту без токена).
 */
interface AuthApi {
    @Headers("No-Auth: true")
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** Захищений ресурс для всіх ролей. */
    @GET("reserve/status")
    suspend fun reserveStatus(): ReserveStatusDto

    /** Лише OPERATOR та ADMIN. */
    @POST("ats/test")
    suspend fun runAtsTest(): AtsTestDto

    /** Лише ADMIN. */
    @GET("admin/audit")
    suspend fun audit(): List<AuditEntryDto>

    /** Демонстрація: запит до захищеного ресурсу без токена. */
    @Headers("No-Auth: true")
    @GET("reserve/status")
    suspend fun reserveStatusAnonymous(): ReserveStatusDto

    /** Демонстрація: запит із явно переданим (наприклад, підробленим) токеном. */
    @GET("reserve/status")
    suspend fun reserveStatusWithToken(@Header("Authorization") authorization: String): ReserveStatusDto
}
