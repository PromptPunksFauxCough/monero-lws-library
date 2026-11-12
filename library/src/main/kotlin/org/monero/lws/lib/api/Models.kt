package org.monero.lws.lib.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String,
    @SerialName("create_account") val createAccount: Boolean,
    @SerialName("generated_locally") val generatedLocally: Boolean
)

@Serializable
data class LoginResponse(
    @SerialName("new_address") val newAddress: Boolean,
    @SerialName("generated_locally") val generatedLocally: Boolean? = null,
    @SerialName("start_height") val startHeight: Long? = null
)

@Serializable
data class AddressRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String
)

@Serializable
data class ImportRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String,
    @SerialName("from_height") val fromHeight: Long
)

@Serializable
data class ProvisionSubaddrsRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String,
    @SerialName("maj_i") val majI: Int? = null,
    @SerialName("min_i") val minI: Int? = null,
    @SerialName("n_maj") val nMaj: Int? = null,
    @SerialName("n_min") val nMin: Int? = null
)

@Serializable
data class SubaddrMap(
    val key: Int,
    val value: List<List<Int>> = emptyList()
)

@Serializable
data class SubaddrsResponse(
    @SerialName("all_subaddrs") val allSubaddrs: List<SubaddrMap> = emptyList(),
    @SerialName("new_subaddrs") val newSubaddrs: List<SubaddrMap>? = null
)

@Serializable
data class RecipientIndex(
    @SerialName("maj_i") val majI: Int = 0,
    @SerialName("min_i") val minI: Int = 0
)

@Serializable
data class Output(
    @SerialName("tx_id") val txId: Long,
    val amount: String,
    val index: Int,
    @SerialName("global_index") val globalIndex: Long,
    val rct: String? = null,
    @SerialName("tx_hash") val txHash: String,
    @SerialName("tx_prefix_hash") val txPrefixHash: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("tx_pub_key") val txPubKey: String,
    @SerialName("spend_key_images") val spendKeyImages: List<String> = emptyList(),
    val timestamp: String,
    val height: Long,
    val recipient: RecipientIndex = RecipientIndex()
)

@Serializable
data class AddressInfoResponse(
    @SerialName("locked_funds") val lockedFunds: String,
    @SerialName("total_received") val totalReceived: String,
    @SerialName("total_sent") val totalSent: String,
    @SerialName("scanned_height") val scannedHeight: Long,
    @SerialName("scanned_block_height") val scannedBlockHeight: Long,
    @SerialName("start_height") val startHeight: Long,
    @SerialName("transaction_height") val transactionHeight: Long,
    @SerialName("blockchain_height") val blockchainHeight: Long,
    @SerialName("spent_outputs") val spentOutputs: List<Spend> = emptyList(),
    val rates: Rates? = null
)

@Serializable
data class Spend(
    val amount: String,
    @SerialName("key_image") val keyImage: String,
    @SerialName("tx_pub_key") val txPubKey: String,
    @SerialName("out_index") val outIndex: Int,
    val mixin: Int,
    val sender: RecipientIndex = RecipientIndex()
)

@Serializable
data class Transaction(
    val id: Long,
    val hash: String,
    val timestamp: String? = null,
    @SerialName("total_received") val totalReceived: String,
    @SerialName("total_sent") val totalSent: String,
    val fee: String? = null,
    @SerialName("unlock_time") val unlockTime: Long,
    val height: Long? = null,
    @SerialName("spent_outputs") val spentOutputs: List<Spend> = emptyList(),
    @SerialName("recipient") val recipient: RecipientIndex = RecipientIndex(),
    @SerialName("payment_id") val paymentId: String? = null,
    val coinbase: Boolean,
    val mempool: Boolean,
    val mixin: Int
)

@Serializable
data class AddressTxsResponse(
    @SerialName("total_received") val totalReceived: String,
    @SerialName("scanned_height") val scannedHeight: Long,
    @SerialName("scanned_block_height") val scannedBlockHeight: Long,
    @SerialName("start_height") val startHeight: Long,
    @SerialName("transaction_height") val transactionHeight: Long = 0L,
    @SerialName("blockchain_height") val blockchainHeight: Long,
    val transactions: List<Transaction> = emptyList()
)

