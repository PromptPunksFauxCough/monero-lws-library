use bytes::{Buf, BufMut, Bytes, BytesMut};
use curve25519_dalek::constants::ED25519_BASEPOINT_TABLE;
use curve25519_dalek::scalar::Scalar as DalekScalar;
use monero_oxide::ed25519::{CompressedPoint, Commitment, Point, Scalar};
use monero_oxide::io::VarInt;
use monero_oxide::ringct::clsag::Decoys;
use monero_oxide::ringct::RctType;
use monero_wallet::address::{MoneroAddress, Network};
use monero_wallet::interface::FeeRate;
use monero_wallet::send::{Change, SendError, SignableTransaction};
use monero_wallet::{OutputWithDecoys, ViewPair};

use zeroize::Zeroizing;

use crate::error::{LwsError, Result};
use crate::models::{Output, PendingTransaction, RandomOutsResponse};
use crate::state::WalletContext;

const LOCK_WINDOW: u64 = 10;

fn estimate_fee(inputs: usize, per_byte_fee: u64, fee_mask: u64) -> u64 {
    let size = 250 + (inputs * 32) + (2 * 32);
    let raw = size as u64 * per_byte_fee;
    if fee_mask > 0 {
        ((raw + fee_mask - 1) / fee_mask) * fee_mask
    } else {
        raw
    }
}

pub fn select_inputs(
    unspent: &[Output],
    amount: u64,
    _mixin: u32,
    per_byte_fee: u64,
    fee_mask: u64,
    blockchain_height: u64,
) -> Result<Vec<Output>> {
    let mut selected = Vec::new();
    let mut total = 0u64;
    let mut fee = 0u64;

    for utxo in unspent {
        if blockchain_height.saturating_sub(utxo.height) < LOCK_WINDOW {
            continue;
        }
        if utxo.rct.is_none() {
            continue;
        }

        selected.push(utxo.clone());
        total = total
            .checked_add(utxo.amount)
            .ok_or_else(|| LwsError::InvalidState("Input amount overflow".into()))?;

        fee = estimate_fee(selected.len(), per_byte_fee, fee_mask);
        let required = amount
            .checked_add(fee)
            .ok_or_else(|| LwsError::InvalidState("Amount + fee overflow".into()))?;

        if total >= required {
            break;
        }
    }

    let required = amount
        .checked_add(fee)
        .ok_or_else(|| LwsError::InvalidState("Amount + fee overflow".into()))?;
    if total < required {
        return Err(LwsError::InvalidState("Insufficient unlocked funds".into()));
    }

    Ok(selected)
}

pub fn select_all_inputs(unspent: &[Output], blockchain_height: u64) -> Vec<Output> {
    unspent
        .iter()
        .filter(|utxo| {
            blockchain_height.saturating_sub(utxo.height) >= LOCK_WINDOW && utxo.rct.is_some()
        })
        .cloned()
        .collect()
}

fn map_send_error(e: SendError) -> LwsError {
    match e {
        SendError::TooLargeTransaction => LwsError::InvalidState(
            "Too many outputs to send in one transaction; consolidate funds first".into(),
        ),
        other => LwsError::Crypto(format!("SignableTransaction error: {}", other)),
    }
}

