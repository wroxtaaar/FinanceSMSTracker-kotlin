"""One-off repairs of Gmail rows written by older parser versions. Run once per API process by gmail_sync."""
import re
import time
from .db import connection
from .ledger import apply_transaction_to_account, void_transaction
from .gmail_parsing import parse_bank_email


def _repair_legacy_unidentified_hdfc_gmail_transactions():
    """Void old HDFC Gmail rows that have no transaction identity.

    These rows were accepted by older parsers using only amount, bank and
    account-last4. A real HDFC UPI transaction email should expose either a
    reference or a counterparty/payee. Notification rows are excluded because
    they are a different source and may legitimately arrive before enrichment.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT DISTINCT
                t.id AS transaction_id,
                gm.id AS gmail_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            JOIN gmail_messages gm
              ON (
                   gm.id = e.source_id
                   OR e.source_id LIKE 'imap:%:' || gm.id
                 )
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND UPPER(TRIM(COALESCE(t.bank,''))) = 'HDFC'
              AND UPPER(TRIM(COALESCE(t.account_type,''))) = 'BANK_ACCOUNT'
              AND UPPER(TRIM(COALESCE(t.type,''))) = 'DEBIT'
              AND UPPER(TRIM(COALESCE(t.payment_method,''))) = 'UPI'
              AND (t.reference IS NULL OR TRIM(t.reference) = '')
              AND (
                   t.merchant_or_payee IS NULL
                   OR TRIM(t.merchant_or_payee) = ''
                   OR TRIM(t.merchant_or_payee) = '-'
              )
            """
        ).fetchall()

    for row in rows:
        result = void_transaction(row["transaction_id"])
        if result.get("status") not in ("VOIDED", "ALREADY_VOIDED"):
            continue
        with connection() as conn:
            conn.execute(
                "UPDATE gmail_messages SET status='PENDING' WHERE id=?",
                (row["gmail_id"],),
            )
        repaired += 1

    return repaired


def _repair_legacy_axis_credit_direction_conflicts():
    """Retire old Axis Gmail rows that were parsed as debits from credit alerts.

    Older parser versions could let unrelated footer text flip an Axis bank
    account credit into DEBIT. The subject "was credited to your A/c." is a
    strong source-level direction signal. Void the stale row and mark its Gmail
    message PENDING so the current parser recreates the correct CREDIT row
    during the same sync pass.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT DISTINCT
                t.id AS transaction_id,
                gm.id AS gmail_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            JOIN gmail_messages gm
              ON (
                   gm.id = e.source_id
                   OR e.source_id LIKE 'imap:%:' || gm.id
                 )
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND UPPER(TRIM(COALESCE(t.bank,''))) = 'AXIS'
              AND UPPER(TRIM(COALESCE(t.account_type,''))) = 'BANK_ACCOUNT'
              AND UPPER(TRIM(COALESCE(t.type,''))) = 'DEBIT'
              AND LOWER(COALESCE(gm.subject,'')) LIKE '%was credited to your a/c%'
            """
        ).fetchall()

    for row in rows:
        result = void_transaction(row["transaction_id"])
        if result.get("status") not in ("VOIDED", "ALREADY_VOIDED"):
            continue
        with connection() as conn:
            conn.execute(
                "UPDATE gmail_messages SET status='PENDING' WHERE id=?",
                (row["gmail_id"],),
            )
        repaired += 1

    return repaired


