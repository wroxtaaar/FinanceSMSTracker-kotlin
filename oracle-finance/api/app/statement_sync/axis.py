"""Axis statement parsing."""

from datetime import datetime, timezone
import io
import re

from .common import _compact, _minor
from . import common


def _axis_metadata(text):
    account = re.search(r"(?mi)Account No\.\s+X{4,}(\d{4})\s+-\s+Quick View", text)
    card = re.search(r"(?mi)Card No:\s+(\d{4})X+\d{4}", text)
    currency = re.search(r"(?mi)Currency\s*:\s*([A-Z]{3})\b", text)
    opening = re.search(r"(?mi)Opening Balance\s+([0-9,]+\.\d{2})", text)

    if account:
        return {
            "bank": "AXIS",
            "account_type": "BANK_ACCOUNT",
            "account_last4": account.group(1),
            "currency": currency.group(1).upper() if currency else "INR",
            "opening_balance_minor": _minor(opening.group(1)) if opening else None,
        }

    if card:
        return {
            "bank": "AXIS",
            "account_type": "CREDIT_CARD",
            "account_last4": card.group(2) if card.lastindex and card.lastindex >= 2 else card.group(1)[-4:],
            "currency": "INR",
            "opening_balance_minor": None,
        }

    raise ValueError("Axis account/card number not found")


def _axis_bank_metadata(text):
    account = re.search(r"(?mi)Account No\.\s+X+(\d{4})\s+-\s+Quick View", text)
    currency = re.search(r"(?mi)Currency\s*:\s*([A-Z]{3})\b", text)
    opening = re.search(r"(?mi)Opening Balance\s+([0-9,]+\.\d{2})", text)
    if not account:
        raise ValueError("Axis bank account number not found")
    return {
        "bank": "AXIS",
        "account_type": "BANK_ACCOUNT",
        "account_last4": account.group(1),
        "currency": currency.group(1).upper() if currency else "INR",
        "opening_balance_minor": _minor(opening.group(1)) if opening else None,
    }


def _axis_card_metadata(text):
    card = re.search(r"(?mi)Card No:\s+(\d{4})X+(\d{4})", text)
    if not card:
        raise ValueError("Axis credit card number not found")
    return {
        "bank": "AXIS",
        "account_type": "CREDIT_CARD",
        "account_last4": card.group(2),
        "currency": "INR",
        "opening_balance_minor": None,
    }


def _axis_merchant(detail):
    value = _compact(detail)
    if value.upper().startswith("UPI/"):
        parts = value.split("/")
        if len(parts) >= 4 and parts[3].strip():
            return parts[3].strip()
    return value or None


def _axis_method(detail):
    value = _compact(detail).upper()
    if value.startswith("UPI/"):
        return "UPI"
    if "BBPS" in value:
        return "BILL_PAYMENT"
    if "CASHBACK" in value:
        return "CASHBACK"
    if "EMI" in value:
        return "BANK_TRANSFER"
    return "CARD"


