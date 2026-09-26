package com.codingpit.muviss.core.sync.di

import com.codingpit.muviss.core.common.AppDispatchers
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.createHttpClient
import com.codingpit.muviss.core.sync.AutomaticSyncSettings
import com.codingpit.muviss.core.sync.BuildSyncAvailability
import com.codingpit.muviss.core.sync.DeepLinkRedirectTarget
import com.codingpit.muviss.core.sync.EntitlementGate
import com.codingpit.muviss.core.sync.MuvissBuildConfig
import com.codingpit.muviss.core.sync.NoOpSyncBackend
import com.codingpit.muviss.core.sync.OAuthRedirectTarget
import com.codingpit.muviss.core.sync.SignInFeedback
import com.codingpit.muviss.core.sync.SqlDelightSyncSessionStore
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncRunner
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.companion.CompanionBackend
import com.codingpit.muviss.core.sync.companion.NoOpCompanionBackend
import com.codingpit.muviss.core.sync.supabase.SupabaseSyncBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Wires the sync feature. [SyncAvailability] gates which [SyncBackend] gets
 * bound: real keys → [SupabaseSyncBackend], blank → [NoOpSyncBackend] — the
 * same "generated key present or not" branch [MuvissBuildConfig] enables
 * elsewhere (TMDB, Sentry — see ADR 0007). Nothing above this module (e.g.
 * profile) needs to know which one is live; they depend on [SyncBackend]/
 * [SyncEngine] only.
 *
 * The [EntitlementGate] bound here is the permissive default. `:app:shared`
 * overrides it with `:core:billing`'s real one — this module cannot do it
 * itself without `:core:sync` depending on a billing vendor, which is exactly
 * what [EntitlementGate] exists to avoid (ADR 0018).
 */
val syncModule: Module = module {
    single<SyncAvailability> { BuildSyncAvailability }
    single<EntitlementGate> { EntitlementGate.AlwaysEntitled }
    single { SignInFeedback() }
    // The scheme-based default. :app:desktopApp rebinds this to its loopback
    // server (ADR 0017); Koin's last binding wins, the same way
    // BillingSyncBridge overrides EntitlementGate above.
    single<OAuthRedirectTarget> { DeepLinkRedirectTarget() }
    single { get<MuvissDatabase>().syncAccountQueries }
    single<SyncSessionStore> { SqlDelightSyncSessionStore(get()) }
    single<SyncBackend> {
        if (get<SyncAvailability>().isConfigured()) {
            SupabaseSyncBackend(
                client = createHttpClient(enableLogging = false),
                baseUrl = MuvissBuildConfig.SUPABASE_URL,
                anonKey = MuvissBuildConfig.SUPABASE_ANON_KEY,
                sessionStore = get(),
                clock = get(),
            )
        } else {
            NoOpSyncBackend()
        }
    }
    // Co-watch (EPIC 41). Derived from the bound SyncBackend rather than built
    // beside it, so both share one PostgREST client, one HttpClient and one
    // refresh path — GoTrue rotates the refresh token, so two racing refreshers
    // would invalidate each other.
    single<CompanionBackend> {
        (get<SyncBackend>() as? SupabaseSyncBackend)?.companionBackend() ?: NoOpCompanionBackend()
    }
    single { AutomaticSyncSettings(get(), get<FeatureFlags>().syncAutomatically) }
    single {
        SyncEngine(
            backend = get(),
            database = get(),
            dispatchers = get(),
            clock = get(),
            entitlementGate = get(),
            automatic = get(),
        )
    }
    single<SyncRunner> { get<SyncEngine>() }
    single {
        val engine = get<SyncEngine>()
        SyncCoordinator(
            runner = engine,
            pendingChanges = engine.observePendingChanges(),
            // Not just the switch: a build without background sync never
            // watches, however the stored preference reads.
            automaticEnabled = get<AutomaticSyncSettings>().enabled,
            clock = get(),
            scope = CoroutineScope(SupervisorJob() + get<AppDispatchers>().default),
        )
    }
}
