package example.com.services.braintree

import com.braintreegateway.BraintreeGateway
import com.braintreegateway.ClientTokenRequest
import com.braintreegateway.TransactionRequest
import com.braintreegateway.Result
import com.braintreegateway.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal

data class BraintreeClientTokenResponse(val clientToken: String)
data class BraintreeCheckoutRequest(val payment_method_nonce: String, val amount: String, val device_data: String? = null, val storeInVault: Boolean = false)
data class BraintreeCheckoutResponse(val success: Boolean, val transactionId: String? = null, val message: String? = null)

class BraintreeService(
    private val gateway: BraintreeGateway
) {

    /** Generate client token. Pass customerId to enable vaulted payment methods for returning users. */
    suspend fun generateClientToken(customerId: String? = null): String = withContext(Dispatchers.IO) {
        val req = if (customerId.isNullOrBlank()) ClientTokenRequest() else ClientTokenRequest().customerId(customerId)
        gateway.clientToken().generate(req)
    }

    /**
     * Create a Transaction with the nonce (works for PayPal nonces, GooglePay nonces and card nonces).
     * deviceData is optional but recommended for fraud checks (DataCollector).
     * storeInVault allows creation of vaulted payment methods on success (if merchant/account supports it).
     */
    suspend fun checkout(nonce: String, amount: String, deviceData: String? = null, storeInVault: Boolean = false): BraintreeCheckoutResponse = withContext(Dispatchers.IO) {
        try {
            val trReq = TransactionRequest()
                .amount(BigDecimal(amount))
                .paymentMethodNonce(nonce)
                .apply {
                    if (!deviceData.isNullOrBlank()) this.deviceData(deviceData)
                }
                .options()
                .submitForSettlement(true)
                .apply {
                    if (storeInVault) this.storeInVaultOnSuccess(true)
                }
                .done()

            val result: Result<Transaction> = gateway.transaction().sale(trReq)

            if (result.isSuccess) {
                val tx = result.target
                BraintreeCheckoutResponse(success = true, transactionId = tx.id, message = "Submitted for settlement")
            } else {
                BraintreeCheckoutResponse(success = false, transactionId = null, message = result.message ?: "Transaction failed")
            }
        } catch (e: Exception) {
            BraintreeCheckoutResponse(success = false, transactionId = null, message = e.message)
        }
    }
}
