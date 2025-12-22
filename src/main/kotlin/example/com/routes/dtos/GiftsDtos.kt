package example.com.routes.dtos

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GiftCatalogEntryDto(
    val id: String,
    val name: String,
    @SerialName("coinCost") val priceCoins: Long,
    val imageUrl: String? = null,
    val rarity: String? = null,
    val isActive: Boolean? = true
)

@Serializable
data class SendGiftRequestDto(
    val giftId: String,
    val quantity: Int,
    val idempotencyKey: String?
)

@Serializable
data class SendGiftSuccessResponseDto(
    val giftTxId: Long,
    val senderBalanceAfter: Long,
    val recipientBalanceAfter: Long,
    val recipientCoins: Long
)

@Serializable
sealed class SendGiftResult
@Serializable
data class SendGiftOk(val response: SendGiftSuccessResponseDto) : SendGiftResult()
@Serializable
data class SendGiftFailed(val reason: String, val required: Long? = null, val balance: Long? = null) : SendGiftResult()


@Serializable
sealed class OutboxEvent {
    // gift-outbox event (billing)
    @Serializable
    @SerialName("GiftOutbox")
    data class GiftOutbox(
        val gift_tx_id: Long? = null,
        val idempotency_key: String? = null,
        val from_user_id: Int,
        val from_username: String? = null,
        val to_user_id: Int,
        val to_username: String? = null,
        val stream_id: String,
        val gift_type: String,
        val quantity: Int,
        val coins_amount: Long,
        val recipient_coins: Long,
        val platform_cut: Long,
        val created_at: String
    ) : OutboxEvent()
}