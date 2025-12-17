// BraintreeRoutes.kt
import example.com.schemas.COINS_PER_USD
import example.com.schemas.PaymentSchema
import example.com.services.braintree.BraintreeService
import example.com.services.token.ITokenService
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.auth.jwt.JWTPrincipal
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.lang.IllegalArgumentException

@Serializable
private data class ClientTokenResponseDto(val clientToken: String)

@Serializable
data class CheckoutRequestDto(
    val payment_method_nonce: String,
    val amount: String,
    val device_data: String? = null,
    val storeInVault: Boolean? = false
)

@Serializable
data class CheckoutResponseDto(
    val success: Boolean,
    val transactionId: String? = null,
    val message: String? = null,
    val updatedBalance: Double? = null,      // USD (keeps client compatibility)
    val updatedBalanceCoins: Long? = null    // coins (source of truth)
)

fun Route.braintreeRoutes(
    braintreeService: BraintreeService,
    paymentSchema: PaymentSchema,
    authTokenService: ITokenService
) {
    authenticate("auth-jwt") {
        route("/braintree") {

            get("/client_token") {
                val customerId = call.request.queryParameters["customerId"]
                try {
                    val token = braintreeService.generateClientToken(customerId)
                    call.respond(HttpStatusCode.OK, ClientTokenResponseDto(clientToken = token))
                } catch (e: Exception) {
                    call.application.environment.log.error("client_token error", e)
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "failed")))
                }
            }

            post("/checkout") {
                val principal = call.principal<JWTPrincipal>()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, "Missing principal")

                // extract userId from token claims
                val idStr = authTokenService.getClaim(principal, "userId")
                    ?: authTokenService.getClaim(principal, "sub")
                val userId = idStr?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, "Invalid user id in token")

                // parse request body
                val req = try {
                    call.receive<CheckoutRequestDto>()
                } catch (e: Exception) {
                    call.application.environment.log.error("Invalid checkout body", e)
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid body: ${e.message}"))
                }

                // validate & parse amount
                val amountUsd = try {
                    BigDecimal(req.amount).setScale(2, RoundingMode.HALF_UP)
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, CheckoutResponseDto(success = false, message = "Invalid amount"))
                }

                try {
                    // 1) process payment with Braintree
                    val result = braintreeService.checkout(
                        nonce = req.payment_method_nonce,
                        amount = req.amount,
                        deviceData = req.device_data,
                        storeInVault = req.storeInVault ?: false
                    )

                    if (!result.success) {
                        val msg = result.message ?: "Transaction failed"
                        return@post call.respond(HttpStatusCode.PaymentRequired, CheckoutResponseDto(success = false, message = msg))
                    }

                    val providerTxId = result.transactionId
                    val provider = "braintree"
                    // normalize instrument type (optional). Provide raw instrument if normalization not desired.
                    val rawInstrument = result.paymentInstrumentType
                    val method = normalizeInstrument(rawInstrument)

                    // 2) apply recharge in DB (idempotent by providerTxId)
                    val newBalanceCoins = paymentSchema.applyRecharge(
                        userId = userId,
                        provider = provider,
                        method = method,
                        providerTransactionId = providerTxId,
                        amountUsd = amountUsd
                    )

                    // 3) convert coins back to USD for the client response
                    val newBalanceUsd = BigDecimal.valueOf(newBalanceCoins)
                        .divide(COINS_PER_USD, 2, RoundingMode.HALF_UP)
                        .toDouble()

                    call.respond(HttpStatusCode.OK,
                        CheckoutResponseDto(
                            success = true,
                            transactionId = providerTxId,
                            message = result.message ?: "Submitted for settlement",
                            updatedBalance = newBalanceUsd,
                            updatedBalanceCoins = newBalanceCoins
                        )
                    )
                } catch (e: Exception) {
                    call.application.environment.log.error("checkout error", e)
                    call.respond(HttpStatusCode.InternalServerError, CheckoutResponseDto(success = false, message = e.message))
                }
            }
        }
    }
}

/**
 * Normalize raw payment instrument strings to simple types used in DB analytics.
 * e.g. "paypal_account" -> "paypal", "google_pay_card" -> "google", "credit_card" -> "card"
 */
fun normalizeInstrument(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown"
    val r = raw.lowercase()
    return when {
        "paypal" in r -> "paypal"
        "google" in r -> "google"
        "apple" in r -> "apple"
        "credit" in r || "card" in r -> "card"
        else -> "other"
    }
}
