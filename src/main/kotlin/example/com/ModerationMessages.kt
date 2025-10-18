package example.com

// Message templates for different moderation scenarios
object ModerationMessages {
    fun getWarningMessage(severity: ModerationSeverity, reason: ModerationReason): String {
        return when (severity) {
            ModerationSeverity.WARNING -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Inappropriate content detected. Please adjust your stream content."
                ModerationReason.VIOLENT_CONTENT -> "Violent content detected. Please adjust your stream content."
                ModerationReason.MANUAL -> "Content policy violation detected. Please review community guidelines."
                ModerationReason.OTHER -> "Content policy violation detected. Please review community guidelines."
            }
            ModerationSeverity.BLOCKED -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Video feed temporarily blocked due to sexual content violations. Audio continues."
                ModerationReason.VIOLENT_CONTENT -> "Video feed temporarily blocked due to violent content violations. Audio continues."
                ModerationReason.MANUAL -> "Video feed temporarily blocked due to content policy violations. Audio continues."
                ModerationReason.OTHER -> "Video feed temporarily blocked due to content policy violations. Audio continues."
            }
            ModerationSeverity.TERMINATED -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "Stream terminated for repeated sexual content violations."
                ModerationReason.VIOLENT_CONTENT -> "Stream terminated for repeated violent content violations."
                ModerationReason.MANUAL -> "Stream terminated by moderator."
                ModerationReason.OTHER -> "Stream terminated for repeated content policy violations."
            }
        }
    }

    fun getTerminationMessage(reason: ModerationReason): String {
        return when (reason) {
            ModerationReason.SEXUAL_CONTENT -> "Stream terminated due to repeated sexual content violations."
            ModerationReason.VIOLENT_CONTENT -> "Stream terminated due to repeated violent content violations."
            ModerationReason.MANUAL -> "Stream terminated by moderator."
            ModerationReason.OTHER -> "Stream terminated due to content policy violations."
        }
    }

    // Add streamer-specific messages
    fun getStreamerTerminationMessage(reason: ModerationReason): String {
        return when (reason) {
            ModerationReason.SEXUAL_CONTENT -> "Your stream was terminated for sexual content violations."
            ModerationReason.VIOLENT_CONTENT -> "Your stream was terminated for violent content violations."
            ModerationReason.MANUAL -> "Your stream was terminated by a moderator."
            ModerationReason.OTHER -> "Your stream was terminated due to content policy violations."
        }
    }

    fun getStreamerWarningMessage(severity: ModerationSeverity, reason: ModerationReason): String {
        return when (severity) {
            ModerationSeverity.WARNING -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "WARNING: Inappropriate content detected in your stream. Please adjust your content immediately to avoid restrictions."
                ModerationReason.VIOLENT_CONTENT -> "WARNING: Violent content detected in your stream. Please adjust your content immediately to avoid restrictions."
                ModerationReason.MANUAL -> "WARNING: Content policy violation detected. Please review community guidelines."
                ModerationReason.OTHER -> "WARNING: Content policy violation detected. Please review community guidelines."
            }
            ModerationSeverity.BLOCKED -> when (reason) {
                ModerationReason.SEXUAL_CONTENT -> "BLOCKED: Your video feed has been temporarily blocked for viewers due to sexual content. You can continue streaming - video will restore automatically after clean content."
                ModerationReason.VIOLENT_CONTENT -> "BLOCKED: Your video feed has been temporarily blocked for viewers due to violent content. You can continue streaming - video will restore automatically after clean content."
                ModerationReason.MANUAL -> "BLOCKED: Your video feed has been temporarily blocked for viewers. You can continue streaming."
                ModerationReason.OTHER -> "BLOCKED: Your video feed has been temporarily blocked for viewers. You can continue streaming."
            }
            ModerationSeverity.TERMINATED -> getStreamerTerminationMessage(reason)
        }
    }
}