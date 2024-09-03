package example.com.schemas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

@Serializable
data class CategoryDataModel(
    val id: Int,
    val name: String,
    val description: String
)

class CategorySchema(private val dbConnection: Connection) {
    companion object {
        private const val INSERT_CATEGORY = "INSERT INTO categories (name, description) VALUES (?, ?)"
        private const val SELECT_CATEGORY_BY_ID = "SELECT * FROM categories WHERE id = ?"
        private const val SELECT_ALL_CATEGORIES = "SELECT * FROM categories"
        private const val DELETE_CATEGORY = "DELETE FROM categories WHERE id = ?"
    }

    suspend fun addCategory(category: CategoryDataModel): Int = dbQuery { connection ->
        val statement = connection.prepareStatement(INSERT_CATEGORY, Statement.RETURN_GENERATED_KEYS)
        statement.setString(1, category.name)
        statement.setString(2, category.description)
        statement.executeUpdate()

        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
            return@dbQuery generatedKeys.getInt(1)
        } else {
            throw Exception("Unable to retrieve the id of the newly inserted category")
        }
    }

    suspend fun getCategoryById(categoryId: Int): CategoryDataModel? = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_CATEGORY_BY_ID)
        statement.setInt(1, categoryId)
        val resultSet = statement.executeQuery()

        return@dbQuery if (resultSet.next()) resultSet.toCategoryDataModel() else null
    }

    suspend fun getAllCategories(): List<CategoryDataModel> = dbQuery { connection ->
        val statement = connection.prepareStatement(SELECT_ALL_CATEGORIES)
        val resultSet = statement.executeQuery()

        val categories = mutableListOf<CategoryDataModel>()
        while (resultSet.next()) {
            categories.add(resultSet.toCategoryDataModel())
        }
        return@dbQuery categories
    }

    suspend fun deleteCategory(categoryId: Int): Boolean = dbQuery { connection ->
        val statement = connection.prepareStatement(DELETE_CATEGORY)
        statement.setInt(1, categoryId)
        statement.executeUpdate() > 0
    }

    private fun ResultSet.toCategoryDataModel(): CategoryDataModel {
        return CategoryDataModel(
            id = getInt("id"),
            name = getString("name"),
            description = getString("description")
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
