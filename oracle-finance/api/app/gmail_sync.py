import base64, hashlib, html, os, re, time
from email.utils import parseaddr
from .db import connection
from .ledger import sync_transaction,sync_evidence,add_review,apply_transaction_to_account,reconcile_duplicate_transaction
from .main_models import SyncTransactionModel,SyncEvidenceModel

GMAIL_READONLY_SCOPE="https://www.googleapis.com/auth/gmail.readonly"

def _decode(payload):
    data=payload.get("body",{}).get("data")
    if data: return base64.urlsafe_b64decode(data+"="*(-len(data)%4)).decode(errors="replace")
    return "".join(_decode(p) for p in payload.get("parts",[]) or [])

def _headers(payload): return {h["name"].lower():h["value"] for h in payload.get("headers",[])}


_REJECT_OTP = re.compile(
    r"\botp\b|one[\s-]?time[\s-]?password|verification code|\bcvv\b",
    re.I,
)
_REJECT_PROMO = re.compile(
    r"pre[\s-]?approved|apply now|click here|hurry|limited period|offer ends|"
    r"t&c apply|download the app|you have won|congratulations|lowest interest|"
    r"upgrade your|refer and earn|reward points|cashback offer|special offer|"
    r"annual fee waiver|annual fee.{0,60}\bspends?\b|"
    r"\bspends?\s+(?:of|rs\.?|inr|₹)|"
    r"\b(?:get|earn|save)\b.{0,60}\b(?:cashback|reward|bonus|points)\b",
    re.I,
)
_REJECT_NOT_COMPLETED = re.compile(
    r"will be (?:debited|deducted|credited|charged|transferred|reversed|refunded|blocked|processed)|"
    r"is due|due on|due date|(?:collect|payment|money) request|has requested|requesting|"
    r"(?:failed|declined|unsuccessful|not processed|could not be processed)|"
    r"\bnot (?:debited|credited|deducted|charged)\b|\brejection\b|"
    r"to (?:authorise|authorize|approve)|\bscheduled\b|\bpending\b",
    re.I,
)
_REJECT_INFO_ONLY = re.compile(
    r"mini statement|statement is ready|statement has been generated|e-statement|"
    r"available balance|available limit|credit limit",
    re.I,
)
_TRANSACTION_SIGNAL = re.compile(
    r"transaction|debited|credited|spent|purchase|withdrawn|"
    r"payment\s+(?:of|received|successful)|card\s+(?:payment|charged)|"
    r"cash\s+withdrawal|upi\s+(?:transaction|payment)",
    re.I,
)
_BANK_SENDER_DOMAINS = {
    "HDFC": ("hdfcbank.net", "hdfcbank.bank.in"),
    "AXIS": ("axisbank.com", "axis.bank.in"),
    "ICICI": ("icicibank.com", "icici.bank.in"),
    "SBI": ("sbi.co.in",),
    "HSBC": ("hsbc.co.in", "hsbc.com"),
    "INDUSIND": ("indusind.com",),
}
def _email_sender(headers):
    return parseaddr(headers.get("from", ""))[1].lower()
def _sender_domain_is_known_bank(bank, sender):
    if not bank or not sender or "@" not in sender:
        return False
    domain = sender.rsplit("@", 1)[1].lower()
    return any(domain == allowed or domain.endswith("." + allowed)
               for allowed in _BANK_SENDER_DOMAINS.get(bank, ()))
def _hard_reject_email(subject, text):
    combined = f"{subject}\n{text}"
    # Real bank transaction emails often contain promotional banners and
    # security disclaimers (for example "Apply Now", "Click here", "OTP", or
    # "CVV"). Those phrases must not veto an otherwise strong transaction alert.
    # Keep the negative-state check strict so failed/declined/pending messages
    # still cannot become transactions.
    info_only = _REJECT_INFO_ONLY.search(combined)
    has_transaction_signal = _TRANSACTION_SIGNAL.search(combined)
    return bool(
        _REJECT_NOT_COMPLETED.search(combined) or
        (
            (_REJECT_OTP.search(combined) or _REJECT_PROMO.search(combined))
            and not has_transaction_signal
        ) or
        (info_only and not has_transaction_signal)
    )

def _recognized_bank(combined, sender=""):
    # Some bank alerts omit the bank name from the body. The authenticated
    # sender domain is a strong bank identity signal and is especially useful
    # for credit alerts such as Axis account credits.
    sender_lower = sender.lower()
    for candidate, domains in _BANK_SENDER_DOMAINS.items():
        if any(sender_lower.endswith("@" + domain) or sender_lower.endswith("." + domain)
               for domain in domains):
            return candidate
    for candidate, pattern in (
        ("HDFC", r"(?i)HDFC"),
        ("AXIS", r"(?i)Axis Bank|\bAxis\b"),
        ("ICICI", r"(?i)ICICI"),
        ("HSBC", r"(?i)HSBC"),
        ("INDUSIND", r"(?i)IndusInd|IndusInd Bank"),
        ("SBI", r"(?i)State Bank of India|\bSBI\b"),
    ):
        if re.search(pattern, combined):
            return candidate
    return None


