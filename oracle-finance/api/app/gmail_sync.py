import hashlib, re, time
from email.utils import parseaddr
from .db import connection
from .ledger import (
    sync_transaction,
    sync_evidence,
    add_review,
    apply_transaction_to_account,
    reconcile_duplicate_transaction,
    repair_duplicate_transactions,
    apply_splitwise_contribution,
    sync_card_bill,
    match_internal_transfers,
    rebuild_manual_splitwise_total,
)
from .main_models import SyncTransactionModel,SyncEvidenceModel
from .statement_sync import process_statement_attachments
from .gmail_parsing import (
    _decode,
    _headers,
    _REJECT_OTP,
    _REJECT_PROMO,
    _REJECT_NOT_COMPLETED,
    _REJECT_INFO_ONLY,
    _TRANSACTION_SIGNAL,
    _BANK_SENDER_DOMAINS,
    _email_sender,
    _sender_domain_is_known_bank,
    _hard_reject_email,
    _recognized_bank,
    _plain_text,
    _account_last4,
    _reference,
    _explicit_transaction_direction,
    _merchant_or_payee,
    _account_type_for_email,
    _looks_like_transaction,
    parse_card_bill_email,
    _gmail_default_category,
    _AMOUNT_RE,
    _BALANCE_CONTEXT,
    _transaction_amount,
    parse_bank_email,
)  # noqa: F401  (moved names stay importable from gmail_sync)
from .gmail_legacy_repairs import (
    _repair_legacy_unidentified_hdfc_gmail_transactions,
    _repair_legacy_axis_credit_direction_conflicts,
    _repair_legacy_gmail_account_classifications,
    _gmail_message_id_from_source_id,
    _repair_legacy_icici_credit_card_classifications,
    _repair_legacy_gmail_merchants,
    _repair_self_transfer_gmail_merchants,
    _repair_legacy_gmail_merchant_values,
)  # noqa: F401  (moved names stay importable from gmail_sync)

GMAIL_READONLY_SCOPE="https://www.googleapis.com/auth/gmail.readonly"


def _retry_pending_statement_messages(service, stats):
    """Retry statement emails that are still pending, regardless of date query.

    Normal incremental Gmail/IMAP syncs intentionally use a recent date window.
    A statement can remain PENDING after a parser/password failure, however,
    and that message may be older than the next incremental window. Retry only
    statement messages already recorded as PENDING so older statements can
    recover without broadening the normal mailbox search.
    """
    retried = 0
    retried_message_ids = set()

    with connection() as conn:
        rows = conn.execute(
            """
            SELECT id, subject
            FROM gmail_messages
            WHERE status='PENDING'
              AND (
                  LOWER(COALESCE(subject,'')) LIKE '%statement%'
                  OR LOWER(COALESCE(subject,'')) LIKE '%e-statement%'
              )
            ORDER BY internal_date DESC
            LIMIT 50
            """
        ).fetchall()

    for row in rows:
        msg_id = row["id"]
        retried_message_ids.add(msg_id)
        try:
            message = service.users().messages().get(
                userId="me",
                id=msg_id,
                format="full",
            ).execute()
        except Exception as exc:
            stats["statementErrors"].append(
                {"messageId": msg_id, "error": str(exc)}
            )
            continue

        retried += 1
        # The e-mail body carries the bill total too. Read it first so the bill
        # is recorded even when the PDF cannot be opened or parsed.
        bill_from_email = parse_card_bill_email(message)
        if bill_from_email:
            sync_card_bill(bill_from_email)
            stats["cardBillDetected"] = stats.get("cardBillDetected", 0) + 1
        statement_stats = process_statement_attachments(service, message)
        stats["statementAttachmentsScanned"] += statement_stats["attachmentsScanned"]
        stats["statementAttachmentsParsed"] += statement_stats["attachmentsParsed"]
        stats["statementTransactionsAdded"] += statement_stats["transactionsAdded"]
        stats["statementTransactionsMatched"] += statement_stats["transactionsMatched"]
        stats["statementErrors"].extend(statement_stats["errors"])

        with connection() as conn:
            parsed_attachment = conn.execute(
                """
                SELECT 1
                FROM gmail_attachments
                WHERE status='PARSED'
                  AND (
                      message_id=?
                      OR message_id LIKE 'imap:%:' || ?
                  )
                LIMIT 1
                """,
                (msg_id, msg_id),
            ).fetchone()

            if statement_stats["attachmentsParsed"] > 0 or parsed_attachment:
                conn.execute(
                    "UPDATE gmail_messages SET status='PARSED' WHERE id=?",
                    (msg_id,),
                )
            elif statement_stats["attachmentsScanned"] > 0:
                conn.execute(
                    "UPDATE gmail_messages SET status='PENDING' WHERE id=?",
                    (msg_id,),
                )

    stats["pendingStatementRetries"] = retried
    return retried_message_ids


