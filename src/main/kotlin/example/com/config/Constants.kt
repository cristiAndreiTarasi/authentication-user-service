package example.com.config

import io.github.cdimascio.dotenv.dotenv

object Constants {
    private val dotenv = dotenv()

    val JWT_SECRET: String = dotenv["JWT_SECRET"]

    val MONGODB_PASSWORD: String = dotenv["MONGODB_PASSWORD"]
    val MONGODB_CLUSTER: String = dotenv["MONGODB_CLUSTER"]
    val MONGODB_USER: String = dotenv["MONGODB_USER"]
    val CONNECTION_STRING = "mongodb+srv://$MONGODB_USER:$MONGODB_PASSWORD@$MONGODB_CLUSTER.vf4jn3v.mongodb.net/?retryWrites=true&w=majority"

}