pub fn create_transaction_from_data(
    ctx: &WalletContext,
    destination: &str,
    amount: u64,
    sweep: bool,
    mixin: u32,
    per_byte_fee: u64,
    fee_mask: u64,
    unspent_json: &str,
    random_outs_json: &str,
) -> Result<PendingTransaction> {
    let selected: Vec<Output> = serde_json::from_str(unspent_json)?;
    let random_resp: RandomOutsResponse = serde_json::from_str(random_outs_json)?;

    if selected.is_empty() {
        return Err(LwsError::InvalidState("No inputs selected".into()));
    }
    if random_resp.amount_outs.len() != selected.len() {
        return Err(LwsError::Crypto(format!(
            "Expected {} decoy rings, got {}",
            selected.len(),
            random_resp.amount_outs.len()
        )));
    }
    if per_byte_fee == 0 {
        return Err(LwsError::InvalidState("Zero per-byte fee".into()));
    }

    let spend_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.spend_key));
    let spend_pub_dalek = &spend_sec * ED25519_BASEPOINT_TABLE;
    let spend_point = CompressedPoint::from(spend_pub_dalek.compress().to_bytes())
        .decompress()
        .ok_or_else(|| LwsError::Crypto("Invalid spend point".into()))?;

    let view_sec = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.view_key));
    let view_sec_oxide = Zeroizing::new(Scalar::from(view_sec));
    let view_pair = ViewPair::new(spend_point, view_sec_oxide)
        .map_err(|e| LwsError::Crypto(e.to_string()))?;

    let nettype = match ctx.network_type {
        0 => Network::Mainnet,
        1 => Network::Testnet,
        _ => Network::Stagenet,
    };
    let dest_addr = MoneroAddress::from_str(nettype, destination)
        .map_err(|_| LwsError::InvalidState("Invalid dest address".into()))?;

    let view_dalek = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(ctx.view_key));
    let mut inputs_with_decoys = Vec::with_capacity(selected.len());

    for (utxo, ring_data) in selected.iter().zip(random_resp.amount_outs.iter()) {
        if ring_data.amount != 0 {
            return Err(LwsError::Crypto("Expected zero-amount RingCT decoy ring".into()));
        }
        if ring_data.outputs.len() != mixin as usize {
            return Err(LwsError::Crypto(format!(
                "Expected {} decoys, got {}",
                mixin,
                ring_data.outputs.len()
            )));
        }

        let tx_pub_dalek = curve25519_dalek::edwards::CompressedEdwardsY::from_slice(
            &utxo.tx_pub_key.to_bytes(),
        )
        .map_err(|_| LwsError::Crypto("Bad tx pub key encoding".into()))?
        .decompress()
        .ok_or_else(|| LwsError::Crypto("Bad tx pub key point".into()))?;

        let r_a_dalek = (view_dalek * tx_pub_dalek).mul_by_cofactor();
        let shared_bytes = r_a_dalek.compress().to_bytes();

        let mut derivation_preimage = BytesMut::with_capacity(32 + 5);
        derivation_preimage.put_slice(&shared_bytes);
        {
            let mut writer = (&mut derivation_preimage).writer();
            VarInt::write(&utxo.index, &mut writer)
                .map_err(|e| LwsError::Crypto(e.to_string()))?;
        }
        let base_secret = Scalar::hash(&derivation_preimage.freeze());

        let key_offset = if utxo.recipient.maj_i == 0 && utxo.recipient.min_i == 0 {
            base_secret
        } else {
            let view_key_bytes: [u8; 32] = <[u8; 32]>::from(ctx.view_key);
            let mut subaddr_preimage = BytesMut::new();
            subaddr_preimage.put_slice(b"SubAddr\0");
            subaddr_preimage.put_slice(&view_key_bytes);
            subaddr_preimage.put_slice(&utxo.recipient.maj_i.to_le_bytes());
            subaddr_preimage.put_slice(&utxo.recipient.min_i.to_le_bytes());

            let subaddr_secret = Scalar::hash(&subaddr_preimage.freeze());
            let sum = DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(base_secret))
                + DalekScalar::from_bytes_mod_order(<[u8; 32]>::from(subaddr_secret));
            Scalar::from(sum)
        };

        let rct_hex = utxo
            .rct
            .as_ref()
            .ok_or_else(|| LwsError::Crypto("Expected RCT data for selected output".into()))?;
        let mut rct_bytes = Bytes::from(
            hex::decode(rct_hex).map_err(|_| LwsError::Crypto("Bad real RCT hex".into()))?,
        );
        if rct_bytes.len() != 96 {
            return Err(LwsError::Crypto(format!(
                "Real RCT field length {} != 96",
                rct_bytes.len()
            )));
        }

        let mut commitment_arr = [0u8; 32];
        rct_bytes.copy_to_slice(&mut commitment_arr);
        let real_commitment_point = CompressedPoint::from(commitment_arr)
            .decompress()
            .ok_or_else(|| LwsError::Crypto("Bad real commitment point".into()))?;

        let mut enc_mask_arr = [0u8; 32];
        rct_bytes.copy_to_slice(&mut enc_mask_arr);
        let mut enc_amount_arr = [0u8; 32];
        rct_bytes.copy_to_slice(&mut enc_amount_arr);

        let base_secret_bytes = Bytes::copy_from_slice(&<[u8; 32]>::from(base_secret));
        let shared_sec1 = Scalar::hash(&base_secret_bytes);
        let shared_sec1_bytes = Bytes::copy_from_slice(&<[u8; 32]>::from(shared_sec1));
        let shared_sec2 = Scalar::hash(&shared_sec1_bytes);

        let enc_mask_dalek = DalekScalar::from_bytes_mod_order(enc_mask_arr);
        let enc_amount_dalek = DalekScalar::from_bytes_mod_order(enc_amount_arr);

        let mask_dalek = enc_mask_dalek - shared_sec1.into();
        let amount_dalek = enc_amount_dalek - shared_sec2.into();

        let mut amount_bytes = Bytes::copy_from_slice(&amount_dalek.to_bytes());
        let decrypted_amount = amount_bytes.get_u64_le();

        if decrypted_amount != utxo.amount {
            return Err(LwsError::Crypto(format!(
                "Decrypted amount mismatch: expected {}, got {}",
                utxo.amount, decrypted_amount
            )));
        }

        let real_commitment = Commitment::new(Scalar::from(mask_dalek), decrypted_amount);

        if real_commitment.commit().compress().to_bytes() != commitment_arr {
            return Err(LwsError::Crypto("Commitment point mismatch".into()));
        }

        let real_pk = utxo
            .public_key
            .decompress()
            .ok_or_else(|| LwsError::Crypto("Bad real output public key".into()))?;

        let mut ring_members = Vec::with_capacity(ring_data.outputs.len() + 1);
        for decoy in &ring_data.outputs {
            let idx = decoy.global_index;
            let pk = decoy
                .public_key
                .decompress()
                .ok_or_else(|| LwsError::Crypto("Bad decoy public key".into()))?;

            let mut decoy_rct_bytes = Bytes::from(
                hex::decode(&decoy.rct)
                    .map_err(|_| LwsError::Crypto("Bad decoy RCT hex".into()))?,
            );
            if decoy_rct_bytes.len() < 32 {
                return Err(LwsError::Crypto("Decoy RCT field too short".into()));
            }

            let mut decoy_commitment_arr = [0u8; 32];
            decoy_rct_bytes.copy_to_slice(&mut decoy_commitment_arr);
            let comm = CompressedPoint::from(decoy_commitment_arr)
                .decompress()
                .ok_or_else(|| LwsError::Crypto("Bad decoy commitment point".into()))?;

            ring_members.push((idx, pk, comm));
        }

        ring_members.push((utxo.global_index, real_pk, real_commitment_point));
        ring_members.sort_by_key(|(idx, _, _)| *idx);

        let signer_index = ring_members
            .iter()
            .position(|(idx, _, _)| *idx == utxo.global_index)
            .ok_or_else(|| LwsError::Crypto("Real output not found in ring".into()))? as u8;

        let mut offsets = Vec::with_capacity(ring_members.len());
        offsets.push(ring_members[0].0);
        for m in 1..ring_members.len() {
            offsets.push(
                ring_members[m]
                    .0
                    .checked_sub(ring_members[m - 1].0)
                    .ok_or_else(|| LwsError::Crypto("Invalid decoy ordering".into()))?,
            );
        }

        let ring: Vec<[Point; 2]> = ring_members
            .into_iter()
            .map(|(_, pk, comm)| [pk, comm])
            .collect();

        let decoys = Decoys::new(offsets, signer_index, ring)
            .ok_or_else(|| LwsError::Crypto("Invalid decoys".into()))?;

        let serialized_decoys = Bytes::from(decoys.serialize());
        let mut output_bytes = BytesMut::with_capacity(104 + serialized_decoys.len());
        output_bytes.put_slice(&utxo.public_key.to_bytes());
        output_bytes.put_slice(&<[u8; 32]>::from(key_offset));
        output_bytes.put_slice(&<[u8; 32]>::from(real_commitment.mask));
        output_bytes.put_slice(&real_commitment.amount.to_le_bytes());
        output_bytes.put_slice(serialized_decoys.as_ref());

        let mut reader = output_bytes.freeze().reader();
        let output_with_decoys = OutputWithDecoys::read(&mut reader)
            .map_err(|e| LwsError::Crypto(format!("OutputWithDecoys read error: {}", e)))?;

        inputs_with_decoys.push(output_with_decoys);
    }

    let outgoing_view_key = Zeroizing::new(rand::random::<[u8; 32]>());
    let fee_rate = FeeRate::new(per_byte_fee, fee_mask)
        .ok_or_else(|| LwsError::Crypto("Invalid fee rate".into()))?;

    let amount = if sweep {
        let total = inputs_with_decoys
            .iter()
            .try_fold(0u64, |acc, input| acc.checked_add(input.commitment().amount))
            .ok_or_else(|| LwsError::InvalidState("Input amount overflow".into()))?;
        let probe = SignableTransaction::new(
            RctType::ClsagBulletproofPlus,
            Zeroizing::new(rand::random::<[u8; 32]>()),
            inputs_with_decoys.clone(),
            vec![(dest_addr.clone(), 1)],
            Change::new(view_pair.clone(), None),
            vec![],
            fee_rate,
        );
        let fee = match probe {
            Ok(tx) => tx.necessary_fee(),
            Err(SendError::NotEnoughFunds {
                necessary_fee: Some(fee),
                ..
            }) => fee,
            Err(e) => return Err(map_send_error(e)),
        };
        if total <= fee {
            return Err(LwsError::InvalidState(
                "Insufficient unlocked funds to cover the fee".into(),
            ));
        }
        total - fee
    } else {
        amount
    };

    let payments = vec![(dest_addr, amount)];
    let change = Change::new(view_pair, None);

    let signable_tx = SignableTransaction::new(
        RctType::ClsagBulletproofPlus,
        outgoing_view_key,
        inputs_with_decoys,
        payments,
        change,
        vec![],
        fee_rate,
    )
    .map_err(map_send_error)?;

    let spend_key_oxide = Zeroizing::new(Scalar::from(spend_sec));
    let mut rng = rand::thread_rng();
    let signed_tx = signable_tx
        .sign(&mut rng, &spend_key_oxide)
        .map_err(|e| LwsError::Crypto(format!("Sign error: {}", e)))?;

    let raw_bytes = Bytes::from(signed_tx.serialize());
    let raw_bytes_hex = hex::encode(&raw_bytes);
    let tx_hash_hex = hex::encode(signed_tx.hash());

    Ok(PendingTransaction {
        status: 0,
        error_string: String::new(),
        tx_hash_hex,
        raw_bytes_hex,
    })
}
