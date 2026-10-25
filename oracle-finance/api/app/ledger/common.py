"""Shared helpers: reference/bank normalisation and row predicates."""

import time
import re


def now_ms(): return int(time.time()*1000)


def normalize_reference(value):
    if value is None:
        return None
    raw = str(value).strip()
    if not raw or raw.lower() in ("null", "none"):
        return None
    raw = re.sub(r"(?i)^\s*Transaction\s+Info\s*:\s*", "", raw).strip()
    match = re.match(r"(?i)^UPI/[^/\s]+/([^/\s]+)", raw)
    if match:
        raw = match.group(1)
    raw = raw.strip(".,;:)]").strip()
    # Bank e-mails run the reference into the next sentence ("...601.FOR any
    # queries"). A long numeric reference followed by a dot and letters is the
    # reference plus sentence residue, not a different reference.
    glued = re.match(r"^([0-9]{8,})\.[A-Za-z]+$", raw)
    if glued:
        raw = glued.group(1)
    return raw.upper() or None


def is_provisional_unresolved_transaction(t):
    """Return True for a fast notification row that is not authoritative yet.

    These rows can have a useful account last-4 but no bank/reference and an
    UNKNOWN payment method. They are intentionally stored for later Gmail
    enrichment, but must not affect the account balance or Splitwise until the
    authoritative bank evidence arrives.
    """
    bank = str(getattr(t, "bank", None) or "").strip()
    reference = str(getattr(t, "reference", None) or "").strip()
    payment_method = str(getattr(t, "paymentMethod", None) or "").strip().upper()
    account_type = str(getattr(t, "accountType", None) or "").strip().upper()
    last4 = str(
        getattr(t, "accountLast4", None)
        or getattr(t, "accountLastFour", None)
        or ""
    ).strip()
    return (
        not bank
        and not reference
        and payment_method == "UNKNOWN"
        and account_type in ("BANK_ACCOUNT", "CREDIT_CARD")
        and bool(last4)
    )


def is_provisional_row(row):
    """Same unresolved-notification check for an existing SQLite row."""
    bank = str(row["bank"] or "").strip()
    reference = str(row["reference"] or "").strip()
    payment_method = str(row["payment_method"] or "").strip().upper()
    account_type = str(row["account_type"] or "").strip().upper()
    last4 = str(row["account_last4"] or "").strip()
    return (
        not bank
        and not reference
        and payment_method == "UNKNOWN"
        and account_type in ("BANK_ACCOUNT", "CREDIT_CARD")
        and bool(last4)
    )


def _void_transaction_in_connection(conn, transaction_id, duplicate_of=None):
    tx = conn.execute(
        "SELECT id,status,type,account_type,category,amount_minor,currency FROM transactions WHERE id=?",
        (transaction_id,),
    ).fetchone()
    if not tx:
        return {"status": "NOT_FOUND", "transactionId": transaction_id}
    if tx["status"] == "VOIDED":
        if duplicate_of:
            conn.execute(
                "UPDATE transactions SET duplicate_of=? WHERE id=?",
                (duplicate_of, transaction_id),
            )
        return {"status": "ALREADY_VOIDED", "transactionId": transaction_id}

    adjustment = conn.execute(
        "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
        (transaction_id,),
    ).fetchone()

    if adjustment:
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
            (adjustment["delta_minor"], now_ms(), adjustment["account_id"]),
        )
        conn.execute(
            "DELETE FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,),
        )

    # The Splitwise value is derived from the ledger (voided rows drop out of
    # it), so only the per-row adjustment record needs cleaning up here.
    conn.execute(
        "DELETE FROM splitwise_adjustments WHERE transaction_id=?",
        (transaction_id,),
    )

    conn.execute(
        "UPDATE transactions SET status='VOIDED', duplicate_of=? WHERE id=?",
        (duplicate_of, transaction_id),
    )
    return {
        "status": "VOIDED",
        "transactionId": transaction_id,
        "reversedAdjustment": bool(adjustment),
    }


def _normalize_account_bank(value):
    raw=(value or "").strip().upper()
    if not raw:
        return ""
    for suffix in (" BANK", " LTD", " LIMITED"):
        if raw.endswith(suffix):
            raw=raw[:-len(suffix)].strip()
    return raw


def _resolve_transaction_row(conn, transaction_id):
    row = conn.execute(
        "SELECT * FROM transactions WHERE id=?",
        (str(transaction_id),),
    ).fetchone()
    visited = set()
    while row and row["duplicate_of"] and row["duplicate_of"] not in visited:
        visited.add(str(row["id"]))
        row = conn.execute(
            "SELECT * FROM transactions WHERE id=?",
            (str(row["duplicate_of"]),),
        ).fetchone()
    return row
