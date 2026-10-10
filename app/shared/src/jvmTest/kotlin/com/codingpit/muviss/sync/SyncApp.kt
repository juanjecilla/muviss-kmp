package com.codingpit.muviss.sync

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.codingpit.muviss.core.billing.Entitlement
import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.sync.AutomaticSyncSettings
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.SyncTiming
import com.codingpit.muviss.core.sync.supabase.createSupabaseSyncBackend
import com.codingpit.muviss.core.testing.CountingDriver
import com.codingpit.muviss.core.testing.FakeSupabaseServer
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal const val FAKE_PROJECT_URL = "https://project.supabase.co"

/** A clock that reads whatever [nowMs] says, so a test can tie it to a scheduler or to the wall clock. */
internal class LambdaClock(private val nowMs: () -> Long) : AppClock {
    override fun nowEpochMs(): Long = nowMs()
}

internal class ControllableEntitlements(entitlement: Entitlement) : EntitlementProvider {
    val state = MutableStateFlow(entitlement)
    override val entitlement = state
    override suspend fun refresh() = Unit
}

/**
 * One installation of the app: the real `appModules` assembled into a Koin
 * graph of its own, over an in-memory database, with the **real**
 * `SyncEngine` and the **real** Supabase backend talking to a shared
 * [FakeSupabaseServer]. Only the things a test has to control are replaced:
 * the dispatchers and clock (virtual time), the entitlement, and whether the
 * build ships background sync — the things `local.properties` decides in a
 * real build.
 *
 * A private `koinApplication` rather than `startKoin`, so two installations
 * (two devices) can coexist in one test.
 *
 * With [timing] null the coordinator is the production binding, 5 s debounce
 * and all; a test that cannot use virtual time (the Compose one) passes a
 * shorter one and gets a coordinator built the same way.
 */
@Suppress("LongParameterList") // a fixture: each argument is a named default a test may override
internal class SyncApp(
    val server: FakeSupabaseServer,
    dispatcher: CoroutineDispatcher,
    clock: AppClock,
    val userId: String = "alice",
    entitlement: Entitlement = Entitlement.Active,
    backgroundAvailable: Boolean = true,
    timing: SyncTiming? = null,
) {
    val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { MuvissDatabase.Schema.synchronous().create(it) })
    val database = MuvissDatabase(driver)
    val entitlements = ControllableEntitlements(entitlement)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    val koin: Koin = koinApplication {
        allowOverride(true)
        modules(
            appModules + module {
                single { database }
                single<AppClock> { clock }
                single<AppDispatchers> {
                    object : AppDispatchers {
                        override val default: CoroutineDispatcher = dispatcher
                        override val io: CoroutineDispatcher = dispatcher
                    }
                }
                single<SyncAvailability> {
                    object : SyncAvailability {
                        override fun isConfigured() = true
                        override fun isBackgroundAvailable() = backgroundAvailable
                    }
                }
                single<EntitlementProvider> { entitlements }
                single<SyncBackend> {
                    createSupabaseSyncBackend(
                        client = HttpClient(server.engine) {
                            install(ContentNegotiation) {
                                json(
                                    Json {
                                        ignoreUnknownKeys = true
                                        isLenient = true
                                        explicitNulls = false
                                    },
                                )
                            }
                        },
                        baseUrl = FAKE_PROJECT_URL,
                        anonKey = "anon-key",
                        sessionStore = get<SyncSessionStore>(),
                        clock = get(),
                    )
                }
                if (timing != null) {
                    single {
                        val engine = get<SyncEngine>()
                        SyncCoordinator(engine, engine.observePendingChanges(), get<AutomaticSyncSettings>().enabled, get(), scope, timing)
                    }
                }
            },
        )
    }.koin

    val engine: SyncEngine get() = koin.get()
    val coordinator: SyncCoordinator get() = koin.get()
    val collection: CollectionApi get() = koin.get()
    val flags: FeatureFlags get() = koin.get()

    /** Puts a live session in the store the backend restores from, as a previous sign-in would have left it. */
    suspend fun signIn() {
        koin.get<SyncSessionStore>().save(
            SyncSession(SyncBackendId.SUPABASE, userId, "$userId@example.com", server.signUp(userId), "refresh-$userId", null),
        )
    }

    /** Starts what `MuvissApp` and `MuvissApplication` start: the coordinator. */
    fun start() = coordinator.start()

    suspend fun addMovie(id: String = "603", title: String = "The Matrix") = collection.add(MediaDetails(MediaSummary(MediaId.tmdbMovie(id), title)))

    fun close() {
        scope.cancel()
        koin.close()
    }
}
