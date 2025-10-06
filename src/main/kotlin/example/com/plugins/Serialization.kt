package example.com.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.Application
import io.ktor.server.application.install
import kotlinx.serialization.json.Json

fun Application.configureSerialization(json: Json) {
    install(ContentNegotiation) {
        json(json)
    }
}
