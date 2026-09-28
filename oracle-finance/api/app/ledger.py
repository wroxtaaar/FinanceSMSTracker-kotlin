import time
from .db import connection

def now_ms():
    return int(time.time() * 1000)

def sync_transaction(t):
    with connection() as conn:
        before = conn.execute("SELECT id FROM transactions WHERE id=?", (t.id,)).fetchone()
        conn.execute(
            """INSERT OR IGNORE INTO transactions
            (id, amount_minor, currency, type, payment_method, account_type, bank,
             merchant_or_payee, account_last4, reference, timestamp, category, confidence, created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (t.id, t.amountMinor, t.currency, t.type, t.paymentMethod, t.accountType,
             t.bank, t.merchantOrPayee, t.accountLast4, t.reference, t.timestamp,
             t.category, t.confidence, now_ms()),
        )
        return before is None

def sync_evidence(e):
    with connection() as conn:
        before = conn.execute(
            "SELECT id FROM evidence WHERE source_type=? AND source_id=?",
            (e.sourceType, e.sourceId),
        ).fetchone()
        conn.execute(
            """INSERT OR IGNORE INTO evidence
            (id, source_type, source_id, status, observed_at, transaction_id,
             matched_transaction_id, created_at)
            VALUES (?,?,?,?,?,?,?,?)""",
            (e.id, e.sourceType, e.sourceId, e.status, e.observedAt,
             e.transactionId, e.matchedTransactionId, now_ms()),
        )
        return before is None

def balances():
    with connection() as conn:
        rows = conn.execute(
            "SELECT id,name,currency,account_type,bank,last4,balance_minor,updated_at FROM accounts ORDER BY name"
        ).fetchall()
        return [dict(r) for r in rows]

def set_balance(account_id, name, currency, account_type, bank, last4, balance_minor):
    with connection() as conn:
        conn.execute(
            """INSERT INTO accounts(id,name,currency,account_type,bank,last4,balance_minor,updated_at)
            VALUES(?,?,?,?,?,?,?,?)
            ON CONFLICT(id) DO UPDATE SET
              name=excluded.name, currency=excluded.currency, account_type=excluded.account_type,
              bank=excluded.bank, last4=excluded.last4, balance_minor=excluded.balance_minor,
              updated_at=excluded.updated_at""",
            (account_id,name,currency,account_type,bank,last4,balance_minor,now_ms()),
        )

def add_receivable(item):
    with connection() as conn:
        conn.execute(
            """INSERT OR IGNORE INTO splitwise_receivables
            (id,description,amount_minor,currency,splitwise_expense_id,status,created_at)
            VALUES(?,?,?,?,?,'OPEN',?)""",
            (item["id"],item["description"],item["amount_minor"],item["currency"],
             item.get("splitwise_expense_id"),now_ms()),
        )

def true_available(currency="INR"):
    with connection() as conn:
        cash = conn.execute(
            """SELECT COALESCE(SUM(balance_minor),0) AS value
               FROM accounts
               WHERE account_type='BANK_ACCOUNT' AND currency=?""", (currency,)
        ).fetchone()["value"]
        receivables = conn.execute(
            """SELECT COALESCE(SUM(amount_minor),0) AS value
               FROM splitwise_receivables
               WHERE status='OPEN' AND currency=?""", (currency,)
        ).fetchone()["value"]
        cards = conn.execute(
            """SELECT COALESCE(SUM(balance_minor),0) AS value
               FROM accounts
               WHERE account_type='CREDIT_CARD' AND currency=?""", (currency,)
        ).fetchone()["value"]
        return {
            "currency": currency,
            "bankCashMinor": cash,
            "splitwiseReceivableMinor": receivables,
            "creditCardOutstandingMinor": cards,
            "trueAvailableMinor": cash + receivables - cards,
        }
