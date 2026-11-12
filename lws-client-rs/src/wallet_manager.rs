use bytes::{Buf, BufMut, Bytes, BytesMut};
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use curve25519_dalek::scalar::Scalar as DalekScalar;
use monero_oxide::ed25519::{CompressedPoint, Scalar};
use monero_oxide::primitives::keccak256;
use monero_oxide::ed25519::Point;
use monero_oxide::io::VarInt;
use monero_seed::{Language, Seed};
use monero_wallet::address::{AddressType, MoneroAddress, Network};
use zeroize::Zeroizing;

use crate::error::{LwsError, Result};
use crate::models::{KeyPair, WalletPayload};
use crate::state::WalletContext;
use crate::storage;

fn derive_keys_from_entropy(entropy: &[u8; 32]) -> Result<(Scalar, Scalar)> {
    let spend_ct = DalekScalar::from_canonical_bytes(*entropy);
    if !bool::from(spend_ct.is_some()) {
        return Err(LwsError::Crypto("Seed entropy is not a canonical scalar".into()));
    }
    let spend_sec = spend_ct.unwrap();

    let view_hash = keccak256(spend_sec.to_bytes());
    let view_sec = DalekScalar::from_bytes_mod_order(view_hash);

    Ok((Scalar::from(spend_sec), Scalar::from(view_sec)))
}

fn build_address(spend: &Scalar, view: &Scalar, nettype: u8) -> Result<String> {
    let spend_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(*spend));
    let spend_pub_dalek = &spend_sec * ED25519_BASEPOINT_TABLE;
    let spend_point = CompressedPoint::from(spend_pub_dalek.compress().to_bytes())
        .decompress()
        .ok_or_else(|| LwsError::Crypto("Invalid spend point".into()))?;

    let view_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(*view));
    let view_pub_dalek = &view_sec * ED25519_BASEPOINT_TABLE;
    let view_point = CompressedPoint::from(view_pub_dalek.compress().to_bytes())
        .decompress()
        .ok_or_else(|| LwsError::Crypto("Invalid view point".into()))?;

    let net = match nettype {
        0 => Network::Mainnet,
        1 => Network::Testnet,
        _ => Network::Stagenet,
    };
    Ok(MoneroAddress::new(net, AddressType::Legacy, spend_point, view_point).to_string())
}

fn create_context(
    view_key: Scalar,
    spend_key: Scalar,
    address: String,
    seed: String,
    network_type: u8,
    restore_height: u64,
) -> WalletContext {
    WalletContext {
        view_key,
        spend_key,
        address,
        seed,
        network_type,
        restore_height,
    }
}

pub fn create_wallet(path: &str, password: &Bytes, network_type: u8) -> Result<WalletContext> {
    let mut rng = rand::thread_rng();
    let seed_obj = Seed::new(&mut rng, Language::English);
    let seed_words = seed_obj.to_string();
    let entropy = seed_obj.entropy();

    let (spend_key, view_key) = derive_keys_from_entropy(&entropy)?;
    let address = build_address(&spend_key, &view_key, network_type)?;

    let payload = WalletPayload {
        language: "English".to_string(),
        scan_height: 0,
        restore_height: 0,
        requested_start: 0,
        lookahead_major: 200,
        lookahead_minor: 200,
        nettype: network_type,
        view: KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(view_key)),
            pub_hex: hex::encode(
                (&DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(view_key)) *
                    ED25519_BASEPOINT_TABLE)
                    .compress()
                    .to_bytes(),
            ),
        },
        spend: KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(spend_key)),
            pub_hex: hex::encode(
                (&DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(spend_key)) *
                    ED25519_BASEPOINT_TABLE)
                    .compress()
                    .to_bytes(),
            ),
        },
        generated_locally: true,
    };

    storage::save_wallet(path, &payload, password)?;
    Ok(create_context(
        view_key,
        spend_key,
        address,
        seed_words.to_string(),
        network_type,
        0,
    ))
}

pub fn recovery_wallet(
    path: &str,
    password: &Bytes,
    mnemonic: &str,
    network_type: u8,
    restore_height: u64,
) -> Result<WalletContext> {
    let seed_obj = Seed::from_string(Language::English, Zeroizing::new(mnemonic.to_string()))
        .map_err(|e| LwsError::Crypto(format!("Invalid seed: {:?}", e)))?;
    let seed_words = seed_obj.to_string();
    let entropy = seed_obj.entropy();

    let (spend_key, view_key) = derive_keys_from_entropy(&entropy)?;
    let address = build_address(&spend_key, &view_key, network_type)?;

    let payload = WalletPayload {
        language: "English".to_string(),
        scan_height: 0,
        restore_height,
        requested_start: restore_height,
        lookahead_major: 200,
        lookahead_minor: 200,
        nettype: network_type,
        view: KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(view_key)),
            pub_hex: hex::encode(
                (&DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(view_key)) *
                    ED25519_BASEPOINT_TABLE)
                    .compress()
                    .to_bytes(),
            ),
        },
        spend: KeyPair {
            sec_hex: hex::encode(<[u8; 32]>::from(spend_key)),
            pub_hex: hex::encode(
                (&DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(spend_key)) *
                    ED25519_BASEPOINT_TABLE)
                    .compress()
                    .to_bytes(),
            ),
        },
        generated_locally: false,
    };

    storage::save_wallet(path, &payload, password)?;
    Ok(create_context(
        view_key,
        spend_key,
        address,
        seed_words.to_string(),
        network_type,
        restore_height,
    ))
}

