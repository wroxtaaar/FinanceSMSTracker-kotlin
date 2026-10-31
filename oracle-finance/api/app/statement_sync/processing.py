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
    with connection() as conn:
        if conn.execute(
            "SELECT 1 FROM card_bill_evidence WHERE source_type='GMAIL_STATEMENT_PDF' AND source_key=?",
            (attachment_id,),
        ).fetchone():
            return
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
                _refresh_card_bill_from_parsed_pdf(bank, pdf_bytes, attachment_id, message)
                continue

            keys = _statement_keys(bank)
            if not keys:
                raise ValueError(f"{bank} statement password is not configured")

            parse_error = None
            for key in keys:
                try:
                    metadata, rows = _parse_statement_pdf(bank, pdf_bytes, key)
                    parse_error = None
                    break
                except Exception as exc:
                    parse_error = exc

            if parse_error is not None:
                raise parse_error

            # A statement PDF is the strongest bill source when the email
            # body omitted the total. The transaction parser and bill parser
            # are intentionally separate: the printed Total Amount Due must
            # never become a fake card purchase.
            is_credit_card_statement = metadata.get("account_type") == "CREDIT_CARD"
            if is_credit_card_statement:
                pdf_text = common._pdf_text(pdf_bytes, key)
                bill_amount = _extract_statement_bill_amount(pdf_text)
                if bill_amount is not None:
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

                # A credit-card statement is a historical snapshot. Its rows
                # are already reflected in the billed amount and must never be
                # imported as new active spend. Only the statement-level bill
                # snapshot is authoritative here. New post-statement card
                # transactions arrive through the normal transaction lanes.
                rows = []

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
                    accountType=metadata.get("account_type", "BANK_ACCOUNT"),
                    bank=metadata["bank"],
                    merchantOrPayee=row.get("merchant"),
                    accountLast4=metadata["account_last4"],
                    reference=row.get("reference"),
                    timestamp=timestamp,
                    category=row["category"],
                    confidence=0.99,
                )

                with connection() as conn:
                    existing_tx = _find_existing(
                        conn,
                        row,
                        {**metadata, "account_type": metadata.get("account_type", "BANK_ACCOUNT")},
                    )
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

                # Use the normal ledger path so the statement transaction
                # gets its balance adjustment exactly once. sync_transaction()
                # is idempotent and INSERT OR IGNORE protects repeated imports.
                inserted = sync_transaction(transaction)

                # Statement rows must update the account balance when they
                # are newly inserted. Keep the explicit application here
                # because statement imports intentionally bypass the normal
                # Gmail balance rules.
                if inserted:
                    with connection() as conn:
                        apply_transaction_to_account(conn, transaction, timestamp)

                        # Verify the ledger adjustment exists. Statement imports
                        # must never silently leave the account balance unchanged.
                        adjustment = conn.execute(
                            "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                            (transaction.id,),
                        ).fetchone()
                        if adjustment is None:
                            account = conn.execute(
                                """SELECT id FROM accounts
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
                            if account:
                                delta = (
                                    -transaction.amountMinor
                                    if transaction.accountType == "BANK_ACCOUNT"
                                    and transaction.type == "DEBIT"
                                    else transaction.amountMinor
                                    if transaction.accountType == "BANK_ACCOUNT"
                                    else transaction.amountMinor
                                    if transaction.type == "DEBIT"
                                    else -transaction.amountMinor
                                )
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
                    (                        attachment_id, message["id"], filename,
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
