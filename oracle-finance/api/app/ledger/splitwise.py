"""Splitwise: per-row deltas, derived total (baseline + ledger change), rules and expenses."""

from ..db import connection
from .common import _resolve_transaction_row, is_provisional_row, now_ms


def splitwise_delta(account_type, transaction_type, amount_minor, category=None):
    """Return the Splitwise effect implied by the final ledger movement.

    Splitwise is deliberately derived from the same ledger movement as the
    account balance, not from a second set of expense rules. The one category
    that matters is OTHER: the user's explicit "keep this out of Splitwise".

    Bank account:
      DEBIT  -> bank -X, so Splitwise +X
      CREDIT -> bank +X, so Splitwise -X

    Credit card outstanding:
      DEBIT  -> outstanding +X, so Splitwise +X
      CREDIT -> outstanding -X, so Splitwise -X

    This also makes transfers self-cancelling:
      bank debit +X + matching bank/card credit -X = 0.
    """
    amount = int(amount_minor)
    account_type = str(account_type or "").strip().upper()
    transaction_type = str(transaction_type or "").strip().upper()

    if str(category or "").strip().upper() == "OTHER":
        return 0

    if account_type in ("BANK_ACCOUNT", "CREDIT_CARD"):
        return amount if transaction_type == "DEBIT" else -amount if transaction_type == "CREDIT" else 0
    return 0


def splitwise_delta_for_transaction(t):
    return splitwise_delta(t.accountType, t.type, t.amountMinor, t.category)


def set_manual_splitwise_total(currency, amount_minor):
    with connection() as conn:
        # Record the ledger-wide sum at this moment so later rebuilds only add
        # what changes after the user's reset.
        ledger = _ledger_splitwise_total(conn, currency)
        conn.execute(
            """INSERT INTO manual_splitwise_total
                   (currency, amount_minor, updated_at, baseline_amount_minor, baseline_ledger_minor)
               VALUES(?,?,?,?,?)
               ON CONFLICT(currency) DO UPDATE SET
                 amount_minor=excluded.amount_minor,
                 updated_at=excluded.updated_at,
                 baseline_amount_minor=excluded.baseline_amount_minor,
                 baseline_ledger_minor=excluded.baseline_ledger_minor""",
            (currency, int(amount_minor), now_ms(), int(amount_minor), ledger)
        )


def apply_splitwise_contribution(conn, t, applied_at=None):
    """Apply a transaction's Splitwise delta exactly once.

    Gmail transactions are authoritative when no SMS/notification canonical
    row exists. They therefore need the same signed Splitwise effect as local
    transactions. A separate adjustment row makes that effect reversible if a
    later SMS/notification proves the Gmail row was a duplicate.
    """
    transaction_id = str(getattr(t, "id", None) or "")
    if not transaction_id:
        return False

    delta = splitwise_delta_for_transaction(t)
    if not delta:
        return False

    applied_at = int(applied_at or now_ms())
    inserted = conn.execute(
        """INSERT OR IGNORE INTO splitwise_adjustments
           (transaction_id,currency,delta_minor,applied_at)
           VALUES(?,?,?,?)""",
        (transaction_id, t.currency, delta, applied_at),
    )
    # Only the reversible record is kept; the shown total is derived from the ledger.
    return bool(inserted.rowcount)


def _ledger_splitwise_total(conn, cur, honor_other=True):
    """Ledger-wide Splitwise sum of the canonical bank/card movements.

    Only active, canonical transactions that actually affect a bank/card
    balance are included. A bank/card transfer naturally cancels because its
    two ledger effects have opposite signs; only the OTHER category (explicit
    opt-out) is consulted.
    """
    rows = conn.execute(
        """SELECT id,amount_minor,currency,type,account_type,payment_method,
                  bank,merchant_or_payee,account_last4,reference,
                  timestamp,category,confidence
           FROM transactions
           WHERE status='ACTIVE'
             AND duplicate_of IS NULL
             AND currency=?
             AND account_type IN ('BANK_ACCOUNT','CREDIT_CARD')""",
        (cur,),
    ).fetchall()

    # Both legs of a matched own-account transfer cancel by definition.
    # Excluding them as a pair keeps a provisional leg (which is skipped
    # below) from leaving its partner's contribution behind.
    transfer_leg_ids = set()
    for transfer in conn.execute(
        """SELECT debit_transaction_id,credit_transaction_id
           FROM internal_transfers WHERE status='MATCHED'"""
    ).fetchall():
        for leg in (transfer["debit_transaction_id"], transfer["credit_transaction_id"]):
            resolved = _resolve_transaction_row(conn, leg)
            if resolved is not None:
                transfer_leg_ids.add(str(resolved["id"]))

    total = 0
    for row in rows:
        if str(row["id"]) in transfer_leg_ids:
            continue
        if is_provisional_row(row):
            continue
        total += splitwise_delta(
            row["account_type"],
            row["type"],
            row["amount_minor"],
            row["category"] if honor_other else None,
        )
    return total


