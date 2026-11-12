package org.monero.lws.lib.wallet

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.monero.lws.lib.api.FeedReceive
import org.monero.lws.lib.api.FeedTransaction
import org.monero.lws.lib.api.LWSApiClient
import org.monero.lws.lib.api.LWSException
import org.monero.lws.lib.api.LWSFeedException
import org.monero.lws.lib.api.LWSFeedMessage
import org.monero.lws.lib.api.Output
import org.monero.lws.lib.api.Transaction
import org.monero.lws.lib.api.WalletData
import org.monero.lws.lib.native.MoneroNative

class Wallet(
    private val api: LWSApiClient,
    private val native: MoneroNative,
    private val data: WalletData,
    private val password: String,
    restorePending: Boolean = false
) {
    val address: String = data.address
    val seed: String = data.seed
    val viewKey: String = data.viewKey
    val spendKey: String = data.spendKey

    @Volatile
    private var restorePending: Boolean = restorePending

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val feedLock = Any()
    private val feedTransactions = linkedMapOf<String, FeedTransaction>()
    private val pendingSends = linkedMapOf<String, PendingLocalSend>()
    private val refreshMutex = Mutex()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null

    @Volatile
    private var serverLocked: Long = 0L

    private val mutableSyncState = MutableStateFlow(WalletSyncState(restorePending = restorePending))
    val syncState: StateFlow<WalletSyncState> = mutableSyncState.asStateFlow()

    fun isRestorePending(): Boolean = restorePending

    internal suspend fun submitInitialRestoreRequest() {
        api.importRequest(address, viewKey, data.restoreHeight)
    }

    fun startSync() {
        synchronized(feedLock) {
            if (syncJob?.isActive == true) return
            mutableSyncState.value = mutableSyncState.value.copy(syncing = true, error = null)
            syncJob = syncScope.launch {
                var retryDelay = 1_000L
                while (currentCoroutineContext().isActive) {
                    try {
                        api.feedMessages(address, viewKey).collect { message ->
                            when (message) {
                                LWSFeedMessage.Connected -> {
                                    mutableSyncState.value = mutableSyncState.value.copy(connected = true, error = null)
                                }
                                is LWSFeedMessage.TxSync -> {
                                    synchronized(feedLock) {
                                        feedTransactions.clear()
                                        message.transactions.forEach(::mergeFeedTransactionLocked)
                                        reconcileConfirmedSendsLocked()
                                    }
                                    publishTransactions()
                                    refreshBalanceFromLws()
                                    retryDelay = 1_000L
                                }
                                is LWSFeedMessage.Blocks -> {
                                    synchronized(feedLock) {
                                        message.transactions.forEach(::mergeFeedTransactionLocked)
                                        reconcileConfirmedSendsLocked()
                                    }
                                    publishTransactions()
                                    refreshBalanceFromLws()
                                }
                                is LWSFeedMessage.Mempool -> {
                                    synchronized(feedLock) {
                                        mergeFeedTransactionLocked(message.transaction)
                                    }
                                    publishTransactions()
                                }
                                is LWSFeedMessage.Warning -> {
                                    if (message.code == BLOCKCHAIN_REORG_CODE) {
                                        synchronized(feedLock) { feedTransactions.clear() }
                                        mutableSyncState.value = mutableSyncState.value.copy(
                                            balance = null,
                                            connected = false,
                                            syncing = true
                                        )
                                        publishTransactions()
                                        throw LWSFeedException(
                                            message.code,
                                            "LWS reported a blockchain reorganization at ${message.height}; resynchronizing"
                                        )
                                    }
                                    mutableSyncState.value = mutableSyncState.value.copy(
                                        error = "LWS warning: ${message.message}"
                                    )
                                }
                                is LWSFeedMessage.Error -> throw LWSFeedException(message.code, message.message)
                            }
                        }
                        throw LWSFeedException(null, "LWS feed ended; reconnecting")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutableSyncState.value = mutableSyncState.value.copy(
                            connected = false,
                            syncing = true,
                            error = e.message ?: "LWS feed disconnected"
                        )
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    fun stopSync() {
        synchronized(feedLock) {
            syncJob?.cancel()
            syncJob = null
            mutableSyncState.value = mutableSyncState.value.copy(connected = false, syncing = false)
        }
    }

    private fun restActive(): Boolean = mutableSyncState.value.let { !it.connected && it.error != null }

    private suspend fun checkRestoreReadiness(): Boolean {
        if (!restorePending) return true

        val importResult = api.importRequest(address, viewKey, data.restoreHeight)
        if (importResult.isFailure) return false

        val importResponse = importResult.getOrThrow()
        if (!importResponse.requestFulfilled) return false

        val infoResult = api.getAddressInfo(address, viewKey)
        if (infoResult.isFailure) return false

        val info = infoResult.getOrThrow()
        if (info.scannedHeight >= info.blockchainHeight) {
            restorePending = false
            return true
        }
        return false
    }

    private fun mergeFeedTransactionLocked(incoming: FeedTransaction) {
        val previous = feedTransactions[incoming.hash]
        if (previous == null) {
            feedTransactions[incoming.hash] = incoming
            return
        }

        val receives = (previous.receives + incoming.receives)
            .groupBy(::receiveFingerprint)
            .values
            .map { group -> group.firstOrNull { it.id != null } ?: group.first() }
        val spends = (previous.spends + incoming.spends)
            .distinctBy { "${it.id}:${it.keyImage}" }
        val height = incoming.height ?: previous.height
        feedTransactions[incoming.hash] = incoming.copy(
            timestamp = incoming.timestamp ?: previous.timestamp,
            fee = incoming.fee ?: previous.fee,
            height = height,
            paymentId = incoming.paymentId ?: previous.paymentId,
            coinbase = incoming.coinbase || previous.coinbase,
            mempool = height == null && (incoming.mempool || previous.mempool),
            receives = receives,
            spends = spends
        )
    }

    private fun reconcileConfirmedSendsLocked() {
        val confirmed = feedTransactions.values.filter { !it.mempool && it.height != null }
        val iterator = pendingSends.entries.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next().value
            val matched = confirmed.any { tx ->
                tx.hash == pending.hash || (
                    pending.spendKeyImages.isNotEmpty() &&
                        pending.spendKeyImages.all { wanted ->
                            tx.spends.any { it.keyImage.equals(wanted, ignoreCase = true) }
                        }
                    )
            }
            if (matched) iterator.remove()
        }
    }

    private fun receiveFingerprint(receive: FeedReceive): String =
        "${receive.txPubKey.lowercase()}:${receive.index}:${receive.recipientMajor}:${receive.recipientMinor}"

    private fun buildFeedSnapshot(): FeedSnapshot {
        val records: List<FeedTransaction>
        val localPending: List<PendingLocalSend>
        synchronized(feedLock) {
            records = feedTransactions.values.toList()
            localPending = pendingSends.values.toList()
        }

        val sourceOutputs = linkedMapOf<String, FeedReceive>()
        records.flatMap { it.receives }.forEach { receive ->
            receive.id?.let { sourceOutputs.putIfAbsent(it, receive) }
        }

        val localPendingHashes = localPending.mapTo(hashSetOf()) { it.hash }
        val txInfos = linkedMapOf<String, TransactionInfo>()
        var confirmedNet = 0L
        var pendingReceived = 0L
        val feedReserved = linkedMapOf<String, Long>()

        for (tx in records) {
            val received = tx.receives.sumFeedReceiveAmounts()
            val spentOutputs = tx.spends.mapNotNull { spend ->
                val source = spend.id?.let(sourceOutputs::get) ?: return@mapNotNull null
                val isReal = native.isRealSpend(
                    txPubKeyHex = source.txPubKey,
                    outIndex = source.index,
                    majI = source.recipientMajor,
                    minI = source.recipientMinor,
                    keyImageHex = spend.keyImage
                )
                source.takeIf { isReal }
            }
            val sent = spentOutputs.sumFeedReceiveAmounts()
            val net = received - sent

            if (!tx.mempool && tx.height != null) {
                confirmedNet += net
            } else {
                if (tx.hash !in localPendingHashes && net > 0L) {
                    pendingReceived += net
                }
                if (sent > 0L) feedReserved[tx.hash] = sent
            }

            if (net != 0L) {
                txInfos[tx.hash] = TransactionInfo(
                    hash = tx.hash,
                    height = tx.height,
                    timestamp = tx.timestamp?.let { runCatching { Instant.ofEpochSecond(it).toString() }.getOrNull() },
                    amount = net,
                    confirmed = !tx.mempool && tx.height != null,
                    unlockTime = tx.unlockTime,
                    paymentId = tx.paymentId
                )
            }
        }

        var pendingReserved = feedReserved.values.sum()
        for (pending in localPending) {
            val matchingRecord = records.firstOrNull { tx ->
                tx.hash == pending.hash || (
                    pending.spendKeyImages.isNotEmpty() &&
                        pending.spendKeyImages.all { wanted ->
                            tx.spends.any { it.keyImage.equals(wanted, ignoreCase = true) }
                        }
                    )
            }
            if (matchingRecord?.let { !it.mempool && it.height != null } == true) continue
            val fee = pending.fee ?: matchingRecord?.fee ?: 0L
            if (matchingRecord == null || feedReserved[matchingRecord.hash] == null) {
                pendingReserved += pending.reservedAmount
                pendingReceived += (pending.reservedAmount - pending.amount - fee).coerceAtLeast(0L)
            }
            val displayHash = matchingRecord?.hash ?: pending.hash
            txInfos[displayHash] = TransactionInfo(
                hash = displayHash,
                height = null,
                timestamp = null,
                amount = -(pending.amount + fee),
                confirmed = false,
                unlockTime = 0L,
                paymentId = null
            )
        }

        val sorted = txInfos.values.sortedWith(
            compareByDescending<TransactionInfo> { it.height ?: Long.MAX_VALUE }
                .thenByDescending { it.timestamp.orEmpty() }
        )
        return FeedSnapshot(
            transactions = sorted,
            confirmedNet = confirmedNet,
            pendingReceived = pendingReceived,
            pendingReserved = pendingReserved
        )
    }

    private suspend fun refreshBalanceFromLws(): Result<Balance> = refreshMutex.withLock {
        if (restorePending && !checkRestoreReadiness()) {
            val pendingBalance = Balance(0L, 0L, 0L, 0L, 0L, emptyMap(), true)
            mutableSyncState.value = mutableSyncState.value.copy(
                balance = pendingBalance,
                restorePending = true
            )
            return@withLock Result.success(pendingBalance)
        }

        val infoResult = api.getAddressInfo(address, viewKey)
        if (infoResult.isFailure) {
            val error = infoResult.exceptionOrNull()!!
            mutableSyncState.value = mutableSyncState.value.copy(error = error.message)
            return@withLock Result.failure(error)
        }

        val info = infoResult.getOrThrow()
        val feed = buildFeedSnapshot()
        serverLocked = info.lockedFunds.toLongOrNull() ?: 0L
        val total = feed.confirmedNet + feed.pendingReceived - feed.pendingReserved
        val locked = serverLocked + feed.pendingReceived
        val balance = Balance(
            total = total,
            unlocked = (total - locked).coerceAtLeast(0L),
            locked = locked,
            scannedHeight = info.scannedHeight,
            blockchainHeight = info.blockchainHeight,
            rates = info.rates?.toMap() ?: emptyMap(),
            restorePending = false,
            pendingReserved = feed.pendingReserved,
            pendingReceived = feed.pendingReceived
        )
        mutableSyncState.value = mutableSyncState.value.copy(
            balance = balance,
            transactions = feed.transactions,
            restorePending = false,
            syncing = false,
            error = null
        )
        Result.success(balance)
    }

    private fun publishTransactions() {
        val feed = buildFeedSnapshot()
        val old = mutableSyncState.value
        val total = feed.confirmedNet + feed.pendingReceived - feed.pendingReserved
        val locked = serverLocked + feed.pendingReceived
        val balance = old.balance?.copy(
            total = total,
            unlocked = (total - locked).coerceAtLeast(0L),
            locked = locked,
            pendingReserved = feed.pendingReserved,
            pendingReceived = feed.pendingReceived
        )
        mutableSyncState.value = old.copy(transactions = feed.transactions, balance = balance)
    }

    suspend fun getBalance(): Result<Balance> =
        if (restActive()) refreshRest() else refreshBalanceFromLws()

    suspend fun getTransactions(): Result<List<TransactionInfo>> {
        if (restActive()) return restTransactions()
        if (restorePending && !checkRestoreReadiness()) return Result.success(emptyList())
        return Result.success(buildFeedSnapshot().transactions)
    }

    private fun filterRealTransactions(txs: List<Transaction>): List<Transaction> {
        return txs.map { tx ->
            if (tx.spentOutputs.isEmpty()) {
                tx
            } else {
                val realSpents = tx.spentOutputs.filter { spend ->
                    native.isRealSpend(
                        txPubKeyHex = spend.txPubKey,
                        outIndex = spend.outIndex,
                        majI = spend.sender.majI,
                        minI = spend.sender.minI,
                        keyImageHex = spend.keyImage
                    )
                }

                val realSent = realSpents.sumOf { it.amount.toLongOrNull() ?: 0L }

                tx.copy(
                    totalSent = realSent.toString(),
                    spentOutputs = realSpents
                )
            }
        }.filter { tx ->
            val received = tx.totalReceived.toLongOrNull() ?: 0L
            val sent = tx.totalSent.toLongOrNull() ?: 0L
            received > 0L || sent > 0L
        }
    }

    private suspend fun restBalance(): Result<Balance> {
        if (restorePending && !checkRestoreReadiness()) {
            return Result.success(Balance(0L, 0L, 0L, 0L, 0L, emptyMap(), true))
        }

        val infoResult = api.getAddressInfo(address, viewKey)
        if (infoResult.isFailure) return Result.failure(infoResult.exceptionOrNull()!!)
        val info = infoResult.getOrThrow()

        val txsResult = api.getAddressTxs(address, viewKey)
        if (txsResult.isFailure) return Result.failure(txsResult.exceptionOrNull()!!)

        val canonicalTxs = filterRealTransactions(txsResult.getOrThrow().transactions)

        var totalReceived = 0L
        var totalSent = 0L
        var pendingIn = 0L
        for (tx in canonicalTxs) {
            val received = tx.totalReceived.toLongOrNull() ?: 0L
            totalReceived += received
            totalSent += tx.totalSent.toLongOrNull() ?: 0L
            if (tx.mempool || tx.height == null) pendingIn += received
        }

        val total = totalReceived - totalSent
        serverLocked = info.lockedFunds.toLongOrNull() ?: 0L
        val locked = serverLocked + pendingIn
        val unlocked = (total - locked).coerceAtLeast(0L)

        return Result.success(
            Balance(
                total = total,
                unlocked = unlocked,
                locked = locked,
                scannedHeight = info.scannedHeight,
                blockchainHeight = info.blockchainHeight,
                rates = info.rates?.toMap() ?: emptyMap(),
                restorePending = false
            )
        )
    }

    private suspend fun restTransactions(): Result<List<TransactionInfo>> {
        if (restorePending) {
            checkRestoreReadiness()
            if (restorePending) return Result.success(emptyList())
        }

        val txsResult = api.getAddressTxs(address, viewKey)
        if (txsResult.isFailure) return Result.failure(txsResult.exceptionOrNull()!!)

        val canonicalTxs = filterRealTransactions(txsResult.getOrThrow().transactions)

        val txs = canonicalTxs.map { tx ->
            val received = tx.totalReceived.toLongOrNull() ?: 0L
            val sent = tx.totalSent.toLongOrNull() ?: 0L

            TransactionInfo(
                hash = tx.hash,
                height = tx.height,
                timestamp = tx.timestamp,
                amount = received - sent,
                confirmed = !tx.mempool && tx.height != null,
                unlockTime = tx.unlockTime,
                paymentId = tx.paymentId
            )
        }

        return Result.success(txs)
    }

    private suspend fun refreshRest(): Result<Balance> = refreshMutex.withLock {
        val txs = restTransactions().getOrElse { error ->
            mutableSyncState.value = mutableSyncState.value.copy(error = error.message, syncing = false)
            return@withLock Result.failure(error)
        }
        val balance = restBalance().getOrElse { error ->
            mutableSyncState.value = mutableSyncState.value.copy(error = error.message, syncing = false)
            return@withLock Result.failure(error)
        }
        if (!mutableSyncState.value.connected) {
            mutableSyncState.value = mutableSyncState.value.copy(
                balance = balance,
                transactions = txs,
                restorePending = balance.restorePending,
                syncing = false
            )
        }
        Result.success(balance)
    }

    suspend fun send(destination: String, amount: Long): Result<SendResult> =
        sendInternal(destination, amount, sweep = false)

    suspend fun sendMax(destination: String): Result<SendResult> =
        sendInternal(destination, 0L, sweep = true)

    private suspend fun sendInternal(destination: String, amount: Long, sweep: Boolean): Result<SendResult> {
        if (restorePending) {
            checkRestoreReadiness()
            if (restorePending) return Result.failure(IllegalStateException("Wallet restore is pending"))
        }
        if (!sweep && amount <= 0L) return Result.failure(IllegalArgumentException("Amount must be positive"))

        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val infoResult = api.getAddressInfo(address, viewKey)
            if (infoResult.isFailure) return@withContext Result.failure(infoResult.exceptionOrNull()!!)
            val info = infoResult.getOrThrow()

            val unspentResult = api.getUnspentOuts(address, viewKey, 0, if (sweep) 0 else 15, false)
            if (unspentResult.isFailure) return@withContext Result.failure(unspentResult.exceptionOrNull()!!)
            val unspent = unspentResult.getOrThrow()
            val reservedKeys = synchronized(feedLock) {
                pendingSends.values.flatMapTo(hashSetOf()) { it.reservedOutputKeys }
            }
            val availableOutputs = unspent.outputs.filter { output ->
                outputKey(output) !in reservedKeys && !isReallySpent(output)
            }
            if (sweep) {
                android.util.Log.i(
                    "LwsSweep",
                    "server outputs=${unspent.outputs.size}, reserved/spent excluded=" +
                        "${unspent.outputs.size - availableOutputs.size}, candidates=${availableOutputs.size}"
                )
            }
            val unspentJson = json.encodeToString(availableOutputs)

            val selectedResult = if (sweep) {
                native.selectAllInputs(unspentJson, info.blockchainHeight)
            } else {
                native.selectInputs(
                    unspentJson, amount, 15, unspent.perByteFee, unspent.feeMask, info.blockchainHeight
                )
            }
            if (selectedResult.isFailure) return@withContext Result.failure(selectedResult.exceptionOrNull()!!)
            val selectedOutputsJson = selectedResult.getOrThrow()
            val selectedOutputs = json.decodeFromString<List<Output>>(selectedOutputsJson)
            if (selectedOutputs.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("No spendable outputs"))
            }

            val randomAmounts = List(selectedOutputs.size) { "0" }
            val randomResult = api.getRandomOuts(15, randomAmounts)
            if (randomResult.isFailure) return@withContext Result.failure(randomResult.exceptionOrNull()!!)
            val randomOutsJson = json.encodeToString(randomResult.getOrThrow())

            val pendingResult = native.createTransaction(
                destination, amount, 15, unspent.perByteFee, unspent.feeMask, selectedOutputsJson, randomOutsJson,
                sweep
            )
            if (pendingResult.isFailure) return@withContext Result.failure(pendingResult.exceptionOrNull()!!)
            val pending = pendingResult.getOrThrow()

            val submitResult = api.submitRawTx(pending.rawTxHex)
            if (submitResult.isFailure) return@withContext Result.failure(submitResult.exceptionOrNull()!!)
            val submit = submitResult.getOrThrow()
            if (submit.notRelayed) {
                return@withContext Result.failure(LWSException.NotRelayed(submit.reason))
            }

            val spendKeyImages = selectedOutputs.flatMap { it.spendKeyImages }
                .mapTo(hashSetOf()) { it.lowercase() }
            val submittedTx = if (spendKeyImages.isEmpty()) null else try {
                api.getAddressTxs(address, viewKey).getOrNull()?.transactions?.firstOrNull { tx ->
                    val txKeyImages = tx.spentOutputs.mapTo(hashSetOf()) { it.keyImage.lowercase() }
                    spendKeyImages.all { it in txKeyImages }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val localHash = (submittedTx?.hash ?: pending.txid).lowercase()
            val reservedAmount = selectedOutputs.sumOutputAmounts()
            val knownFee = submittedTx?.fee?.toLongOrNull()
            val sentAmount = if (sweep) reservedAmount - (knownFee ?: 0L) else amount
            synchronized(feedLock) {
                val alreadyConfirmed = submittedTx?.let { !it.mempool && it.height != null } == true ||
                    feedTransactions[localHash]?.let { !it.mempool && it.height != null } == true
                if (!alreadyConfirmed) {
                    pendingSends[localHash] = PendingLocalSend(
                        hash = localHash,
                        amount = sentAmount,
                        fee = knownFee,
                        reservedAmount = reservedAmount,
                        reservedOutputKeys = selectedOutputs.mapTo(hashSetOf(), ::outputKey),
                        spendKeyImages = spendKeyImages
                    )
                }
            }
            if (restActive()) refreshRest() else publishTransactions()
            Result.success(SendResult(localHash))
        }
    }

    private fun isReallySpent(output: Output): Boolean =
        output.spendKeyImages.any { keyImage ->
            native.isRealSpend(
                txPubKeyHex = output.txPubKey,
                outIndex = output.index,
                majI = output.recipient.majI,
                minI = output.recipient.minI,
                keyImageHex = keyImage
            )
        }

    private fun outputKey(output: Output): String =
        "${output.txPubKey.lowercase()}:${output.index}:${output.recipient.majI}:${output.recipient.minI}"

    suspend fun getSubaddress(index: Int? = null): Result<String> {
        if (index == 0) return Result.success(address)

        if (restorePending) {
            checkRestoreReadiness()
            if (restorePending) return Result.failure(IllegalStateException("Wallet restore is pending"))
        }

        val targetIndex = index ?: run {
            val txsResult = api.getAddressTxs(address, viewKey)
            if (txsResult.isFailure) return Result.failure(txsResult.exceptionOrNull()!!)

            val used = mutableSetOf<Int>()
            txsResult.getOrThrow().transactions.forEach { tx ->
                if (tx.recipient.minI > 0) used.add(tx.recipient.minI)
                tx.spentOutputs.forEach { spend ->
                    if (spend.sender.minI > 0) used.add(spend.sender.minI)
                }
            }
            synchronized(feedLock) {
                feedTransactions.values.forEach { tx ->
                    tx.receives.forEach { receive ->
                        if (receive.recipientMajor == 0 && receive.recipientMinor > 0) {
                            used.add(receive.recipientMinor)
                        }
                    }
                }
            }

            var next = 1
            while (used.contains(next)) next++
            next
        }

        val provisionResult = api.provisionSubaddrs(address, viewKey, 0, targetIndex, 1, 1)
        if (provisionResult.isFailure) return Result.failure(provisionResult.exceptionOrNull()!!)

        return Result.success(native.getAddress(accountIndex = 0, addressIndex = targetIndex))
    }

    suspend fun requestRescan(): Result<String> = api.importRequest(address, viewKey).map { it.status }
}

private data class PendingLocalSend(
    val hash: String,
    val amount: Long,
    val fee: Long?,
    val reservedAmount: Long,
    val reservedOutputKeys: Set<String>,
    val spendKeyImages: Set<String>
)

private data class FeedSnapshot(
    val transactions: List<TransactionInfo>,
    val confirmedNet: Long,
    val pendingReceived: Long,
    val pendingReserved: Long
)

private fun List<FeedReceive>.sumFeedReceiveAmounts(): Long = sumOf { it.amount }
private fun List<Output>.sumOutputAmounts(): Long = sumOf { it.amount.toLongOrNull() ?: 0L }

data class WalletSyncState(
    val connected: Boolean = false,
    val syncing: Boolean = true,
    val balance: Balance? = null,
    val transactions: List<TransactionInfo> = emptyList(),
    val restorePending: Boolean = false,
    val error: String? = null
)

data class Balance(
    val total: Long,
    val unlocked: Long,
    val locked: Long,
    val scannedHeight: Long,
    val blockchainHeight: Long,
    val rates: Map<String, Float> = emptyMap(),
    val restorePending: Boolean = false,
    val pendingReserved: Long = 0L,
    val pendingReceived: Long = 0L
) {
    val availableToSpend: Long get() = unlocked
    fun totalXMR() = total / 1_000_000_000_000.0
    fun unlockedXMR() = unlocked / 1_000_000_000_000.0
    fun totalFiat(currency: String): Double? = rates[currency]?.let { totalXMR() * it }
    fun unlockedFiat(currency: String): Double? = rates[currency]?.let { unlockedXMR() * it }
}

data class TransactionInfo(
    val hash: String,
    val height: Long?,
    val timestamp: String?,
    val amount: Long,
    val confirmed: Boolean,
    val unlockTime: Long,
    val paymentId: String?
) {
    fun amountXMR() = amount / 1_000_000_000_000.0
}

data class SendResult(val txid: String)

private const val BLOCKCHAIN_REORG_CODE = 4
private const val MAX_RETRY_DELAY_MS = 30_000L