def _plain_text(value):
    # Normalize bank HTML into readable text before applying field-specific
    # regexes. This keeps footer/promotional markup from looking like part of
    # the transaction itself.
    text = re.sub(
        r"(?is)<(?:script|style)[^>]*>.*?</(?:script|style)>",
        " ",
        value or "",
    )
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text)
    return re.sub(r"\s+", " ", text).strip()


def _account_last4(combined):
    normalized = _plain_text(combined)

    # Prefer an explicit masked account/card number and require the label to
    # be close to the final four digits. This avoids unrelated footer numbers.
    match = re.search(
        r"(?i)(?:A/c|account|card(?:\s+(?:no\.?|ending(?:\s+in)?))?)[^\d]{0,64}"
        r"(?:X{0,6}|\*{0,8}|[#\- ]*)?(\d{4})(?!\d)",
        normalized,
    )
    return match.group(1) if match else None


def _reference(combined):
    # Do not treat "Transaction alert/1" or similar subject/footer wording as
    # a reference. A transaction reference must have an explicit Ref/Reference,
    # UTR, Transaction ID, or Transaction No. label.
    normalized = _plain_text(combined)
    patterns = (
        r"(?i)\b(?:Ref(?:erence)?|UTR)\s*[:#-]?\s*"
        r"([A-Z0-9][A-Z0-9/-]*\d[A-Z0-9/-]*)",
        r"(?i)\bTransaction\s+(?:ID|No\.?)\s*[:#-]?\s*"
        r"([A-Z0-9][A-Z0-9/-]*\d[A-Z0-9/-]*)",
    )
    token = None
    for pattern in patterns:
        match = re.search(pattern, normalized)
        if match:
            token = match.group(1)
            break

    # Axis alerts often expose the transaction reference as "Transaction
    # Info" rather than "Ref"/"UTR".
    if not token:
        match = re.search(
            r"(?i)\bTransaction\s+Info\s*:\s*([^\s<]{6,120})",
            normalized,
        )
        token = match.group(1) if match else None

    token = token.rstrip(".,;:)") if token else None
    return token or None


def _merchant_or_payee(combined):
    normalized = _plain_text(combined)

    # In Axis account alerts, "Transaction Info:" contains transfer metadata,
    # not a normal merchant field. First prefer a real standalone "Info:" field.
    match = re.search(
        r"(?is)(?<!Transaction )\bInfo\s*:\s*(.*?)(?=\s+(?:The\s+)?"
        r"(?:Available\s+Credit\s+Limit|Total\s+Credit\s+Limit|"
        r"Available\s+Balance|Credit\s+Limit)\b|$)",
        normalized,
    )
    if match:
        value = match.group(1).strip(" \t\r\n.,;:-")
        if value:
            return value

    # Axis UPI account alerts expose the counterparty inside Transaction Info:
    # UPI/P2A/<reference>/<counterparty>/... . Extract only that counterparty
    # segment and never the following bank/footer text.
    transfer_match = re.search(
        r"(?i)\bTransaction\s+Info\s*:\s*"
        r"UPI/[^/\s]+/[^/\s]+/([^/\n]+?)(?=\s*/|\s+If\b|$)",
        normalized,
    )
    if transfer_match:
        value = transfer_match.group(1).strip(" \t\r\n.,;:-")
        return value or None

    return None


def _account_type_for_email(combined, subject, last4):
    normalized = _plain_text(combined)
    masked_last4 = re.escape(last4)

    # Classify from the label attached to the same four digits, rather than
    # from unrelated footer text. A credit-card alert can legitimately mention
    # paying the card bill from a bank account.
    card_patterns = (
        rf"(?i)\bcredit\s+card\b[^\d]{{0,80}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
        rf"(?i)\bcard\s+(?:ending(?:\s+in)?|no\.?|number)\b[^\d]{{0,40}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
        rf"(?i)\bcard\b[^\d]{{0,40}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
    )
    bank_patterns = (
        rf"(?i)\bA/c\b[^\d]{{0,80}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
        rf"(?i)\baccount\s+(?:number|no\.?)\b[^\d]{{0,80}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
        rf"(?i)\bbank\s+account\b[^\d]{{0,80}}"
        rf"(?:X{{0,6}}|\*{{0,8}}|[#\- ]*)?{masked_last4}\b",
    )

    if any(re.search(pattern, normalized) for pattern in card_patterns):
        return "CREDIT_CARD"
    if any(re.search(pattern, normalized) for pattern in bank_patterns):
        return "BANK_ACCOUNT"

    # Subject lines are useful as a final explicit identity signal when the
    # body is fragmented by HTML.
    if re.search(r"(?i)credit\s+card|card\s+transaction", subject or ""):
        return "CREDIT_CARD"
    if re.search(r"(?i)\bA/c\b|account\s+(?:number|no\.?)", subject or ""):
        return "BANK_ACCOUNT"

    return "BANK_ACCOUNT"


