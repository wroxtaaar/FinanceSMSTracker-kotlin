"""Statement attachment import: dispatch, de-duplication and card-bill refresh."""

from datetime import datetime, timezone
import hashlib
import re

from ..db import connection
from ..ledger import apply_transaction_to_account, sync_card_bill, sync_evidence, sync_transaction
from ..main_models import SyncEvidenceModel, SyncTransactionModel
from .axis import parse_axis_statement
from .common import _attachment_bytes, _extract_statement_bill_amount, _headers, _pdf_attachments, _statement_bank, _statement_keys
from .hdfc import parse_hdfc_statement
from .icici import parse_icici_statement
from .indusind import parse_indusind_statement
from .sbi import parse_sbi_statement
from . import common


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


def _parse_statement_pdf(bank, pdf_bytes, key):
    if bank == "HDFC":
        return parse_hdfc_statement(pdf_bytes, key)
    if bank == "ICICI":
        return parse_icici_statement(pdf_bytes, key)
    if bank == "SBI":
        return parse_sbi_statement(pdf_bytes, key)
    if bank == "INDUSIND":
        return parse_indusind_statement(pdf_bytes, key)
    if bank == "AXIS":
        return parse_axis_statement(pdf_bytes, key)
    raise ValueError(f"statement parser for {bank} is not implemented")


def _refresh_card_bill_from_parsed_pdf(bank, pdf_bytes, attachment_id, message):
    """Re-derive the card bill from a PDF whose rows were already imported.

    An attachment is imported once and then skipped, so a bill that was read
    wrongly (or whose record was cleared) could never be corrected. This reads
    only the printed Total Amount Due; the statement rows are left alone.
    """
    for key in _statement_keys(bank):
        try:
            metadata, _rows = _parse_statement_pdf(bank, pdf_bytes, key)
            if metadata.get("account_type") != "CREDIT_CARD":
                return
            amount = _extract_statement_bill_amount(common._pdf_text(pdf_bytes, key))
            if amount is None:
                return
            sync_card_bill({
                "sourceType": "GMAIL_STATEMENT_PDF",
                "sourceKey": attachment_id,
                "timestamp": int(message.get("internalDate", "0")),
                "amountMinor": amount,
                "currency": metadata.get("currency", "INR"),
                "bank": metadata.get("bank"),
                "accountLast4": metadata.get("account_last4"),
                "accountLast2": metadata.get("account_last2"),
                "confidence": 1.0,
            })
            return
        except Exception:
            continue


def _card_statement_fallback(bank, pdf_bytes, keys):
    """(metadata, rows, key) for a credit-card PDF whose rows did not parse."""
    for key in keys:
        try:
            text = common._pdf_text(pdf_bytes, key)
        except Exception:
            continue
        if _extract_statement_bill_amount(text) is None:
            continue
        card = re.search(
            r"(?<![A-Za-z0-9])(?:(?:X{2,}|\*{2,}|[•·]{2,})[\s-]*){1,4}(\d{2,4})(?![0-9])", text
        )
        if not card or "card" not in text.lower():
            continue
        digits = card.group(1)
        return (
            {
                "bank": bank, "account_type": "CREDIT_CARD", "account_last4": digits.zfill(4),
                "account_last2": digits[-2:], "currency": "INR", "opening_balance_minor": None,
            },
            [],
            key,
        )
    return None


