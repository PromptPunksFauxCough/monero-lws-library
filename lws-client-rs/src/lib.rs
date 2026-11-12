mod error;
mod models;
mod state;
mod storage;
mod tx;
mod wallet_manager;

use crate::error::LwsError;
use crate::models::PendingTransaction;
use crate::state::WalletContext;

use bytes::Bytes;
use libc::{c_char, c_int, c_void};
use monero_oxide::ed25519::{CompressedPoint, Scalar};
use monero_wallet::address::{Network, SubaddressIndex};
use monero_wallet::ViewPair;
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use curve25519_dalek::scalar::Scalar as DalekScalar;
use std::cell::RefCell;
use std::ffi::{CStr, CString};
use zeroize::Zeroizing;

fn cstr_to_string(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned()
}

fn cstr_to_bytes(ptr: *const c_char) -> Bytes {
    Bytes::from(cstr_to_string(ptr).into_bytes())
}

fn string_to_cstr(s: &str) -> *mut c_char {
    CString::new(s.as_bytes()).unwrap_or_default().into_raw()
}

std::thread_local! {
    static LAST_ERROR: RefCell<Option<String>> = RefCell::new(None);
}

fn set_last_error(err: LwsError) {
    LAST_ERROR.with(|error| *error.borrow_mut() = Some(err.to_string()));
}

fn take_last_error() -> String {
    LAST_ERROR.with(|error| error.borrow_mut().take().unwrap_or_default())
}

#[no_mangle]
pub extern "C" fn LWS_free_string(value: *mut c_char) {
    if !value.is_null() {
        unsafe {
            drop(CString::from_raw(value));
        }
    }
}

#[no_mangle]
pub extern "C" fn LWSF_WalletManagerFactory_getWalletManager() -> *mut c_void {
    Box::into_raw(Box::new(())) as *mut c_void
}

