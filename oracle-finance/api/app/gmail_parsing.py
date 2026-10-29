"""Gmail e-mail parsing: is this a bank transaction e-mail, and what are its amount, bank, account, direction and merchant."""
import base64
import hashlib
import html
import re
from email.utils import parseaddr
from .main_models import SyncTransactionModel
from .main_models import SyncEvidenceModel


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
    "SBI": ("sbi.co.in", "sbi.bank.in"),
    "HSBC": ("hsbc.co.in", "hsbc.com", "hsbc.bank.in"),
    "INDUSIND": ("indusind.com", "indusind.bank.in"),
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
    """Extract the stable transaction identity from any bank-email format."""
    normalized = _plain_text(combined)
    patterns = (
        r"(?i)\b(?:Ref(?:erence)?|UTR|RRN)\s*(?:No\.?|Number)?\s*[:#-]?\s*"
        r"([A-Z0-9][A-Z0-9/-]*\d[A-Z0-9/-]*)",
        r"(?i)\bTransaction\s+(?:ID|No\.?)\s*[:#-]?\s*"
        r"([A-Z0-9][A-Z0-9/-]*\d[A-Z0-9/-]*)",
        r"(?i)\bUPI\s+(?:transaction\s+)?(?:reference\s+)?(?:No\.?|Number)?\s*[:#-]?\s*"
        r"([0-9]{8,})",
    )
    token = None
    for pattern in patterns:
        match = re.search(pattern, normalized)
        if match:
            token = match.group(1)
            break

    if not token:
        # Axis account alerts put the RRN inside Transaction Info:
        # UPI/P2A/<RRN>/<counterparty>/...
        match = re.search(
            r"(?i)Transaction\s+Info\s*:\s*UPI/P2A/([0-9]{6,})",
            normalized,
        )
        if match:
            token = match.group(1)
        else:
            match = re.search(
                r"(?i)\bTransaction\s+Info\s*:\s*([^\s<]{6,120})",
                normalized,
            )
            token = match.group(1) if match else None

    token = token.rstrip(".,;:)") if token else None
    if not token:
        return None

    axis_match = re.match(r"(?i)^UPI/[^/\s]+/([^/\s]+)", token)
    if axis_match:
        token = axis_match.group(1)

    return token.strip().upper() or None


def _explicit_transaction_direction(subject, text, bank):
    """Return direction from an authoritative transaction sentence when present.

    Bank alert footers routinely contain words such as "charged", "payment",
    "debit", or "credit card". Those words are not the direction of the
    transaction itself. Prefer the bank's explicit account-credit/debit wording
    before falling back to broad transaction-language detection.
    """
    subject_text = _plain_text(subject)
    body_text = _plain_text(text)
    subject_and_body = f"{subject_text}\n{body_text}"

    # Axis account alerts put the authoritative direction in the subject:
    # "INR X was credited/debited to/from your A/c."
    if bank == "AXIS":
        if re.search(r"(?i)\bwas\s+credited\s+to\s+your\s+A/c\b|"
                     r"\bhas\s+been\s+credited\b", subject_text):
            return "CREDIT"
        if re.search(r"(?i)\bwas\s+debited\s+from\s+your\s+A/c\b|"
                     r"\bhas\s+been\s+debited\b", subject_text):
            return "DEBIT"

    # Generic bank transaction lines. These are deliberately specific enough
    # not to treat footer words such as "credit card" or "payment" as a side.
    credit_patterns = (
        r"(?i)\b(?:amount\s+credited|amount\s+has\s+been\s+credited)\b",
        r"(?i)\b(?:was|has\s+been|is)\s+credited\s+(?:to|into)\s+your\s+(?:A/c|account)\b",
        r"(?i)\b(?:your\s+(?:A/c|account)\s+has\s+been\s+credited)\b",
        r"(?i)\bcredited\s+with\s+(?:INR|Rs\\.?|₹)",
    )
    debit_patterns = (
        r"(?i)\b(?:amount\s+debited|amount\s+has\s+been\s+debited)\b",
        r"(?i)\b(?:was|has\s+been|is)\s+debited\s+from\s+your\s+(?:A/c|account)\b",
        r"(?i)\b(?:your\s+(?:A/c|account)\s+has\s+been\s+debited)\b",
        r"(?i)\bdebited\s+by\s+(?:INR|Rs\\.?|₹)",
    )

    if any(re.search(pattern, subject_and_body) for pattern in credit_patterns):
        return "CREDIT"
    if any(re.search(pattern, subject_and_body) for pattern in debit_patterns):
        return "DEBIT"
    return None


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

    # Card alerts such as HSBC's "...used for a transaction of USD 4.00 at
    # HUGGINGFACE on 21/10/26." name the merchant after "at".
    card_merchant = re.search(
        r"(?i)\b(?:transaction|purchase|spend|payment)\s+of\s+"
        r"(?:INR|Rs\.?|\u20b9|USD|EUR|GBP|SGD|AUD|CAD|AED)\s*[0-9][0-9,]*(?:\.\d{1,2})?"
        r"\s+at\s+(.+?)(?=\s+on\s+\d|\s+on\b|[.,;]|$)",
        normalized,
    )
    if card_merchant:
        value = card_merchant.group(1).strip(" \t\r\n.,;:-")
        if value:
            return value

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


