package example.com.schemas.queries

object GiftsQueries {
    const val INSERT_GIFT_TX = """
        INSERT INTO gift_transactions
        (idempotency_key, from_user_id, to_user_id, stream_id, gift_type, quantity,
         coins_amount, recipient_coins_received, platform_cut_coins, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
    """

    const val UPDATE_USER_BALANCE = "UPDATE users SET balance_coins = ? WHERE id = ?"

    const val SELECT_USER_FOR_UPDATE = "SELECT balance_coins FROM users WHERE id = ? FOR UPDATE"

    const val INSERT_USER_LEDGER = """
        INSERT INTO user_coin_ledger (user_id, delta, balance_after, reason, ref_gift_tx_id, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
    """

    const val INSERT_OUTBOX = "INSERT INTO outbox (stream_name, event_type, payload) VALUES (?, ?, ?::jsonb)"

    const val SELECT_GIFTS_CATALOG = """
        SELECT id, name, coin_cost AS price_coins, image_url, rarity, is_active
        FROM gifts_catalog
        WHERE is_active = TRUE
        ORDER BY coin_cost ASC
    """

    const val SELECT_GIFT_TX_STATUS = "SELECT status FROM gift_transactions WHERE id = ?"

    const val UPDATE_GIFT_TX_STATUS = "UPDATE gift_transactions SET status = ?, processed_at = now() WHERE id = ?"

    const val INSERT_GIFT_TX_IDEMPOTENT = """
        INSERT INTO gift_transactions
        (idempotency_key, from_user_id, to_user_id, stream_id, gift_type, quantity, coins_amount, recipient_coins_received, platform_cut_coins, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'created', ?)
        ON CONFLICT (idempotency_key) DO NOTHING
        RETURNING id
    """
}
