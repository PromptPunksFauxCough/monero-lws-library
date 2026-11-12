package org.monero.lws.gui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.monero.lws.lib.MoneroLWSClient
import org.monero.lws.lib.wallet.Balance
import org.monero.lws.lib.wallet.TransactionInfo
import org.monero.lws.lib.wallet.Wallet
import kotlinx.coroutines.launch
import java.io.File

private val Orange = Color(0xFFFF6600)
private val DarkGray = Color(0xFF4C4C4C)
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)

private val LWSColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Black,
    primaryContainer = DarkGray,
    onPrimaryContainer = White,
    secondary = Orange,
    onSecondary = Black,
    secondaryContainer = DarkGray,
    onSecondaryContainer = White,
    background = Black,
    onBackground = White,
    surface = Black,
    onSurface = White,
    surfaceVariant = DarkGray,
    onSurfaceVariant = White,
    error = Color(0xFFCF6679),
    onError = Black
)

fun loadWalletCurrency(context: Context, walletName: String): String {
    val file = File(context.filesDir, "wallets/$walletName.currency")
    return if (file.exists()) file.readText().trim() else "USD"
}

fun saveWalletCurrency(context: Context, walletName: String, currency: String) {
    val file = File(context.filesDir, "wallets/$walletName.currency")
    file.writeText(currency)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = LWSColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Black) {
                    App(applicationContext = this)
                }
            }
        }
    }
}

sealed class Screen {
    object Connect : Screen()
    object WalletSelect : Screen()
    object Dashboard : Screen()
}

@Composable
fun App(applicationContext: ComponentActivity) {
    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }
    var client by remember { mutableStateOf<MoneroLWSClient?>(null) }
    var wallet by remember { mutableStateOf<Wallet?>(null) }
    var walletName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        error?.let {
            Surface(color = Color(0xFF442222), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, modifier = Modifier.weight(1f), color = Orange)
                    TextButton(onClick = { error = null }) {
                        Text("Dismiss", color = Orange)
                    }
                }
            }
        }

        when (screen) {
            Screen.Connect -> ConnectScreen(
                context = applicationContext,
                onConnected = { c ->
                    client = c
                    screen = Screen.WalletSelect
                },
                onError = { error = it }
            )
            Screen.WalletSelect -> WalletSelectScreen(
                context = applicationContext,
                client = client!!,
                onWalletReady = { w, name ->
                    wallet = w
                    walletName = name
                    screen = Screen.Dashboard
                },
                onError = { error = it },
                onBack = { screen = Screen.Connect }
            )
            Screen.Dashboard -> DashboardScreen(
                context = applicationContext,
                wallet = wallet!!,
                walletName = walletName,
                onError = { error = it },
                onLogout = {
                    wallet = null
                    walletName = ""
                    screen = Screen.WalletSelect
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    context: ComponentActivity,
    onConnected: (MoneroLWSClient) -> Unit,
    onError: (String) -> Unit
) {
    var serverUrl by remember { mutableStateOf("http://127.0.0.1:8080") }
    var network by remember { mutableStateOf("stagenet") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val networks = listOf("mainnet", "stagenet", "testnet")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Connect to LWS", style = MaterialTheme.typography.headlineMedium, color = Orange)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange,
                unfocusedBorderColor = DarkGray,
                focusedLabelColor = Orange,
                unfocusedLabelColor = DarkGray,
                cursorColor = Orange
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = network,
                onValueChange = {},
                readOnly = true,
                label = { Text("Network") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = DarkGray,
                    focusedLabelColor = Orange,
                    unfocusedLabelColor = DarkGray
                )
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(DarkGray)
            ) {
                networks.forEach { n ->
                    DropdownMenuItem(
                        text = { Text(n, color = White) },
                        onClick = {
                            network = n
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                loading = true
                scope.launch {
                    val client = MoneroLWSClient(context, serverUrl.trim(), network)
                    val result = client.initialize()
                    loading = false
                    if (result.isSuccess) {
                        onConnected(client)
                    } else {
                        onError(result.exceptionOrNull()?.message ?: "Connection failed")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && serverUrl.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = Black,
                disabledContainerColor = DarkGray,
                disabledContentColor = Black
            )
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Black)
            } else {
                Text("Connect")
            }
        }
    }
}

@Composable
fun WalletSelectScreen(
    context: ComponentActivity,
    client: MoneroLWSClient,
    onWalletReady: (Wallet, String) -> Unit,
    onError: (String) -> Unit,
    onBack: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Create", "Restore", "Open")
    val availableWallets = remember { client.native.getAvailableWallets() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← Back", color = Orange)
            }
            Spacer(modifier = Modifier.weight(1f))
        }

        TabRow(
            selectedTabIndex = tab,
            containerColor = Black,
            contentColor = Orange
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, color = if (tab == index) Orange else DarkGray) }
                )
            }
        }

        when (tab) {
            0 -> CreateWalletForm(client, onWalletReady, onError)
            1 -> RestoreWalletForm(client, onWalletReady, onError)
            2 -> OpenWalletForm(client, availableWallets, onWalletReady, onError)
        }
    }
}