def _now_ms():
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _record_attachment(message, filename, part, attachment_id, content_hash, bank, status,
                       transaction_count=0, error=None):
    with connection() as conn:
        conn.execute(
            """INSERT INTO gmail_attachments
               (id,message_id,filename,mime_type,content_hash,bank,status,
                transaction_count,error,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (
                attachment_id, message["id"], filename,
                part.get("mimeType") or "application/pdf",
                content_hash, bank, status, transaction_count, error, _now_ms(),
            ),
        )


def _parse_with_any_key(bank, pdf_bytes, keys):
    """Parse the statement with each configured password until one works.

    A card statement's rows are never imported, so a row-table layout we cannot
    read must not hide the printed bill: fall back to the bill-only reader.
    Returns (metadata, rows, key); raises the parse error if nothing can read it.
    """
    parse_error = None
    for key in keys:
        try:
            metadata, rows = _parse_statement_pdf(bank, pdf_bytes, key)
            return metadata, rows, key
        except Exception as exc:
            parse_error = exc
    fallback = _card_statement_fallback(bank, pdf_bytes, keys)
    if fallback is None:
        raise parse_error
    return fallback


def _sync_statement_bill(metadata, pdf_bytes, key, attachment_id, message):
    """The statement PDF is the strongest bill source when the e-mail body omits
    the total. The transaction parser and bill parser are intentionally
    separate: the printed Total Amount Due must never become a fake purchase."""
    bill_amount = _extract_statement_bill_amount(common._pdf_text(pdf_bytes, key))
    if bill_amount is None:
        return
    sync_card_bill({
        "sourceType": "GMAIL_STATEMENT_PDF",
        "sourceKey": attachment_id,
        "timestamp": int(message.get("internalDate", "0")),
        "amountMinor": bill_amount,
        "currency": metadata.get("currency", "INR"),
        "bank": metadata.get("bank"),
        "accountLast4": metadata.get("account_last4"),
        "accountLast2": metadata.get("account_last2"),
        "confidence": 1.0,
    })


def _statement_evidence(status, row, metadata, attachment_id, index, evidence_id, timestamp,
                        transaction_id, matched_transaction_id):
    return SyncEvidenceModel(
        id=evidence_id,
        sourceType="STATEMENT",
        sourceId=f"{attachment_id}:{index}",
        status=status,
        observedAt=timestamp,
        transactionId=transaction_id,
        matchedTransactionId=matched_transaction_id,
        amountMinor=row["amount_minor"],
        currency=metadata["currency"],
        direction=row["type"],
        bankProvider=metadata["bank"],
        accountLast4=metadata["account_last4"],
        reference=row.get("reference"),
        contentHash=hashlib.sha256(row["narration"].encode()).hexdigest(),
        confidence=0.99,
    )


def _balance_delta(transaction):
    """Bank: a debit lowers the balance. Card: a debit raises what is owed."""
    amount = transaction.amountMinor
    if transaction.accountType == "BANK_ACCOUNT":
        return -amount if transaction.type == "DEBIT" else amount
    return amount if transaction.type == "DEBIT" else -amount


def _ensure_balance_adjustment(transaction, timestamp):
    """A newly imported statement row must never silently leave the balance unchanged.

    Rows dated on or before the account's last manual balance edit are history
    the verified balance already covers, so they are not replayed.
    """
    with connection() as conn:
        apply_transaction_to_account(conn, transaction, timestamp)
        if conn.execute(
            "SELECT 1 FROM balance_adjustments WHERE transaction_id=?", (transaction.id,)
        ).fetchone():
            return
        account = conn.execute(
            """SELECT id,balance_reconciled_at FROM accounts
               WHERE account_type=? AND currency=?
                 AND UPPER(TRIM(COALESCE(bank,'')))=?
                 AND TRIM(COALESCE(last4,''))=?
               LIMIT 1""",
            (
                transaction.accountType,
                transaction.currency,
                (transaction.bank or "").strip().upper(),
                (transaction.accountLast4 or "").strip(),
            ),
        ).fetchone()
        if not account:
            return
        reconciled_at = int(account["balance_reconciled_at"] or 0)
        if reconciled_at and transaction.timestamp <= reconciled_at:
            return
        delta = _balance_delta(transaction)
        conn.execute(
            """INSERT INTO balance_adjustments
               (transaction_id,account_id,delta_minor,applied_at)
               VALUES(?,?,?,?)""",
            (transaction.id, account["id"], delta, timestamp),
        )
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
            (delta, timestamp, account["id"]),
        )


def _import_statement_row(row, index, metadata, attachment_id, stats):
    timestamp = int(row["date"].timestamp() * 1000)
    row_key = f"{attachment_id}:{index}:{timestamp}:{row['amount_minor']}:{row['type']}"
    digest = hashlib.sha256(row_key.encode()).hexdigest()[:32]
    tx_id = "statement:" + digest
    evidence_id = "statement-evidence:" + digest
    account_metadata = {**metadata, "account_type": metadata.get("account_type", "BANK_ACCOUNT")}

    with connection() as conn:
        existing_tx = _find_existing(conn, row, account_metadata)
        if existing_tx:
            conn.execute(
                """UPDATE transactions
                   SET merchant_or_payee=COALESCE(NULLIF(merchant_or_payee,''),?),
                       reference=COALESCE(NULLIF(reference,''),?)
                   WHERE id=?""",
                (row.get("merchant"), row.get("reference"), existing_tx["id"]),
            )

    if existing_tx:
        sync_evidence(_statement_evidence(
            "MATCHED", row, metadata, attachment_id, index, evidence_id, timestamp,
            existing_tx["id"], existing_tx["id"],
        ))
        stats["transactionsMatched"] += 1
        return

    transaction = SyncTransactionModel(
        id=tx_id,
        amountMinor=row["amount_minor"],
        currency=metadata["currency"],
        type=row["type"],
        paymentMethod=row["payment_method"],
        accountType=account_metadata["account_type"],
        bank=metadata["bank"],
        merchantOrPayee=row.get("merchant"),
        accountLast4=metadata["account_last4"],
        reference=row.get("reference"),
        timestamp=timestamp,
        category=row["category"],
        confidence=0.99,
    )
    # The normal ledger path gives the row its balance adjustment exactly once;
    # sync_transaction() is idempotent and INSERT OR IGNORE protects re-imports.
    if sync_transaction(transaction):
        _ensure_balance_adjustment(transaction, timestamp)

    sync_evidence(_statement_evidence(
        "UNMATCHED", row, metadata, attachment_id, index, evidence_id, timestamp,
        transaction.id, None,
    ))
    stats["transactionsAdded"] += 1


def _process_statement_attachment(message, part, bank, filename, pdf_bytes, stats):
    content_hash = hashlib.sha256(pdf_bytes).hexdigest()
    attachment_id = f"{message['id']}:{filename}:{content_hash[:16]}"

    with connection() as conn:
        existing = conn.execute(
            "SELECT status FROM gmail_attachments WHERE id=?", (attachment_id,)
        ).fetchone()
    if existing and existing["status"] == "PARSED":
        _refresh_card_bill_from_parsed_pdf(bank, pdf_bytes, attachment_id, message)
        return

    keys = _statement_keys(bank)
    if not keys:
        raise ValueError(f"{bank} statement password is not configured")

    metadata, rows, key = _parse_with_any_key(bank, pdf_bytes, keys)

    if metadata.get("account_type") == "CREDIT_CARD":
        _sync_statement_bill(metadata, pdf_bytes, key, attachment_id, message)
        # A credit-card statement is a historical snapshot: its rows are already
        # in the billed amount and must never be imported as new active spend.
        # New post-statement card transactions arrive through the normal lanes.
        rows = []

    for index, row in enumerate(rows):
        _import_statement_row(row, index, metadata, attachment_id, stats)

    _record_attachment(message, filename, part, attachment_id, content_hash,
                       metadata["bank"], "PARSED", len(rows))
    stats["attachmentsParsed"] += 1


def _record_attachment_error(message, part, filename, bank, pdf_bytes, exc):
    try:
        content_hash = hashlib.sha256(pdf_bytes).hexdigest() if pdf_bytes else ""
        attachment_id = f"{message['id']}:{filename}:{content_hash[:16] or 'error'}"
        _record_attachment(message, filename, part, attachment_id, content_hash, bank,
                           "ERROR", 0, str(exc)[:500])
    except Exception:
        pass


def process_statement_attachments(service, message):
    subject = _headers(message).get("subject", "")
    stats = {
        "attachmentsScanned": 0,
        "attachmentsParsed": 0,
        "transactionsAdded": 0,
        "transactionsMatched": 0,
        "errors": [],
    }

    attachments = _pdf_attachments(message)
    bank = _statement_bank(message) if attachments else None
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
            _process_statement_attachment(message, part, bank, filename, pdf_bytes, stats)
        except Exception as exc:
            stats["errors"].append(f"{filename}: {str(exc)[:300]}")
            _record_attachment_error(message, part, filename, bank, pdf_bytes, exc)

    return stats
