package org.monero.lws.lib.native

import android.content.Context
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.monero.lws.lib.api.WalletData

class MoneroNative(private val context: Context, private val network: String = "stagenet") {

    private val baseDir: File = context.filesDir
    val walletDir: File = File(baseDir, "wallets").apply { mkdirs() }

    private val networkType: Int = when (network) {
        "mainnet" -> 0
        "testnet" -> 1
        else -> 2
    }

    private val lib: MoneroLibrary by lazy {
        Native.load("lws_client_rs", MoneroLibrary::class.java)
    }

    private val walletManager: Pointer by lazy {
        lib.LWSF_WalletManagerFactory_getWalletManager()
    }

    private val nativeLock = ReentrantLock()

    private var liveWallet: Pointer? = null
    private var liveWalletName: String? = null
    private var liveWalletPassword: String? = null

    fun initialize(): Result<Unit> = Result.success(Unit)

    suspend fun createWallet(name: String, password: String): Result<WalletData> =
        withContext(Dispatchers.IO) {
            nativeLock.withLock {
                val walletPath = File(walletDir, name).absolutePath
                if (File(walletPath).exists()) {
                    return@withContext Result.failure(
                        MoneroNativeException("Wallet '$name' already exists")
                    )
                }

                closeLiveWallet()

                val wallet = lib.MONERO_WalletManager_createWallet(
                    walletManager, walletPath, password, networkType
                )

                if (wallet == null) {
                    val error = readNativeString(lib.MONERO_Wallet_errorString(null))
                    return@withContext Result.failure(MoneroNativeException(error.ifEmpty { "Create wallet failed" }))
                }

                val data = extractWalletData(name, wallet)
                liveWallet = wallet
                liveWalletName = name
                liveWalletPassword = password

                Result.success(data)
            }
        }

    suspend fun restoreWallet(
        name: String,
        password: String,
        seed: String,
        restoreHeight: Long = 0
    ): Result<WalletData> = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            val walletPath = File(walletDir, name).absolutePath
            if (File(walletPath).exists()) {
                return@withContext Result.failure(
                    MoneroNativeException("Wallet '$name' already exists")
                )
            }

            closeLiveWallet()

            val wallet = lib.MONERO_WalletManager_recoveryWallet(
                walletManager, walletPath, password, seed, networkType, restoreHeight
            )

            if (wallet == null) {
                val error = readNativeString(lib.MONERO_Wallet_errorString(null))
                return@withContext Result.failure(MoneroNativeException(error.ifEmpty { "Restore wallet failed" }))
            }

            val data = extractWalletData(name, wallet, restoreHeight)
            liveWallet = wallet
            liveWalletName = name
            liveWalletPassword = password

