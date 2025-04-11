package example.com.services.token

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.jwt.JWTPrincipal
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date

data class TokenClaim(val name: String, val value: String, )

data class TokenConfig(
    val issuer: String,
    val audience: String,
    val accessExpiresIn: Duration,
    val refreshExpiresIn: Duration,
    val secret: String
)

interface ITokenService {
    fun generateAccessToken(claims: List<TokenClaim>, timezone: String): String
    fun generateRefreshToken(timezone: String): String
    fun getClaimFromToken(token: String, claimName: String): String?
    fun getClaim(principal: JWTPrincipal, claimName: String): String? {
        return principal.payload.getClaim(claimName)?.asString()
    }
}

// Generates a JWT token using the provided configuration and claims
class TokenService(private val tokenConfig: TokenConfig) : ITokenService {
    override fun generateAccessToken(claims: List<TokenClaim>, timezone: String): String {
        val date = ZonedDateTime
            .now(ZoneId.of(timezone))
            .plus(tokenConfig.accessExpiresIn)
            .toInstant()

        var token = JWT.create()
            .withAudience(tokenConfig.audience)
            .withIssuer(tokenConfig.issuer)
            .withExpiresAt(Date.from(date))
            .withIssuedAt(Date.from(ZonedDateTime.now(ZoneId.of(timezone)).toInstant()))

        // Adds claims to the token
        claims.forEach { claim ->
            token = token.withClaim(claim.name, claim.value)
        }

        // Signs the token using the HMAC256 algorithm and the provided secret
        return token.sign(Algorithm.HMAC256(tokenConfig.secret))
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
        return try {
            // Decodes the token and retrieves the claim
            val decodedJWT = JWT.require(Algorithm.HMAC256(tokenConfig.secret))
                .withAudience(tokenConfig.audience)
                .withIssuer(tokenConfig.issuer)
                .build()
                .verify(token)

            // Returns the value of the requested claim
            decodedJWT.getClaim(claimName)?.asString()
        } catch (e: Exception) {
            null // Returns null if the token is invalid or the claim is not present
        }
    }
}