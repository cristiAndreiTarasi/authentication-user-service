package example.com.services.ws_session

import java.util.concurrent.ConcurrentHashMap

// PermissionManager.kt
object PermissionManager {
    private val streamOwners = ConcurrentHashMap<String, String>()
    private val streamOwnerInfo = ConcurrentHashMap<String, Pair<String, String?>>()
    private val moderators = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutedUsers = ConcurrentHashMap<String, MutableSet<String>>()

    fun setStreamOwner(roomId: String, userId: String, username: String, avatarUrl: String?) {
        streamOwners[roomId] = userId
        streamOwnerInfo[userId] = Pair(username, avatarUrl)
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

    fun hasStreamOwner(roomId: String): Boolean {
        return streamOwners.containsKey(roomId)
    }

    fun removeRoom(roomId: String) {
        streamOwners.remove(roomId)?.let { ownerId ->
            streamOwnerInfo.remove(ownerId)
        }
        moderators.remove(roomId)
        mutedUsers.remove(roomId)
    }
}