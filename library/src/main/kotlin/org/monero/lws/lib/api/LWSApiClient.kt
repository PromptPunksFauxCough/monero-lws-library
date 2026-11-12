package org.monero.lws.lib.api

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class LWSApiClient(val serverUrl: String) {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = serverUrl.trimEnd('/')
    private val feedClient = LWSFeedClient(baseUrl)

    internal fun feedMessages(address: String, viewKey: String) =
        feedClient.messages(address, viewKey)

    suspend fun login(
        address: String,
        viewKey: String,
        createAccount: Boolean,
        generatedLocally: Boolean
    ): Result<LoginResponse> {
        return post("/login", LoginRequest(address, viewKey, createAccount, generatedLocally))
    }

    suspend fun getAddressInfo(address: String, viewKey: String): Result<AddressInfoResponse> {
        return post("/get_address_info", AddressRequest(address, viewKey))
    }

    suspend fun getAddressTxs(address: String, viewKey: String): Result<AddressTxsResponse> {
        return post("/get_address_txs", AddressRequest(address, viewKey))
    }

    suspend fun provisionSubaddrs(
        address: String,
        viewKey: String,
        majI: Int? = null,
        minI: Int? = null,
        nMaj: Int? = null,
        nMin: Int? = null
    ): Result<SubaddrsResponse> {
        return post(
            "/provision_subaddrs",
            ProvisionSubaddrsRequest(address, viewKey, majI, minI, nMaj, nMin)
        )
    }

    suspend fun getUnspentOuts(
        address: String,
        viewKey: String,
        amount: Long = 0,
        mixin: Int = 15,
        useDust: Boolean = false
    ): Result<UnspentOutsResponse> {
        return post(
            "/get_unspent_outs",
            UnspentOutsRequest(address, viewKey, amount.toString(), mixin, useDust)
        )
    }

    suspend fun getRandomOuts(count: Int, amounts: List<String>): Result<RandomOutsResponse> {
        return post("/get_random_outs", RandomOutsRequest(count, amounts))
    }

    suspend fun submitRawTx(txHex: String): Result<SubmitTxResponse> {
        return post("/submit_raw_tx", SubmitTxRequest(txHex))
    }

    suspend fun importRequest(
        address: String,
        viewKey: String,
        fromHeight: Long = 0L
    ): Result<ImportResponse> {
        return post("/import_wallet_request", ImportRequest(address, viewKey, fromHeight))
    }

    private suspend inline fun <reified T, reified R> post(endpoint: String, body: T): Result<R> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody =
                    json.encodeToString(body).toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> Result.success(json.decodeFromString<R>(responseBody))
                    400 -> Result.failure(LWSException.BadRequest(responseBody))
                    403 -> Result.failure(LWSException.Unauthorized)
                    404 -> Result.failure(LWSException.NotFound)
                    501 -> Result.failure(LWSException.NotImplemented)
                    503 -> Result.failure(LWSException.Unavailable)
                    else -> Result.failure(LWSException.Unknown(response.code, responseBody))
                }
            } catch (e: Exception) {
                Result.failure(LWSException.Network(e))
            }
        }
    }
}

sealed class LWSException(message: String) : Exception(message) {
    data class BadRequest(val body: String) : LWSException("Bad request: $body")
    object Unauthorized : LWSException("Unauthorized (403)")
    object NotFound : LWSException("Not found (404)")
    object NotImplemented : LWSException("Not implemented (501)")
    object Unavailable : LWSException("Service unavailable (503)")
    data class Unknown(val code: Int, val body: String) : LWSException("HTTP $code: $body")
    data class Network(override val cause: Throwable) : LWSException("Network error: ${cause.message}")
    data class NotRelayed(val reason: String) : LWSException("Transaction accepted but not relayed${if (reason.isBlank()) "" else ": $reason"}")
}
