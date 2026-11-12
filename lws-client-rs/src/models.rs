use bytes::{Buf, Bytes};
use monero_oxide::ed25519::CompressedPoint;
use serde::{Deserialize, Deserializer, Serialize, Serializer};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct KeyPair {
    pub sec_hex: String,
    pub pub_hex: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct WalletPayload {
    pub language: String,
    pub scan_height: u64,
    pub restore_height: u64,
    pub requested_start: u64,
    pub lookahead_major: u32,
    pub lookahead_minor: u32,
    pub nettype: u8,
    pub view: KeyPair,
    pub spend: KeyPair,
    pub generated_locally: bool,
}

#[derive(Clone, Debug)]
pub struct PendingTransaction {
    pub status: i32,
    pub error_string: String,
    pub tx_hash_hex: String,
    pub raw_bytes_hex: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct RecipientIndex {
    #[serde(default)]
    pub maj_i: u32,
    #[serde(default)]
    pub min_i: u32,
}

pub mod hex_32_point {
    use super::*;

    pub fn serialize<S>(value: &CompressedPoint, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&hex::encode(value.to_bytes()))
    }

    pub fn deserialize<'de, D>(deserializer: D) -> std::result::Result<CompressedPoint, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        let mut bytes = Bytes::from(hex::decode(&s).map_err(serde::de::Error::custom)?);
        if bytes.len() != 32 {
            return Err(serde::de::Error::custom("invalid length"));
        }
        let mut arr = [0u8; 32];
        bytes.copy_to_slice(&mut arr);
        Ok(CompressedPoint::from(arr))
    }
}

pub mod uint64_string {
    use serde::{Deserialize, Deserializer, Serializer};

    pub fn serialize<S>(value: &u64, serializer: S) -> std::result::Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_str(&value.to_string())
    }

    pub fn deserialize<'de, D>(deserializer: D) -> std::result::Result<u64, D::Error>
    where
        D: Deserializer<'de>,
    {
        let s = String::deserialize(deserializer)?;
        s.parse::<u64>().map_err(serde::de::Error::custom)
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Output {
    pub tx_id: u64,
    #[serde(with = "uint64_string")]
    pub amount: u64,
    pub index: u32,
    pub global_index: u64,
    #[serde(default)]
    pub rct: Option<String>,
    #[serde(with = "hex_32_point")]
    pub tx_hash: CompressedPoint,
    #[serde(with = "hex_32_point")]
    pub tx_prefix_hash: CompressedPoint,
    #[serde(with = "hex_32_point")]
    pub public_key: CompressedPoint,
    #[serde(with = "hex_32_point")]
    pub tx_pub_key: CompressedPoint,
    pub timestamp: String,
    pub height: u64,
    #[serde(default)]
    pub recipient: RecipientIndex,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RandomOutputs {
    #[serde(with = "uint64_string")]
    pub amount: u64,
    #[serde(default)]
    pub outputs: Vec<RandomOutput>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RandomOutput {
    #[serde(with = "uint64_string")]
    pub global_index: u64,
    #[serde(with = "hex_32_point")]
    pub public_key: CompressedPoint,
    pub rct: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RandomOutsResponse {
    #[serde(default)]
    pub amount_outs: Vec<RandomOutputs>,
}
