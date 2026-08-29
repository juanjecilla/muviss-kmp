package com.codingpit.muviss.feature.profile.domain

import kotlinx.coroutines.flow.Flow

class ObserveSyncAccountUseCase(private val repository: SyncRepository) {
    operator fun invoke(): Flow<SyncAccountState> = repository.observeAccount()
}

class ObserveLastSyncedAtUseCase(private val repository: SyncRepository) {
    operator fun invoke(): Flow<Long?> = repository.observeLastSyncedAt()
}

/**
 * Groups the sync feature's whole surface — observed state and one-shot
 * actions alike — behind one `ProfileViewModel` dependency, mirroring
 * `ProfileActions`/`CollectionToggles`'s "bundle of related use cases"
 * shape. Also keeps `ProfileViewModel`'s constructor under detekt's
 * `LongParameterList` threshold: without this, the observe use cases above
 * would each be a separate constructor parameter.
 */
class SyncActions(
    private val repository: SyncRepository,
    private val observeAccount: ObserveSyncAccountUseCase,
    private val observeLastSyncedAt: ObserveLastSyncedAtUseCase,
) {
    val isAvailable: Boolean get() = repository.isAvailable

    fun observeAccount(): Flow<SyncAccountState> = observeAccount.invoke()

    fun observeLastSyncedAt(): Flow<Long?> = observeLastSyncedAt.invoke()

    suspend fun beginSignIn(provider: SyncProvider): Result<String> = repository.beginSignIn(provider)

    suspend fun completeSignIn(authCode: String): Result<Unit> = repository.completeSignIn(authCode)

    fun observeSignInFailure(): Flow<String?> = repository.observeSignInFailure()

    fun signInFailureShown() = repository.signInFailureShown()

    suspend fun signOut() = repository.signOut()

    suspend fun syncNow(): SyncOutcomeSummary = repository.syncNow()
}
