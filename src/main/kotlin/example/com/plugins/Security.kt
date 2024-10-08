package example.com.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import example.com.config.Constants
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    // Please read the jwt property from the config file if you are using EngineMain
    val config = environment.config

    // Please read the jwt property from the config file if you are using EngineMain
    val jwtDomain = config.property("jwt.domain").getString()
    val jwtAudience = config.property("jwt.audience").getString()
    val jwtIssuer = config.property("jwt.issuer").getString()
    val jwtRealm = config.property("jwt.realm").getString()
    val jwtSecret = Constants.JWT_SECRET

    install(Authentication) {
        jwt("auth-jwt") {
            realm = jwtRealm

            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
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
