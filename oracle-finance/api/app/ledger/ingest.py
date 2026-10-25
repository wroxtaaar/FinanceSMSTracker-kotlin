"""Phone/server ingest: sync_transaction and sync_evidence."""

from ..db import connection
from .accounts import apply_transaction_to_account
from .common import is_provisional_unresolved_transaction, normalize_reference, now_ms
from .foreign import _convert_foreign_transaction


def sync_transaction(t, apply_balance=True):
    t, orig_amount, orig_currency, fx_rate = _convert_foreign_transaction(t)
    with connection() as conn:
        before=conn.execute(
            """SELECT * FROM transactions WHERE id=?""",
            (t.id,)
        ).fetchone()
        if before is not None and before["fx_exact"]:
            # The user fixed the exact rupee charge for this foreign spend;
            # a later re-sync of the same row must not revert it to an estimate.
            t = t.model_copy(update={"amountMinor": int(before["amount_minor"]), "currency": "INR"})
            orig_amount = before["orig_amount_minor"]
            orig_currency = before["orig_currency"]
            fx_rate = before["fx_rate"]
        created_at=now_ms()
        provisional = is_provisional_unresolved_transaction(t)
        conn.execute("""INSERT OR IGNORE INTO transactions
        (id,amount_minor,currency,type,payment_method,account_type,bank,merchant_or_payee,account_last4,reference,timestamp,category,confidence,duplicate_of,status,created_at,
         orig_amount_minor,orig_currency,fx_rate)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (t.id,t.amountMinor,t.currency,t.type,t.paymentMethod,t.accountType,t.bank,t.merchantOrPayee,t.accountLast4,normalize_reference(t.reference),
         t.timestamp,t.category,t.confidence,None,"ACTIVE",created_at,orig_amount,orig_currency,fx_rate))

        if (
            before is None
            and apply_balance
            and not str(t.id).startswith("gmail:")
            and not provisional
        ):
            apply_transaction_to_account(conn, t, created_at, transaction_created_at=created_at)

        if before is not None and before["status"] == "ACTIVE":
            # An Android notification can be synced before Gmail/IMAP clarifies
            # it. When the same canonical ID is later enriched, update the
            # existing ledger row and repair any balance contribution
            # that was based on the provisional direction/account identity.
            balance_adjustment = conn.execute(
                "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
                (t.id,),
            ).fetchone()

            materially_changed = (
                int(before["amount_minor"]) != int(t.amountMinor)
                or str(before["currency"]) != str(t.currency)
                or str(before["type"]) != str(t.type)
                or str(before["account_type"]) != str(t.accountType)
                or str(before["bank"] or "").strip().upper() != str(t.bank or "").strip().upper()
                or str(before["account_last4"] or "").strip() != str(t.accountLast4 or "").strip()
            )

            if materially_changed:
                if balance_adjustment is not None:
                    conn.execute(
                        "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                        (int(balance_adjustment["delta_minor"]), created_at, balance_adjustment["account_id"]),
                    )
                    conn.execute(
                        "DELETE FROM balance_adjustments WHERE transaction_id=?",
                        (t.id,),
                    )

                conn.execute(
                    """UPDATE transactions SET
                       amount_minor=?, currency=?, type=?, payment_method=?,
                       account_type=?, bank=?, merchant_or_payee=?,
                       account_last4=?, reference=?, timestamp=?, confidence=?,
                       orig_amount_minor=?, orig_currency=?, fx_rate=?,
                       status='ACTIVE'
                       WHERE id=?""",
                    (
                        t.amountMinor, t.currency, t.type, t.paymentMethod,
                        t.accountType, t.bank, t.merchantOrPayee,
                        t.accountLast4, normalize_reference(t.reference),
                        t.timestamp, t.confidence,
                        orig_amount, orig_currency, fx_rate, t.id,
                    ),
                )

                if apply_balance and not str(t.id).startswith("gmail:"):
                    apply_transaction_to_account(
                        conn, t, created_at, transaction_created_at=created_at
                    )

        # A transaction can have been stored before its bank account existed
        # (for example after a fresh database reset). On a later sync, repair the
        # missing balance adjustment exactly once. Manual reconciliation timestamps
        # still protect verified balances from replaying genuinely old transactions.
        if (
            before is not None
            and before["status"] == "ACTIVE"
            and apply_balance
            and not str(t.id).startswith("gmail:")
            and not provisional
        ):
            missing_adjustment = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (t.id,),
            ).fetchone()
            if missing_adjustment is None:
                apply_transaction_to_account(
                    conn, t, created_at, transaction_created_at=created_at
                )

        # Category edits are sent as the same transaction ID. They update the
        # ledger metadata only; the bank/card balance is not reapplied. The
        # Splitwise value is derived from the ledger on read, so switching a
        # row to or from OTHER adds or removes its share automatically.
        if (
            before is not None
            and before["status"] == "ACTIVE"
            and not str(t.id).startswith("gmail:")
            and str(before["category"] or "").strip().upper()
                != str(t.category or "").strip().upper()
        ):
            conn.execute(
                "UPDATE transactions SET category=? WHERE id=?",
                (t.category, t.id),
            )

        return before is None


def sync_evidence(e):
    if e.amountMinor is not None and e.currency and str(e.currency).strip().upper() != "INR":
        from .. import fx
        converted = fx.to_inr(e.amountMinor, e.currency, e.observedAt)
        if converted is not None:
            e = e.model_copy(update={"amountMinor": converted[0], "currency": "INR"})
    with connection() as conn:
        before=conn.execute(
            "SELECT id FROM evidence WHERE source_type=? AND source_id=?",
            (e.sourceType,e.sourceId)
        ).fetchone()
        conn.execute("""
            INSERT INTO evidence
            (id,source_type,source_id,status,observed_at,transaction_id,matched_transaction_id,amount_minor,currency,direction,
             bank_provider,account_last4,reference,content_hash,confidence,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source_type, source_id) DO UPDATE SET
                status=excluded.status,
                observed_at=excluded.observed_at,
                transaction_id=excluded.transaction_id,
                matched_transaction_id=excluded.matched_transaction_id,
                amount_minor=excluded.amount_minor,
                currency=excluded.currency,
                direction=excluded.direction,
                bank_provider=excluded.bank_provider,
                account_last4=excluded.account_last4,
                reference=excluded.reference,
                content_hash=excluded.content_hash,
                confidence=excluded.confidence
        """,
        (e.id,e.sourceType,e.sourceId,e.status,e.observedAt,e.transactionId,e.matchedTransactionId,e.amountMinor,e.currency,
         e.direction,e.bankProvider,e.accountLast4,normalize_reference(e.reference),e.contentHash,e.confidence,now_ms()))
        return before is None
