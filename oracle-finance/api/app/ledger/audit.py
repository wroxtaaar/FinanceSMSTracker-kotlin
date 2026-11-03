"""Read-only ledger audit: explains where balances and Splitwise come from.

Nothing here writes. It lets the owner see, per account, how the stored balance
compares with opening balance + applied transaction deltas, which rows moved
the numbers most recently, and how the Splitwise total is composed.
"""

from ..db import connection
from .splitwise import _ledger_splitwise_total, get_manual_splitwise_total, splitwise_delta


def ledger_audit(recent=25):
    recent = max(1, min(int(recent), 200))
    with connection() as conn:
        accounts = []
        for a in conn.execute("SELECT * FROM accounts ORDER BY account_type, bank, last4").fetchall():
            applied = conn.execute(
                "SELECT COALESCE(SUM(delta_minor),0) v, COUNT(*) n FROM balance_adjustments WHERE account_id=?",
                (a["id"],),
            ).fetchone()
            expected = int(a["opening_balance_minor"] or 0) + int(applied["v"])
            accounts.append({
                "id": a["id"], "name": a["name"], "type": a["account_type"], "bank": a["bank"],
                "last4": a["last4"], "balanceMinor": a["balance_minor"],
                "billBalanceMinor": a["bill_balance_minor"],
                "openingMinor": a["opening_balance_minor"],
                "appliedDeltaMinor": int(applied["v"]), "appliedRows": applied["n"],
                "expectedMinor": expected,
                "driftMinor": int(a["balance_minor"]) - expected,
                "balanceReconciledAt": a["balance_reconciled_at"], "billLockedAt": a["bill_locked_at"], "updatedAt": a["updated_at"],
            })
        cols = """t.id, t.timestamp, t.created_at, t.type, t.amount_minor, t.category, t.bank,
                  t.account_type, t.account_last4, t.merchant_or_payee, t.status, t.duplicate_of,
                  t.splitwise_amount_minor,
                  (SELECT SUM(delta_minor) FROM balance_adjustments WHERE transaction_id=t.id) bank_delta"""
        rows = conn.execute(f"SELECT {cols} FROM transactions t ORDER BY t.timestamp DESC LIMIT ?", (recent,)).fetchall()
        created = conn.execute(f"SELECT {cols} FROM transactions t ORDER BY t.created_at DESC LIMIT ?", (recent,)).fetchall()
        credits = conn.execute(
            f"SELECT {cols} FROM transactions t WHERE t.type='CREDIT' AND t.status='ACTIVE' AND t.duplicate_of IS NULL "
            "ORDER BY t.created_at DESC LIMIT ?", (recent,)).fetchall()
        big = conn.execute(
            f"SELECT {cols} FROM transactions t WHERE t.status='ACTIVE' AND t.duplicate_of IS NULL "
            "AND t.account_type IN ('BANK_ACCOUNT','CREDIT_CARD') ORDER BY t.amount_minor DESC LIMIT 12").fetchall()
        transfers = conn.execute(
            "SELECT id, debit_transaction_id, credit_transaction_id, amount_minor, status, reason, created_at "
            "FROM internal_transfers ORDER BY created_at DESC LIMIT 10").fetchall()
        evidence = conn.execute(
            "SELECT source_type, observed_at, amount_minor, bank, account_last4, account_last2, applied, created_at "
            "FROM card_bill_evidence ORDER BY created_at DESC LIMIT 15").fetchall()
        mails = conn.execute(
            "SELECT internal_date, sender, subject, status FROM gmail_messages ORDER BY internal_date DESC LIMIT 12").fetchall()

        attachments = conn.execute(
            "SELECT filename, bank, status, transaction_count, error, created_at "
            "FROM gmail_attachments ORDER BY created_at DESC LIMIT 12").fetchall()

        def shape(r):
            d = dict(r)
            d["sw_delta"] = splitwise_delta(r["account_type"], r["type"], r["amount_minor"], r["category"],
                                            salary_share_minor=r["splitwise_amount_minor"])
            return d
        extra = {
            "recentlyCreated": [shape(r) for r in created],
            "recentCredits": [shape(r) for r in credits],
            "largestRows": [shape(r) for r in big],
            "transfers": [dict(r) for r in transfers],
            "billEvidence": [dict(r) for r in evidence],
            "recentMails": [dict(r) for r in mails],
            "attachments": [dict(r) for r in attachments],
        }
        sw_sum = int(_ledger_splitwise_total(conn, "INR"))
        manual = conn.execute(
            "SELECT amount_minor, baseline_amount_minor, baseline_ledger_minor, updated_at FROM manual_splitwise_total WHERE currency='INR'"
        ).fetchone()
    return {
        **extra,
        "accounts": accounts,
        "recentTransactions": [shape(r) for r in rows],
        "splitwise": {
            "shownMinor": get_manual_splitwise_total("INR"),
            "ledgerSumMinor": sw_sum,
            "manual": dict(manual) if manual else None,
        },
    }
