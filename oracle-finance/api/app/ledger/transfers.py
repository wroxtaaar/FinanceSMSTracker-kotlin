"""Own-account transfers: matching both legs so they net to zero in Splitwise."""

from ..db import connection
from .accounts import apply_transaction_to_account
from .common import _normalize_account_bank, _resolve_transaction_row, normalize_reference, now_ms


def _neutralize_internal_transfer_splitwise(conn, transfer_id, debit_id, credit_id):
    """Make a matched internal transfer pair invisible to Splitwise.

    Moving money between two of the user's own bank accounts changes bank
    balances but is not an expense or receivable. The operation is persisted
    with an idempotency marker because the pair may be encountered repeatedly
    during reconciliation.
    """
    marker = conn.execute(
        "SELECT splitwise_neutralized FROM internal_transfers WHERE id=?",
        (transfer_id,),
    ).fetchone()
    if marker is None or int(marker["splitwise_neutralized"] or 0) == 1:
        return False

    for transaction_id in (debit_id, credit_id):
        tx = conn.execute(
            "SELECT * FROM transactions WHERE id=?",
            (transaction_id,),
        ).fetchone()
        if not tx or tx["status"] != "ACTIVE" or tx["duplicate_of"] is not None:
            return False

        adjustment = conn.execute(
            "SELECT currency,delta_minor FROM splitwise_adjustments WHERE transaction_id=?",
            (transaction_id,),
        ).fetchone()

        # Matched transfer legs are excluded from the derived Splitwise total as
        # a pair; only the per-row adjustment record needs to be cleaned up.
        if adjustment is not None:
            conn.execute(
                "DELETE FROM splitwise_adjustments WHERE transaction_id=?",
                (transaction_id,),
            )

    conn.execute(
        "UPDATE internal_transfers SET splitwise_neutralized=1 WHERE id=?",
        (transfer_id,),
    )
    return True


def _internal_transfer_reason(debit, credit, window_ms=10 * 60 * 1000):
    """Return a match reason when two rows are the user's own-account transfer."""
    if debit["type"] != "DEBIT" or credit["type"] != "CREDIT":
        return None
    if debit["currency"].strip().upper() != credit["currency"].strip().upper():
        return None
    if int(debit["amount_minor"]) != int(credit["amount_minor"]):
        return None

    debit_bank = _normalize_account_bank(debit["bank"])
    credit_bank = _normalize_account_bank(credit["bank"])
    debit_last4 = str(debit["account_last4"] or "").strip()
    credit_last4 = str(credit["account_last4"] or "").strip()

    # Never classify two events from the same known bank account as a transfer.
    if (
        debit_bank
        and credit_bank
        and debit_bank == credit_bank
        and debit_last4
        and credit_last4
        and debit_last4 == credit_last4
    ):
        return None

    debit_ref = normalize_reference(debit["reference"])
    credit_ref = normalize_reference(credit["reference"])

    # The same UPI RRN/reference is the strongest signal. Bank notifications
    # can arrive many minutes apart, so reference matching intentionally does
    # not have the ten-minute time restriction.
    if debit_ref and credit_ref and debit_ref == credit_ref:
        return "same normalized transfer reference"

    if abs(int(debit["timestamp"]) - int(credit["timestamp"])) <= window_ms:
        return "same amount/currency within 10 minutes across distinct bank accounts"

    return None


def _ensure_internal_transfer_balance_adjustment(conn, row):
    """Repair a missing bank/card balance effect before classifying a transfer.

    A transaction can arrive before its account exists, or a previous sync can
    persist the transaction while the balance adjustment is temporarily absent.
    Once both legs are recognized as an own-account transfer, the transfer must
    still have both real bank-balance effects. Never replay a transaction that
    was already covered by a manual reconciliation.
    """
    transaction_id = str(row["id"])
    if transaction_id.startswith("gmail:"):
        return False
    if row["status"] != "ACTIVE" or row["duplicate_of"] is not None:
        return False

    existing = conn.execute(
        "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
        (transaction_id,),
    ).fetchone()
    if existing is not None:
        return False

    reconciled_at = int(row["balance_reconciled_at"] or 0) if "balance_reconciled_at" in row.keys() else 0
    created_at = int(row["created_at"] or 0)
    # If a user manually reconciled the account after this transaction was
    # created, that transaction is already represented by the verified balance.
    if reconciled_at and created_at <= reconciled_at:
        return False

    from types import SimpleNamespace
    transaction = SimpleNamespace(
        id=transaction_id,
        amountMinor=int(row["amount_minor"]),
        currency=row["currency"],
        type=row["type"],
        paymentMethod=row["payment_method"],
        accountType=row["account_type"],
        bank=row["bank"],
        merchantOrPayee=row["merchant_or_payee"],
        accountLast4=row["account_last4"],
        reference=row["reference"],
        timestamp=int(row["timestamp"]),
        category=row["category"],
        confidence=float(row["confidence"] or 0.0),
    )
    apply_transaction_to_account(
        conn,
        transaction,
        now_ms(),
        transaction_created_at=created_at,
    )
    return conn.execute(
        "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
        (transaction_id,),
    ).fetchone() is not None


