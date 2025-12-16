package example.com.routes

import example.com.services.braintree.BraintreeService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
private data class ClientTokenResponseDto(val clientToken: String)

@Serializable
private data class CheckoutRequestDto(val payment_method_nonce: String, val amount: String, val device_data: String? = null, val storeInVault: Boolean? = false)

@Serializable
private data class CheckoutResponseDto(val success: Boolean, val transactionId: String? = null, val message: String? = null)

fun Route.braintreeRoutes(braintreeService: BraintreeService) {
    route("/braintree") {

        // GET /api/braintree/client_token?customerId=...
        get("/client_token") {
            val customerId = call.request.queryParameters["customerId"] // optional
            try {
                val token = braintreeService.generateClientToken(customerId)
                call.respond(HttpStatusCode.OK, ClientTokenResponseDto(clientToken = token))
            } catch (e: Exception) {
                call.application.environment.log.error("client_token error", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed")))
            }
        }

        // POST /api/braintree/checkout
        post("/checkout") {
            val req = try {
                call.receive<CheckoutRequestDto>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid body: ${e.message}"))
                return@post
            }

            try {
                val result = braintreeService.checkout(
                    nonce = req.payment_method_nonce,
                    amount = req.amount,
                    deviceData = req.device_data,
                    storeInVault = req.storeInVault ?: false
                )

                if (result.success) {
                    // TODO: update user's balance in DB here using authenticated user info
                    call.respond(HttpStatusCode.OK, CheckoutResponseDto(success = true, transactionId = result.transactionId, message = result.message))
                } else {
                    call.respond(HttpStatusCode.PaymentRequired, CheckoutResponseDto(success = false, transactionId = null, message = result.message))
                }
            } catch (e: Exception) {
                call.application.environment.log.error("checkout error", e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed")))
            }
        }
    }
}
