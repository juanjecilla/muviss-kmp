package com.codingpit.muviss.core.sync.di

import com.codingpit.muviss.core.database.MuvissDatabase
import com.codingpit.muviss.core.network.createHttpClient
import com.codingpit.muviss.core.sync.EntitlementGate
import com.codingpit.muviss.core.sync.MuvissBuildConfig
import com.codingpit.muviss.core.sync.NoOpSyncBackend
import com.codingpit.muviss.core.sync.SqlDelightSyncSessionStore
import com.codingpit.muviss.core.sync.SyncAvailability
import com.codingpit.muviss.core.sync.SyncBackend
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncSessionStore
import com.codingpit.muviss.core.sync.supabase.SupabaseSyncBackend
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
 * what [EntitlementGate] exists to avoid (ADR 0012).
 */
val syncModule: Module = module {
    single<SyncAvailability> {
        SyncAvailability {
            MuvissBuildConfig.SYNC_ENABLED &&
                MuvissBuildConfig.SUPABASE_URL.isNotBlank() &&
                MuvissBuildConfig.SUPABASE_ANON_KEY.isNotBlank()
        }
    }
    single<EntitlementGate> { EntitlementGate.AlwaysEntitled }
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
    single { SyncEngine(get(), get(), get(), get(), get()) }
}
