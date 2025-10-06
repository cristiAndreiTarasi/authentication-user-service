package example.com.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import example.com.config.Constants
import example.com.services.token.TokenConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity(authTokenConfig: TokenConfig) {
    val jwtRealm  = environment.config.property("jwt.auth.realm").getString()

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm
            verifier(
                JWT.require(Algorithm.HMAC256(authTokenConfig.secret))
                    .withAudience(authTokenConfig.audience)
                    .withIssuer(authTokenConfig.issuer)
                    .build()
            )

            validate { credential ->
                val role = credential.payload.getClaim("role").asString()

                if (role != null && role in listOf("owner", "admin")) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }

            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is invalid or expired")
            }
        }
    }
}

