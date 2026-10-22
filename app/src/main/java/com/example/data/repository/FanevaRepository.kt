package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.FanevaDatabase
import com.example.data.local.entity.*
import com.example.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.*

class FanevaRepository(
    private val database: FanevaDatabase
) {
    private val productDao = database.productDao()
    private val stockDao = database.storeStockDao()
    private val customerDao = database.customerDao()
    private val saleDao = database.saleDao()
    private val movementDao = database.stockMovementDao()
    private val debtPaymentDao = database.debtPaymentDao()
    private val pendingSyncDao = database.pendingSyncDao()
    private val syncCursorDao = database.syncCursorDao()
    private val userPreferenceDao = database.userPreferenceDao()

    // Flows
    val allProducts: Flow<List<ProductEntity>> = productDao.getAllProducts()
    val allCustomers: Flow<List<CustomerEntity>> = customerDao.getAllCustomers()
    val allSales: Flow<List<SaleEntity>> = saleDao.getAllSales()
    val allStocks: Flow<List<StoreStockEntity>> = stockDao.getAllStocks()
    val allMovements: Flow<List<StockMovementEntity>> = movementDao.getAllMovements()
    val pendingOperations: Flow<List<PendingSyncOperationEntity>> = pendingSyncDao.getAllPendingOperations()
    val pendingCount: Flow<Int> = pendingSyncDao.getPendingCount()
    val syncCursor: Flow<SyncCursorEntity?> = syncCursorDao.getCursorFlow()
    val userPreferences: Flow<UserPreferenceEntity?> = userPreferenceDao.getPreferencesFlow()

    fun searchProducts(query: String): Flow<List<ProductEntity>> = productDao.searchProducts(query)

    fun getStocksByStore(storeId: String): Flow<List<StoreStockEntity>> = stockDao.getStocksByStore(storeId)

    suspend fun getStockForProductAndStore(productId: Long, storeId: String): Int {
        return stockDao.getStock(productId, storeId)?.quantity ?: 0
    }

    /**
     * Vente Atomique avec contrôle strict :
     * 1. Vérification de l'idempotence (prévention des doublons lors des retries)
     * 2. Règle absolue : Aucune quantité de stock négative
     * 3. Décrémentation atomique du stock
     * 4. Enregistrement de la vente et des lignes
     * 5. Cohérence vente à crédit / dette client
     * 6. Journalisation des mouvements de stock
     * 7. Mise en file d'attente Outbox (PendingSyncOperation)
     */
    suspend fun processSale(
        storeId: String,
        cartItems: List<CartItem>,
        saleType: SaleType,
        paymentMethod: PaymentMethod,
        customerId: Long?,
        customerName: String?,
        sellerId: String,
        sellerName: String,
        paidAmount: Double,
        idempotencyKey: String = UUID.randomUUID().toString()
    ): SaleValidationResult {
        if (cartItems.isEmpty()) {
            return SaleValidationResult(isSuccess = false, errorMessage = "Le panier est vide")
        }

        return database.withTransaction {
            // 1. Détection des doublons idempotents
            val existingSale = saleDao.getSaleByIdempotencyKey(idempotencyKey)
            if (existingSale != null) {
                return@withTransaction SaleValidationResult(
                    isSuccess = true,
                    saleId = existingSale.id,
                    saleNumber = existingSale.saleNumber,
                    errorMessage = null
                )
            }

            // 2. Vérification stricte des stocks disponibles dans le magasin actif
            for (item in cartItems) {
                val currentStock = stockDao.getStock(item.productId, storeId)?.quantity ?: 0
                if (currentStock < item.quantity) {
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Stock insuffisant pour '${item.productName}' dans ${storeId}. Disponible: $currentStock, Demandé: ${item.quantity}. Aucune quantité négative n'est autorisée."
                    )
                }
            }

            // Calculs financiers
            val totalAmount = cartItems.sumOf { it.total }
            val actualPaid = when (saleType) {
                SaleType.CASH -> totalAmount
                SaleType.CREDIT -> paidAmount.coerceIn(0.0, totalAmount)
            }
            val remaining = (totalAmount - actualPaid).coerceAtLeast(0.0)

            // Vente à crédit : vérification du client et cohérence dette
            if (saleType == SaleType.CREDIT) {
                if (customerId == null) {
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Une vente à crédit exige obligatoirement la sélection d'un client"
                    )
                }
                val customer = customerDao.getCustomerById(customerId)
                    ?: return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Client introuvable dans la base"
                    )

                if (customer.currentDebt + remaining > customer.creditLimit) {
                    val excess = (customer.currentDebt + remaining) - customer.creditLimit
                    return@withTransaction SaleValidationResult(
                        isSuccess = false,
                        errorMessage = "Plafond de crédit dépassé de ${String.format("%,.0f", excess)} Ar pour ${customer.name}. Plafond max: ${String.format("%,.0f", customer.creditLimit)} Ar"
                    )
                }

                // Mise à jour cohérente de la dette client
                customerDao.updateDebt(customerId, customer.currentDebt + remaining)
            }

            val saleId = UUID.randomUUID().toString()
            val dateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
            val saleNumber = "VNT-${dateFormat.format(Date())}-${(100..999).random()}"

            // 3. Décrémentation atomique du stock et création des mouvements
            for (item in cartItems) {
                val stock = stockDao.getStock(item.productId, storeId)
                val currentQty = stock?.quantity ?: 0
                val newQty = currentQty - item.quantity
                if (newQty < 0) {
                    // Double check invariant
                    throw IllegalStateException("Violation de stock négatif détectée")
                }
                stockDao.updateQuantity(item.productId, storeId, newQty)

                // Mouvement de stock traçable
                movementDao.insertMovement(
                    StockMovementEntity(
                        id = UUID.randomUUID().toString(),
                        movementNumber = "MVT-${UUID.randomUUID().toString().take(8).uppercase()}",
                        productId = item.productId,
                        productName = item.productName,
                        fromStoreId = storeId,
                        toStoreId = null,
                        quantity = -item.quantity,
                        movementType = "VENTE",
                        referenceId = saleNumber,
                        reason = "Sortie vente $saleNumber ($storeId)",
                        performedBy = sellerName
                    )
                )
            }

            // 4. Enregistrement de la vente
            val saleEntity = SaleEntity(
                id = saleId,
                saleNumber = saleNumber,
                storeId = storeId,
                customerId = customerId,
                customerName = customerName,
                sellerId = sellerId,
                sellerName = sellerName,
                saleType = saleType.name,
                totalAmount = totalAmount,
                paidAmount = actualPaid,
                remainingAmount = remaining,
                paymentMethod = paymentMethod.name,
                status = "COMPLETED",
                idempotencyKey = idempotencyKey,
                createdAt = System.currentTimeMillis()
            )
            saleDao.insertSale(saleEntity)

            // Lignes de vente
            val itemsEntities = cartItems.map { item ->
                SaleItemEntity(
                    id = UUID.randomUUID().toString(),
                    saleId = saleId,
                    productId = item.productId,
                    productCode = item.productCode,
                    productName = item.productName,
                    unitPrice = item.unitPrice,
                    quantity = item.quantity,
                    total = item.total
                )
            }
            saleDao.insertSaleItems(itemsEntities)

            // 5. Persistance locale Outbox pour future synchronisation Cloudflare Workers
            val itemsSummary = cartItems.map { mapOf("code" to it.productCode, "qty" to it.quantity, "price" to it.unitPrice) }
            val payload = """
                {
                    "saleId": "$saleId",
                    "saleNumber": "$saleNumber",
                    "storeId": "$storeId",
                    "saleType": "${saleType.name}",
                    "totalAmount": $totalAmount,
                    "paidAmount": $actualPaid,
                    "remainingAmount": $remaining,
                    "paymentMethod": "${paymentMethod.name}",
                    "customerId": $customerId,
                    "idempotencyKey": "$idempotencyKey",
                    "items": $itemsSummary,
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "SALE",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = idempotencyKey,
                    status = "PENDING"
                )
            )

            SaleValidationResult(
                isSuccess = true,
                saleId = saleId,
                saleNumber = saleNumber
            )
        }
    }

    /**
     * Transfert de stock inter-magasins (A1..A5) avec vérification de stock source
     */
    suspend fun transferStock(
        productId: Long,
        productName: String,
        fromStoreId: String,
        toStoreId: String,
        quantity: Int,
        operatorName: String,
        reason: String
    ): TransferValidationResult {
        if (fromStoreId == toStoreId) {
            return TransferValidationResult(isSuccess = false, errorMessage = "Le magasin d'origine et de destination doivent être distincts")
        }
        if (quantity <= 0) {
            return TransferValidationResult(isSuccess = false, errorMessage = "La quantité à transférer doit être strictement positive")
        }

        return database.withTransaction {
            val fromStock = stockDao.getStock(productId, fromStoreId)?.quantity ?: 0
            if (fromStock < quantity) {
                return@withTransaction TransferValidationResult(
                    isSuccess = false,
                    errorMessage = "Stock insuffisant dans le magasin d'origine $fromStoreId (Disponible: $fromStock, Requis: $quantity)"
                )
            }

            val toStock = stockDao.getStock(productId, toStoreId)?.quantity ?: 0

            // Mise à jour des stocks
            val newFromQty = fromStock - quantity
            val newToQty = toStock + quantity
            stockDao.updateQuantity(productId, fromStoreId, newFromQty)
            stockDao.updateQuantity(productId, toStoreId, newToQty)

            val transferId = UUID.randomUUID().toString()
            val mvtNumber = "TRF-${UUID.randomUUID().toString().take(8).uppercase()}"

            movementDao.insertMovement(
                StockMovementEntity(
                    id = UUID.randomUUID().toString(),
                    movementNumber = mvtNumber,
                    productId = productId,
                    productName = productName,
                    fromStoreId = fromStoreId,
                    toStoreId = toStoreId,
                    quantity = quantity,
                    movementType = "TRANSFERT",
                    referenceId = transferId,
                    reason = if (reason.isBlank()) "Transfert de $fromStoreId vers $toStoreId" else reason,
                    performedBy = operatorName
                )
            )

            // Outbox
            val payload = """
                {
                    "transferId": "$transferId",
                    "movementNumber": "$mvtNumber",
                    "productId": $productId,
                    "fromStoreId": "$fromStoreId",
                    "toStoreId": "$toStoreId",
                    "quantity": $quantity,
                    "operator": "$operatorName",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "TRANSFER",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = transferId,
                    status = "PENDING"
                )
            )

            TransferValidationResult(isSuccess = true, transferId = transferId)
        }
    }

    /**
     * Règlement de dette client
     */
    suspend fun recordDebtPayment(
        customerId: Long,
        amount: Double,
        paymentMethod: PaymentMethod,
        note: String,
        operatorName: String
    ): Result<String> {
        if (amount <= 0) return Result.failure(IllegalArgumentException("Le montant du règlement doit être supérieur à zéro"))

        return database.withTransaction {
            val customer = customerDao.getCustomerById(customerId)
                ?: return@withTransaction Result.failure(IllegalArgumentException("Client introuvable"))

            val newDebt = (customer.currentDebt - amount).coerceAtLeast(0.0)
            customerDao.updateDebt(customerId, newDebt)

            val receiptNumber = "REC-${System.currentTimeMillis().toString().takeLast(6)}"
            val paymentId = UUID.randomUUID().toString()

            val paymentEntity = DebtPaymentEntity(
                id = paymentId,
                customerId = customerId,
                customerName = customer.name,
                amountPaid = amount,
                paymentMethod = paymentMethod.name,
                note = note,
                receiptNumber = receiptNumber,
                idempotencyKey = paymentId
            )
            debtPaymentDao.insertPayment(paymentEntity)

            val payload = """
                {
                    "paymentId": "$paymentId",
                    "receiptNumber": "$receiptNumber",
                    "customerId": $customerId,
                    "amountPaid": $amount,
                    "newDebt": $newDebt,
                    "paymentMethod": "${paymentMethod.name}",
                    "operator": "$operatorName",
                    "timestamp": ${System.currentTimeMillis()}
                }
            """.trimIndent()

            pendingSyncDao.insertOperation(
                PendingSyncOperationEntity(
                    mutationId = UUID.randomUUID().toString(),
                    entityType = "DEBT_PAYMENT",
                    action = "CREATE",
                    payloadJson = payload,
                    idempotencyKey = paymentId,
                    status = "PENDING"
                )
            )

            Result.success(receiptNumber)
        }
    }

    suspend fun addNewCustomer(
        name: String,
        phone: String,
        address: String,
        creditLimit: Double
    ): Long {
        val customer = CustomerEntity(
            name = name,
            phone = phone,
            address = address,
            creditLimit = creditLimit,
            currentDebt = 0.0
        )
        val id = customerDao.insertCustomer(customer)

        val payload = """
            {
                "customerId": $id,
                "name": "$name",
                "phone": "$phone",
                "address": "$address",
                "creditLimit": $creditLimit,
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        pendingSyncDao.insertOperation(
            PendingSyncOperationEntity(
                mutationId = UUID.randomUUID().toString(),
                entityType = "CUSTOMER",
                action = "CREATE",
                payloadJson = payload,
                idempotencyKey = "CUST-$id",
                status = "PENDING"
            )
        )
        return id
    }

    suspend fun addNewProduct(
        code: String,
        name: String,
        category: String,
        purchasePrice: Double,
        sellingPrice: Double,
        minAlert: Int,
        unit: String,
        initialStoreStocks: Map<String, Int>
    ): Long {
        val product = ProductEntity(
            code = code,
            name = name,
            category = category,
            purchasePrice = purchasePrice,
            sellingPrice = sellingPrice,
            minStockAlert = minAlert,
            unit = unit
        )
        val productId = productDao.insertProduct(product)

        // Initial stock in each store
        StoreId.entries.forEach { store ->
            val qty = initialStoreStocks[store.code] ?: 0
            stockDao.insertOrUpdateStock(
                StoreStockEntity(
                    storeId = store.code,
                    productId = productId,
                    quantity = qty.coerceAtLeast(0)
                )
            )
        }

        val payload = """
            {
                "productId": $productId,
                "code": "$code",
                "name": "$name",
                "category": "$category",
                "sellingPrice": $sellingPrice,
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        pendingSyncDao.insertOperation(
            PendingSyncOperationEntity(
                mutationId = UUID.randomUUID().toString(),
                entityType = "PRODUCT",
                action = "CREATE",
                payloadJson = payload,
                idempotencyKey = "PRD-$productId",
                status = "PENDING"
            )
        )
        return productId
    }

    suspend fun getSaleDetails(saleId: String): List<SaleItemEntity> {
        return saleDao.getSaleItems(saleId)
    }

    suspend fun updateUserPreference(role: String, storeId: String, operator: String) {
        userPreferenceDao.savePreferences(
            UserPreferenceEntity(
                id = 1,
                activeRole = role,
                activeStoreId = storeId,
                operatorName = operator
            )
        )
    }

    suspend fun updateStagingApiUrl(url: String) {
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(stagingApiUrl = url))
    }

    suspend fun toggleNetworkSimulation(isOnline: Boolean) {
        val current = syncCursorDao.getCursor() ?: SyncCursorEntity()
        syncCursorDao.saveCursor(current.copy(isSimulatedOnline = isOnline))
    }

    suspend fun resetDatabase() {
        FanevaDatabase.populateInitialDemoData(database)
    }
}
