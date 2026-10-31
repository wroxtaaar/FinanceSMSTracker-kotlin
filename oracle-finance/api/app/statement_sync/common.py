"""Shared helpers: secrets, message/attachment access, PDF text."""

import base64
import io
import os
import re



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


def _statement_keys(bank):
    name = bank.upper()
    values = os.getenv(f"{name}_STATEMENT_SECRETS", "").strip()
    keys = [item.strip() for item in re.split(r"[,\n]+", values) if item.strip()]
    single = _secret(f"{name}_STATEMENT_SECRET")
    if single and single not in keys:
        keys.append(single)
    return keys


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


def _extract_statement_bill_amount(text):
    normalized = re.sub(r"\s+", " ", text or "")
    patterns = (
        # ICICI's PDF font renders the rupee sign as a backtick (`2,088.00).
        r"(?is)total\s+amount\s+due.{0,180}?(?:INR|Rs\.?|₹|`|C)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)",
        r"(?is)total\s+amt\s*[:\-]?\s*(?:INR|Rs\.?|₹|`|C)\s*(?:Dr\.?|CR\.?|:)?\s*([0-9][0-9,]*(?:\.\d{1,2})?)",
    )
    for pattern in patterns:
        match = re.search(pattern, normalized)
        if match:
            return _minor(match.group(1))
    return None
