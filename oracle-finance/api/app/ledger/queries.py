"""Read-side queries: transactions, review queue, true available."""

from ..db import connection
from .common import now_ms
from .splitwise import get_manual_splitwise_total


def true_available(currency="INR"):
    with connection() as conn:
        cash=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='BANK_ACCOUNT' AND currency=?",(currency,)).fetchone()["value"]
        manual=get_manual_splitwise_total(currency)
        if manual is None:
            rec=conn.execute("SELECT COALESCE(SUM(amount_minor),0) value FROM splitwise_receivables WHERE status='OPEN' AND currency=?",(currency,)).fetchone()["value"]
        else:
            rec=manual
        cards=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='CREDIT_CARD' AND currency=?",(currency,)).fetchone()["value"]
        return {"currency":currency,"bankCashMinor":cash,"splitwiseReceivableMinor":rec,"creditCardOutstandingMinor":cards,"trueAvailableMinor":cash+rec-cards}


def list_transactions(limit=100):
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM transactions WHERE duplicate_of IS NULL AND status='ACTIVE' ORDER BY timestamp DESC LIMIT ?",(limit,)).fetchall()]


def list_review_queue():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM review_queue WHERE status='OPEN' ORDER BY created_at DESC").fetchall()]


def add_review(kind,reason,transaction_id=None,evidence_id=None):
    review_id=f"{kind}:{transaction_id or evidence_id or now_ms()}"
    with connection() as conn:
        conn.execute("INSERT OR IGNORE INTO review_queue(id,kind,transaction_id,evidence_id,reason,status,created_at) VALUES(?,?,?,?,?,'OPEN',?)",
                     (review_id,kind,transaction_id,evidence_id,reason,now_ms()))
    return review_id