def _record_internal_transfer(conn, debit, credit, reason):
    transfer_id = f"transfer:{debit['id']}:{credit['id']}"
    existing = conn.execute(
        "SELECT id,splitwise_neutralized FROM internal_transfers WHERE id=?",
        (transfer_id,),
    ).fetchone()

    if existing is None:
        conn.execute(
            """INSERT OR IGNORE INTO internal_transfers
               (id,debit_transaction_id,credit_transaction_id,currency,amount_minor,status,reason,created_at)
               VALUES(?,?,?,?,?,'MATCHED',?,?)""",
            (
                transfer_id,
                debit["id"],
                credit["id"],
                debit["currency"],
                debit["amount_minor"],
                reason,
                now_ms(),
            ),
        )

    # If the pair already existed but its Splitwise neutralization was
    # interrupted, _neutralize_internal_transfer_splitwise is idempotent and
    # will finish the correction.
    changed = _neutralize_internal_transfer_splitwise(
        conn,
        transfer_id,
        debit["id"],
        credit["id"],
    )
    return {
        "id": transfer_id,
        "debitTransactionId": debit["id"],
        "creditTransactionId": credit["id"],
        "amountMinor": debit["amount_minor"],
        "currency": debit["currency"],
        "reason": reason,
        "splitwiseNeutralized": bool(changed or (existing and int(existing["splitwise_neutralized"] or 0) == 1)),
    }


def apply_internal_transfer_candidates(candidates):
    """Confirm Android-side transfer candidates against the Oracle ledger."""
    if not candidates:
        return []

    results = []
    seen = set()

    with connection() as conn:
        for candidate in candidates:
            debit = _resolve_transaction_row(conn, candidate.get("debitTransactionId"))
            credit = _resolve_transaction_row(conn, candidate.get("creditTransactionId"))

            if not debit or not credit:
                continue
            if debit["id"] == credit["id"]:
                continue
            if debit["status"] != "ACTIVE" or credit["status"] != "ACTIVE":
                continue
            if debit["duplicate_of"] is not None or credit["duplicate_of"] is not None:
                continue
            if debit["account_type"] != "BANK_ACCOUNT" or credit["account_type"] != "BANK_ACCOUNT":
                continue

            reason = _internal_transfer_reason(debit, credit)
            if reason is None:
                continue

            key = f"{debit['id']}:{credit['id']}"
            if key in seen:
                continue
            seen.add(key)

            results.append(_record_internal_transfer(conn, debit, credit, reason))

    return results


def match_internal_transfers():
    with connection() as conn:
        existing_matches = conn.execute(
            """SELECT id,debit_transaction_id,credit_transaction_id
               FROM internal_transfers
               WHERE status='MATCHED'"""
        ).fetchall()

        # A transfer recorded while its legs were still e-mail rows keeps
        # pointing at those rows after they are collapsed into the SMS rows.
        # Remember the canonical legs it resolves to so the same real-world
        # transfer is not recorded a second time under the canonical ids.
        resolved_debit_ids = set()
        resolved_credit_ids = set()

        for transfer in existing_matches:
            debit_row = _resolve_transaction_row(conn, transfer["debit_transaction_id"])
            credit_row = _resolve_transaction_row(conn, transfer["credit_transaction_id"])
            if debit_row:
                resolved_debit_ids.add(str(debit_row["id"]))
            if credit_row:
                resolved_credit_ids.add(str(credit_row["id"]))
            if debit_row:
                _ensure_internal_transfer_balance_adjustment(conn, debit_row)
            if credit_row:
                _ensure_internal_transfer_balance_adjustment(conn, credit_row)
            _neutralize_internal_transfer_splitwise(
                conn,
                transfer["id"],
                transfer["debit_transaction_id"],
                transfer["credit_transaction_id"],
            )

        txs = conn.execute(
            """SELECT * FROM transactions
               WHERE account_type='BANK_ACCOUNT'
                 AND type IN ('DEBIT','CREDIT')
                 AND status='ACTIVE'
                 AND duplicate_of IS NULL
               ORDER BY timestamp DESC"""
        ).fetchall()

        matches = []
        for debit in txs:
            if debit["type"] != "DEBIT":
                continue

            for credit in txs:
                if credit["type"] != "CREDIT" or debit["id"] == credit["id"]:
                    continue

                reason = _internal_transfer_reason(debit, credit)
                if reason is None:
                    continue

                exists = conn.execute(
                    """SELECT 1 FROM internal_transfers
                       WHERE debit_transaction_id=? OR credit_transaction_id=?""",
                    (debit["id"], credit["id"]),
                ).fetchone()
                if exists:
                    continue
                if str(debit["id"]) in resolved_debit_ids or str(credit["id"]) in resolved_credit_ids:
                    continue

                _ensure_internal_transfer_balance_adjustment(conn, debit)
                _ensure_internal_transfer_balance_adjustment(conn, credit)
                matches.append(_record_internal_transfer(conn, debit, credit, reason))

        return matches


def list_transfers():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM internal_transfers ORDER BY created_at DESC").fetchall()]


def is_internal_transfer_transaction(transaction_id):
    """Return True when a canonical transaction is one leg of a matched own-account transfer."""
    with connection() as conn:
        row = conn.execute(
            """SELECT 1 FROM internal_transfers
               WHERE status='MATCHED'
                 AND (debit_transaction_id=? OR credit_transaction_id=?)
               LIMIT 1""",
            (str(transaction_id), str(transaction_id)),
        ).fetchone()
        return row is not None


def _apply_balance_after_identity_enrichment(conn, canonical_id, was_provisional, enrichment):
    """Apply a bank/card balance effect that was impossible before enrichment.

    A canonical SMS row may know the bank but not the account last-four, so
    apply_transaction_to_account could not resolve its account when it was first
    stored. When a duplicate email supplies that identity, the canonical row
    must receive its one balance adjustment; otherwise the duplicate is hidden
    and the money movement is never applied (for a self-transfer this creates
    money on the credited account). Provisional rows keep waiting for their
    normal authoritative path, and an existing adjustment is never replayed.
    """
    if was_provisional or not ({"bank", "account_last4"} & set(enrichment)):
        return
    row = conn.execute("SELECT * FROM transactions WHERE id=?", (canonical_id,)).fetchone()
    if row is not None:
        _ensure_internal_transfer_balance_adjustment(conn, row)
