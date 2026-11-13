# Monero LWS Library

Kotlin library for Android that provides Monero wallet operations against a self-hosted [Monero Light Wallet Server (LWS)](https://github.com/vtnerd/monero-lws). Wallet keys, seed handling, transaction construction and signing run locally via a Rust native library.

## Overview

**Key Features:**
- Create new wallets
- Restore from seed (w/restore height)
- Existing wallets password-protected, encrypted on-device
- View wallet balances (with fiat conversion when the server provides rates)
- View transaction history, with live updates over the LWS WebSocket feed
- Send + `sendMax` (exact network fee deducted)

**Targets:**
- Android (minSdk 28)
- ABIs: armeabi-v7a, arm64-v8a, x86_64

## Architecture

| Layer | Location | Role |
|---|---|---|
| Kotlin library | `library/` | Public API (`MoneroLWSClient`, `Wallet`), LWS REST and feed clients, JNA bindings |
| Rust native library | `lws-client-rs/` | Key derivation, wallet-file encryption, input selection, transaction building and signing (built on `monero-oxide` / `monero-wallet`) |
| Example app | `example/gui/` | Jetpack Compose app demonstrating the library |

## Installation

### Android

Download the latest AAR from [Releases](../../releases/latest).

The AAR includes native libraries for:
- armeabi-v7a
- arm64-v8a
- x86_64

## Build from Source

### Requirements
- JDK 17+
- Gradle 8.9
- Android SDK (compileSdk 34)
- Android NDK
- Rust toolchain (`rustup`) with the Android targets:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

### Native Libraries

`lws-client-rs` only needs the NDK linker for each target.

Create `lws-client-rs/.cargo/config.toml` pointing at your NDK. Example (adjust the NDK path and version):

```toml
[target.aarch64-linux-android]
linker = "/path/to/android-sdk/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"

[target.armv7-linux-androideabi]
linker = "/path/to/android-sdk/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang"

[target.x86_64-linux-android]
linker = "/path/to/android-sdk/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android28-clang"
```

(Note the armv7 linker is named `armv7a-linux-androideabi28-clang` which, differs from the triplet)

For the complete automated build, see `.github/workflows/build.yml`.

```bash
cd lws-client-rs
cargo build --release --target aarch64-linux-android
cargo build --release --target armv7-linux-androideabi
cargo build --release --target x86_64-linux-android
```

Copy each resulting `target/<triplet>/release/liblws_client_rs.so` into the library's `jniLibs`:

| Target triplet | Destination |
|---|---|
| `aarch64-linux-android` | `library/src/main/jniLibs/arm64-v8a/` |
| `armv7-linux-androideabi` | `library/src/main/jniLibs/armeabi-v7a/` |
| `x86_64-linux-android` | `library/src/main/jniLibs/x86_64/` |

### Library

```bash
cd library
gradle wrapper --gradle-version 8.9 && ./gradlew assembleRelease
```

Output: `library/build/outputs/aar/monero-lws-library-release.aar`

### Example App

```bash
cd example/gui
gradle wrapper --gradle-version 8.9 && ./gradlew assembleDebug
```

Output: `example/gui/build/outputs/apk/debug/monero-lws-library-gui-debug.apk`

The example builds the library from source via a Gradle composite build.

## Usage

### Android

```kotlin
import org.monero.lws.lib.MoneroLWSClient

val client = MoneroLWSClient(context, "http://192.168.1.100:8080", "mainnet")
client.initialize()

// wallet calls from coroutine

// create
val wallet = client.createWallet("mywallet", "password123").getOrThrow()
println("Address: ${wallet.address}")
println("Seed: ${wallet.seed}")

// restore from seed
// val wallet = client.restoreWallet("mywallet", "password123", "<25-word seed>", restoreHeight = 3_000_000L).getOrThrow()

// open existing wallet
// val wallet = client.openWallet("mywallet", "password123").getOrThrow()

// sync: collect wallet.syncState (balance, transactions, connection state)
wallet.startSync()
wallet.stopSync()

// balance
val balance = wallet.getBalance().getOrThrow()
println("Total: ${balance.totalXMR()} XMR")
println("Unlocked: ${balance.unlockedXMR()} XMR")

// fiat
balance.totalFiat("USD")?.let { println("≈ $it USD") }

// transactions
wallet.getTransactions().getOrThrow().forEach { tx ->
    println("${tx.hash}: ${tx.amountXMR()} XMR")
}

// send (amount in atomic units)
val result = wallet.send("4...", 1_000_000_000_000L)

// send everything (network fee deducted from total)
val swept = wallet.sendMax("4...")
```

The network is `"mainnet"`, `"testnet"` or `"stagenet"` (default).

### Example App Features

- Create, Restore and Open are tabs on the setup screen.
- On the dashboard header, long-tap abbreviated primary address to copy and long-tap **Show Seed** to display seed.
- Long-tap transaction to copy its TXID.
- Send screen has **Max** button.

## LWS Server Setup

### Prerequisites

Running `monerod` with ZMQ enabled:

```bash
./monerod --zmq-pub tcp://127.0.0.1:18083 --detach
```

### Start LWS

```bash
./monero-lws-daemon \
  --network main \
  --db-path ./lws-data \
  --daemon tcp://127.0.0.1:18082 \
  --sub tcp://127.0.0.1:18083 \
  --rest-server http://0.0.0.0:8080 \
  --admin-rest-server http://127.0.0.1:8081 \
  --auto-accept-creation \
  --auto-accept-import \
  --confirm-external-bind \
  --max-subaddresses 100000
```

Add `--exchange-rate-interval <value>` to enable fiat conversion (now returns: 401 "API key required").

### Demo
https://github.com/PromptPunksFauxCough/monero-lws-library/releases/download/latest/monero-lws-library-proof.mp4

00:00-00:55 | LWS Server config (NON-feed)

01:05-02:00 | Example GUI login/wallet creation/subaddress

02:15-02:30 | Faucet transaction #1

02:45-06:45 | Waiting for +first confirmation of faucet transaction #1

07:50-08:15 | LWS Server config (Enable feed)

08:30-08:45 | Example GUI open existing wallet (Feed indicator)

08:50-09:00 | Example GUI subaddress

09:00-09:45 | Faucet transaction #2

09:50-09:51 | Example GUI mempool transaction

10:00-19:33 | Waiting for +first confirmation of faucet transaction #2

24:00-25:45 | Restore account (from height)

34:20-34:30 | Faucet transaction #1 funds become spendable

36:00-36:05 | Faucet transaction #2 funds become spendable

36-20-36:50 | Send all via max (mempool transaction)

46:44-47:50 | Send transaction receives first confirmation
