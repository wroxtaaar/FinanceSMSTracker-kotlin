import base64
import email
import imaplib
import os
import re
from datetime import datetime, timedelta, timezone
from email.header import decode_header
from email.utils import parsedate_to_datetime


def _decode_header_value(value):
    if value is None:
        return ""
    try:
        return str(email.header.make_header(decode_header(value)))
    except Exception:
        return str(value)


def _text_payload(message_part):
    if message_part.get_content_disposition() == "attachment":
        return None

    content_type = message_part.get_content_type().lower()
    if not content_type.startswith("text/"):
        return None

    raw = message_part.get_payload(decode=True)
    if raw is None:
        payload = message_part.get_payload()
        return payload if isinstance(payload, str) else None

    charset = message_part.get_content_charset() or "utf-8"
    try:
        return raw.decode(charset, errors="replace")
    except (LookupError, UnicodeDecodeError):
        return raw.decode("utf-8", errors="replace")


def _message_to_payload(message):
    headers = [
        {"name": str(name), "value": _decode_header_value(value)}
        for name, value in message.items()
    ]

    if message.is_multipart():
        parts = []
        for part in message.get_payload() or []:
            child = _part_to_payload(part)
            if child is not None:
                parts.append(child)
        return {"headers": headers, "parts": parts}

    text = _text_payload(message)
    encoded = ""
    if text:
        encoded = base64.urlsafe_b64encode(
            text.encode("utf-8")
        ).decode("ascii").rstrip("=")

    return {"headers": headers, "body": {"data": encoded}}


def _part_to_payload(part):
    if part.is_multipart():
        return _message_to_payload(part)

    text = _text_payload(part)
    if text is None:
        return None

    encoded = base64.urlsafe_b64encode(
        text.encode("utf-8")
    ).decode("ascii").rstrip("=")

    return {
        "filename": part.get_filename() or "",
        "headers": [
            {"name": str(name), "value": _decode_header_value(value)}
            for name, value in part.items()
        ],
        "body": {"data": encoded},
    }


def message_to_api_shape(raw_message, uid, uidvalidity, folder):
    message = email.message_from_bytes(raw_message)

    date_ms = 0
    raw_date = message.get("Date")
    if raw_date:
        try:
            parsed = parsedate_to_datetime(raw_date)
            if parsed.tzinfo is None:
                parsed = parsed.replace(tzinfo=timezone.utc)
            date_ms = int(parsed.timestamp() * 1000)
        except (TypeError, ValueError, OverflowError):
            pass

    message_id = f"imap:{folder}:{uidvalidity}:{uid}"

    return {
        "id": message_id,
        "threadId": message.get("Message-ID") or message_id,
        "internalDate": str(date_ms),
        "payload": _message_to_payload(message),
    }


def query_to_imap_search(query):
    query = (query or "").strip()

    newer = re.fullmatch(
        r"newer_than:(\d+)([dmy])",
        query,
        flags=re.IGNORECASE,
    )
    if newer:
        amount = int(newer.group(1))
        unit = newer.group(2).lower()
        days = amount if unit == "d" else amount * (30 if unit == "m" else 365)
        since = (
            datetime.now(timezone.utc) - timedelta(days=days)
        ).strftime("%d-%b-%Y")
        return f'(SINCE "{since}")'

    after = re.fullmatch(r"after:(\d{4})/(\d{1,2})/(\d{1,2})", query)
    if after:
        year, month, day = map(int, after.groups())
        month_name = datetime(year, month, day).strftime("%b")
        return f'(SINCE "{day:02d}-{month_name}-{year}")'

    if not query or query.lower() == "all":
        return "ALL"

    # Safe fallback: do not interpret Gmail search syntax as raw IMAP.
    return "ALL"


class _Request:
    def __init__(self, result):
        self._result = result

    def execute(self):
        return self._result


class _Messages:
    def __init__(self, service):
        self._service = service

    def list(self, **kwargs):
        return _Request(
            self._service._list_messages(
                kwargs.get("q"),
                kwargs.get("maxResults", 100),
            )
        )

    def get(self, **kwargs):
        return _Request(self._service._get_message(kwargs["id"]))


class _Users:
    def __init__(self, service):
        self._service = service

    def messages(self):
        return _Messages(self._service)