@Composable
fun ColumnScope.SeedBackupContent(
    title: String,
    seed: String,
    continueLabel: String,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    Text(title, style = MaterialTheme.typography.headlineSmall, color = Orange)
    Spacer(modifier = Modifier.height(16.dp))
    Text("SAVE YOUR SEED:", color = Orange, style = MaterialTheme.typography.labelLarge)
    Spacer(modifier = Modifier.height(8.dp))
    Surface(color = DarkGray, modifier = Modifier.fillMaxWidth()) {
        Text(
            seed,
            modifier = Modifier.padding(12.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = White
        )
    }
    Spacer(modifier = Modifier.height(16.dp))
    OutlinedButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Monero Seed", seed))
            Toast.makeText(context, "Seed copied to clipboard", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange)
    ) {
        Text("Copy Seed to Clipboard")
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Black)
    ) {
        Text(continueLabel)
    }
}

@Composable
fun CreateWalletForm(
    client: MoneroLWSClient,
    onWalletReady: (Wallet, String) -> Unit,
    onError: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var createdSeed by remember { mutableStateOf<String?>(null) }
    var createdWallet by remember { mutableStateOf<Wallet?>(null) }
    var createdName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        if (createdSeed != null) {
            SeedBackupContent(
                title = "Wallet Created",
                seed = createdSeed!!,
                continueLabel = "I Saved My Seed - Continue",
                onContinue = { onWalletReady(createdWallet!!, createdName) }
            )
        } else {
            Text("Create New Wallet", style = MaterialTheme.typography.headlineSmall, color = Orange)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Wallet Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = DarkGray,
                    focusedLabelColor = Orange,
                    unfocusedLabelColor = DarkGray,
                    cursorColor = Orange
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = DarkGray,
                    focusedLabelColor = Orange,
                    unfocusedLabelColor = DarkGray,
                    cursorColor = Orange
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    loading = true
                    scope.launch {
                        val result = client.createWallet(name.trim(), password)
                        loading = false
                        if (result.isSuccess) {
                            val w = result.getOrThrow()
                            createdSeed = w.seed
                            createdWallet = w
                            createdName = name.trim()
                        } else {
                            onError(result.exceptionOrNull()?.message ?: "Failed to create wallet")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && name.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = Black,
                    disabledContainerColor = DarkGray,
                    disabledContentColor = Black
                )
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Black)
                } else {
                    Text("Create Wallet")
                }
            }
        }
    }
}

