package com.codingpit.muviss.core.testing

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.delay
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * A stand-in for a Supabase project, at the HTTP boundary.
 *
 * Sync's failures lived on the far side of the `SyncBackend` seam, which the
 * old in-memory fake replaced wholesale: it never serialised a request, so a
 * push that silently omitted `"rating": null` looked identical to one that
 * cleared it; and it never truncated a response, so a pull cut off at the
 * server's row cap looked identical to a complete one. This fake is a Ktor
 * [MockEngine] handler that speaks the wire format, so the real
 * `SupabaseSyncBackend` and the real `SyncEngine` both run against it and can
 * fail in the ways the real server makes them fail.
 *
 * What it implements, and where the real behaviour comes from:
 *
 * - **`Prefer: resolution=merge-duplicates` with PostgREST's column-union
 *   rule.** PostgREST builds one `INSERT ... ON CONFLICT DO UPDATE SET c =
 *   EXCLUDED.c` over the *union* of the keys in the request body. A key
 *   present in some objects and absent in others is written as `NULL` for the
 *   ones missing it; a key absent from *every* object is not in the statement
 *   at all, so the stored column is left as it was. That second half is how a
 *   client that omits nulls can never clear a column.
 * - **The last-write-wins trigger** (`discard_stale_write()` in
 *   `supabase/migrations`): an update whose `updated_at_epoch_ms` is strictly
 *   older than the stored row's is discarded and the old row — including its
 *   `server_seq` — is kept, and the response is still 2xx.
 * - **The future-timestamp clamp**: an incoming `updated_at_epoch_ms` is
 *   capped at server-now plus [clampSlackMs] before the comparison.
 * - **`server_seq`**, stamped from one sequence shared by every table. Both
 *   the insert trigger and the update trigger draw from it, so an upsert that
 *   lands on an existing row consumes *two* values — sequence gaps are normal
 *   and a client must not assume density.
 * - **`order`, `limit`, `offset` and the `gt`/`gte`/`lt`/`lte`/`eq`/`neq`
 *   filters** on any column, and **`max_rows`** ([maxRows]): the response is
 *   cut to `min(limit, maxRows)` rows, silently, with a 200. Without `order`
 *   the result is in primary-key order, which is not any order a client
 *   should rely on.
 * - **RLS by bearer token**: each registered token maps to one user, reads and
 *   writes see only that user's rows, and an unknown or revoked token is a
 *   `401`.
 * - **Injectable failures and latency**: [failWith], [dropConnection],
 *   [latencyMs] and the [onRequest] hook, which is how a test edits a local
 *   row *while* a push is in flight.
 * - **Just enough GoTrue** for token refresh: `/auth/v1/token` for
 *   `refresh_token` grants (which rotates both tokens) and `/auth/v1/signup`
 *   for anonymous sign-in, so a session can expire and an account can switch.
 *
 * It parses and validates request bodies against a schema mirroring
 * `supabase/migrations`: an unknown column is `PGRST204` and a `NULL` in a
 * `NOT NULL` column is `23502`, both `400`. If the migration and this schema
 * drift apart the fake is wrong, not the client, so change them together.
 *
 * Not modelled: commit-order visibility of concurrent sequence values (two
 * transactions committing out of sequence order), which the design bounds with
 * a periodic full reconcile rather than tests.
 */
