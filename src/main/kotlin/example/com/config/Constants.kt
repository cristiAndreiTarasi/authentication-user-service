package example.com.config

import io.github.cdimascio.dotenv.dotenv

object Constants {
    private val dotenv = dotenv()

    val JWT_SECRET: String = dotenv["JWT_SECRET"]
}