pub fn open_wallet(path: &str, password: &Bytes, _network_type: u8) -> Result<WalletContext> {
    let payload = storage::load_wallet(path, password)?;

    let mut view_bytes = Bytes::from(hex::decode(&payload.view.sec_hex).map_err(|_| LwsError::Decryption)?);
    if view_bytes.len() != 32 {
        return Err(LwsError::Decryption);
    }
    let mut view_arr = [0u8; 32];
    view_bytes.copy_to_slice(&mut view_arr);
    let view_key = Scalar::from(DalekScalar::from_bytes_mod_order(view_arr));

    let mut spend_bytes = Bytes::from(hex::decode(&payload.spend.sec_hex).map_err(|_| LwsError::Decryption)?);
    if spend_bytes.len() != 32 {
        return Err(LwsError::Decryption);
    }
    let mut spend_arr = [0u8; 32];
    spend_bytes.copy_to_slice(&mut spend_arr);
    let spend_key = Scalar::from(DalekScalar::from_bytes_mod_order(spend_arr));

    let address = build_address(&spend_key, &view_key, payload.nettype)?;
    let seed_words = Seed::from_entropy(Language::English, Zeroizing::new(spend_arr))
        .map(|seed| seed.to_string().to_string())
        .unwrap_or_default();
    Ok(create_context(
        view_key,
        spend_key,
        address,
        seed_words,
        payload.nettype,
        payload.restore_height,
    ))
}

pub fn is_real_spend(
    ctx: &WalletContext,
    tx_pub_key_hex: &str,
    out_index: u32,
    maj_i: u32,
    min_i: u32,
    key_image_hex: &str,
) -> Result<bool> {
    let mut tx_pub_bytes = Bytes::from(hex::decode(tx_pub_key_hex).map_err(|_| LwsError::Crypto("Invalid tx_pub_key hex".into()))?);
    if tx_pub_bytes.len() != 32 {
        return Err(LwsError::Crypto("Invalid tx_pub_key length".into()));
    }
    let mut tx_pub_arr = [0u8; 32];
    tx_pub_bytes.copy_to_slice(&mut tx_pub_arr);

    let tx_pub_dalek = curve25519_dalek::edwards::CompressedEdwardsY::from_slice(&tx_pub_arr)
        .map_err(|_| LwsError::Crypto("Bad tx pub key encoding".into()))?
        .decompress()
        .ok_or_else(|| LwsError::Crypto("Bad tx pub key point".into()))?;

    let view_dalek = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.view_key));
    let r_a_dalek = (view_dalek * tx_pub_dalek).mul_by_cofactor();
    let shared_bytes = r_a_dalek.compress().to_bytes();

    let mut derivation_preimage = BytesMut::with_capacity(32 + 5);
    derivation_preimage.put_slice(&shared_bytes);
    {
        let mut writer = (&mut derivation_preimage).writer();
        VarInt::write(&(out_index as u64), &mut writer)
            .map_err(|e| LwsError::Crypto(e.to_string()))?;
    }
    let base_secret = Scalar::hash(&derivation_preimage.freeze());

    let spend_sec_dalek = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.spend_key));
    let base_secret_dalek = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(base_secret));

    let input_key_dalek = if maj_i == 0 && min_i == 0 {
        spend_sec_dalek + base_secret_dalek
    } else {
        let view_key_bytes: [u8; 32] = <[u8; 32]>::from(ctx.view_key);
        let mut subaddr_preimage = BytesMut::new();
        subaddr_preimage.put_slice(b"SubAddr\0");
        subaddr_preimage.put_slice(&view_key_bytes);
        subaddr_preimage.put_slice(&maj_i.to_le_bytes());
        subaddr_preimage.put_slice(&min_i.to_le_bytes());

        let subaddr_secret = Scalar::hash(&subaddr_preimage.freeze());
        let subaddr_secret_dalek = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(subaddr_secret));
        spend_sec_dalek + subaddr_secret_dalek + base_secret_dalek
    };

    let p_dalek = &input_key_dalek * ED25519_BASEPOINT_TABLE;
    let p_bytes = p_dalek.compress().to_bytes();

    let hp = Point::biased_hash(p_bytes);

    let i_dalek = input_key_dalek * hp.into();
    let expected_key_image_bytes = i_dalek.compress().to_bytes();

    let server_key_image_bytes = Bytes::from(
        hex::decode(key_image_hex).map_err(|_| LwsError::Crypto("Invalid key image hex".into()))?,
    );

    Ok(Bytes::copy_from_slice(&expected_key_image_bytes).as_ref() == server_key_image_bytes.as_ref())
}