_LEGACY_REPAIRS_COMPLETED = False


def _run_legacy_repairs_once(service):
    """Run historical Gmail repair/backfill work once per API process.

    These repairs are migration/backfill operations, not part of the normal
    incremental Gmail scan. Running them on every manual "Gmail" click causes
    unnecessary mailbox fetches and makes a fresh transaction alert appear
    slow. The functions remain available for explicit maintenance/tests.
    """
    global _LEGACY_REPAIRS_COMPLETED
    if _LEGACY_REPAIRS_COMPLETED:
        return 0

    repaired = (
        _repair_legacy_gmail_account_classifications()
        + _repair_legacy_axis_credit_direction_conflicts()
        + _repair_legacy_unidentified_hdfc_gmail_transactions()
        + _repair_legacy_icici_credit_card_classifications(service)
        + _repair_legacy_gmail_merchants(service)
        + _repair_legacy_gmail_merchant_values(service)
        + _repair_self_transfer_gmail_merchants(service)
    )
    _LEGACY_REPAIRS_COMPLETED = True
    return repaired


def ingest_messages(service,query="newer_than:30d"):
    """Run the Gmail ingestion, then settle the materialized Splitwise total.

    The ingestion path adjusts the Splitwise total incrementally while it
    stores, merges and neutralizes rows. Callers such as the background worker
    never rebuilt afterwards, so the stored total could stay off by the
    contribution of a merged e-mail row until the next phone sync. Rebuilding
    here makes the total a pure function of baseline + ledger for every caller.
    """
    try:
        return _ingest_messages(service, query)
    finally:
        rebuild_manual_splitwise_total()


