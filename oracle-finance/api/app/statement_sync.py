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
        r"(?ms)Date\s+SerNo\.\s+Transaction Details.*?(?=^# International Spends\s*$|^Credit Limit \(Including cash\))",
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
        amount_match = re.search(
            r"(?P<amount>[0-9][0-9,]*\.\d{2})(?:\s+(?P<credit>CR))?\s*$",
            body,
            flags=re.IGNORECASE,
        )
        if not amount_match:
            continue

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