def _repair_legacy_gmail_account_classifications():
    """Fix already-parsed Gmail transactions using a strong bank-account label.

    Older parser versions could classify an Axis A/c credit as CREDIT_CARD when
    the HTML/email footer also mentioned cards. Repair only active Gmail
    transactions that are unambiguously tied to an Axis A/c alert and have a
    matching configured bank account.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT
                t.id,
                t.amount_minor,
                t.type,
                t.bank,
                t.account_type,
                t.account_last4,
                t.status,
                t.duplicate_of,
                t.payment_method,
                gm.id AS gmail_id,
                gm.sender,
                gm.subject,
                (
                    SELECT a.id
                    FROM accounts a
                    WHERE a.account_type = 'BANK_ACCOUNT'
                      AND a.currency = t.currency
                      AND UPPER(TRIM(COALESCE(a.bank,''))) = 'AXIS'
                      AND TRIM(COALESCE(a.last4,'')) = TRIM(COALESCE(t.account_last4,''))
                    ORDER BY a.updated_at DESC
                    LIMIT 1
                ) AS target_account_id,
                ba.account_id AS adjustment_account_id,
                ba.delta_minor AS adjustment_delta
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            JOIN gmail_messages gm
              ON (
                   gm.id = e.source_id
                   OR e.source_id LIKE 'imap:%:' || gm.id
                 )
            LEFT JOIN balance_adjustments ba
              ON ba.transaction_id = t.id
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND t.duplicate_of IS NULL
              AND UPPER(TRIM(COALESCE(t.bank,''))) = 'AXIS'
              AND UPPER(TRIM(COALESCE(t.account_type,''))) = 'CREDIT_CARD'
              AND UPPER(TRIM(COALESCE(t.type,''))) = 'CREDIT'
              AND LOWER(TRIM(COALESCE(gm.sender,''))) = 'alerts@axis.bank.in'
              AND LOWER(COALESCE(gm.subject,'')) LIKE '%was credited to your a/c%'
            """
        ).fetchall()

        for row in rows:
            # Undo an adjustment that was attached to the wrong account, if
            # one exists. Normally the old misclassification had no matching
            # adjustment, but this keeps the repair safe and idempotent.
            if row["adjustment_account_id"] and row["adjustment_account_id"] != row["target_account_id"]:
                conn.execute(
                    "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                    (row["adjustment_delta"], int(time.time()*1000), row["adjustment_account_id"]),
                )
                conn.execute(
                    "DELETE FROM balance_adjustments WHERE transaction_id=?",
                    (row["id"],),
                )

            now = int(time.time() * 1000)
            conn.execute(
                """
                UPDATE transactions
                   SET account_type='BANK_ACCOUNT',
                       payment_method='UPI'
                 WHERE id=?
                """,
                (row["id"],),
            )

            adjustment = conn.execute(
                "SELECT account_id FROM balance_adjustments WHERE transaction_id=?",
                (row["id"],),
            ).fetchone()

            if adjustment is None:
                delta = row["amount_minor"] if row["type"] == "CREDIT" else -row["amount_minor"]
                conn.execute(
                    """
                    INSERT OR IGNORE INTO balance_adjustments
                    (transaction_id, account_id, delta_minor, applied_at)
                    VALUES (?,?,?,?)
                    """,
                    (row["id"], row["target_account_id"], delta, now),
                )
                conn.execute(
                    "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
                    (delta, now, row["target_account_id"]),
                )

            repaired += 1

    return repaired


def _gmail_message_id_from_source_id(source_id):
    """Normalize stored IMAP evidence IDs for Gmail/IMAP message lookup.

    IMAP evidence is stored as imap:<folder>:<uidvalidity>:<uid>, while
    IMAPService message lookup expects only <uidvalidity>:<uid>. OAuth Gmail
    message IDs are already in the required form and pass through.
    """
    if not source_id.startswith("imap:"):
        return source_id

    match = re.match(r"^imap:.*:(\d+):(\d+)$", source_id)
    if not match:
        raise ValueError(f"Unsupported IMAP source id: {source_id!r}")
    return f"{match.group(1)}:{match.group(2)}"


