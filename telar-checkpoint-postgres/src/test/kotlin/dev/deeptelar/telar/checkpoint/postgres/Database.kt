package dev.deeptelar.telar.checkpoint.postgres

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** One PostgreSQL for all the tests of this module, started from the binaries in a jar: no Docker. */
internal object Database {
    private val server: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
    private val count = AtomicInteger()

    /** A new, empty database, as a test needs it. */
    fun fresh(): DataSource {
        val name = "test_${count.incrementAndGet()}"
        server.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        return server.getDatabase("postgres", name)
    }
}

/** Runs [sql] on [dataSource] outside of a checkpointer or a store. */
internal fun DataSource.execute(sql: String) {
    connection.use { it.createStatement().execute(sql) }
}