@Serializable
data class UnspentOutsRequest(
    val address: String,
    @SerialName("view_key") val viewKey: String,
    val amount: String,
    val mixin: Int,
    @SerialName("use_dust") val useDust: Boolean,
    @SerialName("dust_threshold") val dustThreshold: String? = null
)

@Serializable
data class UnspentOutsResponse(
    @SerialName("per_byte_fee") val perByteFee: Long,
    @SerialName("fee_mask") val feeMask: Long,
    val amount: String,
    val outputs: List<Output> = emptyList(),
    val fees: List<Long> = emptyList()
)

@Serializable
data class RandomOutsRequest(
    val count: Int,
    val amounts: List<String>
)

@Serializable
data class RandomOutsResponse(
    @SerialName("amount_outs") val amountOuts: List<RandomOutputs> = emptyList()
)

@Serializable
data class RandomOutputs(
    val amount: String,
    val outputs: List<RandomOutput> = emptyList()
)

@Serializable
data class RandomOutput(
    @SerialName("global_index") val globalIndex: String,
    @SerialName("public_key") val publicKey: String,
    val rct: String
)

@Serializable
data class SubmitTxRequest(val tx: String)

@Serializable
data class SubmitTxResponse(
    val status: String,
    val reason: String = "",
    @SerialName("not_relayed") val notRelayed: Boolean = false
)

@Serializable
data class ImportResponse(
    @SerialName("payment_address") val paymentAddress: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("import_fee") val importFee: String? = null,
    @SerialName("new_request") val newRequest: Boolean,
    @SerialName("request_fulfilled") val requestFulfilled: Boolean,
    val status: String
)

@Serializable
data class Rates(
    @SerialName("AUD") val aud: Float? = null,
    @SerialName("BRL") val brl: Float? = null,
    @SerialName("BTC") val btc: Float? = null,
    @SerialName("CAD") val cad: Float? = null,
    @SerialName("CHF") val chf: Float? = null,
    @SerialName("CNY") val cny: Float? = null,
    @SerialName("EUR") val eur: Float? = null,
    @SerialName("GBP") val gbp: Float? = null,
    @SerialName("HKD") val hkd: Float? = null,
    @SerialName("INR") val inr: Float? = null,
    @SerialName("JPY") val jpy: Float? = null,
    @SerialName("KRW") val krw: Float? = null,
    @SerialName("MXN") val mxn: Float? = null,
    @SerialName("NOK") val nok: Float? = null,
    @SerialName("NZD") val nzd: Float? = null,
    @SerialName("SEK") val sek: Float? = null,
    @SerialName("SGD") val sgd: Float? = null,
    @SerialName("TRY") val try_: Float? = null,
    @SerialName("USD") val usd: Float? = null,
    @SerialName("RUB") val rub: Float? = null,
    @SerialName("ZAR") val zar: Float? = null
) {
    fun toMap(): Map<String, Float> = buildMap {
        aud?.let { put("AUD", it) }
        brl?.let { put("BRL", it) }
        btc?.let { put("BTC", it) }
        cad?.let { put("CAD", it) }
        chf?.let { put("CHF", it) }
        cny?.let { put("CNY", it) }
        eur?.let { put("EUR", it) }
        gbp?.let { put("GBP", it) }
        hkd?.let { put("HKD", it) }
        inr?.let { put("INR", it) }
        jpy?.let { put("JPY", it) }
        krw?.let { put("KRW", it) }
        mxn?.let { put("MXN", it) }
        nok?.let { put("NOK", it) }
        nzd?.let { put("NZD", it) }
        sek?.let { put("SEK", it) }
        sgd?.let { put("SGD", it) }
        try_?.let { put("TRY", it) }
        usd?.let { put("USD", it) }
        rub?.let { put("RUB", it) }
        zar?.let { put("ZAR", it) }
    }
}

data class WalletData(
    val name: String,
    val address: String,
    val viewKey: String,
    val spendKey: String,
    val seed: String,
    val restoreHeight: Long = 0
)
