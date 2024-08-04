package example.com.email

import org.apache.commons.mail.SimpleEmail
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

/*
    Sends an email using the provided SimpleEmail instance. The email sending
    operation is executed asynchronously in a separate single-threaded executor.
 */
object EmailUtil {
    private val logger = LoggerFactory.getLogger(EmailUtil::class.java)

    fun sendEmail(message: SimpleEmail) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                message.send()
            } catch (e: Exception) {
                logger.error("Error sending email", e)
            }
        }
    }
}