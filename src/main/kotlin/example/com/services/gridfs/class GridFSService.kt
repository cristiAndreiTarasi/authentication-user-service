package example.com.services.gridfs

import com.mongodb.client.MongoDatabase
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
    private val psqlConnection: Connection
) : IGridFSService {
    companion object {
        private const val SELECT_AVATAR_ID = "SELECT image_id FROM users WHERE id = ?"
        private const val SELECT_THUMBNAIL_ID = "SELECT thumbnail_id FROM streams WHERE id = ?"
        private const val SELECT_EVENT_THUMBNAIL_ID = "SELECT thumbnail_id FROM events WHERE id = ?"
    }

    private val gridFSBuckets = GridFSBuckets.create(mongoDatabase, "images")

    override suspend fun getEventThumbnailIdByEventId(eventId: Int): String? = withContext(Dispatchers.IO) {
        val statement = psqlConnection.prepareStatement(SELECT_EVENT_THUMBNAIL_ID)
        statement.setInt(1, eventId)
        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            resultSet.getString("thumbnail_id")
        } else {
            null
        }
    }

    override suspend fun uploadImage(
        userId: Int,
        imageData: ByteArray,
        transformation: (ByteArray) -> ByteArray
    ): ObjectId = withContext(Dispatchers.IO) {
        val previousImage = getAvatarIdByUserId(userId)?.let { ObjectId(it) }
        previousImage?.let { deleteImage(it) }

        // Process the incoming image bytes to create a thumbnail with 9:16 aspect ratio.
        val processedImage = transformation(imageData)

        val options = GridFSUploadOptions().chunkSizeBytes(255 * 1024) // 255KB
        val streamToUploadFrom: InputStream = ByteArrayInputStream(processedImage)
        val fileId = gridFSBuckets.uploadFromStream("image", streamToUploadFrom, options)
        val imagesCollection = mongoDatabase.getCollection("images")
        val imageDocument = Document("userId", userId).append("imageId", fileId)

        imagesCollection.insertOne(imageDocument)
        fileId
    }

    override suspend fun fetchImage(imageId: ObjectId): ByteArray = withContext(Dispatchers.IO) {
        val streamToDownloadTo = ByteArrayOutputStream()
        gridFSBuckets.downloadToStream(imageId, streamToDownloadTo)
        streamToDownloadTo.toByteArray()
    }

    override suspend fun deleteImage(imageId: ObjectId): Boolean = withContext(Dispatchers.IO) {
        val imagesCollection = mongoDatabase.getCollection("images")
        val gridFSBucket = GridFSBuckets.create(mongoDatabase, "images")

        // Delete from metadata collection
        val deleteMetadataResult = imagesCollection.deleteOne(Document("imageId", imageId))

        // Delete from GridFS
        val deleteGridFSResult = try {
            gridFSBucket.delete(imageId)
            true
        } catch (e: Exception) {
            false
        }

        // Return true if both deletions were successful
        deleteMetadataResult.deletedCount > 0 && deleteGridFSResult
    }

    override suspend fun getAvatarIdByUserId(userId: Int): String? = withContext(Dispatchers.IO) {
        val statement = psqlConnection.prepareStatement(SELECT_AVATAR_ID)
        statement.setInt(1, userId)
        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            resultSet.getString("image_id")
        } else {
            null
        }
    }

    override suspend fun getThumbnailIdByUserId(streamId: Int): String? = withContext(Dispatchers.IO) {
        val statement = psqlConnection.prepareStatement(SELECT_THUMBNAIL_ID)
        statement.setInt(1, streamId)
        val resultSet = statement.executeQuery()

        if (resultSet.next()) {
            resultSet.getString("thumbnail_id")
        } else {
            null
        }
    }
}