            Result.success(data)
        }
    }

    suspend fun openWallet(name: String, password: String): Result<WalletData> =
        withContext(Dispatchers.IO) {
            nativeLock.withLock {
                if (liveWallet != null && liveWalletName == name) {
                    return@withContext Result.success(extractWalletData(name, liveWallet!!))
                }

                closeLiveWallet()
                val walletPath = File(walletDir, name).absolutePath

                val wallet = lib.MONERO_WalletManager_openWallet(
                    walletManager, walletPath, password, networkType
                )

                if (wallet == null) {
                    val error = readNativeString(lib.MONERO_Wallet_errorString(null))
                    return@withContext Result.failure(MoneroNativeException(error.ifEmpty { "Open wallet failed" }))
                }

                val data = extractWalletData(name, wallet)
                liveWallet = wallet
                liveWalletName = name
                liveWalletPassword = password

                Result.success(data)
            }
        }

    fun getAvailableWallets(): List<String> {
        val files = walletDir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && !it.name.endsWith(".currency") }
            .map { it.nameWithoutExtension }
            .filter { name ->
                nativeLock.withLock {
                    try {
                        lib.MONERO_WalletManager_walletExists(
                            walletManager, File(walletDir, name).absolutePath
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            .sorted()
    }

    fun store(): Boolean {
        nativeLock.withLock {
            val wallet = liveWallet ?: return false
            val name = liveWalletName ?: return false
            val password = liveWalletPassword ?: return false
            return lib.MONERO_Wallet_store(
                wallet,
                File(walletDir, name).absolutePath,
                password
            )
        }
    }

    fun getAddress(accountIndex: Int = 0, addressIndex: Int = 0): String {
        nativeLock.withLock {
            val wallet = liveWallet ?: return ""
            return readNativeString(lib.MONERO_Wallet_address(wallet, accountIndex.toLong(), addressIndex.toLong()))
        }
    }

    suspend fun selectInputs(
        unspentJson: String,
        amount: Long,
        mixin: Int,
        perByteFee: Long,
        feeMask: Long,
        blockchainHeight: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            val wallet = liveWallet
                ?: return@withContext Result.failure(MoneroNativeException("No wallet open"))

            val selected = lib.LWS_Wallet_selectInputs(
                wallet,
                unspentJson,
                amount,
                mixin,
                perByteFee,
                feeMask,
                blockchainHeight
            )

            if (selected == null) {
                val error = readNativeString(lib.MONERO_Wallet_errorString(wallet))
                return@withContext Result.failure(
                    MoneroNativeException(error.ifEmpty { "Input selection failed" })
                )
            }

            Result.success(readNativeString(selected))
        }
    }

    suspend fun selectAllInputs(
        unspentJson: String,
        blockchainHeight: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            val wallet = liveWallet
                ?: return@withContext Result.failure(MoneroNativeException("No wallet open"))

            val selected = lib.LWS_Wallet_selectAllInputs(wallet, unspentJson, blockchainHeight)

            if (selected == null) {
                val error = readNativeString(lib.MONERO_Wallet_errorString(wallet))
                return@withContext Result.failure(
                    MoneroNativeException(error.ifEmpty { "Input selection failed" })
                )
            }

            Result.success(readNativeString(selected))
        }
    }

    suspend fun createTransaction(
        destination: String,
        amount: Long,
        mixin: Int,
        perByteFee: Long,
        feeMask: Long,
        selectedOutputsJson: String,
        randomOutsJson: String,
        sweep: Boolean = false
    ): Result<NativePendingTx> = withContext(Dispatchers.IO) {
        nativeLock.withLock {
            val wallet = liveWallet
                ?: return@withContext Result.failure(MoneroNativeException("No wallet open"))

            val pending = if (sweep) {
                lib.LWS_Wallet_createSweepTransaction(
                    wallet,
                    destination,
                    mixin,
                    perByteFee,
                    feeMask,
                    selectedOutputsJson,
                    randomOutsJson
                )
            } else {
                lib.LWS_Wallet_createTransaction(
                    wallet,
                    destination,
                    amount,
                    mixin,
                    perByteFee,
                    feeMask,
                    selectedOutputsJson,
                    randomOutsJson
                )
            }

            if (pending == null) {
                val error = readNativeString(lib.MONERO_Wallet_errorString(null))
                return@withContext Result.failure(
                    MoneroNativeException(error.ifEmpty { "Transaction creation failed" })
                )
            }

            try {
                if (lib.MONERO_PendingTransaction_status(pending) != 0) {
                    val error = readNativeString(lib.MONERO_PendingTransaction_errorString(pending))
                    return@withContext Result.failure(MoneroNativeException(error))
                }

                val txid = readNativeString(lib.MONERO_PendingTransaction_txid(pending, ","))
                val rawTxHex = readNativeString(lib.LWS_PendingTransaction_rawTxHex(pending))

                Result.success(NativePendingTx(txid, rawTxHex))
            } finally {
                lib.MONERO_PendingTransaction_free(pending)
            }
        }
    }

    fun isRealSpend(
        txPubKeyHex: String,
        outIndex: Int,
        majI: Int,
        minI: Int,
        keyImageHex: String
    ): Boolean {
        nativeLock.withLock {
            val wallet = liveWallet ?: return false
            return lib.LWS_Wallet_isRealSpend(wallet, txPubKeyHex, outIndex, majI, minI, keyImageHex)
        }
    }

    private fun readNativeString(pointer: Pointer?): String {
        if (pointer == null) return ""
        return try {
            pointer.getString(0, "UTF-8")
        } finally {
            lib.LWS_free_string(pointer)
        }
    }

    private fun closeLiveWallet() {
        nativeLock.withLock {
            val wallet = liveWallet ?: return@withLock
            if (liveWalletName != null && liveWalletPassword != null) {
                lib.MONERO_Wallet_store(
                    wallet,
                    File(walletDir, liveWalletName!!).absolutePath,
                    liveWalletPassword!!
                )
            }
            lib.MONERO_WalletManager_closeWallet(walletManager, wallet, false)
            liveWallet = null
            liveWalletName = null
            liveWalletPassword = null
        }
    }

    private fun extractWalletData(
        name: String,
        wallet: Pointer,
        restoreHeight: Long = 0
    ) = WalletData(
        name = name,
        address = readNativeString(lib.MONERO_Wallet_address(wallet, 0, 0)),
        viewKey = readNativeString(lib.MONERO_Wallet_secretViewKey(wallet)),
        spendKey = readNativeString(lib.MONERO_Wallet_secretSpendKey(wallet)),
        seed = readNativeString(lib.MONERO_Wallet_seed(wallet, "")),
        restoreHeight = restoreHeight
    )

    data class NativePendingTx(
        val txid: String,
        val rawTxHex: String
    )

    private interface MoneroLibrary : Library {
        fun LWS_free_string(value: Pointer?)
        fun LWSF_WalletManagerFactory_getWalletManager(): Pointer

        fun MONERO_WalletManager_createWallet(
            wm: Pointer,
            path: String,
            password: String,
            networkType: Int
        ): Pointer?

        fun MONERO_WalletManager_recoveryWallet(
            wm: Pointer,
            path: String,
            password: String,
            mnemonic: String,
            networkType: Int,
            restoreHeight: Long
        ): Pointer?

        fun MONERO_WalletManager_openWallet(
            wm: Pointer,
            path: String,
            password: String,
            networkType: Int
        ): Pointer?

        fun MONERO_WalletManager_closeWallet(
            wm: Pointer,
            wallet: Pointer,
            store: Boolean
        ): Boolean

        fun MONERO_WalletManager_walletExists(wm: Pointer, path: String): Boolean

        fun MONERO_Wallet_status(wallet: Pointer): Int
        fun MONERO_Wallet_errorString(wallet: Pointer?): Pointer?
        fun MONERO_Wallet_address(wallet: Pointer, accountIndex: Long, addressIndex: Long): Pointer?
        fun MONERO_Wallet_secretViewKey(wallet: Pointer): Pointer?
        fun MONERO_Wallet_secretSpendKey(wallet: Pointer): Pointer?
        fun MONERO_Wallet_seed(wallet: Pointer, seedOffset: String): Pointer?
        fun MONERO_Wallet_store(wallet: Pointer, path: String, password: String): Boolean

        fun LWS_Wallet_selectInputs(
            wallet: Pointer,
            unspentJson: String,
            amount: Long,
            mixin: Int,
            perByteFee: Long,
            feeMask: Long,
            blockchainHeight: Long
        ): Pointer?

        fun LWS_Wallet_selectAllInputs(
            wallet: Pointer,
            unspentJson: String,
            blockchainHeight: Long
        ): Pointer?

        fun LWS_Wallet_createTransaction(
            wallet: Pointer,
            dstAddr: String,
            amount: Long,
            mixin: Int,
            perByteFee: Long,
            feeMask: Long,
            selectedOutputsJson: String,
            randomOutsJson: String
        ): Pointer?

        fun LWS_Wallet_createSweepTransaction(
            wallet: Pointer,
            dstAddr: String,
            mixin: Int,
            perByteFee: Long,
            feeMask: Long,
            selectedOutputsJson: String,
            randomOutsJson: String
        ): Pointer?

        fun MONERO_PendingTransaction_status(pendingTx: Pointer?): Int
        fun MONERO_PendingTransaction_free(pendingTx: Pointer?)
        fun MONERO_PendingTransaction_errorString(pendingTx: Pointer?): Pointer?
        fun MONERO_PendingTransaction_txid(pendingTx: Pointer?, separator: String): Pointer?
        fun LWS_PendingTransaction_rawTxHex(pendingTx: Pointer?): Pointer?

        fun LWS_Wallet_isRealSpend(
            wallet: Pointer,
            txPubKeyHex: String,
            outIndex: Int,
            majI: Int,
            minI: Int,
            keyImageHex: String
        ): Boolean
    }
}

class MoneroNativeException(message: String) : Exception(message)