def _repair_legacy_icici_credit_card_classifications(service):
    """Reparse legacy ICICI Gmail rows that were stored as bank accounts.

    Older parser versions could misclassify an ICICI credit-card alert because
    the footer mentions paying the card bill from a bank account. Re-fetch the
    original Gmail message using the stored evidence source ID and let the
    current parser supply the corrected account type, payment method, merchant,
    and reference. The balance adjustment is then applied exactly once.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT
                t.id,
                t.amount_minor,
                t.type,
                t.currency,
                t.bank,
                t.account_type,
                t.account_last4,
                t.status,
                t.duplicate_of,
                t.payment_method,
                e.source_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND t.duplicate_of IS NULL
              AND e.source_type = 'GMAIL'
              AND UPPER(TRIM(COALESCE(t.bank,''))) = 'ICICI'
              AND UPPER(TRIM(COALESCE(t.account_type,''))) = 'BANK_ACCOUNT'
            ORDER BY t.timestamp ASC
            """
        ).fetchall()

        for row in rows:
            try:
                message_id = _gmail_message_id_from_source_id(row["source_id"])
            except ValueError:
                # Do not fail the entire Gmail sync because one legacy evidence
                # row has an unexpected source-id shape.
                continue

            message = service.users().messages().get(
                userId="me",
                id=message_id,
                format="full",
            ).execute()
            parsed = parse_bank_email(message)
            if not parsed:
                continue

            parsed_t, parsed_e = parsed

            # Only mutate the legacy row when the current parser resolves the
            # same transaction identity. This prevents an unrelated email
            # format change from rewriting an existing ledger entry.
            if parsed_t.id != row["id"]:
                continue
            if (
                parsed_t.amountMinor != row["amount_minor"]
                or parsed_t.currency != row["currency"]
                or parsed_t.type != row["type"]
                or parsed_t.bank != row["bank"]
                or parsed_t.accountLast4 != row["account_last4"]
                or parsed_t.accountType != "CREDIT_CARD"
            ):
                continue

            now = int(time.time() * 1000)
            target_account = conn.execute(
                """
                SELECT id
                FROM accounts
                WHERE account_type=?
                  AND currency=?
                  AND UPPER(TRIM(COALESCE(bank,'')))=?
                  AND TRIM(COALESCE(last4,''))=?
                LIMIT 1
                """,
                (
                    parsed_t.accountType,
                    parsed_t.currency,
                    parsed_t.bank,
                    parsed_t.accountLast4,
                ),
            ).fetchone()
            if not target_account:
                continue

            adjustment = conn.execute(
                "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
                (row["id"],),
            ).fetchone()

            # Move an old adjustment to the corrected account if necessary.
            if adjustment and adjustment["account_id"] != target_account["id"]:
                conn.execute(
                    "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                    (adjustment["delta_minor"], now, adjustment["account_id"]),
                )
                conn.execute(
                    "DELETE FROM balance_adjustments WHERE transaction_id=?",
                    (row["id"],),
                )
                adjustment = None

            conn.execute(
                """
                UPDATE transactions
                   SET payment_method=?,
                       account_type=?,
                       merchant_or_payee=?,
                       reference=?,
                       confidence=?
                 WHERE id=?
                """,
                (
                    parsed_t.paymentMethod,
                    parsed_t.accountType,
                    parsed_t.merchantOrPayee,
                    parsed_t.reference,
                    parsed_t.confidence,
                    row["id"],
                ),
            )

            conn.execute(
                """
                UPDATE evidence
                   SET status=?,
                       amount_minor=?,
                       currency=?,
                       direction=?,
                       bank_provider=?,
                       account_last4=?,
                       reference=?,
                       content_hash=?,
                       confidence=?
                 WHERE source_type='GMAIL' AND source_id=?
                """,
                (
                    parsed_e.status,
                    parsed_e.amountMinor,
                    parsed_e.currency,
                    parsed_e.direction,
                    parsed_e.bankProvider,
                    parsed_e.accountLast4,
                    parsed_e.reference,
                    parsed_e.contentHash,
                    parsed_e.confidence,
                    row["source_id"],
                ),
            )

            if adjustment is None:
                apply_transaction_to_account(conn, parsed_t, now)

            repaired += 1

    return repaired


