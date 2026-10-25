"""Account balances, balance adjustments, card bills and voiding."""

import re
from ..db import connection
from .common import _normalize_account_bank, is_provisional_row, now_ms


def repair_missing_balance_adjustments():
    """Backfill account balances for active transactions missing an adjustment.

    This is safe to run at API startup: each transaction gets at most one
    balance_adjustments row, Gmail/provisional rows are excluded, and manual
    reconciliation timestamps prevent replaying old events into a verified
    balance.
    """
    from types import SimpleNamespace

    with connection() as conn:
        rows = conn.execute(
            """SELECT t.*
               FROM transactions t
               LEFT JOIN balance_adjustments ba ON ba.transaction_id=t.id
               WHERE t.status='ACTIVE'
                 AND ba.transaction_id IS NULL
               ORDER BY t.created_at, t.id"""
        ).fetchall()

        repaired = 0
        for row in rows:
            transaction_id = str(row["id"])
            if transaction_id.startswith("gmail:"):
                continue

            provisional = is_provisional_row(row)
            if provisional:
                continue

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
            before = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,),
            ).fetchone()
            if before is not None:
                continue

            apply_transaction_to_account(
                conn,
                transaction,
                now_ms(),
                transaction_created_at=int(row["created_at"]),
            )
            after = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,),
            ).fetchone()
            if after is not None:
                repaired += 1

        return repaired


def _auto_provision_bank_account(conn, t, bank, last4, now_ms):
    """Create or complete an unambiguous bank account discovered from a transaction.

    A fresh database has no bank roster because bank identities are user-specific.
    When the first trusted bank transaction arrives, provision the bank account
    only when its identity is unambiguous. Never create a second account merely
    because a later source supplies a last-four for an existing bank account.
    """
    if t.accountType != "BANK_ACCOUNT" or not bank:
        return None

    rows = conn.execute(
        """SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
           FROM accounts
           WHERE account_type=? AND currency=?
           ORDER BY updated_at DESC""",
        (t.accountType, t.currency),
    ).fetchall()

    bank_rows = [
        row for row in rows
        if _normalize_account_bank(row["bank"]) == bank
    ]

    # Prefer an exact last-four match.
    if last4:
        for row in bank_rows:
            if str(row["last4"] or "").strip() == last4:
                return row

        # If there is exactly one existing account for this bank and it has no
        # last-four yet, enrich that account rather than creating another one.
        blank_identity = [
            row for row in bank_rows
            if not str(row["last4"] or "").strip()
        ]
        if len(blank_identity) == 1:
            account = blank_identity[0]
            conn.execute(
                "UPDATE accounts SET last4=?, updated_at=? WHERE id=?",
                (last4, now_ms, account["id"]),
            )
            return conn.execute(
                """SELECT id, balance_minor, bill_balance_minor,
                          balance_reconciled_at, bank, last4
                   FROM accounts WHERE id=?""",
                (account["id"],),
            ).fetchone()

        # Multiple accounts exist and none identifies this last-four: do not
        # guess which account should receive the transaction.
        if bank_rows:
            return None

    # Without a last-four, do not guess for a transaction that already has
    # a concrete payment method. The legacy balance-matching path only allowed
    # this fallback for UNKNOWN notification rows. Keep that boundary here too:
    # a known UPI/card transaction without a last-four must wait for a stronger
    # account identity rather than applying a second balance adjustment.
    if bank_rows:
        if (
            len(bank_rows) == 1
            and str(getattr(t, "paymentMethod", "") or "").strip().upper() == "UNKNOWN"
        ):
            return bank_rows[0]
        return None

    suffix = last4 or "primary"
    account_id = "bank-" + re.sub(r"[^a-z0-9]+", "-", bank.lower()).strip("-")
    if last4:
        account_id += "-" + last4
    elif suffix != "primary":
        account_id += "-" + suffix

    name = (str(t.bank or "").strip().title() + " Bank").strip()
    conn.execute(
        """INSERT INTO accounts(
            id,name,currency,account_type,bank,last4,opening_balance_minor,
            balance_minor,bill_balance_minor,balance_reconciled_at,updated_at
        ) VALUES(?,?,?,?,?,?,0,0,0,0,?)""",
        (
            account_id,
            name,
            t.currency,
            "BANK_ACCOUNT",
            t.bank,
            last4 or None,
            now_ms,
        ),
    )
    return conn.execute(
        """SELECT id, balance_minor, bill_balance_minor,
                  balance_reconciled_at, bank, last4
           FROM accounts WHERE id=?""",
        (account_id,),
    ).fetchone()


