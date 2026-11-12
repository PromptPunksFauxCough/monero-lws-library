use thiserror::Error;

#[derive(Debug, Error)]
pub enum LwsError {
    #[error("JSON serialization/deserialization failed: {0}")]
    Json(#[from] serde_json::Error),

    #[error("Hex decoding error: {0}")]
    Hex(#[from] hex::FromHexError),

    #[error("Serialization/Deserialization failed: {0}")]
    Serialization(String),

    #[error("Invalid state: {0}")]
    InvalidState(String),

    #[error("Cryptographic operation failed: {0}")]
    Crypto(String),

    #[error("File I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("Unable to decrypt file (likely bad password)")]
    Decryption,
}

pub type Result<T> = std::result::Result<T, LwsError>;