def _ingest_messages(service,query="newer_than:30d"):
    stats={
        "messagesScanned":0,
        "alreadyProcessed":0,
        "parsedTransactions":0,
        "axisCredits":0,
        "duplicateTransactions":0,
        "reviewCount":0,
        "ignoredCount":0,
        "createdEvidence":0,
        "repairedTransactions":0,
        "statementAttachmentsScanned":0,
        "statementAttachmentsParsed":0,
        "statementTransactionsAdded":0,
        "statementTransactionsMatched":0,
        "statementErrors":[],
        "gmailDiagnostics":None,
        # One short line per scanned e-mail saying what the scan did with it,
        # so a message that never became a transaction can be explained.
        "messageLog":[],
    }

    def note(sender, subject, outcome):
        if len(stats["messageLog"]) < 300:
            stats["messageLog"].append({
                "sender": str(sender or "")[:80],
                "subject": str(subject or "")[:90],
                "outcome": outcome,
            })

    # These two repairs are cheap, database-only safety passes. Run them
    # on every sync so a long-lived API process also cleans up false rows that
    # were created after its one-time legacy repair pass.
    stats["repairedTransactions"] = _run_legacy_repairs_once(service)
    stats["repairedTransactions"] += _repair_legacy_axis_credit_direction_conflicts()
    stats["repairedTransactions"] += _repair_legacy_unidentified_hdfc_gmail_transactions()

    # Retry previously discovered statement emails before the normal date-bounded
    # mailbox scan. This is intentionally limited to PENDING statement rows,
    # so historical statements do not require a full mailbox search.
    retried_statement_message_ids = _retry_pending_statement_messages(service, stats)

    # Gmail's API is paginated. IMAPService intentionally exposes only one
    # result page, so this loop also works with IMAP while consuming every
    # Gmail API page when OAuth is enabled.
    page_token=None
    while True:
        kwargs={"userId":"me","q":query,"maxResults":100}
        if page_token:
            kwargs["pageToken"]=page_token
        result=service.users().messages().list(**kwargs).execute()
        if result.get("diagnostics") is not None:
            stats["gmailDiagnostics"] = result["diagnostics"]

        for item in result.get("messages",[]):
            stats["messagesScanned"] += 1
            msg_id=item["id"]

            # A PENDING statement was already fetched and processed by the
            # explicit retry pass above. Do not process the same message again
            # during the normal mailbox scan.
            if msg_id in retried_statement_message_ids:
                stats["alreadyProcessed"] += 1
                continue

            # IMAP can cheaply return headers without downloading the full
            # message. On a first/manual scan this avoids fetching newsletters,
            # promotions, OTPs, etc. Only likely bank/transaction mail gets a
            # full RFC822 fetch.
            if item.get("from") is not None:
                sender_preview = parseaddr(item.get("from", ""))[1].lower()
                subject_preview = item.get("subject", "") or ""
                sender_domain = sender_preview.rsplit("@", 1)[-1] if "@" in sender_preview else ""
                known_bank_sender = any(
                    sender_domain == domain or sender_domain.endswith("." + domain)
                    for domains in _BANK_SENDER_DOMAINS.values()
                    for domain in domains
                )
                transaction_subject = bool(re.search(
                    r"(?i)transaction|debited|credited|payment|purchase|spent|withdraw",
                    subject_preview,
                ))
                if not known_bank_sender and not transaction_subject:
                    stats["ignoredCount"] += 1
                    note(sender_preview, subject_preview,
                         "Skipped: sender is not a known bank and the subject has no transaction words")
                    continue

            subject_preview = item.get("subject", "") or ""
            statement_hint = bool(re.search(
                r"(?i)\bstatement\b|\be[- ]?statement\b",
                subject_preview,
            ))

            with connection() as conn:
                existing = conn.execute(
                    "SELECT status FROM gmail_messages WHERE id=?",
                    (msg_id,),
                ).fetchone()

                # A parsed message is terminal only while its evidence still
                # points at an active ledger transaction. This matters after a
                # local-history reset and after legacy repair work: the Gmail
                # processing marker can survive while the transaction that was
                # created from it has been voided/removed. In that case the
                # message must be eligible for parsing again or a valid Axis
                # credit (or any other authoritative bank email) can be lost
                # forever behind the PARSED marker.
                if existing and existing["status"] == "PARSED":
                    # Statement attachments are retried explicitly before the
                    # normal mailbox scan. Once that retry has marked the
                    # message PARSED, do not fetch/process the same attachment
                    # a second time in this pass.
                    if statement_hint:
                        stats["alreadyProcessed"] += 1
                        continue

                    active_link = conn.execute(
                        """
                        SELECT 1
                        FROM evidence e
                        JOIN transactions t
                          ON (
                               t.id = e.transaction_id
                               OR t.id = e.matched_transaction_id
                             )
                        WHERE e.source_type='GMAIL'
                          AND (
                               e.source_id=?
                               OR e.source_id LIKE 'imap:%:' || ?
                             )
                          AND t.status='ACTIVE'
                          AND t.duplicate_of IS NULL
                        LIMIT 1
                        """,
                        (msg_id, msg_id),
                    ).fetchone()
                    if active_link:
                        stats["alreadyProcessed"] += 1
                        note(item.get("from"), subject_preview,
                             "Already processed (its transaction exists)")
                        continue

                    # The message was marked PARSED but no active transaction
                    # remains. Move it back to PENDING so the current parser
                    # gets a chance to recreate the authoritative transaction.
                    conn.execute(
                        "UPDATE gmail_messages SET status='PENDING' WHERE id=?",
                        (msg_id,),
                    )
                    # Treat this as a fresh parse below. The local variable
                    # still contains the old PARSED marker and would otherwise
                    # hit the post-fetch terminal-message guard.
                    existing = None

            message=service.users().messages().get(
                userId="me",id=msg_id,format="full"
            ).execute()

            bill_from_email = parse_card_bill_email(message)
            if bill_from_email:
                bill_result = sync_card_bill(bill_from_email)
                stats.setdefault("cardBillDetected", 0)
                stats["cardBillDetected"] += 1
                stats.setdefault("cardBillResults", [])
                stats["cardBillResults"].append(bill_result)

            statement_stats = process_statement_attachments(service, message)
            stats["statementAttachmentsScanned"] += statement_stats["attachmentsScanned"]
            stats["statementAttachmentsParsed"] += statement_stats["attachmentsParsed"]
            stats["statementTransactionsAdded"] += statement_stats["transactionsAdded"]
            stats["statementTransactionsMatched"] += statement_stats["transactionsMatched"]
            stats["statementErrors"].extend(statement_stats["errors"])

            # If this message was already fully processed and its statement
            # attachments have now been checked, do not feed the statement
            # notification itself back through the transaction parser.
            if existing and existing["status"] == "PARSED":
                stats["alreadyProcessed"] += 1
                continue

            parsed=parse_bank_email(message)

            with connection() as conn:
                h=_headers(message.get("payload",{}))
                sender=parseaddr(h.get("from",""))[1]
                message_status = (
                    "PARSED"
                    if parsed or statement_stats["attachmentsParsed"] > 0
                    else "PENDING"
                )
                conn.execute(
                    """INSERT OR IGNORE INTO gmail_messages
                    (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
                    VALUES(?,?,?,?,?,?,?,?)""",
                    (
                        msg_id,
                        message.get("threadId"),
                        int(message.get("internalDate","0")),
                        sender,
                        h.get("subject"),
                        hashlib.sha256((msg_id+h.get("subject","")).encode()).hexdigest(),
                        message_status,
                        int(time.time()*1000),
                    ),
                )

            # A statement email is not itself a transaction. When a PDF
            # attachment is present, never feed the surrounding statement
            # summary through the bank-alert transaction parser. A failed PDF
            # stays PENDING so the next sync retries it.
            if statement_hint and statement_stats["attachmentsScanned"] > 0:
                if statement_stats["attachmentsParsed"] > 0:
                    with connection() as conn:
                        conn.execute(
                            "UPDATE gmail_messages SET status='PARSED' WHERE id=?",
                            (msg_id,),
                        )
                else:
                    with connection() as conn:
                        conn.execute(
                            "UPDATE gmail_messages SET status='PENDING' WHERE id=?",
                            (msg_id,),
                        )
                continue

            if parsed:
                t,e=parsed
                with connection() as conn:
                    conn.execute(
                        "UPDATE gmail_messages SET status='PARSED' WHERE id=?",
                        (msg_id,),
                    )
                sync_transaction(t)
                sync_evidence(e)
                stats["parsedTransactions"] += 1
                stats["createdEvidence"] += 1
                if t.bank == "AXIS" and t.type == "CREDIT":
                    stats["axisCredits"] += 1

                # Gmail can be a second source for an SMS transaction. Delay
                # its balance adjustment until after duplicate reconciliation
                # so one real-world transaction never changes the balance twice.
                reconcile_duplicate_transaction(t.id)
                with connection() as conn:
                    row=conn.execute(
                        "SELECT * FROM transactions WHERE id=?",(t.id,)
                    ).fetchone()
                    note(
                        sender, h.get("subject"),
                        f"Read as {t.type} {t.amountMinor/100:.2f} {t.currency} "
                        f"{t.bank or '?'} {t.accountType} {t.accountLast4 or '?'}: "
                        + ("merged into an existing transaction (duplicate)"
                           if row and row["duplicate_of"] else "new transaction")
                    )
                    if row and row["duplicate_of"]:
                        stats["duplicateTransactions"] += 1
                    elif row:
                        # A Gmail transaction can be the only authoritative
                        # source (for example an Axis credit with no SMS).
                        # In that case it must affect Splitwise just like an
                        # SMS transaction. The adjustment is idempotent and
                        # can be reversed if a later SMS proves this Gmail row
                        # was a duplicate.
                        now = int(time.time() * 1000)
                        apply_transaction_to_account(conn, t, now)
                        apply_splitwise_contribution(conn, t, now)
            else:
                h=_headers(message.get("payload",{}))
                combined=f"{h.get('subject','')}\\n{_decode(message.get('payload',{}))}"

                # Only create a review for messages that actually resemble a
                # bank/card transaction. Ordinary newsletters and unrelated
                # mail are recorded as ignored evidence without flooding the
                # review queue.
                has_amount=bool(re.search(
                    r"(?i)(?:INR|Rs\\.?)[\\s₹]*[0-9][0-9,]*(?:\\.\\d{1,2})?",
                    combined,
                ))
                has_bank=_recognized_bank(combined,sender) is not None
                has_financial_marker=bool(_TRANSACTION_SIGNAL.search(combined))
                h_status="REVIEW" if has_amount and has_bank and has_financial_marker else "IGNORED"

                e=SyncEvidenceModel(
                    id="gmail-evidence:"+msg_id,
                    sourceType="GMAIL",
                    sourceId=msg_id,
                    status="UNMATCHED",
                    observedAt=int(message.get("internalDate","0")),
                    transactionId=None,
                    amountMinor=None,
                    currency="INR",
                    direction=None,
                    bankProvider=None,
                    accountLast4=None,
                    reference=None,
                    contentHash=hashlib.sha256(combined.encode()).hexdigest(),
                    confidence=0.0,
                )
                sync_evidence(e)

                with connection() as conn:
                    conn.execute(
                        "UPDATE gmail_messages SET status=? WHERE id=?",
                        (h_status, msg_id),
                    )

                note(
                    sender, h.get("subject"),
                    "Looks like a bank e-mail but could not be read safely (sent to Review)"
                    if h_status == "REVIEW"
                    else "Not a transaction e-mail (no amount/bank/transaction wording)"
                )
                if h_status == "REVIEW":
                    stats["reviewCount"] += 1
                    add_review(
                        "GMAIL",
                        "bank-like email could not be parsed safely",
                        evidence_id=e.id,
                    )
                else:
                    stats["ignoredCount"] += 1

        page_token=result.get("nextPageToken")
        if not page_token:
            break

    # Final safety pass collapses same-side duplicates that may have been
    # created by separate notification/email sources during this sync.
    stats["repairedTransactions"] += repair_duplicate_transactions()

    # Gmail can be the second side of an internal bank-to-bank transfer.
    # Unlike /api/v1/sync, this ingestion path used to stop after duplicate
    # repair, leaving the receiving Gmail credit as a Splitwise change even
    # though the matching bank debit already existed. Pair and neutralize
    # internal transfers here too.
    stats["internalTransfersMatched"] = match_internal_transfers()


    return stats