class FakeSupabaseServer(
    /** Supabase's `[api] max_rows` (`supabase/config.toml`). The hosted project's value is not known, so tests run several. */
    var maxRows: Int = 1000,
    /** The server's clock, as epoch milliseconds. Tests pass a controllable one to age rows past the clamp window. */
    private val serverClock: () -> Long = { System.currentTimeMillis() },
    /** How far ahead of server time an `updated_at_epoch_ms` may be before it is clamped down. */
    val clampSlackMs: Long = 60_000L,
    /** Set false to model the schema as it was before the clamp existed. */
    var clampFutureTimestamps: Boolean = true,
) {

    /** One HTTP request as the server received it. */
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    ) {
        /** The table a `/rest/v1/<table>` request addressed, or null for anything else. */
        val table: String? get() = path.removePrefix("/rest/v1/").takeIf { path.startsWith("/rest/v1/") }

        val isUpsert: Boolean get() = method == "POST" && table != null
        val isSelect: Boolean get() = method == "GET" && table != null

        /** The rows of an upsert body, parsed. Empty for a request that carried none. */
        val rows: List<JsonObject>
            get() = if (body.isBlank()) emptyList() else (Json.parseToJsonElement(body) as JsonArray).map { it as JsonObject }
    }

    private class Fault(val matches: (Request) -> Boolean, val status: Int?, val body: String, var remaining: Int)

    private val lock = Any()
    private val recorded = mutableListOf<Request>()
    private val faults = mutableListOf<Fault>()

    /** Access token to user id. A token missing from here is an unauthorized request. */
    private val accessTokens = mutableMapOf<String, String>()

    /** Refresh token to user id. Single use: redeeming one rotates it. */
    private val refreshTokens = mutableMapOf<String, String>()
    private var nextSeq = 1L
    private var nextUser = 1
    private var nextToken = 1

    /** table name to (user id + key) to the stored row. */
    private val stores = mutableMapOf<String, MutableMap<String, MutableMap<String, JsonElement>>>()

    /** Milliseconds every request waits before being answered — real time, since the mock engine runs off the test scheduler. */
    var latencyMs: Long = 0L

    /**
     * Runs after a request is recorded and before it is processed. A test that
     * wants to edit a local row *during* a push does it here, then returns and
     * lets the write land — the interleaving a real network gives for free.
     * May suspend, which is how a test holds a request open.
     */
    var onRequest: (suspend (Request) -> Unit)? = null

    /** The mock engine to build an `HttpClient` over. */
    val engine: MockEngine = MockEngine { request -> handle(request) }

    /** Every request received so far, oldest first. */
    val requests: List<Request> get() = synchronized(lock) { recorded.toList() }

    fun requestsTo(table: String, method: String? = null): List<Request> = requests.filter { it.table == table && (method == null || it.method == method) }

    fun clearRecordedRequests() = synchronized(lock) { recorded.clear() }

    // ------------------------------------------------------------- accounts ---

    /** Registers a user with a live access token and refresh token, as if they had signed in. Returns the access token. */
    fun signUp(
        userId: String,
        accessToken: String = "access-$userId",
        refreshToken: String = "refresh-$userId",
    ): String = synchronized(lock) {
        accessTokens[accessToken] = userId
        refreshTokens[refreshToken] = userId
        accessToken
    }

    /** Makes [accessToken] stop working, as if it had expired; the next request carrying it is a 401. */
    fun expireAccessToken(accessToken: String) = synchronized(lock) { accessTokens.remove(accessToken) }

    /** Makes [refreshToken] stop working, as if the session had been revoked; redeeming it is a 400. */
    fun revokeRefreshToken(refreshToken: String) = synchronized(lock) { refreshTokens.remove(refreshToken) }

    // ------------------------------------------------------------- failures ---

    /** Answers matching requests with [status] instead of processing them, [times] times. */
    fun failWith(
        status: Int,
        body: String = """{"message":"injected failure"}""",
        times: Int = Int.MAX_VALUE,
        matching: (Request) -> Boolean = { true },
    ) = synchronized(lock) { faults += Fault(matching, status, body, times) }

    /** Fails matching requests as a dropped connection would — an exception, not a response. */
    fun dropConnection(times: Int = Int.MAX_VALUE, matching: (Request) -> Boolean = { true }) = synchronized(lock) { faults += Fault(matching, null, "", times) }

    fun clearFailures() = synchronized(lock) { faults.clear() }

    // ----------------------------------------------------------------- data ---

    /** [table]'s rows for [userId], in key order, as the server stores them (including `user_id` and `server_seq`). */
    fun rows(table: String, userId: String): List<JsonObject> = synchronized(lock) {
        store(table).filterKeys { it.startsWith("$userId|") }.toSortedMap().values.map { JsonObject(it) }
    }

    /** The stored row of [table] for [userId] with primary key [key] (its key columns joined by `|`, in schema order), or null. */
    fun row(table: String, userId: String, key: String): JsonObject? = synchronized(lock) { store(table)["$userId|$key"]?.let { JsonObject(it) } }

    /** Writes [row] as [userId] would have upserted it, so it goes through the same trigger logic — clamp, staleness, `server_seq`. */
    fun seed(table: String, userId: String, row: JsonObject) {
        val result = synchronized(lock) { upsert(schemaOf(table), userId, listOf(row), merge = true) }
        check(result == null) { "seed rejected: $result" }
    }

    /** The highest `server_seq` handed out so far. */
    fun lastSeq(): Long = synchronized(lock) { nextSeq - 1 }

    // -------------------------------------------------------------- handler ---

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val recordedRequest = request.toRecorded()
        synchronized(lock) { recorded += recordedRequest }
        if (latencyMs > 0) delay(latencyMs)
        onRequest?.invoke(recordedRequest)
        injectedFault(recordedRequest)?.let { fault ->
            val status = fault.status ?: throw IOException("injected connection failure")
            return json(fault.body, HttpStatusCode.fromValue(status))
        }
        return when {
            recordedRequest.path == "/auth/v1/token" -> synchronized(lock) { refresh(recordedRequest) }
            recordedRequest.path == "/auth/v1/signup" -> synchronized(lock) { signUpAnonymously() }
            recordedRequest.path == "/auth/v1/logout" -> respond("", HttpStatusCode.NoContent)
            recordedRequest.table != null -> synchronized(lock) { rest(recordedRequest) }
            else -> json("""{"message":"no route"}""", HttpStatusCode.NotFound)
        }
    }

    private fun injectedFault(request: Request): Fault? = synchronized(lock) {
        val fault = faults.firstOrNull { it.remaining > 0 && it.matches(request) } ?: return null
        fault.remaining--
        fault
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun HttpRequestData.toRecorded(): Request = Request(
        method = method.value,
        path = url.encodedPath,
        query = url.parameters.entries().associate { (name, values) -> name to values.first() },
        headers = headers.entries().associate { (name, values) -> name to values.joinToString(",") },
        body = bodyText(),
    )

    private suspend fun HttpRequestData.bodyText(): String = when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        is OutgoingContent.ReadChannelContent -> content.readFrom().drainToString()
        else -> ""
    }

    private suspend fun ByteReadChannel.drainToString(): String = readRemaining().readByteArray().decodeToString()

    // ------------------------------------------------------------------ auth ---

    private fun MockRequestHandleScope.refresh(request: Request): HttpResponseData {
        val supplied = (Json.parseToJsonElement(request.body) as? JsonObject)?.get("refresh_token")?.let { it as? JsonPrimitive }?.contentOrNull
        val userId = supplied?.let { refreshTokens.remove(it) }
            ?: return json("""{"error":"invalid_grant","error_description":"Invalid Refresh Token"}""", HttpStatusCode.BadRequest)
        return json(newSession(userId))
    }

    private fun MockRequestHandleScope.signUpAnonymously(): HttpResponseData = json(newSession("anon-${nextUser++}"))

    private fun newSession(userId: String): String {
        val access = "access-$userId-${nextToken++}"
        val refresh = "refresh-$userId-${nextToken++}"
        accessTokens[access] = userId
        refreshTokens[refresh] = userId
        return """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"user":{"id":"$userId","is_anonymous":false}}"""
    }

    // ------------------------------------------------------------------ rest ---

    private fun MockRequestHandleScope.rest(request: Request): HttpResponseData {
        val table = request.table!!
        val schema = SCHEMAS[table] ?: return json(pgError("PGRST205", "Could not find the table 'public.$table'"), HttpStatusCode.NotFound)
        if (request.headers.keys.none { it.equals("apikey", ignoreCase = true) }) {
            return json(pgError("PGRST301", "No API key found in request"), HttpStatusCode.Unauthorized)
        }
        val bearer = request.headers.entries.firstOrNull { it.key.equals(HttpHeaders.Authorization, ignoreCase = true) }?.value?.removePrefix("Bearer ")
        val userId = bearer?.let { accessTokens[it] } ?: return json(pgError("PGRST301", "JWT expired"), HttpStatusCode.Unauthorized)
        return when (request.method) {
            "POST" -> {
                val merge = request.headers.entries.any { it.key.equals("Prefer", ignoreCase = true) && "resolution=merge-duplicates" in it.value }
                val rows = runCatching { request.rows }.getOrElse { return json(pgError("PGRST102", "Empty or invalid json"), HttpStatusCode.BadRequest) }
                val failure = upsert(schema, userId, rows, merge)
                if (failure == null) respond("", HttpStatusCode.NoContent) else json(failure, HttpStatusCode.BadRequest)
            }

            "GET" -> json(select(schema, userId, request.query).toString())

            else -> json(pgError("PGRST117", "Unsupported HTTP method: ${request.method}"), HttpStatusCode.MethodNotAllowed)
        }
    }

    /**
     * The upsert path, mirroring what PostgREST issues and what the triggers
     * then do. Returns an error body, or null on success. All-or-nothing:
     * one bad row rejects the whole request, as a single SQL statement does.
     */
    private fun upsert(schema: TableSchema, userId: String, rows: List<JsonObject>, merge: Boolean): String? {
        val union = LinkedHashSet<String>().apply { rows.forEach { addAll(it.keys) } }
        union.firstOrNull { it !in schema.columns }?.let { return pgError("PGRST204", "Could not find the '$it' column of '${schema.name}' in the schema cache") }
        for (row in rows) {
            for ((name, value) in row) schema.columns.getValue(name).typeError(value)?.let { return pgError("22P02", "column \"$name\": $it") }
        }
        val stored = store(schema.name)
        // Written to an overlay and committed at the end, so a rejected row leaves the table untouched.
        val overlay = mutableMapOf<String, MutableMap<String, JsonElement>>()
        var seq = nextSeq
        for (row in rows) {
            // json_populate_recordset: a key in the union but missing from this object is NULL, not "absent".
            val payload = union.associateWith { row[it] ?: JsonNull }
            val keyParts = schema.key.map { (payload[it] as? JsonPrimitive)?.contentOrNull }
            if (keyParts.any { it == null }) return pgError("23502", "null value in a primary key column of \"${schema.name}\"")
            val storeKey = "$userId|${keyParts.joinToString("|")}"

            // BEFORE INSERT trigger: fires for every proposed row, conflict or not, and draws from the sequence.
            val proposed = LinkedHashMap<String, JsonElement>()
            for ((name, column) in schema.columns) {
                proposed[name] = if (name in union) payload.getValue(name) else column.default ?: JsonNull
            }
            proposed["user_id"] = JsonPrimitive(userId)
            proposed[SERVER_SEQ] = JsonPrimitive(seq++)
            clamp(proposed)

            if (storeKey in overlay) return pgError("21000", "ON CONFLICT DO UPDATE command cannot affect row a second time")
            val existing = stored[storeKey]
            if (existing == null) {
                schema.notNullViolation(proposed)?.let { return pgError("23502", it) }
                overlay[storeKey] = proposed
            } else {
                if (!merge) return pgError("23505", "duplicate key value violates unique constraint \"${schema.name}_pkey\"")
                // ON CONFLICT DO UPDATE SET c = EXCLUDED.c, for the union's non-key columns only.
                val candidate = existing.toMutableMap()
                for (name in union) if (name !in schema.key) candidate[name] = proposed.getValue(name)
                schema.notNullViolation(candidate)?.let { return pgError("23502", it) }
                // BEFORE UPDATE trigger: clamp, then discard a strictly older write, keeping the old row and its seq.
                clamp(candidate)
                if (candidate.updatedAt() < existing.updatedAt()) {
                    overlay[storeKey] = existing
                    continue
                }
                candidate[SERVER_SEQ] = JsonPrimitive(seq++)
                overlay[storeKey] = candidate
            }
        }
        nextSeq = seq
        stored.putAll(overlay)
        return null
    }

    private fun clamp(row: MutableMap<String, JsonElement>) {
        if (!clampFutureTimestamps) return
        val ceiling = serverClock() + clampSlackMs
        if (row.updatedAt() > ceiling) row[UPDATED_AT] = JsonPrimitive(ceiling)
    }

    private fun Map<String, JsonElement>.updatedAt(): Long = (getValue(UPDATED_AT) as JsonPrimitive).longOrNull ?: error("$UPDATED_AT is not a number")

    private fun select(schema: TableSchema, userId: String, query: Map<String, String>): JsonArray {
        var rows: List<Map<String, JsonElement>> = store(schema.name).filterKeys { it.startsWith("$userId|") }.toSortedMap().values.toList()
        for ((name, expression) in query) {
            if (name in RESERVED_PARAMETERS) continue
            rows = rows.filter { row -> matches(row[name] ?: JsonNull, expression) }
        }
        query["order"]?.let { order ->
            val comparator = order.split(',').map { term ->
                val (column, direction) = term.split('.').let { it[0] to (it.getOrNull(1) ?: "asc") }
                Comparator<Map<String, JsonElement>> { a, b -> compareValues(a[column], b[column]) }.let { if (direction == "desc") it.reversed() else it }
            }.reduce { first, second -> first.then(second) }
            rows = rows.sortedWith(comparator)
        }
        val offset = query["offset"]?.toInt() ?: 0
        val limit = minOf(query["limit"]?.toInt() ?: Int.MAX_VALUE, maxRows)
        return JsonArray(rows.drop(offset).take(limit).map { JsonObject(it) })
    }

    private fun matches(value: JsonElement, expression: String): Boolean {
        val (operator, operand) = expression.split('.', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val comparison = compareValues(value, operand.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(operand))
        return when (operator) {
            "eq" -> comparison == 0
            "neq" -> comparison != 0
            "gt" -> comparison > 0
            "gte" -> comparison >= 0
            "lt" -> comparison < 0
            "lte" -> comparison <= 0
            else -> error("unsupported operator $operator")
        }
    }

    private fun compareValues(a: JsonElement?, b: JsonElement?): Int {
        val left = a as? JsonPrimitive
        val right = b as? JsonPrimitive
        val leftNumber = left?.longOrNull
        val rightNumber = right?.longOrNull
        return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) else (left?.contentOrNull ?: "").compareTo(right?.contentOrNull ?: "")
    }

    private fun store(table: String): MutableMap<String, MutableMap<String, JsonElement>> = stores.getOrPut(table) { mutableMapOf() }

    private fun schemaOf(table: String): TableSchema = SCHEMAS[table] ?: error("no such table $table")

    private fun pgError(code: String, message: String): String = buildJsonObject {
        put("code", code)
        put("message", message)
    }.toString()

    // ---------------------------------------------------------------- schema ---

    private enum class Type { TEXT, INTEGER, BOOLEAN }

    private class Column(val type: Type, val nullable: Boolean = false, val default: JsonElement? = null) {
        fun typeError(value: JsonElement): String? {
            if (value is JsonNull) return null
            val primitive = value as? JsonPrimitive ?: return "expected a scalar"
            return when (type) {
                Type.TEXT -> if (primitive.isString) null else "expected text, got ${primitive.content}"
                Type.INTEGER -> if (!primitive.isString && primitive.longOrNull != null) null else "expected an integer, got ${primitive.content}"
                Type.BOOLEAN -> if (!primitive.isString && runCatching { primitive.boolean }.isSuccess) null else "expected a boolean, got ${primitive.content}"
            }
        }
    }

    private class TableSchema(val name: String, val key: List<String>, val columns: Map<String, Column>) {
        /** Every NOT NULL column must hold a value, and `user_id` and `server_seq` always do. */
        fun notNullViolation(row: Map<String, JsonElement>): String? = columns.entries
            .firstOrNull { (column, spec) -> !spec.nullable && (row[column] ?: JsonNull) is JsonNull }
            ?.let { "null value in column \"${it.key}\" of relation \"$name\" violates not-null constraint" }
    }

    private companion object {
        const val UPDATED_AT = "updated_at_epoch_ms"
        const val SERVER_SEQ = "server_seq"
        val RESERVED_PARAMETERS = setOf("select", "order", "limit", "offset")

        private val text = Column(Type.TEXT)
        private val nullableText = Column(Type.TEXT, nullable = true)
        private val integer = Column(Type.INTEGER)
        private val nullableInteger = Column(Type.INTEGER, nullable = true)
        private fun integerDefault(value: Long) = Column(Type.INTEGER, default = JsonPrimitive(value))
        private fun booleanDefault(value: Boolean) = Column(Type.BOOLEAN, default = JsonPrimitive(value))
        private val nullableBoolean = Column(Type.BOOLEAN, nullable = true)
        private fun textDefault(value: String) = Column(Type.TEXT, default = JsonPrimitive(value))

        /** Mirrors `supabase/migrations/` — the sync schema plus everything later migrations added. Keep them in step. */
        val SCHEMAS: Map<String, TableSchema> = listOf(
            TableSchema(
                "collection_entry",
                listOf("media_id"),
                linkedMapOf(
                    "media_id" to text,
                    "media_type" to text,
                    "title" to text,
                    "poster_url" to nullableText,
                    "release_year" to nullableInteger,
                    "production_status" to text,
                    "total_episodes" to integerDefault(0),
                    "aired_episodes" to integerDefault(0),
                    "favorite" to booleanDefault(false),
                    "genres" to textDefault(""),
                    "runtime_minutes" to nullableInteger,
                    "added_at_epoch_ms" to integer,
                    UPDATED_AT to integer,
                    "deleted" to booleanDefault(false),
                    "rating" to nullableInteger,
                    "note" to nullableText,
                    // EPIC 41 (ADR 0022). `revisit_willingness` is nullable and
                    // its null is meaningful — "never answered", not "no" — so
                    // it is one of the columns the push must send explicitly.
                    "revisit_willingness" to nullableBoolean,
                    "cowatch_pinned" to booleanDefault(false),
                ),
            ),
            TableSchema(
                "episode_progress",
                listOf("episode_id"),
                linkedMapOf(
                    "episode_id" to text,
                    "media_id" to text,
                    "season_number" to integer,
                    "episode_number" to integer,
                    "seen" to booleanDefault(false),
                    UPDATED_AT to integer,
                ),
            ),
            TableSchema(
                "media_list",
                listOf("id"),
                linkedMapOf(
                    "id" to text,
                    "name" to text,
                    "created_at_epoch_ms" to integer,
                    UPDATED_AT to integer,
                    "deleted" to booleanDefault(false),
                ),
            ),
            TableSchema(
                "list_entry",
                listOf("list_id", "media_id"),
                linkedMapOf(
                    "list_id" to text,
                    "media_id" to text,
                    "added_at_epoch_ms" to integer,
                    UPDATED_AT to integer,
                    "deleted" to booleanDefault(false),
                ),
            ),
            TableSchema(
                "triage_decision",
                listOf("media_id"),
                linkedMapOf(
                    "media_id" to text,
                    "media_type" to text,
                    "verdict" to text,
                    "title" to text,
                    "poster_url" to nullableText,
                    "decided_at_epoch_ms" to integer,
                    "resolved" to booleanDefault(true),
                    UPDATED_AT to integer,
                    "deleted" to booleanDefault(false),
                ),
            ),
            TableSchema(
                "episode_play",
                listOf("id"),
                linkedMapOf(
                    "id" to text,
                    "episode_id" to text,
                    "media_id" to text,
                    "watched_at_epoch_ms" to integer,
                    UPDATED_AT to integer,
                    "deleted" to booleanDefault(false),
                ),
            ),
        ).associateBy { it.name }
    }
}
