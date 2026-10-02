import base64
import hashlib
import io
import os
import re
from datetime import datetime, timezone

from .db import connection
from .ledger import apply_transaction_to_account, sync_evidence, sync_transaction
from .main_models import SyncEvidenceModel, SyncTransactionModel


_BANK_DOMAINS = {
    "HDFC": ("hdfcbank.net", "hdfcbank.bank.in"),
    "AXIS": ("axisbank.com", "axis.bank.in"),
    "ICICI": ("icicibank.com", "icici.bank.in"),
    "SBI": ("sbi.co.in", "sbicard.com"),
    "HSBC": ("hsbc.co.in", "hsbc.com"),
    "INDUSIND": ("indusind.com",),
}


def _secret(name):
    value = os.getenv(name, "").strip()
    if value:
        return value
    path = os.getenv(f"{name}_FILE", "").strip()
    if not path:
        return ""
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError:
        return ""


def _statement_key(bank):
    return _secret(f"{bank.upper()}_STATEMENT_SECRET")


def _headers(message):
    return {
        str(item.get("name", "")).lower(): str(item.get("value", ""))
        for item in (message.get("payload", {}).get("headers", []) or [])
        if isinstance(item, dict)
    }


def _statement_bank(message):
    from email.utils import parseaddr

    headers = _headers(message)
    sender = parseaddr(headers.get("from", ""))[1].lower()
    subject = headers.get("subject", "")
    combined = f"{sender}\n{subject}".upper()

    for bank, domains in _BANK_DOMAINS.items():
        if any(sender.endswith(f"@{domain}") or sender.endswith(f".{domain}") for domain in domains):
            return bank
        if re.search(rf"\b{re.escape(bank)}\b", combined):
            return bank
    return None


def _iter_parts(payload):
    if not isinstance(payload, dict):
        return
    for part in payload.get("parts", []) or []:
        if not isinstance(part, dict):
            continue
        yield part
        yield from _iter_parts(part)


def _pdf_attachments(message):
    result = []
    for part in _iter_parts(message.get("payload", {})):
        filename = (part.get("filename") or "").strip()
        mime_type = (part.get("mimeType") or "").lower()
        if filename.lower().endswith(".pdf") or mime_type == "application/pdf":
            result.append(part)
    return result


def _attachment_bytes(service, message, part):
    body = part.get("body") or {}
    data = body.get("data")
    if data:
        return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))

    attachment_id = body.get("attachmentId")
    if not attachment_id:
        raise ValueError("PDF attachment has no content")

    attachments_api = getattr(service.users().messages(), "attachments", None)
    if not callable(attachments_api):
        raise ValueError("Gmail attachment download is unavailable")

    response = attachments_api().get(
        userId="me",
        messageId=message["id"],
        id=attachment_id,
    ).execute()
    data = response.get("data")
    if not data:
        raise ValueError("Gmail returned an empty PDF")
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def _pdf_text(pdf_bytes, key):
    try:
        import pdfplumber
    except ImportError as exc:
        raise RuntimeError("pdfplumber is not installed") from exc

    if not key:
        raise ValueError("statement secret is not configured")

    try:
        with pdfplumber.open(io.BytesIO(pdf_bytes), password=key) as pdf:
            return "\n".join(page.extract_text() or "" for page in pdf.pages)
    except Exception as exc:
        detail = str(exc).lower()
        if "password" in detail or "decrypt" in detail or "encrypted" in detail:
            raise ValueError("statement could not be opened with the configured secret") from exc
        raise


def _minor(value):
    return int(round(float(value.replace(",", "")) * 100))


def _compact(value):
    return re.sub(r"\s+", " ", value or "").strip()


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
    text = _pdf_text(pdf_bytes, key)
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
            r"(?P<amount>[0-9][0-9,]*\\.\\d{2})(?:\\s+(?P<credit>CR))?",
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


def _axis_card_metadata(text):
    card = re.search(r"(?mi)Card No:\s+(\d{4})X+(\d{4})", text)
    if not card:
        card = re.search(r"(?mi)Your cheque should be payable to Axis Bank Card No\.\s*\n?\s*(\d{4})X+(\d{4})", text)
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
        if len(parts) >= 4:
            candidate = parts[3].strip()
            if candidate:
                return candidate
    return value or None


def _axis_method(detail):
    value = _compact(detail).upper()
    if value.startswith("UPI/"):
        return "UPI"
    if "BBPS" in value:
        return "BILL_PAYMENT"
    if "CASHBACK" in value:
        return "CASHBACK"
    if "AIRTELPAYMENTSBANKLTD" in value:
        return "BILL_PAYMENT"
    return "CARD"


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
    text = _pdf_text(pdf_bytes, key)
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
    text = _pdf_text(pdf_bytes, key)
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
                        else "OTHER"
                    ),
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
    text = _pdf_text(pdf_bytes, key)

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
        "account_last4": account.group(1)[-4:],
        "currency": currency.group(1).upper() if currency else "INR",
        "statement_from": period.group(1),
        "statement_to": period.group(2),
        "opening_balance_minor": _minor(opening.group(1)) if opening else None,
    }


