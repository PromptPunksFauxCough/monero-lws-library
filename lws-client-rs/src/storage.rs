use aead::{Aead, KeyInit, Payload};
use argon2::Argon2;
use bytes::{BufMut, Bytes, BytesMut};
use chacha20poly1305::ChaCha20Poly1305;
use serde::{Deserialize, Serialize};
use std::fs::File;
use std::io::{Read, Write};

use crate::error::{LwsError, Result};
use crate::models::WalletPayload;

const SALT_SIZE: usize = 16;
const NONCE_SIZE: usize = 12;
const MEMORY_LIMIT_KB: u32 = 7 * 1024;
const OPS_MIN: u32 = 5;
const FILE_MAGIC: &[u8] = b"lwsf-wallet-1.0";

#[derive(Serialize, Deserialize)]
struct EncryptedFileEnvelope {
    cipher: String,
    pwhasher: String,
    salt_hex: String,
    nonce_hex: String,
    epayload_hex: String,
    iterations: u32,
    memory: u32,
}

fn serialize_msgpack<T: Serialize>(value: &T) -> Result<Bytes> {
    let mut output = BytesMut::new();
    {
        let mut serializer = rmp_serde::Serializer::new((&mut output).writer());
        value
            .serialize(&mut serializer)
            .map_err(|e| LwsError::Serialization(e.to_string()))?;
    }
    Ok(output.freeze())
}

pub fn save_wallet(path: &str, payload: &WalletPayload, password: &Bytes) -> Result<()> {
    let payload_bytes = serialize_msgpack(payload)?;

    let salt = generate_random_bytes(SALT_SIZE);
    let nonce = generate_random_bytes(NONCE_SIZE);
    let iterations = std::cmp::max(OPS_MIN, 3);

    let key = derive_key(password, &salt, iterations)?;
    let epayload = encrypt_payload(&key, &nonce, &payload_bytes)?;

    let envelope = EncryptedFileEnvelope {
        cipher: "chacha20-poly1305_ietf".to_string(),
        pwhasher: "argon2id".to_string(),
        salt_hex: hex::encode(&salt),
        nonce_hex: hex::encode(&nonce),
        epayload_hex: hex::encode(&epayload),
        iterations,
        memory: MEMORY_LIMIT_KB,
    };

    let mut file = File::create(path)?;
    file.write_all(FILE_MAGIC)?;
    let env_bytes = serialize_msgpack(&envelope)?;
    file.write_all(env_bytes.as_ref())?;
    Ok(())
}

pub fn load_wallet(path: &str, password: &Bytes) -> Result<WalletPayload> {
    let mut file = File::open(path)?;

    let mut magic = BytesMut::with_capacity(FILE_MAGIC.len());
    let magic_read = {
        let mut limited = (&mut file).take(FILE_MAGIC.len() as u64);
        let mut writer = (&mut magic).writer();
        std::io::copy(&mut limited, &mut writer)?
    };
    if magic_read != FILE_MAGIC.len() as u64 || magic.as_ref() != FILE_MAGIC {
        return Err(LwsError::Decryption);
    }

    let mut env_bytes = BytesMut::new();
    {
        let mut writer = (&mut env_bytes).writer();
        std::io::copy(&mut file, &mut writer)?;
    }
    let env_bytes = env_bytes.freeze();

    let envelope: EncryptedFileEnvelope = rmp_serde::from_slice(env_bytes.as_ref())
        .map_err(|e| LwsError::Serialization(e.to_string()))?;

    if envelope.cipher != "chacha20-poly1305_ietf" || envelope.pwhasher != "argon2id" {
        return Err(LwsError::InvalidState("Unsupported crypto primitive".into()));
    }

    let salt = Bytes::from(hex::decode(&envelope.salt_hex).map_err(|_| LwsError::Serialization("Bad salt".into()))?);
    let nonce = Bytes::from(hex::decode(&envelope.nonce_hex).map_err(|_| LwsError::Serialization("Bad nonce".into()))?);
    let epayload = Bytes::from(hex::decode(&envelope.epayload_hex).map_err(|_| LwsError::Serialization("Bad payload".into()))?);

    let key = derive_key(password, &salt, envelope.iterations)?;
    let payload_bytes = decrypt_payload(&key, &nonce, &epayload).ok_or(LwsError::Decryption)?;

    rmp_serde::from_slice(payload_bytes.as_ref()).map_err(|e| LwsError::Serialization(e.to_string()))
}

fn derive_key(password: &Bytes, salt: &Bytes, iterations: u32) -> Result<[u8; 32]> {
    let mut key = [0u8; 32];
    let argon2 = Argon2::new(
        argon2::Algorithm::Argon2id,
        argon2::Version::V0x13,
        argon2::Params::new(MEMORY_LIMIT_KB, iterations, 1, Some(32)).map_err(|e| LwsError::Crypto(e.to_string()))?,
    );
    argon2
        .hash_password_into(password.as_ref(), salt.as_ref(), &mut key)
        .map_err(|e| LwsError::Crypto(e.to_string()))?;
    Ok(key)
}

fn encrypt_payload(key: &[u8; 32], nonce: &Bytes, payload: &Bytes) -> Result<Bytes> {
    let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(|e| LwsError::Crypto(e.to_string()))?;
    let nonce = nonce.as_ref().try_into().map_err(|_| LwsError::Crypto("Invalid nonce length".to_string()))?;
    cipher
        .encrypt(nonce, Payload { msg: payload.as_ref(), aad: b"" })
        .map(Bytes::from)
        .map_err(|e| LwsError::Crypto(e.to_string()))
}

fn decrypt_payload(key: &[u8; 32], nonce: &Bytes, epayload: &Bytes) -> Option<Bytes> {
    let cipher = ChaCha20Poly1305::new_from_slice(key).ok()?;
    let nonce = nonce.as_ref().try_into().ok()?;
    cipher
        .decrypt(nonce, Payload { msg: epayload.as_ref(), aad: b"" })
        .ok()
        .map(Bytes::from)
}

fn generate_random_bytes(len: usize) -> Bytes {
    use rand::RngCore;
    let mut buf = BytesMut::with_capacity(len);
    buf.put_bytes(0, len);
    rand::thread_rng().fill_bytes(buf.as_mut());
    buf.freeze()
}
