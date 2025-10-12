package example.com.schemas.queries

object TokenQueries {
    const val INSERT_TOKEN = "INSERT INTO tokens (user_id, token, created_at, expires_at) VALUES (?, ?, ?, ?)"
    const val SELECT_TOKEN = "SELECT * FROM tokens WHERE token = ?"
    const val SELECT_TOKEN_BY_USER_ID = "SELECT * FROM tokens WHERE user_id = ?"
    const val UPDATE_TOKEN = "UPDATE tokens SET token = ?, expires_at = ?, created_at = ? WHERE user_id = ?"
    const val DELETE_TOKENS_FOR_USER = "DELETE FROM tokens WHERE user_id = ?"
}