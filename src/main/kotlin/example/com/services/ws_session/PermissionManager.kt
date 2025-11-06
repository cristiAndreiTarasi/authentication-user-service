package example.com.services.ws_session

import java.util.concurrent.ConcurrentHashMap

object PermissionManager {
    private val streamOwners = ConcurrentHashMap<String, String>()
    private val streamOwnerInfo = ConcurrentHashMap<String, Pair<String, String?>>()
    private val moderators = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutedUsers = ConcurrentHashMap<String, MutableSet<String>>()
    private val kickedUsers = ConcurrentHashMap<String, MutableSet<String>>()
    private val roomCreationTimes = ConcurrentHashMap<String, Long>()

    fun setStreamOwner(roomId: String, userId: String, username: String, avatarUrl: String?) {
        streamOwners[roomId] = userId
        streamOwnerInfo[userId] = Pair(username, avatarUrl)
        roomCreationTimes[roomId] = System.currentTimeMillis()
    }

    fun getStreamOwnerInfo(ownerId: String): Pair<String, String?>? {
        return streamOwnerInfo[ownerId]
    }

    fun getStreamOwner(roomId: String): String? {
        return streamOwners[roomId]
    }

    fun isStreamOwner(roomId: String, userId: String): Boolean {
        return streamOwners[roomId] == userId
    }

    fun isModerator(roomId: String, userId: String): Boolean {
        return moderators[roomId]?.contains(userId) ?: false
    }

    fun isMuted(roomId: String, userId: String): Boolean {
        return mutedUsers[roomId]?.contains(userId) ?: false
    }

    fun isKicked(roomId: String, userId: String): Boolean {
        return kickedUsers[roomId]?.contains(userId) ?: false
    }

    fun grantModerator(roomId: String, userId: String) {
        moderators.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
    }

    fun revokeModerator(roomId: String, userId: String) {
        moderators[roomId]?.remove(userId)
    }

    fun muteUser(roomId: String, userId: String) {
        mutedUsers.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
    }

    fun unmuteUser(roomId: String, userId: String) {
        mutedUsers[roomId]?.remove(userId)
    }

    fun kickUser(roomId: String, userId: String) {
        kickedUsers.getOrPut(roomId) { ConcurrentHashMap.newKeySet() }.add(userId)
    }

    fun hasStreamOwner(roomId: String): Boolean {
        return streamOwners.containsKey(roomId)
    }

    fun cleanupOldRooms(maxAgeHours: Long = 6) {
        val now = System.currentTimeMillis()
        val maxAgeMillis = maxAgeHours * 60 * 60 * 1000

        roomCreationTimes.forEach { (roomId, createTime) ->
            if (now - createTime > maxAgeMillis) {
                // Remove stale room data
                removeRoom(roomId)
                roomCreationTimes.remove(roomId)
            }
        }
    }

    fun removeRoom(roomId: String) {
        streamOwners.remove(roomId)?.let { ownerId ->
            streamOwnerInfo.remove(ownerId)
        }
        moderators.remove(roomId)
        mutedUsers.remove(roomId)
        kickedUsers.remove(roomId)
    }
}