def rebuild_manual_splitwise_total(currency=None):
    """Rebuild the materialized Splitwise total from the canonical ledger.

    The transactions table is the source of truth for *changes*; the user's
    manual total is the baseline. The total is the manual amount plus whatever
    the ledger-wide Splitwise sum has changed by since that amount was set, so
    opening the app, retrying sync, restarting the API, duplicate repair or a
    late transfer match cannot make Splitwise drift or overwrite a reset.
    """
    with connection() as conn:
        currencies = [currency] if currency else [
            row["currency"]
            for row in conn.execute(
                "SELECT DISTINCT currency FROM transactions WHERE currency IS NOT NULL AND TRIM(currency)<>''"
            ).fetchall()
        ]

        for cur in currencies:
            ledger = _ledger_splitwise_total(conn, cur)
            row = conn.execute(
                """SELECT amount_minor,baseline_amount_minor,baseline_ledger_minor
                   FROM manual_splitwise_total WHERE currency=?""",
                (cur,),
            ).fetchone()

            if row is None:
                # No manual total was ever set: the ledger is the whole story.
                baseline_amount, baseline_ledger = 0, 0
            elif row["baseline_amount_minor"] is None or row["baseline_ledger_minor"] is None:
                # Value stored before baselines existed (possibly a manual
                # reset). Keep it and track changes from now on.
                baseline_amount, baseline_ledger = int(row["amount_minor"]), ledger
            else:
                baseline_amount = int(row["baseline_amount_minor"])
                baseline_ledger = int(row["baseline_ledger_minor"])

            total = baseline_amount + ledger - baseline_ledger
            conn.execute(
                """INSERT INTO manual_splitwise_total
                       (currency,amount_minor,updated_at,baseline_amount_minor,baseline_ledger_minor)
                   VALUES(?,?,?,?,?)
                   ON CONFLICT(currency) DO UPDATE SET
                       amount_minor=excluded.amount_minor,
                       updated_at=excluded.updated_at,
                       baseline_amount_minor=excluded.baseline_amount_minor,
                       baseline_ledger_minor=excluded.baseline_ledger_minor""",
                (cur, total, now_ms(), baseline_amount, baseline_ledger),
            )

    return get_manual_splitwise_total(currency or "INR")


def get_manual_splitwise_total(currency="INR"):
    """The Splitwise value shown to the user.

    Derived on every read: the manual baseline plus the change in the ledger's
    Splitwise sum since that baseline was set. The stored amount_minor is only
    a cache (and the fallback for legacy rows that have no baseline yet), so a
    stale incremental update can never be what the user sees.
    """
    with connection() as conn:
        row=conn.execute(
            """SELECT amount_minor,baseline_amount_minor,baseline_ledger_minor
               FROM manual_splitwise_total WHERE currency=?""",
            (currency,),
        ).fetchone()
        if row is None:
            # No manual total was ever set: the ledger is the whole story, but
            # with no transactions at all there is nothing to show yet.
            has_rows = conn.execute(
                "SELECT 1 FROM transactions WHERE currency=? LIMIT 1", (currency,)
            ).fetchone()
            return _ledger_splitwise_total(conn, currency) if has_rows else None
        if row["baseline_amount_minor"] is None or row["baseline_ledger_minor"] is None:
            return int(row["amount_minor"])
        ledger = _ledger_splitwise_total(conn, currency)
        return int(row["baseline_amount_minor"]) + ledger - int(row["baseline_ledger_minor"])


def upsert_splitwise_rule(item):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_rules(id,merchant_pattern,group_id,split_mode,user_shares_json,enabled,created_at)
        VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET merchant_pattern=excluded.merchant_pattern,group_id=excluded.group_id,
        split_mode=excluded.split_mode,user_shares_json=excluded.user_shares_json,enabled=excluded.enabled""",
        (item["id"],item["merchant_pattern"],item["group_id"],item.get("split_mode","EQUAL"),item.get("user_shares_json"),
         1 if item.get("enabled",True) else 0,now_ms()))


def list_splitwise_rules():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY merchant_pattern").fetchall()]


def get_splitwise_rule_for(merchant):
    if not merchant: return None
    needle=merchant.casefold()
    with connection() as conn:
        rows=conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY LENGTH(merchant_pattern) DESC").fetchall()
    for row in rows:
        if row["merchant_pattern"].casefold() in needle: return dict(row)
    return None


def record_splitwise_expense(transaction_id,expense_id,status="CREATED",error=None):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_expenses(transaction_id,splitwise_expense_id,status,error,created_at,updated_at)
        VALUES(?,?,?,?,?,?) ON CONFLICT(transaction_id) DO UPDATE SET splitwise_expense_id=excluded.splitwise_expense_id,
        status=excluded.status,error=excluded.error,updated_at=excluded.updated_at""",
        (transaction_id,expense_id,status,error,now_ms(),now_ms()))


def splitwise_expense_for(transaction_id):
    with connection() as conn:
        row=conn.execute("SELECT * FROM splitwise_expenses WHERE transaction_id=?",(transaction_id,)).fetchone()
        return dict(row) if row else None
