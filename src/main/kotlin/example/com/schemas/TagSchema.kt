package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.*

@Serializable
data class TagDataModel(
    val id: Int,
    val name: String
)

class TagSchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_TAG = "INSERT INTO tags (name) VALUES (?)"
        private const val SELECT_TAG_BY_ID = "SELECT * FROM tags WHERE id = ?"
        private const val SELECT_ALL_TAGS = "SELECT * FROM tags"
        private const val DELETE_TAG = "DELETE FROM tags WHERE id = ?"
    }

    suspend fun addTag(tag: TagDataModel): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_TAG, Statement.RETURN_GENERATED_KEYS)
        statement.setString(1, tag.name)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted tag")
        }
    }

    suspend fun getTagById(tagId: Int): TagDataModel? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_TAG_BY_ID)
        statement.setInt(1, tagId)
        val resultSet = statement.executeQuery()

        return@dbQuery if (resultSet.next()) resultSet.toTagDataModel() else null
    }

    suspend fun getAllTags(): List<TagDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_TAGS)
        val resultSet = statement.executeQuery()

        val tags = mutableListOf<TagDataModel>()
        while (resultSet.next()) {
            tags.add(resultSet.toTagDataModel())
        }
        return@dbQuery tags
    }

    suspend fun deleteTag(tagId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_TAG)
        statement.setInt(1, tagId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toTagDataModel(): TagDataModel {
        return TagDataModel(
            id = getInt("id"),
            name = getString("name")
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
