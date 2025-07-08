package example.com.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

/*fun Application.configureHTTP() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("MyCustomHeader")
        anyHost() // @TODO: Don't do this in production if possible. Try to limit it.
    }
}*/

fun Application.configureHTTP() {
    install(CORS) {
        // Standard headers you’ll need
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        // Any custom headers
        allowHeader("MyCustomHeader")

        // HTTP methods you use
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)

        // Allow cookies / `credentials: include` if you use them
        allowCredentials = true

        val devMode = this@configureHTTP.environment
            .config
            .propertyOrNull("ktor.developmentMode")
            ?.getString()
            ?.toBoolean() ?: false

        if (devMode) {
            // in development allow any host
            anyHost()
        } else {
            // in production lock it down
            allowHost("app.myapp.com", schemes = listOf("https"))
            allowHost("api.myapp.com", schemes = listOf("https"))
        }
    }
}