def apply_transaction_to_account(conn, t, applied_at, transaction_created_at=None):
    if t.accountType not in ("BANK_ACCOUNT", "CREDIT_CARD"):
        return

    bank=_normalize_account_bank(t.bank)
    last4=(getattr(t, "accountLast4", None) or getattr(t, "accountLastFour", None) or "").strip()

    # Bank names arrive from different sources as "AXIS", "AXIS BANK", etc.
    # Match by normalized identity instead of requiring byte-for-byte equality.
    query="""
        SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
        FROM accounts
        WHERE account_type=?
          AND currency=?
          AND TRIM(COALESCE(last4,''))=?
        ORDER BY updated_at DESC
    """
    candidates=conn.execute(query,(t.accountType,t.currency,last4)).fetchall()

    account=None
    for candidate in candidates:
        candidate_bank=_normalize_account_bank(candidate["bank"])
        if candidate_bank == bank:
            account=candidate
            break

    # Gmail/notification alerts can sometimes identify the issuing bank but
    # omit the account last-four. When there is exactly one configured account
    # for that bank/type/currency, use that unambiguous identity instead of
    # dropping the balance adjustment. Never guess when multiple matching
    # accounts exist.
    if (
        account is None
        and not last4
        and bank
        and str(getattr(t, "paymentMethod", "") or "").strip().upper() == "UNKNOWN"
    ):
        fallback = conn.execute(
            """
            SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
            FROM accounts
            WHERE account_type=?
              AND currency=?
            ORDER BY updated_at DESC
            """,
            (t.accountType, t.currency),
        ).fetchall()
        bank_matches = [
            row for row in fallback
            if _normalize_account_bank(row["bank"]) == bank
        ]
        if len(bank_matches) == 1:
            account = bank_matches[0]

    # If the transaction has no bank name, only use an unambiguous account
    # with the same type/currency/last4.
    if account is None and not bank and len(candidates) == 1:
        account=candidates[0]

    if not account:
        account = _auto_provision_bank_account(conn, t, bank, last4, applied_at)

    if not account:
        return

    # A manual balance edit is a reconciliation point. Transactions that
    # already existed before that reconciliation are assumed to be included in
    # the manually verified balance. A genuinely new transaction discovered
    # after reconciliation must still affect the balance even when its bank
    # event timestamp is older because the notification/email arrived late.
    reconciled_at = int(account["balance_reconciled_at"] or 0)
    created_at = int(transaction_created_at or applied_at)
    event_at = int(t.timestamp)
    # A manual reconciliation must ignore genuinely historical rows discovered
    # later. We still allow a recent bank event whose notification/email arrived
    # after reconciliation; this covers the real late-Gmail case without
    # replaying arbitrarily old transactions into a verified balance.
    # A real bank event timestamp is epoch milliseconds. Keep the
    # "discovered after reconciliation" exception for plausible transaction
    # timestamps, while rejecting obviously synthetic/invalid ancient values.
    # This also preserves late-email/SMS ingestion of legitimate older events.
    min_plausible_event_ms = 946684800000  # 2000-01-01
    if reconciled_at and event_at <= reconciled_at:
        if created_at <= reconciled_at:
            return
        if event_at < min_plausible_event_ms:
            return

    # Bank account balance: DEBIT decreases it, CREDIT increases it.
    # Credit card balance is the amount owed (outstanding): a card spend
    # (DEBIT) increases it and a payment/refund (CREDIT) decreases it.
    if t.type == "DEBIT":
        delta = t.amountMinor if t.accountType == "CREDIT_CARD" else -t.amountMinor
    elif t.type == "CREDIT":
        delta = -t.amountMinor if t.accountType == "CREDIT_CARD" else t.amountMinor
    else:
        return

    inserted=conn.execute(
        """INSERT OR IGNORE INTO balance_adjustments
           (transaction_id,account_id,delta_minor,applied_at)
           VALUES(?,?,?,?)""",
        (t.id,account["id"],delta,applied_at)
    )
    if inserted.rowcount:
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
            (delta,applied_at,account["id"])
        )

        # Keep the statement bill separate from live/unbilled card spend.
        # Card debits are new spend and therefore do not increase the bill
        # bucket. Card credits (bill payments, refunds, etc.) reduce the bill
        # bucket first; any amount beyond the bill reduces live spend.
        if t.accountType == "CREDIT_CARD" and t.type == "CREDIT":
            conn.execute(
                """UPDATE accounts
                   SET bill_balance_minor=MAX(0, COALESCE(bill_balance_minor,0)-?)
                 WHERE id=?""",
                (t.amountMinor, account["id"])
            )


