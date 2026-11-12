package org.monero.lws.lib.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString

internal sealed interface LWSFeedMessage {
    data object Connected : LWSFeedMessage
    data class TxSync(val scannedHeight: Long, val blockchainHeight: Long, val transactions: List<FeedTransaction>) : LWSFeedMessage
    data class Blocks(val scanStart: Long, val scanEnd: Long, val blockchainHeight: Long, val transactions: List<FeedTransaction>) : LWSFeedMessage
    data class Mempool(val transaction: FeedTransaction) : LWSFeedMessage
    data class Warning(val message: String, val code: Int, val height: Long) : LWSFeedMessage
    data class Error(val message: String, val code: Int) : LWSFeedMessage
}

internal data class FeedTransaction(
    val hash: String,
    val timestamp: Long?,
    val unlockTime: Long,
    val fee: Long?,
    val height: Long?,
    val paymentId: String?,
    val coinbase: Boolean,
    val mempool: Boolean,
    val receives: List<FeedReceive>,
    val spends: List<FeedSpend>
)

internal data class FeedReceive(
    val id: String?,
    val amount: Long,
    val index: Int,
    val txPubKey: String,
    val recipientMajor: Int,
    val recipientMinor: Int
)

internal data class FeedSpend(val id: String?, val keyImage: String)

