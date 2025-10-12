package example.com.services.gridfs

import com.mongodb.client.MongoDatabase
import com.mongodb.client.gridfs.GridFSBucket
import com.mongodb.client.gridfs.GridFSBuckets
import com.mongodb.client.gridfs.model.GridFSUploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bson.Document
import org.bson.types.ObjectId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Interface for handling image-related operations using GridFS.
 */
interface IGridFSService {
    /**
     * Uploads an image to GridFS.
     *
     * This method uploads the given image data to GridFS and associates it with the specified user.
     * If the user already has an image stored, the previous image is replaced with the new one.
     *
     * @param userId The ID of the user for whom the image is being uploaded.
     * @param imageData The byte array representing the image to be uploaded.
     * @return ObjectId The unique identifier for the uploaded image in GridFS.
     */
    suspend fun uploadImage(userId: Int, imageData: ByteArray, transformation: (ByteArray) -> ByteArray): ObjectId

    /**
     * Fetches an image from GridFS by its ID.
     *
     * This method retrieves the image data stored in GridFS corresponding to the provided image ID.
     *
     * @param imageId The unique identifier of the image in GridFS.
     * @return ByteArray The byte array representing the fetched image.
     */
    suspend fun fetchImage(imageId: ObjectId): ByteArray

    /**
     * Deletes an image from GridFS.
     *
     * This method deletes the image associated with the provided image ID from both GridFS and its metadata.
     *
     * @param imageId The unique identifier of the image to be deleted in GridFS.
     * @return Boolean `true` if the image was successfully deleted, `false` otherwise.
     */
    suspend fun deleteImage(imageId: ObjectId): Boolean

    /**
     * Retrieves the image ID associated with a specific user.
     *
     * This method queries the database to find the image ID linked to the specified user.
     * If no image is found for the user, it returns `null`.
     *
     * @param userId The ID of the user whose image ID is being retrieved.
     * @return String? The image ID as a string, or `null` if no image is found for the user.
     */
    suspend fun getAvatarIdByUserId(userId: Int): String?

    suspend fun getThumbnailIdByUserId(userId: Int): String?

    suspend fun getEventThumbnailIdByEventId(eventId: Int): String?
}

class GridFSService(
    private val mongoDatabase: MongoDatabase,
    private val dataSource: DataSource
) : IGridFSService {
    companion object {
        private const val SELECT_AVATAR_ID = "SELECT image_id FROM users WHERE id = ?"
        private const val SELECT_THUMBNAIL_ID = "SELECT thumbnail_id FROM streams WHERE id = ?"
        private const val SELECT_EVENT_THUMBNAIL_ID = "SELECT thumbnail_id FROM events WHERE id = ?"
    }

    private val gridFSBucket: GridFSBucket = GridFSBuckets.create(mongoDatabase, "images")
    private val imagesCollection = mongoDatabase.getCollection("images")

    override suspend fun getEventThumbnailIdByEventId(eventId: Int): String? = dbQuery { conn ->
        conn.prepareStatement(SELECT_EVENT_THUMBNAIL_ID).use { stmt ->
            stmt.setInt(1, eventId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("thumbnail_id") else null
            }
        }
    }

    override suspend fun uploadImage(
        userId: Int,
        imageData: ByteArray,
        transformation: (ByteArray) -> ByteArray
    ): ObjectId = withContext(Dispatchers.IO) {
        // delete previous avatar if any — but the caller is responsible for transactional semantics
        val processed = transformation(imageData)
        val options = com.mongodb.client.gridfs.model.GridFSUploadOptions().chunkSizeBytes(255 * 1024)
        val stream = ByteArrayInputStream(processed)
        val fileId = gridFSBucket.uploadFromStream("image", stream, options)
        imagesCollection.insertOne(Document("userId", userId).append("imageId", fileId))
        fileId
    }

    override suspend fun fetchImage(imageId: ObjectId): ByteArray = withContext(Dispatchers.IO) {
        val baos = ByteArrayOutputStream()
        gridFSBucket.downloadToStream(imageId, baos)
        baos.toByteArray()
    }

    override suspend fun deleteImage(imageId: ObjectId): Boolean = withContext(Dispatchers.IO) {
        // delete metadata
        val deleteMetadata = imagesCollection.deleteOne(Document("imageId", imageId))
        // delete GridFS file
        val deletedGridFs = try {
            gridFSBucket.delete(imageId)
            true
        } catch (e: Exception) {
            false
        }
        deleteMetadata.deletedCount > 0 && deletedGridFs
    }

    override suspend fun getAvatarIdByUserId(userId: Int): String? = dbQuery { conn ->
        conn.prepareStatement(SELECT_AVATAR_ID).use { stmt ->
            stmt.setInt(1, userId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("image_id") else null
            }
        }
    }

    override suspend fun getThumbnailIdByUserId(streamId: Int): String? = dbQuery { conn ->
        conn.prepareStatement(SELECT_THUMBNAIL_ID).use { stmt ->
            stmt.setInt(1, streamId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getString("thumbnail_id") else null
            }
        }
    }

    private suspend fun <T> dbQuery(block: suspend (Connection) -> T): T = withContext(Dispatchers.IO) {
        var conn: Connection? = null
        try {
            conn = dataSource.connection
            block(conn)
        } catch (e: SQLException) {
            throw RuntimeException("Database query failed: ${e.message}", e)
        } finally {
            try { conn?.close() } catch (_: Exception) {}
        }
    }
}