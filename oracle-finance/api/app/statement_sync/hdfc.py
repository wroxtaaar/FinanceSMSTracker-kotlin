"""HDFC statement parsing."""

from datetime import datetime, timezone
import re

from .common import _compact, _minor
from . import common


def _hdfc_metadata(text):
    account = re.search(r"(?mi)\bAccount Number\s*:\s*(\d{8,20})\b", text)
    period = re.search(
        r"(?mi)\bStatement From\s*:\s*(\d{2}/\d{2}/\d{4})\s+To\s+(\d{2}/\d{2}/\d{4})",
        text,
    )
    currency = re.search(r"(?mi)\bCurrency\s*:\s*([A-Z]{3})\b", text)
    opening = re.search(r"(?mi)\bOpening Balance\s*:\s*([0-9,]+\.\d{2})", text)
    if not account:
        raise ValueError("HDFC account number not found")
    if not period:
        raise ValueError("HDFC statement period not found")
    return {
        "bank": "HDFC",
        "account_type": "BANK_ACCOUNT",
        "account_last4": account.group(1)[-4:],
        "currency": currency.group(1).upper() if currency else "INR",
        "statement_from": period.group(1),
        "statement_to": period.group(2),
        "opening_balance_minor": _minor(opening.group(1)) if opening else None,
    }


def _hdfc_card_metadata(text):
    card = re.search(r"(?mi)Credit Card No\.\s*\r?\n?\s*(\d{6,12})X+(\d{4})", text)
    if not card:
        card = re.search(r"(?mi)Credit Card No\.\s+(\d{6,12})X+(\d{4})", text)
    if not card:
        raise ValueError("HDFC credit card number not found")
    period = re.search(
        r"(?mi)Billing Period\s*\r?\n?\s*(\d{2}\s+[A-Za-z]{3},\s+\d{4})\s*-\s*(\d{2}\s+[A-Za-z]{3},\s+\d{4})",
        text,
    )
    return {
        "bank": "HDFC",
        "account_type": "CREDIT_CARD",
        "account_last4": card.group(2),
        "currency": "INR",
        "statement_from": period.group(1) if period else None,
        "statement_to": period.group(2) if period else None,
        "opening_balance_minor": None,
    }


def parse_hdfc_credit_card_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)
    metadata = _hdfc_card_metadata(text)

    table_start = text.find("Domestic Transactions")
    if table_start < 0:
        raise ValueError("HDFC credit-card transaction table not found")
    table = text[table_start:]

    rows = []
    row_re = re.compile(
        r"(?m)^(?P<date>\d{2}/\d{2}/\d{4})\|\s*"
        r"(?P<time>\d{2}:\d{2})\s+"
        r"(?P<detail>.+?)\s+(?P<credit>\+)?\s*C\s+"
        r"(?P<amount>[0-9][0-9,]*\.\d{2})\s+l\s*$"
    )
    for match in row_re.finditer(table):
        amount = _minor(match.group("amount"))
        detail = _compact(match.group("detail"))
        if amount <= 0 or not detail:
            continue
        upper = detail.upper()
        method = "CARD"
        if "UPI" in upper:
            method = "UPI"
        elif "BBPS" in upper or "BPPY" in upper:
            method = "BILL_PAYMENT"
        elif "EMI" in upper:
            method = "CARD"
        transaction_type = "CREDIT" if match.group("credit") else "DEBIT"
        rows.append({
            "date": datetime.strptime(
                f"{match.group('date')} {match.group('time')}",
                "%d/%m/%Y %H:%M",
            ).replace(tzinfo=timezone.utc),
            "amount_minor": amount,
            "type": transaction_type,
            "merchant": _compact(detail),
            "reference": (
                re.search(r"(?i)\bRef#\s*([A-Z0-9-]+)", detail).group(1)
                if re.search(r"(?i)\bRef#\s*([A-Z0-9-]+)", detail)
                else None
            ),
            "payment_method": method,
            "category": "PAYMENT" if transaction_type == "CREDIT" else "OTHER",
            "narration": detail,
        })

    if not rows:
        raise ValueError("no HDFC credit-card transactions were parsed")
    return metadata, rows