#[no_mangle]
pub extern "C" fn MONERO_WalletManager_createWallet(
    _wm: *mut c_void,
    path: *const c_char,
    password: *const c_char,
    network_type: c_int,
) -> *mut c_void {
    match crate::wallet_manager::create_wallet(
        &cstr_to_string(path),
        &cstr_to_bytes(password),
        network_type as u8,
    ) {
        Ok(ctx) => Box::into_raw(Box::new(ctx)) as *mut c_void,
        Err(e) => {
            set_last_error(e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn MONERO_WalletManager_recoveryWallet(
    _wm: *mut c_void,
    path: *const c_char,
    password: *const c_char,
    mnemonic: *const c_char,
    network_type: c_int,
    restore_height: u64,
) -> *mut c_void {
    match crate::wallet_manager::recovery_wallet(
        &cstr_to_string(path),
        &cstr_to_bytes(password),
        &cstr_to_string(mnemonic),
        network_type as u8,
        restore_height,
    ) {
        Ok(ctx) => Box::into_raw(Box::new(ctx)) as *mut c_void,
        Err(e) => {
            set_last_error(e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn MONERO_WalletManager_openWallet(
    _wm: *mut c_void,
    path: *const c_char,
    password: *const c_char,
    network_type: c_int,
) -> *mut c_void {
    match crate::wallet_manager::open_wallet(
        &cstr_to_string(path),
        &cstr_to_bytes(password),
        network_type as u8,
    ) {
        Ok(ctx) => Box::into_raw(Box::new(ctx)) as *mut c_void,
        Err(e) => {
            set_last_error(e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn MONERO_WalletManager_closeWallet(
    _wm: *mut c_void,
    wallet_ptr: *mut c_void,
    _store: bool,
) -> bool {
    if !wallet_ptr.is_null() {
        unsafe {
            let _ = Box::from_raw(wallet_ptr as *mut WalletContext);
        }
    }
    true
}

#[no_mangle]
pub extern "C" fn MONERO_WalletManager_walletExists(
    _wm: *mut c_void,
    path: *const c_char,
) -> bool {
    std::path::Path::new(&cstr_to_string(path)).exists()
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_status(_wallet_ptr: *mut c_void) -> c_int {
    0
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_errorString(_wallet_ptr: *mut c_void) -> *mut c_char {
    string_to_cstr(&take_last_error())
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_address(
    wallet_ptr: *mut c_void,
    account_index: u64,
    address_index: u64,
) -> *mut c_char {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };

    if account_index == 0 && address_index == 0 {
        return string_to_cstr(&ctx.address);
    }

    let spend_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.spend_key));
    let spend_pub_dalek = &spend_sec * ED25519_BASEPOINT_TABLE;
    let view_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.view_key));
    let view_sec_oxide = Zeroizing::new(Scalar::from(view_sec));

    if let Some(spend_point) = CompressedPoint::from(spend_pub_dalek.compress().to_bytes()).decompress() {
        if let Ok(view_pair) = ViewPair::new(spend_point, view_sec_oxide) {
            if let Some(sub_idx) = SubaddressIndex::new(account_index as u32, address_index as u32) {
                let net = match ctx.network_type {
                    0 => Network::Mainnet,
                    1 => Network::Testnet,
                    _ => Network::Stagenet,
                };
                return string_to_cstr(&view_pair.subaddress(net, sub_idx).to_string());
            }
        }
    }

    string_to_cstr(&ctx.address)
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_secretViewKey(wallet_ptr: *mut c_void) -> *mut c_char {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };
    string_to_cstr(&hex::encode(<[u8; 32]>::from(ctx.view_key)))
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_secretSpendKey(wallet_ptr: *mut c_void) -> *mut c_char {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };
    string_to_cstr(&hex::encode(<[u8; 32]>::from(ctx.spend_key)))
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_seed(
    wallet_ptr: *mut c_void,
    _seed_offset: *const c_char,
) -> *mut c_char {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };
    string_to_cstr(&ctx.seed)
}

#[no_mangle]
pub extern "C" fn MONERO_Wallet_store(
    wallet_ptr: *mut c_void,
    path: *const c_char,
    password: *const c_char,
) -> bool {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };

    let view_pub_dalek =
        &DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.view_key)) * ED25519_BASEPOINT_TABLE;
    let view_pub = hex::encode(view_pub_dalek.compress().to_bytes());

    let spend_pub_dalek =
        &DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.spend_key)) * ED25519_BASEPOINT_TABLE;
    let spend_pub = hex::encode(spend_pub_dalek.compress().to_bytes());

    let payload = crate::models::WalletPayload {
        language: "English".to_string(),
        scan_height: 0,
        restore_height: ctx.restore_height,
        requested_start: ctx.restore_height,
        lookahead_major: 200,
        lookahead_minor: 200,
        nettype: ctx.network_type,
        view: crate::models::KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(ctx.view_key)),
            pub_hex: view_pub,
        },
        spend: crate::models::KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(ctx.spend_key)),
            pub_hex: spend_pub,
        },
        generated_locally: true,
    };

    crate::storage::save_wallet(
        &cstr_to_string(path),
        &payload,
        &cstr_to_bytes(password),
    )
    .is_ok()
}

#[no_mangle]
pub extern "C" fn LWS_Wallet_selectInputs(
    _wallet_ptr: *mut c_void,
    unspent_json: *const c_char,
    amount: u64,
    mixin: c_int,
    per_byte_fee: u64,
    fee_mask: u64,
    blockchain_height: u64,
) -> *mut c_char {
    let json = cstr_to_string(unspent_json);

    let unspent: Vec<crate::models::Output> = match serde_json::from_str(&json) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(LwsError::Json(e));
            return std::ptr::null_mut();
        }
    };

    match crate::tx::select_inputs(
        &unspent,
        amount,
        mixin as u32,
        per_byte_fee,
        fee_mask,
        blockchain_height,
    ) {
        Ok(selected) => match serde_json::to_string(&selected) {
            Ok(s) => string_to_cstr(&s),
            Err(e) => {
                set_last_error(LwsError::Json(e));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            set_last_error(e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn LWS_Wallet_selectAllInputs(
    _wallet_ptr: *mut c_void,
    unspent_json: *const c_char,
    blockchain_height: u64,
) -> *mut c_char {
    let json = cstr_to_string(unspent_json);

    let unspent: Vec<crate::models::Output> = match serde_json::from_str(&json) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(LwsError::Json(e));
            return std::ptr::null_mut();
        }
    };

    let selected = crate::tx::select_all_inputs(&unspent, blockchain_height);
    match serde_json::to_string(&selected) {
        Ok(s) => string_to_cstr(&s),
        Err(e) => {
            set_last_error(LwsError::Json(e));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "C" fn LWS_Wallet_createTransaction(
    wallet_ptr: *mut c_void,
    dst_addr: *const c_char,
    amount: u64,
    mixin: c_int,
    per_byte_fee: u64,
    fee_mask: u64,
    selected_outputs_json: *const c_char,
    random_outs_json: *const c_char,
) -> *mut c_void {
    create_transaction_impl(
        wallet_ptr,
        dst_addr,
        amount,
        false,
        mixin,
        per_byte_fee,
        fee_mask,
        selected_outputs_json,
        random_outs_json,
    )
}

#[no_mangle]
pub extern "C" fn LWS_Wallet_createSweepTransaction(
    wallet_ptr: *mut c_void,
    dst_addr: *const c_char,
    mixin: c_int,
    per_byte_fee: u64,
    fee_mask: u64,
    selected_outputs_json: *const c_char,
    random_outs_json: *const c_char,
) -> *mut c_void {
    create_transaction_impl(
        wallet_ptr,
        dst_addr,
        0,
        true,
        mixin,
        per_byte_fee,
        fee_mask,
        selected_outputs_json,
        random_outs_json,
    )
}

fn create_transaction_impl(
    wallet_ptr: *mut c_void,
    dst_addr: *const c_char,
    amount: u64,
    sweep: bool,
    mixin: c_int,
    per_byte_fee: u64,
    fee_mask: u64,
    selected_outputs_json: *const c_char,
    random_outs_json: *const c_char,
) -> *mut c_void {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };

    match crate::tx::create_transaction_from_data(
        ctx,
        &cstr_to_string(dst_addr),
        amount,
        sweep,
        mixin as u32,
        per_byte_fee,
        fee_mask,
        &cstr_to_string(selected_outputs_json),
        &cstr_to_string(random_outs_json),
    ) {
        Ok(ptx) => Box::into_raw(Box::new(ptx)) as *mut c_void,
        Err(e) => {
            let error_string = e.to_string();
            set_last_error(e);
            let ptx = Box::new(PendingTransaction {
                status: 1,
                error_string,
                tx_hash_hex: String::new(),
                raw_bytes_hex: String::new(),
            });
            Box::into_raw(ptx) as *mut c_void
        }
    }
}

#[no_mangle]
pub extern "C" fn MONERO_PendingTransaction_status(pending_tx_ptr: *mut c_void) -> c_int {
    if pending_tx_ptr.is_null() {
        return 1;
    }
    unsafe { &*(pending_tx_ptr as *mut PendingTransaction) }.status
}

#[no_mangle]
pub extern "C" fn MONERO_PendingTransaction_errorString(pending_tx_ptr: *mut c_void) -> *mut c_char {
    if pending_tx_ptr.is_null() {
        return string_to_cstr("");
    }
    string_to_cstr(&unsafe { &*(pending_tx_ptr as *mut PendingTransaction) }.error_string)
}

#[no_mangle]
pub extern "C" fn MONERO_PendingTransaction_txid(
    pending_tx_ptr: *mut c_void,
    _separator: *const c_char,
) -> *mut c_char {
    if pending_tx_ptr.is_null() {
        return string_to_cstr("");
    }
    string_to_cstr(&unsafe { &*(pending_tx_ptr as *mut PendingTransaction) }.tx_hash_hex)
}

#[no_mangle]
pub extern "C" fn LWS_PendingTransaction_rawTxHex(pending_tx_ptr: *mut c_void) -> *mut c_char {
    if pending_tx_ptr.is_null() {
        return string_to_cstr("");
    }
    string_to_cstr(&unsafe { &*(pending_tx_ptr as *mut PendingTransaction) }.raw_bytes_hex)
}

#[no_mangle]
pub extern "C" fn MONERO_PendingTransaction_free(pending_tx_ptr: *mut c_void) {
    if !pending_tx_ptr.is_null() {
        unsafe {
            drop(Box::from_raw(pending_tx_ptr as *mut PendingTransaction));
        }
    }
}

#[no_mangle]
pub extern "C" fn LWS_Wallet_isRealSpend(
    wallet_ptr: *mut c_void,
    tx_pub_key_hex: *const c_char,
    out_index: u32,
    maj_i: u32,
    min_i: u32,
    key_image_hex: *const c_char,
) -> bool {
    let ctx = unsafe { &*(wallet_ptr as *mut WalletContext) };
    match crate::wallet_manager::is_real_spend(
        ctx,
        &cstr_to_string(tx_pub_key_hex),
        out_index,
        maj_i,
        min_i,
        &cstr_to_string(key_image_hex),
    ) {
        Ok(v) => v,
        Err(e) => {
            set_last_error(e);
            false
        }
    }
}