class IMAPService:
    def __init__(self):
        self.host = os.getenv("GMAIL_IMAP_HOST", "imap.gmail.com")
        self.port = int(os.getenv("GMAIL_IMAP_PORT", "993"))
        self.username = os.getenv("GMAIL_USERNAME", "").strip()
        self.password = os.getenv("GMAIL_APP_PASSWORD", "").replace(" ", "").strip()
        self.folder = os.getenv("GMAIL_IMAP_FOLDER", "INBOX")
        # Manual Gmail checks must finish quickly enough for the Android client.
        # Keep the scan focused on the newest messages; already-processed
        # messages are skipped by ingest_messages before their full body is
        # fetched. The window can be increased with an environment variable.
        self.max_results = max(
            1,
            min(500, int(os.getenv("GMAIL_IMAP_MAX_RESULTS", "100"))),
        )

        if not self.username:
            raise RuntimeError("GMAIL_USERNAME is not configured")
        if not self.password:
            raise RuntimeError("GMAIL_APP_PASSWORD is not configured")

        self._imap = imaplib.IMAP4_SSL(self.host, self.port)
        try:
            self._imap.login(self.username, self.password)
            status, _ = self._imap.select(self.folder, readonly=True)
            if status != "OK":
                raise RuntimeError(f"Could not select Gmail folder: {self.folder}")
        except Exception:
            try:
                self._imap.logout()
            except Exception:
                pass
            raise

        response_code, uidvalidity_data = self._imap.response("UIDVALIDITY")
        if response_code == "UIDVALIDITY" and uidvalidity_data:
            self.uidvalidity = uidvalidity_data[-1].decode(errors="replace")
        else:
            self.uidvalidity = "0"

    def users(self):
        return _Users(self)

    def _list_messages(self, query, max_results):
        search_criteria = query_to_imap_search(query)
        status, data = self._imap.uid("SEARCH", None, search_criteria)
        if status != "OK":
            raise RuntimeError("Gmail IMAP search failed")

        raw_uids = data[0].split() if data and data[0] else []
        # Gmail API defaults to 100 messages and permits up to 500. For IMAP,
        # use the configured bounded window so a manual Android check does not
        # spend tens of seconds fetching hundreds of full messages.
        imap_limit = min(max_results, self.max_results)
        uids = [uid.decode("ascii") for uid in raw_uids[-imap_limit:]]
        uids.reverse()

        messages = []
        if not uids:
            return {"messages": messages}

        # Fetch lightweight headers for the whole bounded UID set in one IMAP
        # request. This is much cheaper than downloading 100 full messages.
        status, header_data = self._imap.uid(
            "FETCH",
            ",".join(uids),
            "(BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)])",
        )
        headers_by_uid = {}
        if status == "OK":
            for item in header_data or []:
                if not (
                    isinstance(item, tuple)
                    and len(item) == 2
                    and isinstance(item[0], bytes)
                    and isinstance(item[1], bytes)
                ):
                    continue
                match = re.search(rb"(\d+) FETCH", item[0])
                if not match:
                    continue
                uid = match.group(1).decode("ascii")
                header_message = email.message_from_bytes(item[1])
                headers_by_uid[uid] = {
                    "from": _decode_header_value(header_message.get("From", "")),
                    "subject": _decode_header_value(header_message.get("Subject", "")),
                }

        for uid in uids:
            item = {"id": f"{self.uidvalidity}:{uid}"}
            item.update(headers_by_uid.get(uid, {}))
            messages.append(item)
        return {"messages": messages}

    def _get_message(self, message_id):
        try:
            uidvalidity, uid = message_id.split(":", 1)
        except ValueError as exc:
            raise RuntimeError("Invalid Gmail IMAP message id") from exc

        if uidvalidity != self.uidvalidity:
            raise RuntimeError("Gmail IMAP UIDVALIDITY changed")

        status, data = self._imap.uid("FETCH", uid, "(RFC822)")
        if status != "OK":
            raise RuntimeError(f"Gmail IMAP fetch failed for UID {uid}")

        raw_message = None
        for item in data or []:
            if (
                isinstance(item, tuple)
                and len(item) == 2
                and isinstance(item[1], bytes)
            ):
                raw_message = item[1]
                break

        if raw_message is None:
            raise RuntimeError(f"Gmail IMAP returned no message for UID {uid}")

        return message_to_api_shape(
            raw_message,
            uid=uid,
            uidvalidity=uidvalidity,
            folder=self.folder,
        )

    def close(self):
        try:
            self._imap.close()
        except Exception:
            pass
        try:
            self._imap.logout()
        except Exception:
            pass
