"""ICICI statement parsing."""

from datetime import datetime, timezone
import re

from .common import _compact, _minor
from . import common


def _icici_metadata(text):
    card = re.search(r"(?mi)^\s*(\d{4}X{4,}\d{4})\s*$", text)
    period = re.search(
        r"(?mi)Statement period\s*:\s*([A-Za-z]+\s+\d{1,2},\s+\d{4})\s+to\s+([A-Za-z]+\s+\d{1,2},\s+\d{4})",
        text,
    )
    currency = "INR"

    if not card:
        raise ValueError("ICICI card number not found")
    if not period:
        raise ValueError("ICICI statement period not found")

    return {
        "bank": "ICICI",
        "account_type": "CREDIT_CARD",
        "account_last4": card.group(1)[-4:],
        "currency": currency,
        "statement_from": period.group(1),
        "statement_to": period.group(2),
    }


def _icici_merchant(detail):
    value = _compact(detail)
    if not value:
        return None
    value = re.sub(r"(?i)\s+CR\s*$", "", value).strip()
    return value or None


def _icici_payment_method(detail):
    value = _compact(detail).upper()
    if "UPI" in value:
        return "UPI"
    if any(token in value for token in ("NEFT", "IMPS", "RTGS")):
        return "BANK_TRANSFER"
    if any(token in value for token in ("CASH", "ATM")):
        return "CASH"
    return "CARD"


def parse_icici_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)
    metadata = _icici_metadata(text)

    table_match = re.search(
        r"(?s)Date\s+SerNo\.\s+Transaction Details.*?(?=# International Spends|Credit Limit \(Including cash\))",
        text,
    )
    if not table_match:
        raise ValueError("ICICI transaction table not found")

    table = table_match.group(0)
    blocks = re.finditer(
        r"(?ms)^(?P<date>\d{2}/\d{2}/\d{4})\s+"
        r"(?P<serial>\d{6,})\s+"
        r"(?P<body>.*?)(?=^\d{2}/\d{2}/\d{4}\s+\d{6,}\s+|\Z)",
        table,
    )

    rows = []
    for block in blocks:
        body = _compact(block.group("body"))
        # pdfplumber can place a wrapped location/country token after the
        # amount (for example "... BANGALORE 15 504.99\\nIN"). Use the final
        # decimal money token in the transaction block rather than requiring it
        # to be the final characters.
        amount_matches = list(re.finditer(
            r"(?P<amount>[0-9][0-9,]*\.[0-9]{2})(?:\s+(?P<credit>CR))?",
            body,
            flags=re.IGNORECASE,
        ))
        if not amount_matches:
            continue
        amount_match = amount_matches[-1]

        detail = body[:amount_match.start()].strip()
        # ICICI places Reward Points immediately before the amount. They are
        # integer values and are not part of the merchant/transaction detail.
        detail = re.sub(r"\s+\d+\s*$", "", detail).strip()
        if not detail:
            continue

        amount = _minor(amount_match.group("amount"))
        if amount <= 0:
            continue

        transaction_date = datetime.strptime(
            block.group("date"), "%d/%m/%Y"
        ).replace(hour=12, tzinfo=timezone.utc)

        is_credit = bool(amount_match.group("credit"))
        rows.append({
            "date": transaction_date,
            "amount_minor": amount,
            "type": "CREDIT" if is_credit else "DEBIT",
            "merchant": _icici_merchant(detail),
            "reference": block.group("serial").strip(),
            "payment_method": _icici_payment_method(detail),
            "category": "PAYMENT" if is_credit else "OTHER",
            "narration": detail,
        })

    if not rows:
        raise ValueError("no ICICI statement transactions were parsed")
    return metadata, rows
