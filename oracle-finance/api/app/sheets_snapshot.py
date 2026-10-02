import hmac
import os
from datetime import datetime, timezone
from fastapi import Header, HTTPException
from .ledger import balances, true_available, list_transactions

def get_snapshot(x_sheets_token: str = Header(default="")):
    expected = os.getenv("SHEETS_SYNC_TOKEN", "").strip()
    if not expected or not x_sheets_token or not hmac.compare_digest(x_sheets_token, expected):
        raise HTTPException(status_code=401, detail="unauthorized")

    accounts = []
    for row in balances():
        accounts.append({
            "id": row["id"],
            "name": row["name"],
            "currency": row["currency"],
            "accountType": row["account_type"],
            "bank": row["bank"],
            "last4": row["last4"],
            "balanceMinor": row["balance_minor"],
            "billBalanceMinor": row["bill_balance_minor"],
            "activeSpendMinor": max(0, row["balance_minor"] - row["bill_balance_minor"])
                if row["account_type"] == "CREDIT_CARD" else 0,
        })

    transactions = []
    for row in list_transactions(1000):
        transactions.append({
            "id": row["id"],
            "timestamp": row["timestamp"],
            "source": "GMAIL" if str(row["id"]).startswith("gmail:") else "CANONICAL",
            "accountType": row["account_type"],
            "bank": row["bank"],
            "last4": row["account_last4"],
            "type": row["type"],
            "amountMinor": row["amount_minor"],
            "currency": row["currency"],
            "category": row["category"],
            "merchant": row["merchant_or_payee"],
            "reference": row["reference"],
            "status": row["status"],
        })

    return {
        "generatedAt": int(datetime.now(timezone.utc).timestamp() * 1000),
        "summary": true_available("INR"),
        "accounts": accounts,
        "transactions": transactions,
    }