def _hdfc_merchant(narration):
    compact = _compact(re.sub(r"(?i)\s+Value Dt\b.*$", "", narration))
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


def parse_hdfc_statement(pdf_bytes, key):
    text = _pdf_text(pdf_bytes, key)
    metadata = _hdfc_metadata(text)
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
            "category": "TRANSFER" if payment_method == "BANK_TRANSFER" else "OTHER",
            "narration": _compact(narration),
        })

    if not rows:
        raise ValueError("no HDFC transactions were parsed")
    return metadata, rows


def _find_existing(conn, row, metadata):
    timestamp = int(row["date"].timestamp() * 1000)
    candidates = conn.execute(
        """SELECT * FROM transactions
           WHERE status='ACTIVE' AND duplicate_of IS NULL
             AND currency=? AND amount_minor=? AND type=?
             AND ABS(timestamp-?) <= ?
             AND UPPER(TRIM(COALESCE(bank,'')))=?
             AND TRIM(COALESCE(account_last4,''))=?
             AND UPPER(TRIM(COALESCE(account_type,'')))=?
           ORDER BY ABS(timestamp-?) LIMIT 10""",
        (
            metadata["currency"], row["amount_minor"], row["type"], timestamp,
            2 * 24 * 60 * 60 * 1000, metadata["bank"], metadata["account_last4"],
            metadata["account_type"], timestamp,
        ),
    ).fetchall()

    # SBI Card PDFs expose only the final two card digits. If the local
    # transaction has the full four-digit suffix (for example 7345), allow
    # the masked statement to match on those visible final two digits.
    if not candidates and metadata.get("account_last2"):
        candidates = conn.execute(
            """SELECT * FROM transactions
               WHERE status='ACTIVE' AND duplicate_of IS NULL
                 AND currency=? AND amount_minor=? AND type=?
                 AND ABS(timestamp-?) <= ?
                 AND UPPER(TRIM(COALESCE(bank,'')))=?
                 AND TRIM(COALESCE(account_last4,'')) LIKE ?
                 AND UPPER(TRIM(COALESCE(account_type,'')))=?
               ORDER BY ABS(timestamp-?) LIMIT 10""",
            (
                metadata["currency"], row["amount_minor"], row["type"], timestamp,
                2 * 24 * 60 * 60 * 1000, metadata["bank"],
                "%" + metadata["account_last2"], metadata["account_type"], timestamp,
            ),
        ).fetchall()

    if len(candidates) == 1:
        return candidates[0]

    reference = (row.get("reference") or "").casefold()
    if reference:
        exact = [item for item in candidates if (item["reference"] or "").casefold() == reference]
        if len(exact) == 1:
            return exact[0]

    merchant = (row.get("merchant") or "").casefold()
    if merchant:
        exact = [item for item in candidates if (item["merchant_or_payee"] or "").casefold() == merchant]
        if len(exact) == 1:
            return exact[0]

    return None


