package example.com.schemas.queries

// PaymentQueries.kt
object PaymentQueries {
    const val SELECT_TX_BY_PROVIDER_ID =
        "SELECT id, status FROM payment_transactions WHERE provider_transaction_id = ? LIMIT 1"

    const val SELECT_BALANCE =
        "SELECT balance_coins FROM users WHERE id = ?"

    const val UPDATE_BALANCE_RETURNING =
        "UPDATE users SET balance_coins = balance_coins + ? WHERE id = ? RETURNING balance_coins"

    const val INSERT_TX =
        """
        INSERT INTO payment_transactions
        (user_id, provider, method, provider_transaction_id, amount_usd, coins_added, status)
        VALUES (?, ?, ?, ?, ?, ?, 'success')
        """

    // optional: helper to insert failed transactions in future
    const val INSERT_TX_FAILED =
        """
        INSERT INTO payment_transactions
        (user_id, provider, method, provider_transaction_id, amount_usd, coins_added, status)
        VALUES (?, ?, ?, ?, ?, ?, 'failed')
        """
}
