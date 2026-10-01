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

def _recognized_bank(combined):
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
    token = match.group(1).rstrip(".,;:)") if match else None
    return token or None


def _looks_like_transaction(combined, amount, direction):
    if amount is None or direction is None:
        return False
    return bool(re.search(
        r"(?i)transaction|debited|credited|spent|purchase|withdrawn|payment\s+received|card\s+payment",
        combined,
    ))


def parse_bank_email(message):
    payload=message.get("payload",{}); headers=_headers(payload); text=_decode(payload)
    combined=f"{headers.get('subject','')}\n{text}"
    m=re.search(r"(?i)(?:INR|Rs\.?)[\s₹]*([0-9][0-9,]*(?:\.\d{1,2})?)",combined)
    amount=int(round(float(m.group(1).replace(",",""))*100)) if m else None
    direction="CREDIT" if re.search(r"(?i)credited|credit alert|payment.*received",combined) else (
        "DEBIT" if re.search(r"(?i)debited|spent|sent|purchase|withdrawn",combined) else None)
    bank=_recognized_bank(combined)
    last4=_account_last4(combined)

    # Only promote email content to a transaction when identity and
    # transaction intent are both strong enough to map it to an account.
    if not bank or not last4 or not _looks_like_transaction(combined, amount, direction):
        return None

    account_type="CREDIT_CARD" if re.search(r"(?i)card",combined) else "BANK_ACCOUNT"
    reference=_reference(combined)
    tx_id="gmail:"+hashlib.sha256((message["id"]+":"+str(amount)+":"+direction).encode()).hexdigest()[:32]
    ev_id="gmail-evidence:"+message["id"]
    t=SyncTransactionModel(id=tx_id,amountMinor=amount,currency="INR",type=direction,
       paymentMethod="CARD" if account_type=="CREDIT_CARD" else "UPI",accountType=account_type,
       bank=bank,merchantOrPayee=None,accountLast4=last4,reference=reference,
       timestamp=int(message.get("internalDate","0")),category="OTHER",confidence=0.9)
    e=SyncEvidenceModel(id=ev_id,sourceType="GMAIL",sourceId=message["id"],status="UNMATCHED",
       observedAt=int(message.get("internalDate","0")),transactionId=tx_id,amountMinor=amount,currency="INR",
       direction=direction,bankProvider=bank,accountLast4=last4,reference=reference,
       contentHash=hashlib.sha256(combined.encode()).hexdigest(),confidence=0.9)
    return t,e

def ingest_messages(service,query="newer_than:30d"):
    result=service.users().messages().list(userId="me",q=query,maxResults=100).execute()
    created=0
    for item in result.get("messages",[]):
        msg_id=item["id"]
        with connection() as conn:
            if conn.execute("SELECT 1 FROM gmail_messages WHERE id=?",(msg_id,)).fetchone(): continue
        message=service.users().messages().get(userId="me",id=msg_id,format="full").execute()
        parsed=parse_bank_email(message)
        with connection() as conn:
            h=_headers(message.get("payload",{})); sender=parseaddr(h.get("from",""))[1]
            conn.execute("""INSERT OR IGNORE INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES(?,?,?,?,?,?,?,?)""",(msg_id,message.get("threadId"),int(message.get("internalDate","0")),sender,h.get("subject"),
            hashlib.sha256((msg_id+h.get("subject","")).encode()).hexdigest(),("PARSED" if parsed else "PENDING"),int(time.time()*1000)))
        if parsed:
            t,e=parsed
            sync_transaction(t)
            sync_evidence(e)

            # Gmail can be a second source for an SMS transaction. Delay its
            # balance adjustment until after duplicate reconciliation so one
            # real-world transaction never changes the balance twice.
            reconcile_duplicate_transaction(t.id)
            with connection() as conn:
                row=conn.execute("SELECT * FROM transactions WHERE id=?",(t.id,)).fetchone()
                if row and not row["duplicate_of"]:
                    apply_transaction_to_account(conn, t, int(time.time()*1000))
            created+=1
        else:
            h=_headers(message.get("payload",{}))
            combined=f"{h.get('subject','')}\n{_decode(message.get('payload',{}))}"
            # Only create a review for messages that actually resemble a
            # bank/card transaction. Ordinary newsletters and unrelated mail
            # are recorded as ignored evidence without flooding the review queue.
            has_amount = bool(re.search(r"(?i)(?:INR|Rs\.?)[\\s₹]*[0-9][0-9,]*(?:\\.\\d{1,2})?", combined))
            has_bank = _recognized_bank(combined) is not None
            has_financial_marker = bool(re.search(
                r"(?i)transaction|debited|credited|spent|purchase|withdrawn|payment\s+received|card\s+payment",
                combined,
            ))
            h_status = "REVIEW" if has_amount and has_bank and has_financial_marker else "IGNORED"
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
            if h_status == "REVIEW":
                add_review("GMAIL","bank-like email could not be parsed safely",evidence_id=e.id)
    return created
