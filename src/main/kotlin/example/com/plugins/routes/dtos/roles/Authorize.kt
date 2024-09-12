package example.com.plugins.routes.dtos.roles

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline.ApplicationPhase.Plugins
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Enforces authentication and optionally restricts access to users with specific roles.
 *
 * @param roles A list of roles that are allowed access. If no roles are provided, the route will default to
 *              requiring only authentication without role-based restrictions.
 * @param block The route block to execute if the user is authenticated and, if roles are specified, has one of the required roles.
 *
 * @return The route that applies the authentication and authorization checks.
 *
 * Usage:
 *
 * - To restrict access to users with specific roles:
 *      authorize(listOf(UserRole.ADMIN.roleName, UserRole.MODERATOR.roleName)) { ... }
 *
 * - To allow access to any authenticated user:
 *      authorize() { ... }
 */
fun Route.authorize(vararg requiredRoles: String, block: Route.() -> Unit): Route {
    return authenticate("auth-jwt") {
        intercept(Plugins) {
            val principal = call.principal<JWTPrincipal>()
            val role = principal?.getClaim("role", String::class)

            // If no roles are provided, just authenticate without checking roles
            if (requiredRoles.isNotEmpty()) {
                if (role == null || role !in requiredRoles) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        "You do not have access to this resource."
                    )

                    return@intercept finish()
                }
            }
        }

        block()
    }
}