@Composable
fun RestoreWalletForm(
    client: MoneroLWSClient,
    onWalletReady: (Wallet, String) -> Unit,
    onError: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var seed by remember { mutableStateOf("") }
    var height by remember { mutableStateOf("0") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Restore Wallet", style = MaterialTheme.typography.headlineSmall, color = Orange)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Wallet Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange,
                unfocusedBorderColor = DarkGray,
                focusedLabelColor = Orange,
                unfocusedLabelColor = DarkGray,
                cursorColor = Orange
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange,
                unfocusedBorderColor = DarkGray,
                focusedLabelColor = Orange,
                unfocusedLabelColor = DarkGray,
                cursorColor = Orange
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = seed,
            onValueChange = { seed = it },
            label = { Text("25-word Seed") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange,
                unfocusedBorderColor = DarkGray,
                focusedLabelColor = Orange,
                unfocusedLabelColor = DarkGray,
                cursorColor = Orange
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = height,
            onValueChange = { height = it.filter { c -> c.isDigit() } },
            label = { Text("Restore Height (0 for full scan)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Orange,
                unfocusedBorderColor = DarkGray,
                focusedLabelColor = Orange,
                unfocusedLabelColor = DarkGray,
                cursorColor = Orange
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                loading = true
                scope.launch {
                    val result = client.restoreWallet(
                        name.trim(),
                        password,
                        seed.trim(),
                        height.toLongOrNull() ?: 0L
                    )
                    loading = false
                    if (result.isSuccess) {
                        onWalletReady(result.getOrThrow(), name.trim())
                    } else {
                        onError(result.exceptionOrNull()?.message ?: "Failed to restore wallet")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && name.isNotBlank() && password.isNotBlank() && seed.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Orange,
                contentColor = Black,
                disabledContainerColor = DarkGray,
                disabledContentColor = Black
            )
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Black)
            } else {
                Text("Restore Wallet")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWalletForm(
    client: MoneroLWSClient,
    availableWallets: List<String>,
    onWalletReady: (Wallet, String) -> Unit,
    onError: (String) -> Unit
) {
    var selectedWallet by remember { mutableStateOf(availableWallets.firstOrNull() ?: "") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Open Existing Wallet", style = MaterialTheme.typography.headlineSmall, color = Orange)
        Spacer(modifier = Modifier.height(24.dp))

        if (availableWallets.isEmpty()) {
            Text(
                "No wallets found. Create or restore a wallet first.",
                color = DarkGray,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedWallet,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select Wallet") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = DarkGray,
                        focusedLabelColor = Orange,
                        unfocusedLabelColor = DarkGray
                    )
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(DarkGray)
                ) {
                    availableWallets.forEach { wallet ->
                        DropdownMenuItem(
                            text = { Text(wallet, color = White) },
                            onClick = {
                                selectedWallet = wallet
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = DarkGray,
                    focusedLabelColor = Orange,
                    unfocusedLabelColor = DarkGray,
                    cursorColor = Orange
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    loading = true
                    scope.launch {
                        val result = client.openWallet(selectedWallet, password)
                        loading = false
                        if (result.isSuccess) {
                            onWalletReady(result.getOrThrow(), selectedWallet)
                        } else {
                            onError(result.exceptionOrNull()?.message ?: "Failed to open wallet")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && selectedWallet.isNotBlank() && password.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Orange,
                    contentColor = Black,
                    disabledContainerColor = DarkGray,
                    disabledContentColor = Black
                )
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Black)
                } else {
                    Text("Open Wallet")
                }
            }
        }
    }
}

@Composable
fun DashboardScreen(
    context: ComponentActivity,
    wallet: Wallet,
    walletName: String,
    onError: (String) -> Unit,
    onLogout: () -> Unit
) {
    val walletState by wallet.syncState.collectAsState()
    val scope = rememberCoroutineScope()
    val balance = walletState.balance
    val transactions = walletState.transactions
    val loading = walletState.syncing && balance == null
    var showSendDialog by remember { mutableStateOf(false) }
    var showReceiveDialog by remember { mutableStateOf(false) }
    var showSeedDialog by remember { mutableStateOf(false) }
    var selectedCurrency by remember { mutableStateOf(loadWalletCurrency(context, walletName)) }

    val restorePending = balance?.restorePending == true
    val sendEnabled = !restorePending && (balance?.availableToSpend ?: 0L) > 0L

    DisposableEffect(wallet) {
        wallet.startSync()
        onDispose { wallet.stopSync() }
    }

    LaunchedEffect(wallet, walletState.error) {
        if (walletState.error != null) {
            wallet.getBalance().onFailure { onError(it.message ?: "Refresh failed") }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = DarkGray, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(walletName, style = MaterialTheme.typography.titleMedium, color = Orange)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onLogout) { Text("Logout", color = Orange) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        wallet.address.take(12) + "..." + wallet.address.takeLast(8),
                        modifier = Modifier.pointerInput(wallet.address) {
                            detectTapGestures(
                                onLongPress = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Address", wallet.address))
                                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = White
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "Show Seed",
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .pointerInput(wallet) {
                                detectTapGestures(
                                    onLongPress = {
                                        if (wallet.seed.isBlank()) {
                                            Toast.makeText(context, "Seed unavailable", Toast.LENGTH_SHORT).show()
                                        } else {
                                            showSeedDialog = true
                                        }
                                    }
                                )
                            },
                        fontSize = 12.sp,
                        color = Orange
                    )
                }
            }
        }

        if (loading && balance == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Orange)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        walletState.error ?: if (walletState.connected) "Syncing wallet" else "Connecting to LWS",
                        color = DarkGray
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    BalanceCard(
                        balance = balance,
                        loading = loading,
                        connected = walletState.connected,
                        onRefresh = {
                            scope.launch {
                                wallet.getBalance().onFailure { onError(it.message ?: "Refresh failed") }
                            }
                        },
                        selectedCurrency = selectedCurrency,
                        onCurrencyChange = { currency ->
                            selectedCurrency = currency
                            saveWalletCurrency(context, walletName, currency)
                        }
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { showReceiveDialog = true },
                            enabled = !restorePending,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DarkGray,
                                contentColor = Orange,
                                disabledContainerColor = DarkGray,
                                disabledContentColor = Color(0xFF888888)
                            )
                        ) {
                            Text("Receive", fontSize = 16.sp)
                        }
                        Button(
                            onClick = { showSendDialog = true },
                            enabled = sendEnabled,
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Orange,
                                contentColor = Black,
                                disabledContainerColor = DarkGray,
                                disabledContentColor = Color(0xFF888888)
                            )
                        ) {
                            Text("Send", fontSize = 16.sp)
                        }
                    }
                }

                item {
                    Text(
                        "Transactions (${transactions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
                        color = Orange
                    )
                }

                if (transactions.isEmpty()) {
                    item {
                        Text(
                            "No transactions yet",
                            modifier = Modifier.padding(16.dp),
                            color = DarkGray
                        )
                    }
                } else {
                    items(transactions) { tx ->
                        TransactionRow(tx)
                    }
                }
            }
        }
    }

    if (showSeedDialog) {
        Dialog(
            onDismissRequest = { showSeedDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.95f),
                color = Black,
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    SeedBackupContent(
                        title = "Wallet Seed",
                        seed = wallet.seed,
                        continueLabel = "Done",
                        onContinue = { showSeedDialog = false }
                    )
                }
            }
        }
    }

    if (showReceiveDialog) {
        ReceiveDialog(
            wallet = wallet,
            onDismiss = { showReceiveDialog = false }
        )
    }

    if (showSendDialog) {
        SendDialog(
            wallet = wallet,
            rates = balance?.rates ?: emptyMap(),
            availableToSpend = balance?.availableToSpend ?: 0L,
            selectedCurrency = selectedCurrency,
            onDismiss = { showSendDialog = false },
            onSent = {
                showSendDialog = false
            },
            onError = onError
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BalanceCard(
    balance: Balance?,
    loading: Boolean,
    connected: Boolean,
    onRefresh: () -> Unit,
    selectedCurrency: String,
    onCurrencyChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkGray)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance", style = MaterialTheme.typography.titleMedium, color = Orange)
                Spacer(modifier = Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Orange
                    )
                } else if (!connected) {
                    TextButton(onClick = onRefresh) { Text("Refresh", fontSize = 12.sp, color = Orange) }
                } else {
                    Text(
                        if (connected) "Feed" else "Reconnecting",
                        fontSize = 12.sp,
                        color = if (connected) Color(0xFF4CAF50) else Orange
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (balance != null) {
                if (balance.restorePending) {
                    Text(
                        "Pending",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Orange
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Waiting for server import/rescan approval.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "The wallet will update when the server scan advances.",
                        fontSize = 12.sp,
                        color = DarkGray
                    )
                } else {
                    Text(
                        String.format("%.12f XMR", balance.totalXMR()),
                        style = MaterialTheme.typography.headlineMedium,
                        color = White
                    )

                    if (balance.rates.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val fiatValue = balance.totalFiat(selectedCurrency)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (fiatValue != null) {
                                Text(
                                    String.format("≈ %.2f %s", fiatValue, selectedCurrency),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Orange
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            CurrencySelector(
                                currencies = balance.rates.keys.sorted(),
                                selected = selectedCurrency,
                                onSelect = onCurrencyChange
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Unlocked",
                                style = MaterialTheme.typography.labelSmall,
                                color = Orange
                            )
                            Text(
                                String.format("%.6f", balance.unlockedXMR()),
                                color = White
                            )
                            if (balance.rates.isNotEmpty()) {
                                balance.unlockedFiat(selectedCurrency)?.let {
                                    Text(
                                        String.format("≈ %.2f", it),
                                        fontSize = 11.sp,
                                        color = Orange
                                    )
                                }
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Locked",
                                style = MaterialTheme.typography.labelSmall,
                                color = Orange
                            )
                            Text(
                                String.format(
                                    "%.6f",
                                    balance.locked / 1_000_000_000_000.0
                                ),
                                color = White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "Synced: ${balance.scannedHeight} / ${balance.blockchainHeight}",
                        fontSize = 12.sp,
                        color = DarkGray
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrencySelector(
    currencies: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        Surface(
            modifier = Modifier.menuAnchor(),
            color = Black,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(selected, color = White, fontSize = 12.sp)
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkGray)
        ) {
            currencies.forEach { currency ->
                DropdownMenuItem(
                    text = { Text(currency, color = White) },
                    onClick = {
                        onSelect(currency)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun ReceiveDialog(
    wallet: Wallet,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showSubaddress by remember { mutableStateOf(true) }
    var subaddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        wallet.getSubaddress()
            .onSuccess { subaddress = it }
    }

    val primaryAddress = wallet.address
    val displayAddress = if (showSubaddress && subaddress != null) {
        subaddress!!
    } else {
        primaryAddress
    }

    val qrBitmap = remember(displayAddress) { generateQRCode(displayAddress) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            color = Black,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Receive",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Orange
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Close", color = Orange)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    if (showSubaddress) "Subaddress" else "Primary Address",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (showSubaddress) White else Orange
                )

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = White),
                    modifier = Modifier.size(280.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        qrBitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(260.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Surface(
                    color = DarkGray,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        displayAddress,
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = White,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Monero Address", displayAddress)
                        )
                        Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT)
                            .show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange,
                        contentColor = Black
                    )
                ) {
                    Text("Copy Address")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showSubaddress = !showSubaddress },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Orange)
                ) {
                    Text(if (showSubaddress) "Show Primary Address" else "Show Subaddress")
                }
            }
        }
    }
}

@Composable
fun SendDialog(
    wallet: Wallet,
    rates: Map<String, Float>,
    availableToSpend: Long,
    selectedCurrency: String,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    onError: (String) -> Unit
) {
    var destination by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var isXMRMode by remember { mutableStateOf(true) }
    var sendMax by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hasRates = rates.isNotEmpty() && rates.containsKey(selectedCurrency)
    val rate = rates[selectedCurrency]

    val xmrAmount: Double? = if (isXMRMode) {
        amountInput.toDoubleOrNull()
    } else {
        val fiatAmount = amountInput.toDoubleOrNull()
        if (fiatAmount != null && rate != null && rate > 0) {
            fiatAmount / rate
        } else null
    }

    val displayConversion: String? = if (amountInput.isNotBlank()) {
        if (isXMRMode) {
            val xmr = amountInput.toDoubleOrNull()
            if (xmr != null && rate != null) {
                String.format("≈ %.2f %s", xmr * rate, selectedCurrency)
            } else null
        } else {
            xmrAmount?.let { String.format("≈ %.6f XMR", it) }
        }
    } else null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            color = Black,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Send",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Orange
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = Orange)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text("Destination", style = MaterialTheme.typography.labelLarge, color = Orange)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    placeholder = { Text("Enter address", color = DarkGray) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = DarkGray,
                        cursorColor = Orange
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Amount", style = MaterialTheme.typography.labelLarge, color = Orange)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            sendMax = true
                            isXMRMode = true
                            amountInput = java.math.BigDecimal(availableToSpend)
                                .movePointLeft(12)
                                .stripTrailingZeros()
                                .toPlainString()
                        },
                        enabled = availableToSpend > 0L && !loading
                    ) {
                        Text("Max", color = Orange)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = {
                            sendMax = false
                            amountInput = it.filter { c -> c.isDigit() || c == '.' }
                        },
                        placeholder = { Text("0.00", color = DarkGray) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Orange,
                            unfocusedBorderColor = DarkGray,
                            cursorColor = Orange
                        )
                    )

                    if (hasRates) {
                        Spacer(modifier = Modifier.width(12.dp))
                        Surface(
                            onClick = {
                                sendMax = false
                                isXMRMode = !isXMRMode
                            },
                            color = DarkGray,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (isXMRMode) Orange else DarkGray,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        "XMR",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        fontSize = 14.sp,
                                        color = if (isXMRMode) Black else White
                                    )
                                }
                                Surface(
                                    color = if (!isXMRMode) Orange else DarkGray,
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Text(
                                        selectedCurrency,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        fontSize = 14.sp,
                                        color = if (!isXMRMode) Black else White
                                    )
                                }
                            }
                        }
                    }
                }

                displayConversion?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, fontSize = 14.sp, color = Orange)
                }

                if (sendMax) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sends all spendable funds. The network fee is deducted from this amount.",
                        fontSize = 12.sp,
                        color = White
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        val finalXMR = xmrAmount
                        if (finalXMR == null || finalXMR <= 0) {
                            onError("Invalid amount")
                            return@Button
                        }
                        if (destination.isBlank() || destination.length < 95 || destination.length > 106) {
                            onError("Invalid destination address")
                            return@Button
                        }

                        loading = true
                        scope.launch {
                            val result = if (sendMax) {
                                wallet.sendMax(destination.trim())
                            } else {
                                val amountAtomic = (finalXMR * 1_000_000_000_000).toLong()
                                wallet.send(destination.trim(), amountAtomic)
                            }
                            loading = false
                            if (result.isSuccess) {
                                onSent()
                            } else {
                                onError(result.exceptionOrNull()?.message ?: "Send failed")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = !loading && destination.isNotBlank() && amountInput.isNotBlank() && xmrAmount != null && xmrAmount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Orange,
                        contentColor = Black,
                        disabledContainerColor = DarkGray,
                        disabledContentColor = Black
                    )
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = Black)
                    } else {
                        Text("Send", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

fun generateQRCode(content: String): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

@Composable
fun TransactionRow(tx: TransactionInfo) {
    val context = LocalContext.current
    val isIncoming = tx.amount >= 0
    val amountColor = if (isIncoming) Color(0xFF4CAF50) else Orange
    val prefix = if (isIncoming) "+" else ""

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .pointerInput(tx.hash) {
                detectTapGestures(
                    onLongPress = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TXID", tx.hash))
                        Toast.makeText(context, "TXID copied", Toast.LENGTH_SHORT).show()
                    }
                )
            },
        color = DarkGray,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isIncoming) "IN" else "OUT",
                    style = MaterialTheme.typography.labelMedium,
                    color = amountColor
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    "$prefix${String.format("%.6f", tx.amountXMR())} XMR",
                    color = amountColor,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    if (tx.confirmed) "confirmed" else "pending",
                    fontSize = 11.sp,
                    color = if (tx.confirmed) White else Orange
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                tx.hash.take(16) + "..." + tx.hash.takeLast(8),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = White
            )
            tx.height?.let {
                Text("Height: $it", fontSize = 11.sp, color = White)
            }
        }
    }
}
