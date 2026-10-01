import base64, hashlib, os, re, time
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
    # "Available balance/limit" is often included in genuine transaction
    # alerts. Treat it as informational only when the message has no
    # transaction signal at all; otherwise it must not veto the transaction.
    info_only = _REJECT_INFO_ONLY.search(combined)
    has_transaction_signal = _TRANSACTION_SIGNAL.search(combined)
    return bool(
        _REJECT_OTP.search(combined) or
        _REJECT_PROMO.search(combined) or
        _REJECT_NOT_COMPLETED.search(combined) or
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


def _account_last4(combined):
    match = re.search(
        r"(?i)(?:A/c|account|card(?:\s+(?:no\.?|ending|ending\s+in))?)[^\d]{0,24}"
        r"(?:X{0,4}|\*{0,6}|[#\- ]*)?(\d{4})(?!\d)",
        combined,
    )
    return match.group(1) if match else None


def _reference(combined):
    # Bank reference fields vary between emails (for example
    # "Ref UPI-12345", "Reference: 123456", and "UTR ABC/12345").
    # Capture a token only when it contains at least one digit so ordinary
    # prose such as "references-center" cannot become a transaction reference.
    match = re.search(
        r"(?i)(?:Ref(?:erence)?|Transaction\s*(?:ID|No\.?)?|UTR)"
        r"\s*[:#-]?\s*([A-Z0-9][A-Z0-9/-]*\d[A-Z0-9/-]*)",
        combined,
    )
    if not match:
        match = re.search(
            r"(?i)Transaction\s+Info\s*:\s*([^\s<]{6,120})",
            combined,
        )
    token = match.group(1).rstrip(".,;:)") if match else None
    return token or None


def _looks_like_transaction(combined, amount, direction):
    if amount is None or direction is None:
        return False
    return bool(_TRANSACTION_SIGNAL.search(combined))


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

    account_type="CREDIT_CARD" if re.search(r"(?i)credit card|card ending|card no|card number",combined) else "BANK_ACCOUNT"
    tx_id="gmail:"+hashlib.sha256((message["id"]+":"+str(amount)+":"+direction).encode()).hexdigest()[:32]
    ev_id="gmail-evidence:"+message["id"]
    t=SyncTransactionModel(id=tx_id,amountMinor=amount,currency="INR",type=direction,
       paymentMethod="CARD" if account_type=="CREDIT_CARD" else "UPI",accountType=account_type,
       bank=bank,merchantOrPayee=None,accountLast4=last4,reference=reference,
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
    }

    # Gmail's API is paginated. IMAPService intentionally exposes only one
    # result page, so this loop also works with IMAP while consuming every
    # Gmail API page when OAuth is enabled.
    page_token=None
    while True:
        kwargs={"userId":"me","q":query,"maxResults":100}
        if page_token:
            kwargs["pageToken"]=page_token
        result=service.users().messages().list(**kwargs).execute()

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
