package example.com.plugins

import io.ktor.server.application.*
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

fun Application.connectToPostgres(embedded: Boolean): Connection {
    Class.forName("org.postgresql.Driver")

    println("Embedded flag value: $embedded")

    return if (embedded) {
        println("Connecting to embedded H2 database.*********************************************************************")
        return DriverManager.getConnection("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "root", "")
    } else {
        val url = environment.config.property("postgres.url").getString()
        val user = environment.config.property("postgres.user").getString()
        val password = environment.config.property("postgres.password").getString()

        try {
            println("Connecting to PostgreSQL database at $url ***********************************************************")
            DriverManager.getConnection(url, user, password)
        } catch (e: SQLException) {
            println("Error connecting to PostgreSQL database: ${e.message} ***********************************************")
            throw e
        }
    }
}