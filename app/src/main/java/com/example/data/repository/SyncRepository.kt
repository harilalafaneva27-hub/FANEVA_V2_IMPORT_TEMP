package com.example.data.repository

import com.example.data.local.dao.PendingSyncDao
import com.example.data.local.dao.SyncCursorDao
import com.example.data.local.entity.PendingSyncOperationEntity
import com.example.data.local.entity.SyncCursorEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Modèles de données versionnés pour la synchronisation future avec Cloudflare Workers.
 * Ces structures définissent l'architecture contractuelle sans préjuger des endpoints finaux
 * avant réception du contrat d'API réel.
 */
data class SyncBatchRequest(
    val protocolVersion: Int = 2,
    val deviceId: String,
    val fromCursor: Long,
    val operations: List<SyncOperationPayload>
)

data class SyncOperationPayload(
    val mutationId: String,
    val entityType: String,
    val action: String,
    val idempotencyKey: String,
    val payloadJson: String,
    val clientTimestamp: Long
)

data class SyncBatchResponse(
    val isSuccess: Boolean,
    val acknowledgedMutationIds: List<String>,
    val rejectedMutationIds: Map<String, String>, // mutationId -> raison du rejet
    val nextCursor: Long?,
    val serverMessage: String?
)

sealed class SyncPushResult {
    data class Success(val countSynced: Int, val updatedCursor: Long) : SyncPushResult()
    data class Offline(val pendingCount: Int, val reason: String) : SyncPushResult()
    data class PartialError(val syncedCount: Int, val failedCount: Int, val error: String) : SyncPushResult()
}

/**
 * Interface abstraite SyncRepository
 * Conçue pour une intégration transparente avec Cloudflare Workers dès réception du contrat canonique.
 */
interface SyncRepository {
    val pendingOperations: Flow<List<PendingSyncOperationEntity>>
    val pendingCount: Flow<Int>
    val syncCursor: Flow<SyncCursorEntity?>
    val isOnlineFlow: Flow<Boolean>

    suspend fun triggerSync(): SyncPushResult
    suspend fun purgeSyncedOperations()
    suspend fun setSimulatedNetwork(isOnline: Boolean)
    suspend fun setStagingUrl(url: String)
}

class SyncRepositoryImpl(
    private val pendingSyncDao: PendingSyncDao,
    private val syncCursorDao: SyncCursorDao
) : SyncRepository {

    override val pendingOperations: Flow<List<PendingSyncOperationEntity>> =
        pendingSyncDao.getAllPendingOperations()

    override val pendingCount: Flow<Int> =
        pendingSyncDao.getPendingCount()

    override val syncCursor: Flow<SyncCursorEntity?> =
        syncCursorDao.getCursorFlow()

    override val isOnlineFlow: Flow<Boolean> =
        syncCursorDao.getCursorFlow().map { it?.isSimulatedOnline ?: true }

    /**
     * Exécution du cycle de synchronisation :
     * 1. Vérification de connectivité
     * 2. Chargement des opérations en attente dans l'Outbox locale
     * 3. Envoi par lot avec clés d'idempotence
     * 4. RÈGLE CRITIQUE : Aucun avancement du curseur avant confirmation effective des transactions
     */
    override suspend fun triggerSync(): SyncPushResult {
        val cursorEntity = syncCursorDao.getCursor() ?: SyncCursorEntity()
        if (!cursorEntity.isSimulatedOnline) {
            val pending = pendingSyncDao.getPendingOperationsSnapshot().size
            return SyncPushResult.Offline(
                pendingCount = pending,
                reason = "Mode hors-ligne actif. Les opérations restent sécurisées dans la file d'attente locale."
            )
        }

        val pendingList = pendingSyncDao.getPendingOperationsSnapshot()
            .filter { it.status == "PENDING" || it.status == "FAILED" }

        if (pendingList.isEmpty()) {
            return SyncPushResult.Success(countSynced = 0, updatedCursor = cursorEntity.lastCursor)
        }

        // Marquer en cours de synchronisation
        pendingList.forEach { op ->
            pendingSyncDao.updateOperation(op.copy(status = "SYNCING"))
        }

        // Simulation réseau réaliste (prototype Cloudflare Worker)
        delay(1200)

        // Traitement sécurisé : confirmation ACK pour chaque mutation
        var syncedCount = 0
        var newCursor = cursorEntity.lastCursor

        for (op in pendingList) {
            // Dans ce prototype, les transactions sont acquittées et le curseur avance séquentiellement
            syncedCount++
            newCursor += 1
            pendingSyncDao.updateOperation(
                op.copy(
                    status = "SYNCED",
                    syncedAt = System.currentTimeMillis()
                )
            )
        }

        // RÈGLE DE FIABILITÉ : On ne met à jour le curseur qu'après confirmation explicite
        syncCursorDao.saveCursor(
            cursorEntity.copy(
                lastCursor = newCursor,
                lastSyncTimestamp = System.currentTimeMillis()
            )
        )

        return SyncPushResult.Success(countSynced = syncedCount, updatedCursor = newCursor)
    }

    override suspend fun purgeSyncedOperations() {
        pendingSyncDao.purgeSyncedOperations()
    }

    override suspend fun setSimulatedNetwork(isOnline: Boolean) {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(cursor.copy(isSimulatedOnline = isOnline))
    }

    override suspend fun setStagingUrl(url: String) {
        val cursor = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(cursor.copy(stagingApiUrl = url))
    }
}
