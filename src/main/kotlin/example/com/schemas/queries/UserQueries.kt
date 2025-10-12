package example.com.schemas.queries

object UserQueries {
    const val INSERT_USER = """
            INSERT INTO users 
            (email, password, salt, username, role, bio, occupation, created_at, birth_date, timezone, is_live) 
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
    const val SELECT_USER_BY_EMAIL = "SELECT * FROM users WHERE email = ?"
    const val SELECT_USER_BY_USERNAME = "SELECT * FROM users WHERE username = ?"
    const val SELECT_USER_BY_ID = "SELECT * FROM users WHERE id = ?"
    const val SELECT_USER_BY_TOKEN = "SELECT * FROM users WHERE password_reset_token = ?"
    const val UPDATE_PASSWORD_RESET_TOKEN = """
            UPDATE users 
            SET password_reset_token = ?, password_reset_token_expiry = ? 
            WHERE id = ?
        """
    const val UPDATE_USER_PASSWORD = "UPDATE users SET password = ? WHERE id = ?"
    const val SELECT_ALL_USERS = "SELECT * FROM users"
    const val DELETE_USER_BY_ID = "DELETE FROM users WHERE id = ?"
    const val UPDATE_USER_BIO = "UPDATE users SET bio = ? WHERE id = ?"
    const val UPDATE_USER_OCCUPATION = "UPDATE users SET occupation = ? WHERE id = ?"
    const val UPDATE_USER_ISSTREAMING = "UPDATE users SET is_live = ? WHERE id = ?"
    const val UPDATE_USER_NAME = "UPDATE users SET username = ? WHERE id = ?"
    const val UPDATE_USER_AVATAR = "UPDATE users SET image_url = ? WHERE id = ?"
    const val UPDATE_USER_IMAGE_ID = "UPDATE users SET image_id = ? WHERE id = ?"

    const val SELECT_USER_LIKES = "SELECT liker_id FROM likes WHERE user_id = ?"
    const val SELECT_USER_FOLLOWERS = "SELECT follower_id FROM followers WHERE followed_id = ?"
    const val SELECT_USER_FOLLOWING = "SELECT followed_id FROM followers WHERE follower_id = ?"
    const val SELECT_LIVE_USERS = "SELECT * FROM users WHERE is_live = true"
}