def _hdfc_merchant(narration):
    compact = _compact(re.sub(r"(?i)\s+Value Dt\b.*$", "", narration))
    # HDFC FT rows may put the beneficiary on the following line:
    # "FT-A2A - - - 0.00 200.00 1,050.00" followed by
    # "WBS SALARY ACCOUNT Value Dt ...". Keep the beneficiary text.
    if compact.upper().startswith("FT-"):
        compact = re.sub(r"(?i)^FT-[^\s]+(?:\s+-){1,3}\s*", "", compact).strip()
        compact = re.sub(r"(?i)\s+Value Dt\b.*$", "", compact).strip()
    if compact.upper().startswith("UPI-"):
        body = compact[4:].strip()
        if "@" in body:
            before_at = body.split("@", 1)[0].strip()
            if "-" in before_at:
                name = before_at.rsplit("-", 1)[0].strip()
                name = re.sub(r"-\d{6,}$", "", name).strip()
                if name:
                    return name
        return body or None
    if compact.upper().startswith("FT-"):
        segment = compact.rsplit(" - ", 1)[-1].strip()
        if re.search(r"(?i)\bACCOUNT\b$", segment):
            return segment
    if compact.upper().startswith("BAJAJFINOTP"):
        return "BAJAJFIN"
    return compact or None


def _hdfc_reference(narration):
    match = re.search(r"(?i)\bRef\s+([A-Z0-9][A-Z0-9/-]*)\b", narration)
    return match.group(1).rstrip(".,;)") if match else None


def _method(narration):
    value = _compact(narration).upper()
    if value.startswith("UPI-"):
        return "UPI"
    if value.startswith("FT-") or any(token in value for token in ("NEFT", "IMPS", "RTGS")):
        return "BANK_TRANSFER"
    return "OTHER"


def _parse_hdfc_rows(text, metadata):
    money = r"(?:[0-9][0-9,]*\.[0-9]{2}|-)"
    blocks = re.finditer(
        r"(?ms)^(?P<date>\d{2}/\d{2}/\d{4})\s+(?P<body>.*?)(?=^\d{2}/\d{2}/\d{4}\s+|^Page \d+ of \d+\s*$|\Z)",
        text,
    )
    
    rows = []
    previous_closing = metadata["opening_balance_minor"]
    for block in blocks:
        body = block.group("body").strip()
        lines = body.splitlines()
        if not lines:
            continue
    
        first_line = lines[0]
        amounts = list(re.finditer(rf"(?<![A-Za-z0-9])({money})", first_line))
        if len(amounts) < 3:
            continue
    
        withdrawal_value, deposit_value, closing_value = [item.group(1) for item in amounts[-3:]]
        narration = first_line[:amounts[-3].start()].strip()
        remainder = first_line[amounts[-1].end():].strip()
        if remainder:
            narration = _compact(f"{narration} {remainder}")
        if len(lines) > 1:
            narration = (narration + "\n" + "\n".join(lines[1:])).strip()
    
        withdrawals = _minor(withdrawal_value) if withdrawal_value != "-" else 0
        deposits = _minor(deposit_value) if deposit_value != "-" else 0
        closing = _minor(closing_value) if closing_value != "-" else None
        if withdrawals and deposits or closing is None:
            continue
    
        amount = withdrawals or deposits
        if amount <= 0:
            continue
    
        if previous_closing is not None:
            expected = previous_closing - withdrawals + deposits
            if expected != closing:
                raise ValueError(f"HDFC balance sequence mismatch on {block.group('date')}")
        previous_closing = closing
    
        payment_method = _method(narration)
        rows.append({
            "date": datetime.strptime(block.group("date"), "%d/%m/%Y").replace(hour=12, tzinfo=timezone.utc),
            "amount_minor": amount,
            "type": "DEBIT" if withdrawals else "CREDIT",
            "merchant": _hdfc_merchant(narration),
            "reference": _hdfc_reference(narration),
            "payment_method": payment_method,
            "category": "TRANSFER" if payment_method == "BANK_TRANSFER" else "OTHER",            "narration": _compact(narration),
        })
    
    if not rows:
        raise ValueError("no HDFC transactions were parsed")
    
    return rows


def parse_hdfc_statement(pdf_bytes, key):
    text = common._pdf_text(pdf_bytes, key)
    if re.search(r"(?mi)Millennia Credit Card Statement|Credit Card No\.", text):
        return parse_hdfc_credit_card_statement(pdf_bytes, key)
    metadata = _hdfc_metadata(text)
    return metadata, _parse_hdfc_rows(text, metadata)