def parse_card_bill_email(message):
    """Extract a credit-card statement total from the full Gmail message.

    This is deliberately separate from transaction parsing. A statement's
    Total Amount Due is a bill snapshot, not a purchase/debit transaction.
    """
    payload=message.get("payload",{})
    headers=_headers(payload)
    subject=headers.get("subject","")
    sender=_email_sender(headers)
    # Bank HTML puts tags and inline styles between a label and its value
    # ("Total Amount Due:" ... "<b style=...>₹2,088.00"), so read the visible
    # text, not the raw markup.
    text=_plain_text(_decode(payload))
    combined=f"{subject}\n{text}"
    lower=combined.lower()

    if not re.search(r"(?i)\bstatement\b|\be[- ]?statement\b|\btotal\s+amount\s+due\b", combined):
        return None

    amount=None
    amount_patterns=(
        r"(?is)total\s+amount\s+due.{0,160}?(?:INR|Rs\.?|₹)\s*(?:\(?INR\)?\s*)?(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)",
        r"(?is)total\s+amt\s*[:\-]?\s*(?:INR|Rs\.?|₹)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)",
    )
    for pattern in amount_patterns:
        match=re.search(pattern, combined)
        if match:
            amount=int(round(float(match.group(1).replace(",",""))*100))
            break
    if amount is None or amount <= 0:
        return None

    bank=_recognized_bank(combined,sender)

    # Statement subjects commonly expose only two visible digits (XX06/XX75).
    # Prefer a full four-digit card number when it exists in the body.
    card_match=re.search(
        r"(?i)\b(?:credit\s+card|card)\s+(?:no\.?|number|ending(?:\s+in)?)\s*"
        r"(?:X{1,8}|\*{1,8})?\s*[- ]?(\d{2,4})\b",
        combined,
    )
    if not card_match:
        card_match=re.search(
            r"(?i)\bending\s+(?:X{1,8}|\*{1,8})?\s*(\d{2,4})\b",
            subject,
        )
    if not card_match:
        # "ICICI Bank Credit Card  XX1012": the card is only identified by a
        # masked number straight after the words "Credit Card". The mask is
        # required so ordinary sentences do not match.
        card_match=re.search(
            r"(?i)\bcredit\s+card\s*(?:no\.?|number)?\s*[:\-]?\s*(?:X{1,8}|\*{1,8}|[•·]{1,8})\s*(\d{2,4})\b",
            combined,
        )
    if not card_match:
        return None

    visible=card_match.group(1)
    last4=visible if len(visible)==4 else None
    last2=visible[-2:]

    return {
        "sourceType":"GMAIL",
        "sourceKey":"gmail-card-bill:"+message["id"],
        "timestamp":int(message.get("internalDate","0")),
        "amountMinor":amount,
        "currency":"INR",
        "bank":bank,
        "accountLast4":last4,
        "accountLast2":last2,
        "confidence":0.99,
    }


def _gmail_default_category(transaction_type, combined):
    """Return a valid automatic category for Gmail-derived transactions.

    Neither direction may fall back to OTHER because OTHER is an explicit user
    choice meaning "exclude from Splitwise". Generic credits are transfers;
    refunds/reversals and salary/payroll retain their specific categories;
    debits default to GROCERIES, the same default the phone uses.
    """
    if str(transaction_type or "").upper() == "CREDIT":
        if re.search(r"(?i)\b(?:refund|reversal|reversed)\b", combined):
            return "REFUND"
        if re.search(r"(?i)\b(?:salary|payroll|stipend)\b", combined):
            return "SALARY"
        return "TRANSFER"
    return "GROCERIES"


