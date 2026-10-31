"""IndusInd statement parsing."""

from datetime import datetime, timezone
import re

from .common import _compact, _minor
from . import common


def _indusind_card_metadata(text):
    card = re.search(r"(?mi)Credit Card No\.\s*[\r\n]+\s*(\d{4})X+(\d{4})", text)
    if not card:
        card = re.search(r"(?mi)Credit Card No\.\s*(\d{4})X+(\d{4})", text)
    if not card:
        raise ValueError("IndusInd credit card number not found")
    return {
        "bank": "INDUSIND",
        "account_type": "CREDIT_CARD",
        "account_last4": card.group(2),
        "currency": "INR",
        "opening_balance_minor": None,
    }


def parse_indusind_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)
    metadata = _indusind_card_metadata(text)

    table_start = text.find("Date TransactionDetails Merchant Category Reward")
    if table_start < 0:
        table_start = text.find("Date Transaction Details Merchant Category Reward")
    if table_start < 0:
        raise ValueError("IndusInd transaction table not found")

    table = text[table_start:]
    stop = len(table)
    for marker in ("Total ", "Rewards", "IMPORTANT MESSAGES"):
        idx = table.find(marker)
        if idx > 0:
            stop = min(stop, idx)
    table = table[:stop]

    rows = []
    row_re = re.compile(
        r"(?ms)^(?P<date>\d{2}/\d{2}/\d{4})\s+"
        r"(?P<detail>.+?)\s+"
        r"(?P<amount>[0-9][0-9,]*\.\d{2})\s+"
        r"(?P<direction>CR|DR)\b"
    )
    for match in row_re.finditer(table):
        amount = _minor(match.group("amount"))
        detail = _compact(match.group("detail"))
        if amount <= 0 or not detail:
            continue
        direction = "CREDIT" if match.group("direction") == "CR" else "DEBIT"
        upper = detail.upper()
        method = "BANK_TRANSFER" if "BBPS" in upper or "PAYMENT" in upper else "CARD"
        rows.append({
            "date": datetime.strptime(
                match.group("date"), "%d/%m/%Y"
            ).replace(hour=12, tzinfo=timezone.utc),
            "amount_minor": amount,
            "type": direction,
            "merchant": detail,
            "reference": None,
            "payment_method": method,
            "category": "PAYMENT" if direction == "CREDIT" else "OTHER",
            "narration": detail,
        })

    if not rows:
        raise ValueError("no IndusInd credit-card transactions were parsed")
    return metadata, rows
