package com.codingpit.muviss.core.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.driver.jdbc.ConnectionManager
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Desktop's file driver: SQLDelight's `JdbcSqliteDriver` for a file URL, except that a
 * transaction starts with `BEGIN IMMEDIATE`.
 *
 * Like that driver, it gives each thread its own connection. SQLDelight starts its
 * transactions with a plain, deferred `BEGIN TRANSACTION` (and ignores sqlite-jdbc's
 * `transaction_mode`), so two transactions that both read and then write — a tick's
 * `playCountOf`, then `insertPlay`, on two IO threads — both take a read lock first. The second
 * to write cannot upgrade it without a deadlock, so SQLite answers SQLITE_BUSY at once instead
 * of waiting out the busy timeout, and that transaction rolls back: the tick is lost. An
 * immediate transaction takes the write lock when it starts, so the second one waits there
 * (sqlite-jdbc's busy timeout, 3 s) and both commit. Android and iOS write through a single
 * connection and never reach this.
 *
 * `JdbcSqliteDriver` is final, hence a copy rather than a subclass. Listener bookkeeping and
 * the per-thread connections mirror its `ThreadedConnectionManager`: a connection outside a
 * transaction is closed after each statement.
 */
internal class ImmediateTransactionSqliteDriver(private val url: String, private val properties: Properties = Properties()) : JdbcDriver() {

    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()
    private val transactions = ThreadLocal<ConnectionManager.Transaction?>()
    private val connections = ThreadLocal<Connection?>()

    override var transaction: ConnectionManager.Transaction?
        get() = transactions.get()
        set(value) = transactions.set(value)

    override fun getConnection(): Connection = connections.get() ?: DriverManager.getConnection(url, properties).also(connections::set)

    override fun closeConnection(connection: Connection) {
        check(connections.get() == connection) { "Connections must be closed on the thread that opened them" }
        if (transaction == null) {
            connection.close()
            connections.remove()
        }
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN IMMEDIATE TRANSACTION").use { it.execute() }
    }

    override fun Connection.endTransaction() {
        prepareStatement("END TRANSACTION").use { it.execute() }
    }

    override fun Connection.rollbackTransaction() {
        prepareStatement("ROLLBACK TRANSACTION").use { it.execute() }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it, ::linkedSetOf).add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(toNotify::addAll) }
        }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }

    override fun close() = Unit
}