_AMOUNT_RE = re.compile(
    r"(?i)(INR|Rs\.?|₹|USD|EUR|GBP|SGD|AUD|CAD|AED)[\s₹]*([0-9][0-9,]*(?:\.\d{1,2})?)"
)


_BALANCE_CONTEXT = ("limit", "balance", "avl bal", "avbl", "available", "outstanding", "due")


def _transaction_amount(text):
    """First amount that is not an available-limit/balance/due figure.

    Returns (amount_minor, currency) or (None, "INR"). Foreign-currency spends
    ("Spent USD 205.7 ... Avl limit INR 61312.07") are read in their own
    currency instead of mistaking the INR limit for the spend.
    """
    for match in _AMOUNT_RE.finditer(text):
        prefix = text[max(0, match.start() - 35):match.start()].lower()
        if any(word in prefix for word in _BALANCE_CONTEXT):
            continue
        marker = match.group(1).upper().rstrip(".")
        currency = "INR" if marker in ("INR", "RS", "₹") else marker
        return int(round(float(match.group(2).replace(",", "")) * 100)), currency
    return None, "INR"


def parse_bank_email(message):
    payload=message.get("payload",{})
    headers=_headers(payload)
    text=_decode(payload)
    subject=headers.get("subject","")
    sender=_email_sender(headers)
    combined=f"{subject}\n{text}"

    if _hard_reject_email(subject, text):
        return None

    amount, txn_currency = _transaction_amount(combined)
    bank=_recognized_bank(combined, sender)
    # Resolve direction only after bank identification so bank-specific
    # authoritative wording (especially Axis account alerts) can win over
    # unrelated footer text.
    direction=_explicit_transaction_direction(subject, text, bank)
    if direction is None:
        # Broad fallback is retained for bank/card formats that do not expose
        # a structured account-credit/debit sentence.
        direction="DEBIT" if re.search(
            r"(?i)debited|spent|sent|purchase|withdrawn|payment.*successful|"
            r"used\s+for\s+(?:a\s+)?transaction|charged",
            combined,
        ) else (
            "CREDIT" if re.search(
                r"(?i)credited|credit alert|payment.*received|received\s+(?:your\s+)?payment|refund|"
                r"amount\s+credited",
                combined,
            ) else None
        )

    last4=_account_last4(combined)

    if not bank or not last4 or not _looks_like_transaction(combined, amount, direction):
        return None

    reference=_reference(combined)
    merchant_or_payee=_merchant_or_payee(combined)
    account_type = _account_type_for_email(combined, subject, last4)
    payment_method = "CARD" if account_type == "CREDIT_CARD" else "UPI"

    # HDFC UPI bank-account emails must carry a stable transaction identity
    # or counterparty. This is the hardened path for the legacy false-HDFC
    # amount-only rows; other bank/card formats retain their established
    # identity rules until they expose an equivalent proven false-positive case.
    if bank == "HDFC" and account_type == "BANK_ACCOUNT" and payment_method == "UPI" and not reference and not merchant_or_payee:
        return None

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

    tx_id="gmail:"+hashlib.sha256((message["id"]+":"+str(amount)+":"+direction).encode()).hexdigest()[:32]
    ev_id="gmail-evidence:"+message["id"]
    t=SyncTransactionModel(id=tx_id,amountMinor=amount,currency=txn_currency,type=direction,
       paymentMethod="CARD" if account_type=="CREDIT_CARD" else "UPI",accountType=account_type,
       bank=bank,merchantOrPayee=merchant_or_payee,accountLast4=last4,reference=reference,
       timestamp=int(message.get("internalDate","0")),category=_gmail_default_category(direction, combined),confidence=min(score, 1.0))
    e=SyncEvidenceModel(id=ev_id,sourceType="GMAIL",sourceId=message["id"],status="UNMATCHED",
       observedAt=int(message.get("internalDate","0")),transactionId=tx_id,amountMinor=amount,currency=txn_currency,
       direction=direction,bankProvider=bank,accountLast4=last4,reference=reference,
       contentHash=hashlib.sha256(combined.encode()).hexdigest(),confidence=min(score, 1.0))
    return t,e