def sync_card_bill(bill):
    """Record bill evidence and apply only the strongest confirmed bill amount.

    Source precedence:
      Gmail/full statement email or PDF > SMS > notification.
    Lower-priority evidence is retained but cannot overwrite a stronger
    conflicting bill amount.
    """
    source_type = str(bill.get("sourceType") or "").upper()
    source_key = str(bill.get("sourceKey") or "")
    bank = (bill.get("bank") or "").strip().upper() or None
    last4 = (bill.get("accountLast4") or "").strip() or None
    last2 = (bill.get("accountLast2") or "").strip() or None
    amount = bill.get("amountMinor")
    amount = int(amount) if amount is not None else None
    observed_at = int(bill.get("timestamp") or now_ms())
    confidence = float(bill.get("confidence") or 0.0)
    if not source_key or amount is None or amount < 0:
        return {"status":"IGNORED","reason":"missing_bill_amount_or_source"}

    priority = {
        "GMAIL": 300,
        "GMAIL_STATEMENT_PDF": 300,
        "SMS": 200,
        "GMAIL_NOTIFICATION": 100,
    }.get(source_type, 0)

    with connection() as conn:
        inserted = conn.execute(
            """INSERT OR IGNORE INTO card_bill_evidence
               (id,source_type,source_key,observed_at,amount_minor,currency,bank,
                account_last4,account_last2,confidence,applied,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,0,?)""",
            (
                f"card-bill:{source_type}:{source_key}",
                source_type,
                source_key,
                observed_at,
                amount,
                bill.get("currency") or "INR",
                bank,
                last4,
                last2,
                confidence,
                now_ms(),
            ),
        ).rowcount

        # Find the configured card. Prefer a full last-4 match; fall back to
        # a unique visible last-two suffix from statement notifications.
        account = None
        if last4:
            account = conn.execute(
                """SELECT id,balance_minor,bill_balance_minor,balance_reconciled_at
                   FROM accounts
                   WHERE account_type='CREDIT_CARD' AND currency=?
                     AND UPPER(TRIM(COALESCE(bank,'')))=?
                     AND TRIM(COALESCE(last4,''))=?
                   ORDER BY updated_at DESC LIMIT 1""",
                (bill.get("currency") or "INR", bank or "", last4),
            ).fetchone()

        if not account and last2:
            candidates = conn.execute(
                """SELECT id,balance_minor,bill_balance_minor,balance_reconciled_at,last4
                   FROM accounts
                   WHERE account_type='CREDIT_CARD' AND currency=?
                     AND UPPER(TRIM(COALESCE(bank,'')))=?
                     AND substr(TRIM(COALESCE(last4,'')),-2)=?
                   ORDER BY updated_at DESC""",
                (bill.get("currency") or "INR", bank or "", last2),
            ).fetchall()
            if len(candidates) == 1:
                account = candidates[0]

        if not account:
            return {"status":"REVIEW","reason":"card_not_found","bank":bank,"last4":last4,"last2":last2}

        # A manual card balance is a reconciliation point. Older statement
        # evidence discovered later must not rewrite the manually verified
        # Bill/Active Spend split. A statement observed after reconciliation
        # is allowed to establish the new bill bucket.
        reconciled_at = int(account["balance_reconciled_at"] or 0)
        if reconciled_at and observed_at <= reconciled_at:
            return {
                "status":"RETAINED_MANUAL_RECONCILIATION",
                "accountId":account["id"],
                "sourceType":source_type,
            }

        # Compare the strongest already-applied evidence for this exact card.
        previous = conn.execute(
            """SELECT e.source_type,e.amount_minor
               FROM card_bill_evidence e
               JOIN accounts a ON a.id=?
               WHERE e.applied=1
                 AND e.currency=?
                 AND UPPER(TRIM(COALESCE(e.bank,'')))=?
                 AND (
                      (e.account_last4 IS NOT NULL AND TRIM(e.account_last4)=TRIM(a.last4))
                      OR (e.account_last2 IS NOT NULL AND substr(TRIM(COALESCE(a.last4,'')),-2)=TRIM(e.account_last2))
                 )
               ORDER BY e.observed_at DESC
               LIMIT 1""",
            (account["id"], bill.get("currency") or "INR", bank or ""),
        ).fetchone()

        if previous:
            previous_priority = {
                "GMAIL": 300,
                "GMAIL_STATEMENT_PDF": 300,
                "SMS": 200,
                "GMAIL_NOTIFICATION": 100,
            }.get(str(previous["source_type"]).upper(), 0)

            if amount != previous["amount_minor"] and priority < previous_priority:
                return {
                    "status":"RETAINED_LOWER_PRIORITY",
                    "accountId":account["id"],
                    "currentBillMinor":previous["amount_minor"],
                    "receivedBillMinor":amount,
                    "sourceType":source_type,
                }

            if amount != previous["amount_minor"] and priority == previous_priority:
                # Same-tier disagreement is a review condition. Do not silently
                # choose one source.
                review_id = "CARD_BILL_CONFLICT:" + account["id"] + ":" + str(observed_at)
                conn.execute(
                    """INSERT OR IGNORE INTO review_queue
                       (id,kind,transaction_id,evidence_id,reason,status,created_at)
                       VALUES(?,?,NULL,NULL,?,'OPEN',?)""",
                    (
                        review_id,
                        "CARD_BILL_CONFLICT",
                        f"Card bill disagreement for {bank} {account['id']}: {previous['amount_minor']} vs {amount}",
                        now_ms(),
                    ),
                )
                return {
                    "status":"REVIEW",
                    "reason":"same_priority_bill_conflict",
                    "accountId":account["id"],
                }

        # Bill balance is a separate statement/debt bucket. It must not be
        # capped by the signed live card balance because the live card balance
        # follows the application's invariant sign convention.
        bill_value = amount
        conn.execute(
            """UPDATE accounts
               SET bill_balance_minor=?, updated_at=?
             WHERE id=?""",
            (bill_value, now_ms(), account["id"]),
        )
        conn.execute(
            """UPDATE card_bill_evidence
               SET applied=1
             WHERE source_type=? AND source_key=?""",
            (source_type, source_key),
        )

        return {
            "status":"APPLIED",
            "accountId":account["id"],
            "billBalanceMinor":bill_value,
            "sourceType":source_type,
        }


