package example.com.email

data class EmailConfig(
    val provider: String,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpEmail: String,
    val smtpPassword: String,
    val fromAddress: String,
)
