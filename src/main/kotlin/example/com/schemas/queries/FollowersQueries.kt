package example.com.schemas.queries

object FollowersQueries {
    const val INSERT_FOLLOWER = "INSERT INTO followers (follower_id, followed_id) VALUES (?, ?)"

    const val SELECT_FOLLOWERS_BY_USER_ID = "SELECT * FROM followers WHERE followed_id = ?"

    const val SELECT_FOLLOWING_BY_USER_ID = "SELECT * FROM followers WHERE follower_id = ?"

    const val DELETE_FOLLOWER = "DELETE FROM followers WHERE follower_id = ? AND followed_id = ?"
}