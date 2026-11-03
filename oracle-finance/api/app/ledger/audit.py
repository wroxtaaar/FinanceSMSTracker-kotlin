"""Read-only ledger audit: explains where balances and Splitwise come from.

Nothing here writes. It lets the owner see, per account, how the stored balance
compares with opening balance + applied transaction deltas, which rows moved
the numbers most recently, and how the Splitwise total is composed.
"""

from ..db import connection
from .splitwise import _ledger_splitwise_total, get_manual_splitwise_total


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
                "balanceReconciledAt": a["balance_reconciled_at"], "updatedAt": a["updated_at"],
            })
        rows = conn.execute(
            """SELECT t.id, t.timestamp, t.type, t.amount_minor, t.category, t.bank, t.account_last4,
                      t.merchant_or_payee, t.status, t.duplicate_of,
                      (SELECT SUM(delta_minor) FROM balance_adjustments WHERE transaction_id=t.id) bank_delta,
                      (SELECT account_id FROM balance_adjustments WHERE transaction_id=t.id) account_id,
                      (SELECT delta_minor FROM splitwise_adjustments WHERE transaction_id=t.id) sw_delta
               FROM transactions t ORDER BY t.timestamp DESC LIMIT ?""",
            (recent,),
        ).fetchall()
        sw_sum = int(_ledger_splitwise_total(conn, "INR"))
        manual = conn.execute(
            "SELECT amount_minor, baseline_amount_minor, baseline_ledger_minor, updated_at FROM manual_splitwise_total WHERE currency='INR'"
        ).fetchone()
    return {
        "accounts": accounts,
        "recentTransactions": [dict(r) for r in rows],
        "splitwise": {
            "shownMinor": get_manual_splitwise_total("INR"),
            "ledgerSumMinor": sw_sum,
            "manual": dict(manual) if manual else None,
        },
    }
