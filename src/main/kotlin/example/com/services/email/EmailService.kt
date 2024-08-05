package example.com.services.email

import org.apache.commons.mail.DefaultAuthenticator
import org.apache.commons.mail.EmailException
import org.apache.commons.mail.HtmlEmail

data class EmailConfig(
    val provider: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpEmail: String,
    val smtpPassword: String,
    val fromAddress: String,
)

interface IEmailService {
    // Sends an email to the specified recipient with the given subject and body.
    suspend fun sendEmail(to: String, subject: String, body: String)
}

class EmailService(private val config: EmailConfig) : IEmailService {
    override suspend fun sendEmail(to: String, subject: String, body: String) {
//        val message = SimpleEmail()
        val message = HtmlEmail()

        message.hostName = config.smtpHost
        message.setSmtpPort(config.smtpPort)
        message.setAuthenticator(DefaultAuthenticator(config.smtpEmail, config.smtpPassword))
        message.isSSLOnConnect = true
        message.isStartTLSEnabled = false
        message.setFrom(config.fromAddress)
        message.addTo(to)
        message.subject = subject
        message.setHtmlMsg(body)

        try {
            message.send()
        } catch (e: EmailException) {
            // Handle exception here
            e.printStackTrace()
        }
    }
}