def _repair_legacy_gmail_merchants(service):
    """Backfill missing merchant/payee names on existing Gmail transactions.

    Re-fetch the original Gmail message and let the current parser recover a
    merchant when the stored transaction has no merchant value. Only the
    transaction's descriptive merchant field and confidence/evidence metadata
    are updated; balances and transaction identity are never changed here.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT
                t.id,
                t.amount_minor,
                t.type,
                t.currency,
                t.bank,
                t.account_last4,
                t.status,
                t.duplicate_of,
                t.merchant_or_payee,
                e.source_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND t.duplicate_of IS NULL
              AND TRIM(COALESCE(t.merchant_or_payee,'')) = ''
            ORDER BY t.timestamp ASC
            """
        ).fetchall()

        for row in rows:
            try:
                message_id = _gmail_message_id_from_source_id(row["source_id"])
            except ValueError:
                continue

            try:
                message = service.users().messages().get(
                    userId="me",
                    id=message_id,
                    format="full",
                ).execute()
            except Exception:
                continue

            parsed = parse_bank_email(message)
            if not parsed:
                continue

            parsed_t, parsed_e = parsed
            if parsed_t.id != row["id"]:
                continue
            if (
                parsed_t.amountMinor != row["amount_minor"]
                or parsed_t.currency != row["currency"]
                or parsed_t.type != row["type"]
                or parsed_t.bank != row["bank"]
                or parsed_t.accountLast4 != row["account_last4"]
                or not parsed_t.merchantOrPayee
            ):
                continue

            conn.execute(
                """
                UPDATE transactions
                   SET merchant_or_payee=?,
                       confidence=?
                 WHERE id=?
                """,
                (
                    parsed_t.merchantOrPayee,
                    parsed_t.confidence,
                    row["id"],
                ),
            )

            conn.execute(
                """
                UPDATE evidence
                   SET reference=?,
                       content_hash=?,
                       confidence=?
                 WHERE source_type='GMAIL'
                   AND source_id=?
                """,
                (
                    parsed_e.reference,
                    parsed_e.contentHash,
                    parsed_e.confidence,
                    row["source_id"],
                ),
            )

            repaired += 1

    return repaired


def _repair_self_transfer_gmail_merchants(service):
    """Enrich Gmail transfer names from a uniquely matching opposite entry.

    Some bank templates truncate the counterparty name. When a Gmail
    transaction has no useful merchant/payee, pair it with a single opposite
    debit/credit in a different account within ten minutes, using amount,
    currency, bank and account identity as guards. This updates descriptive
    metadata only.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT
                t.id,
                t.amount_minor,
                t.type,
                t.currency,
                t.bank,
                t.account_last4,
                t.timestamp,
                t.merchant_or_payee,
                e.source_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND t.duplicate_of IS NULL
              AND UPPER(TRIM(COALESCE(t.account_type,''))) = 'BANK_ACCOUNT'
            ORDER BY t.timestamp ASC
            """
        ).fetchall()

        for row in rows:
            current = (row["merchant_or_payee"] or "").strip()
            if current and not current.upper().startswith("ABDUL WAS"):
                # A populated merchant should normally be preserved.
                continue

            partner_rows = conn.execute(
                """
                SELECT id,merchant_or_payee
                FROM transactions
                WHERE id <> ?
                  AND id NOT LIKE 'gmail:%'
                  AND status='ACTIVE'
                  AND duplicate_of IS NULL
                  AND amount_minor=?
                  AND currency=?
                  AND type=?
                  AND UPPER(TRIM(COALESCE(bank,''))) <> UPPER(TRIM(COALESCE(?,'')))
                  AND TRIM(COALESCE(account_last4,'')) <> TRIM(COALESCE(?,'')) 
                  AND ABS(timestamp-?) <= 600000
                  AND TRIM(COALESCE(merchant_or_payee,'')) <> ''
                ORDER BY ABS(timestamp-?)
                LIMIT 2
                """,
                (
                    row["id"],
                    row["amount_minor"],
                    row["currency"],
                    "CREDIT" if row["type"] == "DEBIT" else "DEBIT",
                    row["bank"] or "",
                    row["account_last4"] or "",
                    row["timestamp"],
                    row["timestamp"],
                ),
            ).fetchall()

            candidates = [x for x in partner_rows if (x["merchant_or_payee"] or "").strip()]
            if len(candidates) != 1:
                continue

            merchant = candidates[0]["merchant_or_payee"].strip()
            if merchant == current:
                continue

            conn.execute(
                "UPDATE transactions SET merchant_or_payee=? WHERE id=?",
                (merchant, row["id"]),
            )
            repaired += 1

    return repaired