def _parse_axis_bank_pdf(pdf_bytes, key):
    try:
        import pdfplumber
    except ImportError as exc:
        raise RuntimeError("pdfplumber is not installed") from exc

    with pdfplumber.open(io.BytesIO(pdf_bytes), password=key) as pdf:
        full_text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        metadata = _axis_bank_metadata(full_text)

        rows = []
        for page in pdf.pages:
            words = page.extract_words()
            lines = {}
            for word in words:
                top = round(float(word["top"]), 1)
                lines.setdefault(top, []).append(word)

            ordered = sorted(lines.items(), key=lambda item: item[0])
            current = None

            def flush():
                nonlocal current
                if not current:
                    return
                detail = _compact(" ".join(current["detail"]))
                if not detail:
                    current = None
                    return

                withdrawal = current.get("withdrawal")
                deposit = current.get("deposit")
                balance = current.get("balance")
                if withdrawal is None and deposit is None:
                    current = None
                    return

                if withdrawal is not None and deposit is not None:
                    raise ValueError(
                        f"Axis statement has both withdrawal and deposit on {current['date']}"
                    )

                amount = withdrawal if withdrawal is not None else deposit
                transaction_type = "DEBIT" if withdrawal is not None else "CREDIT"

                rows.append({
                    "date": datetime.strptime(
                        current["date"], "%d-%m-%Y"
                    ).replace(hour=12, tzinfo=timezone.utc),
                    "amount_minor": amount,
                    "type": transaction_type,
                    "merchant": _axis_merchant(detail),
                    "reference": None,
                    "payment_method": _axis_method(detail),
                    "category": (
                        "TRANSFER"
                        if detail.upper().startswith("UPI/P2A/")
                        else "OTHER"                    ),
                    "narration": detail,
                    "_balance_minor": balance,
                })
                current = None

            for _, line_words in ordered:
                line_words.sort(key=lambda w: float(w["x0"]))
                line_text = _compact(" ".join(w["text"] for w in line_words))

                date_match = re.match(r"^(\d{2}-\d{2}-\d{4})\b", line_text)
                if date_match:
                    flush()
                    current = {
                        "date": date_match.group(1),
                        "detail": [],
                        "withdrawal": None,
                        "deposit": None,
                        "balance": None,
                    }

                if current is None:
                    continue

                # The statement's coordinates place Withdrawals around x=291,
                # Deposits around x=367, and Balance around x=441. We use
                # these columns rather than guessing debit/credit from narration.
                for word in line_words:
                    value = word["text"].replace(",", "")
                    if not re.fullmatch(r"\d+\.\d{2}", value):
                        continue
                    number = _minor(value)
                    x0 = float(word["x0"])
                    if 285 <= x0 < 365:
                        current["withdrawal"] = number
                    elif 365 <= x0 < 440:
                        current["deposit"] = number
                    elif 440 <= x0 < 495:
                        current["balance"] = number

                # Keep transaction description but not numeric column values.
                for word in line_words:
                    x0 = float(word["x0"])
                    if x0 < 285 and not re.fullmatch(r"\d{2}-\d{2}-\d{4}", word["text"]):
                        current["detail"].append(word["text"])

            flush()

        if not rows:
            raise ValueError("no Axis bank transactions were parsed")

        for row in rows:
            row.pop("_balance_minor", None)
        return metadata, rows


def parse_axis_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)

    if re.search(r"(?mi)^Detailed Statement for a/c no\.", text):
        return _parse_axis_bank_pdf(pdf_bytes, key)

    metadata = _axis_card_metadata(text)
    table_start = text.find("DATE TRANSACTION DETAILS MERCHANT CATEGORY AMOUNT")
    if table_start < 0:
        raise ValueError("Axis credit-card transaction table not found")
    table = text[table_start:]

    rows = []
    blocks = re.finditer(
        r"(?ms)^(?P<date>\d{2}/\d{2}/\d{4})\s+"
        r"(?P<body>.*?)(?=^\d{2}/\d{2}/\d{4}\s+|^\*{4} End of Statement|^Airtel Axis Bank|\Z)",
        table,
    )
    for block in blocks:
        body = _compact(block.group("body"))
        match = re.search(
            r"(?P<amount>[0-9][0-9,]*\.\d{2})\s+(?P<direction>Dr|Cr)\b",
            body,
            re.I,
        )
        if not match:
            continue
        amount = _minor(match.group("amount"))
        detail = body[:match.start()].strip()
        if amount <= 0 or not detail:
            continue
        direction = "CREDIT" if match.group("direction").upper() == "CR" else "DEBIT"
        rows.append({
            "date": datetime.strptime(
                block.group("date"), "%d/%m/%Y"
            ).replace(hour=12, tzinfo=timezone.utc),
            "amount_minor": amount,
            "type": direction,
            "merchant": _axis_merchant(detail),
            "reference": None,
            "payment_method": _axis_method(detail),
            "category": (
                "CASHBACK"
                if "CASHBACK" in detail.upper()
                else ("PAYMENT" if direction == "CREDIT" else "OTHER")
            ),
            "narration": detail,
        })

    if not rows:
        raise ValueError("no Axis credit-card transactions were parsed")
    return metadata, rows
