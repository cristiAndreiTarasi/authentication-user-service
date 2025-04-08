package example.com.schemas

import example.com.routes.dtos.FollowerDataModel
import example.com.schemas.queries.FollowersQueries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

class FollowerSchema(private val dbConnection: Connection) {
    suspend fun addFollower(followerId: Int, followedId: Int): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(FollowersQueries.INSERT_FOLLOWER, Statement.RETURN_GENERATED_KEYS)
        statement.setInt(1, followerId)
        statement.setInt(2, followedId)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted follower")
        }
    }

    suspend fun getUserFollowers(userId: Int): List<FollowerDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(FollowersQueries.SELECT_FOLLOWERS_BY_USER_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val followers = mutableListOf<FollowerDataModel>()
        while (resultSet.next()) {
            followers.add(resultSet.toFollowerDataModel())
        }
        return@dbQuery followers
    }

    suspend fun getUserFollowing(userId: Int): List<FollowerDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(FollowersQueries.SELECT_FOLLOWING_BY_USER_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        val following = mutableListOf<FollowerDataModel>()
        while (resultSet.next()) {
            following.add(resultSet.toFollowerDataModel())
        }
        return@dbQuery following
    }

    suspend fun removeFollower(followerId: Int, followedId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(FollowersQueries.DELETE_FOLLOWER)
        statement.setInt(1, followerId)
        statement.setInt(2, followedId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toFollowerDataModel(): FollowerDataModel {
        return FollowerDataModel(
            id = getInt("id"),
            followerId = getInt("follower_id"),
            followedId = getInt("followed_id")
        )
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(dbConnection)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        }
    }
}