def _repair_legacy_gmail_merchant_values(service):
    """Repair stale Gmail merchant values using the current parser.

    This covers both missing merchants and old values that accidentally stored
    bank disclaimer text as the merchant. Only an identity-verified Gmail row
    is updated, and no balance adjustment is touched.
    """
    repaired = 0
    with connection() as conn:
        rows = conn.execute(
            """
            SELECT
                t.id,
                t.amount_minor,
                t.type,
                t.currency,
                t.bank,
                t.account_last4,
                t.status,
                t.duplicate_of,
                t.merchant_or_payee,
                e.source_id
            FROM transactions t
            JOIN evidence e
              ON e.transaction_id = t.id
             AND e.source_type = 'GMAIL'
            WHERE t.id LIKE 'gmail:%'
              AND t.status = 'ACTIVE'
              AND t.duplicate_of IS NULL
            ORDER BY t.timestamp ASC
            """
        ).fetchall()

        for row in rows:
            try:
                message_id = _gmail_message_id_from_source_id(row["source_id"])
                message = service.users().messages().get(
                    userId="me",
                    id=message_id,
                    format="full",
                ).execute()
            except (ValueError, RuntimeError):
                continue
            except Exception:
                continue

            parsed = parse_bank_email(message)
            if not parsed:
                continue

            parsed_t, parsed_e = parsed
            if (
                parsed_t.id != row["id"]
                or parsed_t.amountMinor != row["amount_minor"]
                or parsed_t.currency != row["currency"]
                or parsed_t.type != row["type"]
                or parsed_t.bank != row["bank"]
                or parsed_t.accountLast4 != row["account_last4"]
            ):
                continue

            current = (row["merchant_or_payee"] or "").strip()
            parsed_merchant = (parsed_t.merchantOrPayee or "").strip()

            # Update only when the parser has a useful replacement, or when
            # the current value is clearly a bank disclaimer/reference block.
            looks_like_garbage = bool(re.search(
                r"(?i)If this transaction|Feel free to connect|Copyright Axis Bank|"
                r"system generated|Please do not share|Internet communications|"
                r"Transaction Info\s*:",
                current,
            ))
            if not parsed_merchant and not looks_like_garbage:
                continue
            if current == parsed_merchant:
                continue

            conn.execute(
                """
                UPDATE transactions
                   SET merchant_or_payee=?,
                       confidence=?
                 WHERE id=?
                """,
                (
                    parsed_t.merchantOrPayee,
                    parsed_t.confidence,
                    row["id"],
                ),
            )
            conn.execute(
                """
                UPDATE evidence
                   SET reference=?,
                       content_hash=?,
                       confidence=?
                 WHERE source_type='GMAIL'
                   AND source_id=?
                """,
                (
                    parsed_e.reference,
                    parsed_e.contentHash,
                    parsed_e.confidence,
                    row["source_id"],
                ),
            )
            repaired += 1

    return repaired