def _looks_like_transaction(combined, amount, direction):
    if amount is None or direction is None:
        return False
    return bool(_TRANSACTION_SIGNAL.search(combined))


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
                a.id AS target_account_id,
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
            JOIN accounts a
              ON a.account_type = 'BANK_ACCOUNT'
             AND a.currency = t.currency
             AND UPPER(TRIM(COALESCE(a.bank,''))) = 'AXIS'
             AND TRIM(COALESCE(a.last4,'')) = TRIM(COALESCE(t.account_last4,''))
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

def parse_bank_email(message):
    payload=message.get("payload",{})
    headers=_headers(payload)
    text=_decode(payload)
    subject=headers.get("subject","")
    sender=_email_sender(headers)
    combined=f"{subject}\n{text}"

    if _hard_reject_email(subject, text):
        return None

    m=re.search(r"(?i)(?:INR|Rs\.?)[\s₹]*([0-9][0-9,]*(?:\.\d{1,2})?)",combined)
    amount=int(round(float(m.group(1).replace(",",""))*100)) if m else None
    direction="CREDIT" if re.search(r"(?i)credited|credit alert|payment.*received|refund",combined) else (
        "DEBIT" if re.search(
            r"(?i)debited|spent|sent|purchase|withdrawn|payment.*successful|"
            r"used\s+for\s+(?:a\s+)?transaction",
            combined,
        ) else None)
    bank=_recognized_bank(combined, sender)
    last4=_account_last4(combined)

    if not bank or not last4 or not _looks_like_transaction(combined, amount, direction):
        return None

    reference=_reference(combined)
    merchant_or_payee=_merchant_or_payee(combined)
    score=0.75
    if _sender_domain_is_known_bank(bank, sender):
        score += 0.10
    if reference:
        score += 0.10
    if re.search(r"(?i)(transaction|debit|credit|payment)\s+(alert|confirmation|notification)|transaction alert", subject):
        score += 0.05
    if re.search(
        r"(?i)(debited from|credited to|transaction of|purchase of|withdrawn|"
        r"payment of|spent\s+on|used\s+for\s+(?:a\s+)?transaction)",
        combined,
    ):
        score += 0.05
    # An explicit bank name in the message is an additional identity signal
    # for test fixtures and real emails whose sender address is not in our
    # trusted-domain allowlist.
    if bank and re.search(rf"(?i)\b{re.escape(bank)}\b", combined):
        score += 0.05

    if score < 0.90:
        return None

    account_type = _account_type_for_email(combined, subject, last4)
    tx_id="gmail:"+hashlib.sha256((message["id"]+":"+str(amount)+":"+direction).encode()).hexdigest()[:32]
    ev_id="gmail-evidence:"+message["id"]
    t=SyncTransactionModel(id=tx_id,amountMinor=amount,currency="INR",type=direction,
       paymentMethod="CARD" if account_type=="CREDIT_CARD" else "UPI",accountType=account_type,
       bank=bank,merchantOrPayee=merchant_or_payee,accountLast4=last4,reference=reference,
       timestamp=int(message.get("internalDate","0")),category="OTHER",confidence=min(score, 1.0))
    e=SyncEvidenceModel(id=ev_id,sourceType="GMAIL",sourceId=message["id"],status="UNMATCHED",
       observedAt=int(message.get("internalDate","0")),transactionId=tx_id,amountMinor=amount,currency="INR",
       direction=direction,bankProvider=bank,accountLast4=last4,reference=reference,
       contentHash=hashlib.sha256(combined.encode()).hexdigest(),confidence=min(score, 1.0))
    return t,e

def ingest_messages(service,query="newer_than:30d"):
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
        "gmailDiagnostics":None,
    }

    stats["repairedTransactions"] = (
        _repair_legacy_gmail_account_classifications()
        + _repair_legacy_icici_credit_card_classifications(service)
        + _repair_legacy_gmail_merchants(service)
        + _repair_legacy_gmail_merchant_values(service)
        + _repair_self_transfer_gmail_merchants(service)
    )

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
                    continue

            with connection() as conn:
                existing = conn.execute(
                    "SELECT status FROM gmail_messages WHERE id=?",
                    (msg_id,),
                ).fetchone()
                # Only PARSED is terminal. REVIEW/IGNORED/PENDING messages
                # must be retried so parser fixes and newly supported bank
                # formats can recover emails that were classified before the
                # current parser rules were deployed.
                if existing and existing["status"] == "PARSED":
                    stats["alreadyProcessed"] += 1
                    continue

            message=service.users().messages().get(
                userId="me",id=msg_id,format="full"
            ).execute()
            parsed=parse_bank_email(message)

            with connection() as conn:
                h=_headers(message.get("payload",{}))
                sender=parseaddr(h.get("from",""))[1]
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
                        ("PARSED" if parsed else "PENDING"),
                        int(time.time()*1000),
                    ),
                )

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
                    if row and row["duplicate_of"]:
                        stats["duplicateTransactions"] += 1
                    elif row:
                        apply_transaction_to_account(
                            conn,t,int(time.time()*1000)
                        )
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

    return stats
