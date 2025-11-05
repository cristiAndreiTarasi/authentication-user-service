package example.com.services.ws_session

import example.com.schemas.UserSchema
import example.com.services.token.ITokenService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close

/**
 * Handles WebSocket authentication by extracting user information from JWT tokens
 */
object WebSocketAuthHelper {
    /**
     * Authenticates a WebSocket session and returns user information
     *
     * @param call The application call containing JWT principal
     * @param session The WebSocket session for closing connections
     * @param authTokenService Service for extracting claims from JWT tokens
     * @param userSchema Database schema for fetching user information
     * @return AuthResult containing userId, or null if authentication fails
     */
    suspend fun authenticateUser(
        call: ApplicationCall,
        session: WebSocketSession,
        authTokenService: ITokenService,
    ): String? {
        // Extract JWT principal from the WebSocket call
        val principal = call.principal<JWTPrincipal>() ?: run {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthenticated"))
            return null
        }

        // Extract user ID from JWT claims (try both "userId" and "sub" claims)
        val idStr = authTokenService.getClaim(principal, "userId") ?: authTokenService.getClaim(principal, "sub")
        val userId = idStr?.toIntOrNull()?.toString() ?: run {
            session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid user id"))
            return null
        }

        return userId
    }
}