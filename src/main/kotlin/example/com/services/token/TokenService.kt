package example.com.services.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.JWTPrincipal
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

data class TokenClaim(val name: String, val value: String, )
data class GeneratedToken(val token: String, val expiresAt: Instant)

data class TokenConfig(
    val issuer: String,
    val audience: String,
    val accessExpiresIn: Duration,
    val refreshExpiresIn: Duration,
    val secret: String
)

interface ITokenService {
    fun generateAccessToken(claims: List<TokenClaim>, timezone: String): String
    fun generateAccessTokenWithExpiry(claims: List<TokenClaim>, timezone: String): GeneratedToken
    fun generateRefreshToken(timezone: String): String
    fun getClaimFromToken(token: String, claimName: String): String?
    fun getClaim(principal: JWTPrincipal, claimName: String): String? {
        return principal.payload.getClaim(claimName)?.asString()
    }
}

// Generates a JWT token using the provided configuration and claims
class TokenService(val tokenConfig: TokenConfig) : ITokenService {
    override fun generateAccessToken(claims: List<TokenClaim>, timezone: String): String {
        // delegate to the new method and return only the token string (backwards compatible)
        return generateAccessTokenWithExpiry(claims, timezone).token
    }

    override fun generateAccessTokenWithExpiry(claims: List<TokenClaim>, timezone: String): GeneratedToken {
        val dateTime = ZonedDateTime.now(ZoneId.of(timezone)).plus(tokenConfig.accessExpiresIn).toInstant()
        var tokenBuilder = JWT.create()
            .withAudience(tokenConfig.audience)
            .withIssuer(tokenConfig.issuer)
            .withExpiresAt(Date.from(dateTime))
            .withIssuedAt(Date.from(ZonedDateTime.now(ZoneId.of(timezone)).toInstant()))

        claims.forEach { tokenBuilder = tokenBuilder.withClaim(it.name, it.value) }

        val tokenString = tokenBuilder.sign(Algorithm.HMAC256(tokenConfig.secret))
        val expiresAtKtx = Instant.fromEpochMilliseconds(dateTime.toEpochMilli())
        return GeneratedToken(tokenString, expiresAtKtx)
    }

    override fun generateRefreshToken(timezone: String): String {
        val date = ZonedDateTime
            .now(ZoneId.of(timezone))
            .plus(tokenConfig.refreshExpiresIn)
            .toInstant()

        val token = JWT.create()
            .withAudience(tokenConfig.audience)
            .withIssuer(tokenConfig.issuer)
            .withExpiresAt(date)
            .withIssuedAt(Date(System.currentTimeMillis()))

        // Signs the token using the HMAC256 algorithm and the provided secret
        return token.sign(Algorithm.HMAC256(tokenConfig.secret))
    }

    override fun getClaimFromToken(token: String, claimName: String): String? {
        try {
            val decodedJWT = JWT.require(Algorithm.HMAC256(tokenConfig.secret))
                .withAudience(tokenConfig.audience)
                .withIssuer(tokenConfig.issuer)
                .build()
                .verify(token)

            val claim = decodedJWT.getClaim(claimName)
            // Try common conversions in order
            return claim.asString()
                ?: claim.asLong()?.toString()
                ?: claim.asInt()?.toString()
                ?: claim.asDate()?.time?.div(1000)?.toString()  // seconds
        } catch (e: Exception) {
            // Helpful debug logging: show unverified aud/iss for quick inspection
            try {
                val unverified = JWT.decode(token)
                println("getClaimFromToken: verification failed for claim='$claimName' token_aud=${unverified.audience} token_iss=${unverified.issuer} -> ${e::class.simpleName}: ${e.message}")
            } catch (_: Exception) {
                println("getClaimFromToken: verification failed and token decode failed -> ${e::class.simpleName}: ${e.message}")
            }
            return null
        }
    }
}