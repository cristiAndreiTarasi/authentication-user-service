package example.com.services.braintree

import com.braintreegateway.BraintreeGateway
import com.braintreegateway.Environment

fun createBraintreeGatewayFromConfig(
    isSandbox: Boolean,
    merchantId: String,
    publicKey: String,
    privateKey: String
): BraintreeGateway {
    val env = if (isSandbox) Environment.SANDBOX else Environment.PRODUCTION
    return BraintreeGateway(env, merchantId, publicKey, privateKey)
}