internal class LWSFeedClient(serverUrl: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val websocketClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(60, TimeUnit.SECONDS)
        .build()
    private val url = feedUrl(serverUrl)

    fun messages(address: String, viewKey: String): Flow<LWSFeedMessage> = callbackFlow {
        val channel = this
        val login = buildJsonObject {
            putJsonObject("account") {
                put("address", address)
                put("view_key", viewKey)
            }
            put("tx_sync", true)
            put("receives_only", false)
        }

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", JSON_SUBPROTOCOL)
            .build()

        val socket = websocketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                if (!webSocket.send("login:$login")) {
                    channel.close(IOException("Unable to send LWS feed login"))
                    return
                }
                channel.trySend(LWSFeedMessage.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = parseMessage(text)
                    val queued = channel.trySend(message)
                    if (queued.isFailure) {
                        channel.close(LWSFeedException(null, "LWS feed consumer fell behind; reconnecting for a fresh tx_sync"))
                    } else if (message is LWSFeedMessage.Error) {
                        channel.close(LWSFeedException(message.code, message.message))
                    }
                } catch (e: Exception) {
                    channel.close(LWSFeedException(null, "Invalid LWS feed message: ${e.message}", e))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                channel.close(LWSFeedException(null, "Received binary data on JSON LWS feed"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                channel.close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.close(LWSFeedException(code, "LWS feed closed: $reason"))
            }
        })

        awaitClose { socket.cancel() }
    }

    private fun parseMessage(text: String): LWSFeedMessage {
        val separator = text.indexOf(':')
        require(separator > 0) { "Missing feed message prefix" }
        val prefix = text.substring(0, separator)
        val payload = json.parseToJsonElement(text.substring(separator + 1)).asObject()

        return when (prefix) {
            "tx_sync" -> LWSFeedMessage.TxSync(
                scannedHeight = payload.long("scanned_block_height"),
                blockchainHeight = payload.long("blockchain_height"),
                transactions = payload.array("transactions").map { it.asObject().toFeedTransaction() }
            )
            "blocks" -> LWSFeedMessage.Blocks(
                scanStart = payload.long("scan_start"),
                scanEnd = payload.long("scan_end"),
                blockchainHeight = payload.long("blockchain_height"),
                transactions = payload.array("transactions").map { it.asObject().toFeedTransaction() }
            )
            "mempool" -> LWSFeedMessage.Mempool(payload.toMempoolTransaction())
            "warning" -> LWSFeedMessage.Warning(
                message = payload.string("msg").orEmpty(),
                code = payload.int("code"),
                height = payload.longOrNull("height") ?: 0L
            )
            "error" -> LWSFeedMessage.Error(
                message = payload.string("msg").orEmpty(),
                code = payload.int("code")
            )
            else -> throw IllegalArgumentException("Unknown LWS feed prefix '$prefix'")
        }
    }

    private fun JsonObject.toFeedTransaction(): FeedTransaction {
        val height = longOrNull("height")
        return FeedTransaction(
            hash = (string("hash") ?: throw IllegalArgumentException("Transaction hash missing")).lowercase(),
            timestamp = longOrNull("timestamp"),
            unlockTime = longOrNull("unlock_time") ?: 0L,
            fee = longOrNull("fee"),
            height = height,
            paymentId = string("payment_id"),
            coinbase = bool("coinbase") ?: false,
            mempool = bool("mempool") ?: (height == null),
            receives = array("receives").map { element ->
                val receive = element.asObject()
                val recipient = receive["recipient"]?.asObject()
                FeedReceive(
                    id = receive["id"]?.outputId(),
                    amount = receive.long("amount"),
                    index = receive.int("index"),
                    txPubKey = receive.string("tx_pub_key")
                        ?: throw IllegalArgumentException("Receive tx_pub_key missing"),
                    recipientMajor = recipient?.int("maj_i") ?: 0,
                    recipientMinor = recipient?.int("min_i") ?: 0
                )
            },
            spends = array("spends").map { element ->
                val spend = element.asObject()
                FeedSpend(
                    id = spend["id"]?.outputId(),
                    keyImage = spend.string("key_image")
                        ?: throw IllegalArgumentException("Spend key_image missing")
                )
            }
        )
    }

    private fun JsonObject.toMempoolTransaction(): FeedTransaction {
        val recipient = this["recipient"]?.asObject()
        val hash = (string("hash") ?: throw IllegalArgumentException("Mempool hash missing")).lowercase()
        return FeedTransaction(
            hash = hash,
            timestamp = null,
            unlockTime = longOrNull("unlock_time") ?: 0L,
            fee = longOrNull("fee"),
            height = null,
            paymentId = string("payment_id"),
            coinbase = false,
            mempool = true,
            receives = listOf(
                FeedReceive(
                    id = null,
                    amount = long("amount"),
                    index = int("index"),
                    txPubKey = string("tx_pub_key")
                        ?: throw IllegalArgumentException("Mempool tx_pub_key missing"),
                    recipientMajor = recipient?.int("maj_i") ?: 0,
                    recipientMinor = recipient?.int("min_i") ?: 0
                )
            ),
            spends = emptyList()
        )
    }

    private fun JsonElement.outputId(): String? {
        val obj = this as? JsonObject ?: return null
        val legacy = obj["legacy"] as? JsonObject
        if (legacy != null) {
            val amount = legacy.longOrNull("amount") ?: return null
            val index = legacy.longOrNull("index") ?: return null
            return "legacy:$amount:$index"
        }
        val unified = obj.longOrNull("unified") ?: return null
        return "unified:$unified"
    }

    private fun JsonElement.asObject(): JsonObject = this as? JsonObject
        ?: throw IllegalArgumentException("Expected JSON object")

    private fun JsonObject.array(name: String): List<JsonElement> =
        when (val value = this[name]) {
            null, JsonNull -> emptyList()
            is JsonArray -> value.toList()
            else -> throw IllegalArgumentException("Expected '$name' array")
        }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content

    private fun JsonObject.long(name: String): Long = longOrNull(name)
        ?: throw IllegalArgumentException("Missing/invalid '$name'")

    private fun JsonObject.longOrNull(name: String): Long? = this[name].longOrNull()

    private fun JsonObject.int(name: String): Int = long(name).toInt()

    private fun JsonObject.bool(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement?.longOrNull(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.longOrNull ?: primitive.content.toLongOrNull()
    }

    private fun feedUrl(serverUrl: String): String {
        val parsed = serverUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid LWS URL: $serverUrl")
        return parsed.newBuilder()
            .addPathSegment("feed")
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private companion object {
        const val JSON_SUBPROTOCOL = "lws.feed.v0.json"
    }
}

internal class LWSFeedException(
    val code: Int?,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
