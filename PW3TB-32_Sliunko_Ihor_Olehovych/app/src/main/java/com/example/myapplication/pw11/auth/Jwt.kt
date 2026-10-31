package com.example.myapplication.pw11.auth

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.annotations.SerializedName
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8

/** Ролі користувачів пульта аварійного живлення. */
enum class Role(val label: String) {
    ADMIN("Головний енергетик"),
    OPERATOR("Черговий диспетчер"),
    VIEWER("Інспектор"),
}

/** Корисне навантаження (payload) JWT. Часи — у секундах Unix, як вимагає стандарт RFC 7519. */
data class JwtClaims(
    @SerializedName("sub") val subject: String,
    @SerializedName("name") val name: String,
    @SerializedName("role") val role: String,
    @SerializedName("iat") val issuedAt: Long,
    @SerializedName("exp") val expiresAt: Long,
) {
    val roleOrNull: Role? get() = Role.entries.firstOrNull { it.name == role }

    fun isExpired(nowSec: Long) = nowSec >= expiresAt
}

/**
 * Мінімальна реалізація JWT (HS256): header.payload.signature у Base64URL без «=».
 *
 * Клієнт використовує лише [decode] — читає роль і термін дії, щоб адаптувати UI.
 * Підпис перевіряє тільки сервер ([verify]), бо секретний ключ є лише в нього:
 * змінити роль у токені на клієнті можна, але сервер такий токен відхилить (401).
 */
object Jwt {
    private val gson = Gson()
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    sealed interface Verification {
        data class Valid(val claims: JwtClaims) : Verification
        data object Malformed : Verification
        data object BadSignature : Verification
        data class Expired(val claims: JwtClaims) : Verification
    }

    fun encode(claims: JwtClaims, secret: String): String {
        val header = HEADER.encodeUtf8().base64UrlNoPad()
        val payload = gson.toJson(claims).encodeUtf8().base64UrlNoPad()
        return "$header.$payload.${sign("$header.$payload", secret)}"
    }

    /** Розбір payload без перевірки підпису (сторона клієнта). */
    fun decode(token: String): JwtClaims? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val json = parts[1].decodeBase64()?.utf8() ?: return null
        return try {
            gson.fromJson(json, JwtClaims::class.java)?.takeIf { it.subject != null && it.role != null }
        } catch (e: JsonParseException) {
            null
        }
    }

    /** Повна перевірка (сторона сервера): формат, підпис HMAC-SHA256, термін дії. */
    fun verify(token: String, secret: String, nowSec: Long): Verification {
        val parts = token.split('.')
        if (parts.size != 3) return Verification.Malformed
        val claims = decode(token) ?: return Verification.Malformed
        if (sign("${parts[0]}.${parts[1]}", secret) != parts[2]) return Verification.BadSignature
        return if (claims.isExpired(nowSec)) Verification.Expired(claims) else Verification.Valid(claims)
    }

    private fun sign(data: String, secret: String) =
        data.encodeUtf8().hmacSha256(secret.encodeUtf8()).base64UrlNoPad()

    private fun okio.ByteString.base64UrlNoPad() = base64Url().trimEnd('=')
}
