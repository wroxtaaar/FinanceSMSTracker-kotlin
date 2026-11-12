"""Loan EMIs that are auto-debited from a bank account.

An EMI is not a shared expense, so its debit and the loan account's credit
stay out of Splitwise. Until the debit lands (the 1st to the due day of each
month) the amount is reserved, so "true available" already reflects money the
bank is about to take, and a bank balance too low to cover it is flagged.
"""

import datetime
import re

from .common import now_ms

_IST = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
# Banks sometimes debit a day or two after the due date.
_LATE_DEBIT_DAYS = 3
# An EMI debit names no payee; anything naming a person or shop is a real payment.
_LOAN_PAYEE = re.compile(r"(?i)\b(?:emi|loan|ach|nach|ecs|mandate)\b")


def _ist(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, _IST)


def _month_start_ms(moment):
    start = moment.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp() * 1000)


def list_loans(conn, currency="INR"):
    return conn.execute(
        "SELECT * FROM emi_loans WHERE enabled=1 AND currency=?", (currency,)
    ).fetchall()


def _bank_matches(row_bank, loan_bank):
    return str(row_bank or "").strip().upper().startswith(str(loan_bank or "").strip().upper())


def is_emi_debit(loan, row):
    """A bank debit of exactly the EMI from the loan's account, due-day window."""
    if row["type"] != "DEBIT" or row["account_type"] != "BANK_ACCOUNT":
        return False
    if int(row["amount_minor"]) != int(loan["emi_minor"]):
        return False
    if not _bank_matches(row["bank"], loan["bank"]):
        return False
    if str(row["account_last4"] or "").strip() != loan["debit_last4"]:
        return False
    payee = str(row["merchant_or_payee"] or "").strip()
    if payee and payee.lower() != "null" and not _LOAN_PAYEE.search(payee):
        return False
    day = _ist(int(row["timestamp"])).day
    return int(loan["start_day"]) <= day <= int(loan["due_day"]) + _LATE_DEBIT_DAYS


def is_loan_credit(loan, row):
    """The loan account's own side of the payment (not a balance we track)."""
    return (
        row["type"] == "CREDIT"
        and row["account_type"] == "BANK_ACCOUNT"
        and _bank_matches(row["bank"], loan["bank"])
        and str(row["account_last4"] or "").strip() == loan["loan_last4"]
    )


def excluded_from_splitwise(loans, row):
    """Only rows stored after the loan was set up, so history is not shifted."""
    for loan in loans:
        if int(row["created_at"] or 0) < int(loan["created_at"]):
            continue
        if is_emi_debit(loan, row) or is_loan_credit(loan, row):
            return True
    return False


def _paid_this_month(conn, loan, month_start):
    rows = conn.execute(
        """SELECT type,account_type,bank,account_last4,amount_minor,timestamp,created_at,merchant_or_payee
           FROM transactions
           WHERE status='ACTIVE' AND duplicate_of IS NULL AND timestamp>=?""",
        (month_start,),
    ).fetchall()
    return any(is_emi_debit(loan, r) or is_loan_credit(loan, r) for r in rows)


def _debit_balance(conn, loan):
    rows = conn.execute(
        "SELECT bank,last4,balance_minor FROM accounts WHERE account_type='BANK_ACCOUNT' AND currency=?",
        (loan["currency"],),
    ).fetchall()
    for row in rows:
        if _bank_matches(row["bank"], loan["bank"]) and str(row["last4"] or "").strip() == loan["debit_last4"]:
            return int(row["balance_minor"] or 0)
    return None


def emi_reservations(conn, currency="INR", at_ms=None):
    """EMIs still to be debited this month, with a flag when the account is short."""
    moment = _ist(at_ms if at_ms is not None else now_ms())
    month_start = _month_start_ms(moment)
    reservations = []
    for loan in list_loans(conn, currency):
        if not int(loan["start_day"]) <= moment.day <= int(loan["due_day"]):
            continue
        if _paid_this_month(conn, loan, month_start):
            continue
        balance = _debit_balance(conn, loan)
        reservations.append({
            "label": loan["label"],
            "bank": loan["bank"],
            "accountLast4": loan["debit_last4"],
            "amountMinor": int(loan["emi_minor"]),
            "dueDay": int(loan["due_day"]),
            "accountBalanceMinor": balance,
            "shortfall": balance is not None and balance < int(loan["emi_minor"]),
        })
    return reservations