def process_statement_attachments(service, message):
    headers = _headers(message)
    subject = headers.get("subject", "")
    attachments = _pdf_attachments(message)
    stats = {
        "attachmentsScanned": 0,
        "attachmentsParsed": 0,
        "transactionsAdded": 0,
        "transactionsMatched": 0,
        "errors": [],
    }

    if not attachments:
        return stats

    bank = _statement_bank(message)
    if not bank:
        return stats

    for part in attachments:
        filename = (part.get("filename") or "statement.pdf").strip() or "statement.pdf"
        if not re.search(r"(?i)\bstatement\b", subject) and "statement" not in filename.casefold():
            continue

        stats["attachmentsScanned"] += 1
        pdf_bytes = b""
        try:
            pdf_bytes = _attachment_bytes(service, message, part)
            content_hash = hashlib.sha256(pdf_bytes).hexdigest()
            attachment_id = f"{message['id']}:{filename}:{content_hash[:16]}"

            with connection() as conn:
                existing = conn.execute(
                    "SELECT status FROM gmail_attachments WHERE id=?",
                    (attachment_id,),
                ).fetchone()
            if existing and existing["status"] == "PARSED":
                continue

            if bank == "HDFC":
                metadata, rows = parse_hdfc_statement(
                    pdf_bytes,
                    _statement_key(bank),
                )
            elif bank == "ICICI":
                metadata, rows = parse_icici_statement(
                    pdf_bytes,
                    _statement_key(bank),
                )
            elif bank == "SBI":
                metadata, rows = parse_sbi_statement(
                    pdf_bytes,
                    _statement_key(bank),
                )
            elif bank == "INDUSIND":
                metadata, rows = parse_indusind_statement(
                    pdf_bytes,
                    _statement_key(bank),
                )
            elif bank == "AXIS":
                metadata, rows = parse_axis_statement(
                    pdf_bytes,
                    _statement_key(bank),
                )
            else:
                raise ValueError(f"statement parser for {bank} is not implemented")

            for index, row in enumerate(rows):
                timestamp = int(row["date"].timestamp() * 1000)
                row_key = f"{attachment_id}:{index}:{timestamp}:{row['amount_minor']}:{row['type']}"
                tx_id = "statement:" + hashlib.sha256(row_key.encode()).hexdigest()[:32]
                evidence_id = "statement-evidence:" + hashlib.sha256(row_key.encode()).hexdigest()[:32]

                transaction = SyncTransactionModel(
                    id=tx_id,
                    amountMinor=row["amount_minor"],
                    currency=metadata["currency"],
                    type=row["type"],
                    paymentMethod=row["payment_method"],
                    accountType=metadata["account_type"],
                    bank=metadata["bank"],
                    merchantOrPayee=row.get("merchant"),
                    accountLast4=metadata["account_last4"],
                    reference=row.get("reference"),
                    timestamp=timestamp,
                    category=row["category"],
                    confidence=0.99,
                )

                with connection() as conn:
                    existing_tx = _find_existing(conn, row, metadata)
                    if existing_tx:
                        conn.execute(
                            """UPDATE transactions
                               SET merchant_or_payee=COALESCE(NULLIF(merchant_or_payee,''),?),
                                   reference=COALESCE(NULLIF(reference,''),?)
                               WHERE id=?""",
                            (row.get("merchant"), row.get("reference"), existing_tx["id"]),
                        )

                if existing_tx:
                    evidence = SyncEvidenceModel(
                        id=evidence_id,
                        sourceType="STATEMENT",
                        sourceId=f"{attachment_id}:{index}",
                        status="MATCHED",
                        observedAt=timestamp,
                        transactionId=existing_tx["id"],
                        matchedTransactionId=existing_tx["id"],
                        amountMinor=row["amount_minor"],
                        currency=metadata["currency"],
                        direction=row["type"],
                        bankProvider=metadata["bank"],
                        accountLast4=metadata["account_last4"],
                        reference=row.get("reference"),
                        contentHash=hashlib.sha256(row["narration"].encode()).hexdigest(),
                        confidence=0.99,
                    )
                    sync_evidence(evidence)
                    stats["transactionsMatched"] += 1
                    continue

                sync_transaction(transaction, apply_balance=False)
                with connection() as conn:
                    apply_transaction_to_account(conn, transaction, timestamp)

                sync_evidence(
                    SyncEvidenceModel(
                        id=evidence_id,
                        sourceType="STATEMENT",
                        sourceId=f"{attachment_id}:{index}",
                        status="UNMATCHED",
                        observedAt=timestamp,
                        transactionId=transaction.id,
                        matchedTransactionId=None,
                        amountMinor=row["amount_minor"],
                        currency=metadata["currency"],
                        direction=row["type"],
                        bankProvider=metadata["bank"],
                        accountLast4=metadata["account_last4"],
                        reference=row.get("reference"),
                        contentHash=hashlib.sha256(row["narration"].encode()).hexdigest(),
                        confidence=0.99,
                    )
                )
                stats["transactionsAdded"] += 1

            with connection() as conn:
                conn.execute(
                    """INSERT INTO gmail_attachments
                       (id,message_id,filename,mime_type,content_hash,bank,status,
                        transaction_count,error,created_at)
                       VALUES(?,?,?,?,?,?, 'PARSED',?,?,?)""",
                    (
                        attachment_id, message["id"], filename,
                        part.get("mimeType") or "application/pdf",
                        content_hash, metadata["bank"], len(rows), None,
                        int(datetime.now(timezone.utc).timestamp() * 1000),
                    ),
                )
            stats["attachmentsParsed"] += 1

        except Exception as exc:
            stats["errors"].append(f"{filename}: {str(exc)[:300]}")
            try:
                content_hash = hashlib.sha256(pdf_bytes).hexdigest() if pdf_bytes else ""
                attachment_id = f"{message['id']}:{filename}:{content_hash[:16] or 'error'}"
                with connection() as conn:
                    conn.execute(
                        """INSERT INTO gmail_attachments
                           (id,message_id,filename,mime_type,content_hash,bank,status,
                            transaction_count,error,created_at)
                           VALUES(?,?,?,?,?,?, 'ERROR',0,?,?,?)""",
                        (
                            attachment_id, message["id"], filename,
                            part.get("mimeType") or "application/pdf",
                            content_hash, bank, str(exc)[:500],
                            int(datetime.now(timezone.utc).timestamp() * 1000),
                        ),
                    )
            except Exception:
                pass

    return stats
