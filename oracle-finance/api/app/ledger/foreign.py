"""Foreign-currency spends: INR estimate, and the user's exact-rupee correction."""

from ..db import connection
from .accounts import _resolve_canonical_row, apply_transaction_to_account
from .common import is_provisional_row, now_ms


def _convert_foreign_transaction(t):
    """Foreign-currency spend -> INR estimate. Returns (model, orig_amount, orig_currency, rate)."""
    from .. import fx
    converted = fx.to_inr(t.amountMinor, t.currency, t.timestamp)
    if converted is None:
        return t, None, None, None
    inr_minor, rate = converted
    return (
        t.model_copy(update={"amountMinor": inr_minor, "currency": "INR"}),
        int(t.amountMinor), str(t.currency).strip().upper(), rate,
    )


def migrate_foreign_currency_rows():
    """One-time: convert already-stored foreign-currency rows to INR estimates.

    New foreign spends are converted on arrival (see fx.py). Rows that were
    stored before that existed stay in their own currency bucket and move no
    account, so convert them once and apply the missing balance adjustment.
    Returns the number of rows converted.
    """
    from types import SimpleNamespace
    from .. import fx

    converted_count = 0
    with connection() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if conn.execute(
            "SELECT 1 FROM applied_migrations WHERE name='foreign_currency_to_inr'"
        ).fetchone():
            return 0
        rows = conn.execute(
            """SELECT * FROM transactions
               WHERE status='ACTIVE'
                 AND UPPER(TRIM(COALESCE(currency,''))) NOT IN ('', 'INR')
                 AND orig_currency IS NULL
               ORDER BY created_at, id"""
        ).fetchall()
        for row in rows:
            converted = fx.to_inr(row["amount_minor"], row["currency"], row["timestamp"])
            if converted is None:
                continue
            inr_minor, rate = converted
            conn.execute(
                """UPDATE transactions
                   SET orig_amount_minor=?, orig_currency=?, fx_rate=?,
                       amount_minor=?, currency='INR'
                   WHERE id=?""",
                (int(row["amount_minor"]), str(row["currency"]).strip().upper(), rate,
                 inr_minor, row["id"]),
            )
            converted_count += 1
            if conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?", (row["id"],)
            ).fetchone():
                continue
            if is_provisional_row(row):
                continue
            apply_transaction_to_account(
                conn,
                SimpleNamespace(
                    id=str(row["id"]), amountMinor=inr_minor, currency="INR",
                    type=row["type"], paymentMethod=row["payment_method"],
                    accountType=row["account_type"], bank=row["bank"],
                    merchantOrPayee=row["merchant_or_payee"],
                    accountLast4=row["account_last4"], reference=row["reference"],
                    timestamp=int(row["timestamp"]), category=row["category"],
                    confidence=float(row["confidence"] or 0.0),
                ),
                now_ms(),
                transaction_created_at=int(row["created_at"]),
            )
        conn.execute("INSERT INTO applied_migrations(name) VALUES('foreign_currency_to_inr')")
    return converted_count


def foreign_amount_info(transaction_id):
    """Foreign-spend details for a row, or {"foreign": False} for rupee rows."""
    with connection() as conn:
        row = _resolve_canonical_row(conn, transaction_id)
        if row is None or row["status"] != "ACTIVE" or not row["orig_currency"]:
            return {"foreign": False, "transactionId": transaction_id}
        return {
            "foreign": True,
            "transactionId": row["id"],
            "inrMinor": int(row["amount_minor"]),
            "origAmountMinor": int(row["orig_amount_minor"]),
            "origCurrency": row["orig_currency"],
            "fxRate": row["fx_rate"],
            "exact": bool(row["fx_exact"]),
        }


def foreign_amount_map():
    """{transaction id: foreign-spend info} for every active foreign row.

    Rows merged into a canonical foreign row as duplicates map to that row's
    amounts too, so a phone-side copy of the same spend can show the rupees.
    """
    with connection() as conn:
        canon = conn.execute(
            """SELECT id,amount_minor,orig_amount_minor,orig_currency,fx_exact
               FROM transactions
               WHERE status='ACTIVE' AND orig_currency IS NOT NULL AND duplicate_of IS NULL"""
        ).fetchall()
        info = {
            r["id"]: {
                "inrMinor": int(r["amount_minor"]),
                "origAmountMinor": int(r["orig_amount_minor"]),
                "origCurrency": r["orig_currency"],
                "exact": bool(r["fx_exact"]),
            }
            for r in canon
        }
        for ident in list(info):
            for d in conn.execute("SELECT id FROM transactions WHERE duplicate_of=?", (ident,)).fetchall():
                info.setdefault(d["id"], info[ident])
        return info


def set_foreign_inr_amount(transaction_id, inr_minor):
    """Set the exact rupee charge of a foreign-currency spend.

    Only rows that were converted from another currency can be corrected. The
    account balance moves by the difference and Splitwise (derived from the
    ledger) follows automatically.
    """
    inr_minor = int(inr_minor)
    if inr_minor <= 0:
        return {"status": "INVALID_AMOUNT", "transactionId": transaction_id}
    with connection() as conn:
        row = _resolve_canonical_row(conn, transaction_id)
        if row is None or row["status"] != "ACTIVE":
            return {"status": "NOT_FOUND", "transactionId": transaction_id}
        if not row["orig_currency"]:
            return {"status": "NOT_FOREIGN", "transactionId": transaction_id}

        old_minor = int(row["amount_minor"])
        adjustment = conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (row["id"],),
        ).fetchone()
        if adjustment and old_minor:
            old_delta = int(adjustment["delta_minor"])
            new_delta = (1 if old_delta >= 0 else -1) * inr_minor
            conn.execute(
                "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
                (new_delta - old_delta, now_ms(), adjustment["account_id"]),
            )
            conn.execute(
                "UPDATE balance_adjustments SET delta_minor=? WHERE transaction_id=?",
                (new_delta, row["id"]),
            )
        orig = int(row["orig_amount_minor"])
        conn.execute(
            "UPDATE transactions SET amount_minor=?, fx_rate=?, fx_exact=1 WHERE id=?",
            (inr_minor, (inr_minor / orig) if orig else row["fx_rate"], row["id"]),
        )
        return {
            "status": "UPDATED",
            "transactionId": row["id"],
            "inrMinor": inr_minor,
            "previousInrMinor": old_minor,
        }
