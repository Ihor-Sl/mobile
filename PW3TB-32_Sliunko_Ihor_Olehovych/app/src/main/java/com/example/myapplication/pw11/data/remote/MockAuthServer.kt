package com.example.myapplication.pw11.data.remote

import com.example.myapplication.net.MockHttp
import com.example.myapplication.pw11.auth.Jwt
import com.example.myapplication.pw11.auth.JwtClaims
import com.example.myapplication.pw11.auth.Role
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Імітаційний (mocked) сервер автентифікації та захищених ресурсів.
 *
 *  - POST /auth/login — перевіряє логін і пароль, видає JWT, підписаний секретом сервера;
 *  - кожен захищений запит перевіряє заголовок «Authorization: Bearer <jwt>»:
 *    немає токена / неправильний підпис / минув термін → 401, недостатньо прав (роль) → 403;
 *  - після 3 невдалих спроб входу обліковий запис блокується на 30 с (429);
 *  - сервер веде журнал входів і відмов у доступі (доступний лише адміністратору).
 */
class MockAuthServer(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val latencyMs: Long = 500,
) : Interceptor {

    private data class Account(val password: String, val name: String, val role: Role)

    private val accounts = mapOf(
        "admin" to Account("admin123", "Ігор Слюнько", Role.ADMIN),
        "operator" to Account("oper123", "Олена Коваль", Role.OPERATOR),
        "viewer" to Account("view123", "Петро Мельник", Role.VIEWER),
    )

    private val failedLogins = ConcurrentHashMap<String, Int>()
    private val lockedUntil = ConcurrentHashMap<String, Long>()
    private val audit = CopyOnWriteArrayList<AuditEntryDto>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        MockHttp.latency(latencyMs)
        val path = MockHttp.path(request).joinToString("/")
        return when {
            request.method == "POST" && path == "auth/login" -> login(request)
            request.method == "GET" && path == "reserve/status" -> protected(request, Role.entries.toSet(), "Перегляд стану резерву") { _ ->
                MockHttp.json(
                    request, 200, ReserveStatusDto(
                        gridOnline = false,
                        units = listOf(
                            UnitStatusDto("ДГУ-1 Лікарня № 3", "Працює під навантаженням", 72),
                            UnitStatusDto("ДБЖ ЦОД «Енергохаб»", "Резерв, АКБ 100 %", 100),
                            UnitStatusDto("ДГУ-4 Котельня", "Мало палива", 20),
                        )
                    )
                )
            }
            request.method == "POST" && path == "ats/test" -> protected(request, setOf(Role.OPERATOR, Role.ADMIN), "Тест АВР") { claims ->
                MockHttp.json(request, 200, AtsTestDto(claims.name, "Резерв прийняв навантаження", 4_800))
            }
            request.method == "GET" && path == "admin/audit" -> protected(request, setOf(Role.ADMIN), "Журнал входів") { _ ->
                MockHttp.json(request, 200, audit.reversed().take(12))
            }
            else -> MockHttp.error(request, 404, "not_found", "Ресурс не знайдено")
        }
    }

    private fun login(request: Request): Response {
        val body = runCatching { MockHttp.gson.fromJson(MockHttp.bodyOf(request), LoginRequest::class.java) }.getOrNull()
        val username = body?.username?.trim().orEmpty()
        if (username.isEmpty() || body?.password.isNullOrEmpty()) {
            return MockHttp.error(request, 400, "bad_request", "Логін і пароль обов'язкові")
        }
        val now = nowMs()
        lockedUntil[username]?.let { until ->
            if (now < until) {
                val wait = (until - now + 999) / 1000
                return MockHttp.error(request, 429, "locked", "Забагато невдалих спроб. Зачекайте $wait с", mapOf("Retry-After" to wait.toString()))
            }
        }
        val account = accounts[username]
        if (account == null || account.password != body?.password) {
            val fails = failedLogins.merge(username, 1, Int::plus) ?: 1
            if (fails >= 3) {
                lockedUntil[username] = now + 30_000
                failedLogins.remove(username)
            }
            record(username, "Вхід (невірний пароль)", false)
            return MockHttp.error(request, 401, "invalid_credentials", "Невірний логін або пароль")
        }
        failedLogins.remove(username)
        val ttl = (body?.ttlSec ?: 120).coerceIn(15, 600)
        val iat = now / 1000
        val claims = JwtClaims(username, account.name, account.role.name, iat, iat + ttl)
        record(username, "Вхід", true)
        return MockHttp.json(
            request, 200,
            LoginResponse(Jwt.encode(claims, SECRET), "Bearer", ttl, UserDto(username, account.name, account.role.name))
        )
    }

    /** Спільна перевірка токена і ролі для всіх захищених ресурсів. */
    private fun protected(request: Request, allowed: Set<Role>, action: String, handler: (JwtClaims) -> Response): Response {
        val header = request.header("Authorization")
            ?: return MockHttp.error(request, 401, "token_missing", "Потрібна автентифікація: токен не передано")
        val token = header.removePrefix("Bearer ").trim()
        return when (val v = Jwt.verify(token, SECRET, nowMs() / 1000)) {
            Jwt.Verification.Malformed -> MockHttp.error(request, 401, "token_invalid", "Токен має неправильний формат")
            Jwt.Verification.BadSignature -> {
                record(Jwt.decode(token)?.subject ?: "?", "$action (підроблений токен)", false)
                MockHttp.error(request, 401, "token_invalid", "Підпис токена недійсний")
            }
            is Jwt.Verification.Expired -> MockHttp.error(request, 401, "token_expired", "Термін дії токена минув")
            is Jwt.Verification.Valid -> {
                val role = v.claims.roleOrNull
                if (role == null || role !in allowed) {
                    record(v.claims.subject, "$action (немає прав)", false)
                    MockHttp.error(request, 403, "forbidden", "Недостатньо прав для дії «$action»")
                } else {
                    handler(v.claims)
                }
            }
        }
    }

    private fun record(user: String, action: String, success: Boolean) {
        audit += AuditEntryDto(nowMs(), user, action, success)
    }

    companion object {
        /** Секрет підпису відомий лише серверу; клієнт його не використовує. */
        private const val SECRET = "pw11-emergency-power-secret"
    }
}
