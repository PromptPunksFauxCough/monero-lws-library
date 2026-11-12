use monero_oxide::ed25519::Scalar;

pub struct WalletContext {
    pub spend_key: Scalar,
    pub view_key: Scalar,
    pub address: String,
    pub seed: String,
    pub network_type: u8,
    pub restore_height: u64,
}
