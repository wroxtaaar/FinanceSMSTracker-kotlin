"""SBI Card statement parsing."""

from datetime import datetime, timezone
import re

from .common import _compact, _minor
from . import common


def _sbi_card_metadata(text):
    card = re.search(r"(?mi)Credit Card Number\s+.*?ABDUL WASIQ\s+X{2,}\s+X{2,}\s+X{2,}\s+XX(\d{2})", text)
    if not card:
        card = re.search(r"(?mi)XXXX\s+XXXX\s+XXXX\s+XX(\d{2})", text)
    if not card:
        raise ValueError("SBI Card number not found")

    # SBI statements mask all but the final two digits. Preserve a stable
    # four-digit identifier by zero-padding the visible suffix.
    suffix = card.group(1)
    return {
        "bank": "SBI",
        "account_type": "CREDIT_CARD",
        "account_last4": suffix.zfill(4),
        "account_last2": suffix,
        "currency": "INR",
        "opening_balance_minor": None,
    }


def parse_sbi_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)
    metadata = _sbi_card_metadata(text)

    period = re.search(
        r"(?mi)for Statement Period:\s*(\d{2}\s+[A-Za-z]{3}\s+\d{2})\s+to\s+(\d{2}\s+[A-Za-z]{3}\s+\d{2})",
        text,
    )
    if not period:
        raise ValueError("SBI statement period not found")

    table_start = text.find("Date\nAmount\nTransaction Details")
    if table_start < 0:
        table_start = text.find("Date Amount Transaction Details")
    if table_start < 0:
        raise ValueError("SBI transaction table not found")

    table = text[table_start:]
    stop_markers = [
        "Points Expiry Details",
        "SHOP & SMILE SUMMARY",
        "A day after the statement is generated",
    ]
    stop = len(table)
    for marker in stop_markers:
        idx = table.find(marker)
        if idx >= 0:
            stop = min(stop, idx)
    table = table[:stop]

    rows = []
    row_re = re.compile(
        r"(?m)^(?P<date>\d{2}\s+[A-Za-z]{3}\s+\d{2})\s+"
        r"(?P<detail>.+?)\s+"
        r"(?P<amount>[0-9][0-9,]*\.\d{2})\s+"
        r"(?P<direction>[CD])\s*$"
    )
    for match in row_re.finditer(table):
        amount = _minor(match.group("amount"))
        detail = _compact(match.group("detail"))
        if amount <= 0 or not detail:
            continue

        direction = "CREDIT" if match.group("direction") == "C" else "DEBIT"
        method = "CARD"
        upper = detail.upper()
        if "PAYMENT RECEIVED" in upper:
            method = "BANK_TRANSFER"
        elif "UPI" in upper:
            method = "UPI"
        elif "BBPS" in upper:
            method = "BILL_PAYMENT"

        rows.append({
            "date": datetime.strptime(
                match.group("date"), "%d %b %y"
            ).replace(hour=12, tzinfo=timezone.utc),
            "amount_minor": amount,
            "type": direction,
            "merchant": detail,
            "reference": None,
            "payment_method": method,
            "category": (
                "PAYMENT"
                if direction == "CREDIT"
                else ("CASHBACK" if "WAIVER" in upper or "CASHBACK" in upper else "OTHER")
            ),
            "narration": detail,
        })

    if not rows:
        raise ValueError("no SBI Card transactions were parsed")

    return metadata, rows
