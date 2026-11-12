package org.monero.lws.lib

import android.content.Context
import org.monero.lws.lib.api.LWSApiClient
import org.monero.lws.lib.native.MoneroNative
import org.monero.lws.lib.wallet.Wallet

class MoneroLWSClient(
    context: Context,
    private val serverUrl: String,
    network: String = "stagenet"
) {
    val native = MoneroNative(context, network)
    private val api = LWSApiClient(serverUrl)

    fun initialize(): Result<Unit> = native.initialize()

    suspend fun createWallet(name: String, password: String): Result<Wallet> {
        val dataResult = native.createWallet(name, password)
        if (dataResult.isFailure) {
            return Result.failure(dataResult.exceptionOrNull()!!)
        }
        val data = dataResult.getOrThrow()

        val loginResult = api.login(data.address, data.viewKey, true, true)
        if (loginResult.isFailure) {
            return Result.failure(loginResult.exceptionOrNull()!!)
        }

        native.store()

        return Result.success(Wallet(api, native, data, password))
    }

    suspend fun restoreWallet(
        name: String,
        password: String,
        seed: String,
        restoreHeight: Long = 0
    ): Result<Wallet> {
        val dataResult = native.restoreWallet(name, password, seed, restoreHeight)
        if (dataResult.isFailure) {
            return Result.failure(dataResult.exceptionOrNull()!!)
        }
        val data = dataResult.getOrThrow()

        val loginResult = api.login(data.address, data.viewKey, true, false)
        if (loginResult.isFailure) {
            return Result.failure(loginResult.exceptionOrNull()!!)
        }

        val wallet = Wallet(api, native, data, password, restorePending = true)

        wallet.submitInitialRestoreRequest()

        native.store()

        return Result.success(wallet)
    }

    suspend fun openWallet(name: String, password: String): Result<Wallet> {
        val dataResult = native.openWallet(name, password)
        if (dataResult.isFailure) {
            return Result.failure(dataResult.exceptionOrNull()!!)
        }
        val data = dataResult.getOrThrow()

        val loginResult = api.login(data.address, data.viewKey, false, false)
        if (loginResult.isFailure) {
            return Result.failure(loginResult.exceptionOrNull()!!)
        }

        return Result.success(Wallet(api, native, data, password))
    }
}