def void_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute(
            "SELECT id,status,type,account_type,category,amount_minor,currency FROM transactions WHERE id=?",
            (transaction_id,)
        ).fetchone()
        if not tx:
            return {"status":"NOT_FOUND","transactionId":transaction_id}
        if tx["status"]=="VOIDED":
            return {"status":"ALREADY_VOIDED","transactionId":transaction_id}

        adjustment=conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,)
        ).fetchone()

        if adjustment:
            conn.execute(
                "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                (adjustment["delta_minor"], now_ms(), adjustment["account_id"])
            )
            conn.execute(
                "DELETE FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,)
            )

        # The Splitwise value is derived from the ledger; the voided row drops out of it.
        conn.execute(
            "UPDATE transactions SET status='VOIDED' WHERE id=?",
            (transaction_id,)
        )
        return {
            "status":"VOIDED",
            "transactionId":transaction_id,
            "reversedAdjustment": bool(adjustment)
        }


def _resolve_canonical_row(conn, transaction_id):
    row = conn.execute("SELECT * FROM transactions WHERE id=?", (transaction_id,)).fetchone()
    hops = 0
    while row is not None and row["duplicate_of"] and hops < 5:
        row = conn.execute("SELECT * FROM transactions WHERE id=?", (row["duplicate_of"],)).fetchone()
        hops += 1
    return row


def balances():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM accounts ORDER BY name").fetchall()]


def set_balance(account_id,name,currency,account_type,bank,last4,balance_minor,bill_balance_minor=None):
    with connection() as conn:
        adjustment_total=conn.execute(
            "SELECT COALESCE(SUM(delta_minor),0) value FROM balance_adjustments WHERE account_id=?",
            (account_id,)
        ).fetchone()["value"]

        existing=conn.execute(
            "SELECT bill_balance_minor FROM accounts WHERE id=?",
            (account_id,)
        ).fetchone()

        if account_type == "CREDIT_CARD":
            if bill_balance_minor is None:
                bill_value = (
                    int(existing["bill_balance_minor"])
                    if existing and existing["bill_balance_minor"] is not None
                    else int(balance_minor)
                )
            else:
                bill_value = max(0, min(int(bill_balance_minor), int(balance_minor)))
        else:
            bill_value = 0

        # Manual entry is an explicit reconciliation of the live balance.
        # Preserve the transaction-derived opening snapshot, but establish a
        # timestamp so transactions discovered later with older event times do
        # not retroactively change this manually verified balance.
        opening_balance=balance_minor-adjustment_total
        reconciled_at=now_ms()
        conn.execute("""INSERT INTO accounts(
            id,name,currency,account_type,bank,last4,opening_balance_minor,balance_minor,bill_balance_minor,balance_reconciled_at,updated_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
            name=excluded.name,
            currency=excluded.currency,
            account_type=excluded.account_type,
            bank=excluded.bank,
            last4=excluded.last4,
            opening_balance_minor=excluded.opening_balance_minor,
            balance_minor=excluded.balance_minor,
            bill_balance_minor=excluded.bill_balance_minor,
            balance_reconciled_at=excluded.balance_reconciled_at,
            updated_at=excluded.updated_at""",
        (account_id,name,currency,account_type,bank,last4,opening_balance,balance_minor,bill_value,reconciled_at,reconciled_at))


def add_receivable(item):
    with connection() as conn:
        conn.execute("""INSERT OR IGNORE INTO splitwise_receivables
        (id,description,amount_minor,currency,splitwise_expense_id,status,created_at)
        VALUES(?,?,?,?,?,'OPEN',?)""",
        (item["id"],item["description"],item["amount_minor"],item["currency"],item.get("splitwise_expense_id"),now_ms()))
