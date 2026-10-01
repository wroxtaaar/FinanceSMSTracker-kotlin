import base64
import email
import imaplib
import os
import re
from datetime import datetime, timedelta, timezone
from email.header import decode_header
from email.utils import parsedate_to_datetime, parseaddr


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
        self.folder = os.getenv("GMAIL_IMAP_FOLDER", "[Gmail]/All Mail")
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

        self._imap = None
        self.uidvalidity = "0"
        self._connect()

    def _connect(self):
        """Create and initialize the IMAP connection if it is not present."""
        if self._imap is not None:
            return self._imap

        imap_timeout = max(
            5,
            min(30, int(os.getenv("GMAIL_IMAP_TIMEOUT_SECONDS", "20"))),
        )
        imap = None
        try:
            imap = imaplib.IMAP4_SSL(
                self.host, self.port, timeout=imap_timeout
            )
            imap.login(self.username, self.password)
            status, _ = imap.select(self.folder, readonly=True)
            if status != "OK":
                raise RuntimeError(
                    f"Could not select Gmail folder: {self.folder}"
                )
            response_code, uidvalidity_data = imap.response("UIDVALIDITY")
            if response_code == "UIDVALIDITY" and uidvalidity_data:
                self.uidvalidity = uidvalidity_data[-1].decode(errors="replace")
            self._imap = imap
            return imap
        except Exception as exc:
            if imap is not None:
                try:
                    imap.logout()
                except Exception:
                    pass
            raise RuntimeError(f"Gmail IMAP connection failed: {exc}") from exc

    def users(self):
        return _Users(self)

    def _last_synced_since(self):
        """Return a small overlap window based on the newest stored Gmail email.

        IMAP SINCE is date-based, so keep a two-day overlap. This makes the
        incremental scan resilient to a short outage while avoiding a full
        mailbox scan on every manual check.
        """
        initial_days = max(
            1,
            min(90, int(os.getenv("GMAIL_IMAP_INITIAL_DAYS", "30"))),
        )
        fallback = datetime.now(timezone.utc) - timedelta(days=initial_days)

        try:
            from .db import connection

            with connection() as conn:
                row = conn.execute(
                    "SELECT MAX(internal_date) AS max_date "
                    "FROM gmail_messages"
                ).fetchone()
            max_date = int(row["max_date"]) if row and row["max_date"] else 0
            if max_date <= 0:
                return fallback
            latest = datetime.fromtimestamp(max_date / 1000, tz=timezone.utc)
            return latest - timedelta(days=2)
        except Exception:
            # Mail fetching must remain usable even if the optional cursor
            # lookup is unavailable during startup/migration.
            return fallback

    def _list_messages(self, query, max_results):
        # Keep the optimized per-sender search pattern, but make the configured
        # Gmail query part of the actual IMAP SEARCH. The incremental SINCE
        # window is retained as an additional lower bound so normal syncs stay
        # bounded while explicit date queries are never silently ignored.
        imap = self._connect()
        since = self._last_synced_since().strftime("%d-%b-%Y")
        query_criteria = query_to_imap_search(query)
        incremental_criteria = f'SINCE "{since}"'
        if query_criteria == "ALL":
            search_suffix = incremental_criteria
        else:
            search_suffix = f'{query_criteria[1:-1]} {incremental_criteria}'

        sender_rules = (
            # Axis — account/card transaction alerts.
            "alerts@axis.bank.in",
            "alerts@axisbank.com",
            # ICICI — credit-card transaction alerts.
            "credit_cards@icici.bank.in",
            "credit_cards@icicibank.com",
            "customernotification@icici.bank.in",
            "customercare@icicibank.com",
            # HDFC.
            "alerts@hdfcbank.net",
            "alerts@hdfcbank.bank.in",
            # SBI Card / BillDesk.
            "onlinesbicard@sbicard.com",
            "paynet@billdesk.in",
            # HSBC.
            "hsbc@mail.hsbc.co.in",
            "alerts@mail.hsbc.co.in",
            # IndusInd.
            "transactionalert@indusind.com",
            "indusind_bank@indusind.com",
            "IndusInd_Bank@indusind.com",
        )

        per_sender_limit = max(
            1,
            min(50, int(os.getenv("GMAIL_IMAP_PER_SENDER_LIMIT", "25"))),
        )
        uid_to_sender = {}
        sender_search_counts = {}

        for sender in sender_rules:
            criteria = f'(FROM "{sender}" {search_suffix})'
            try:
                status, data = imap.uid("SEARCH", None, criteria)
            except (OSError, imaplib.IMAP4.error) as exc:
                raise RuntimeError(
                    f"Gmail IMAP sender search failed for {sender}: {exc}"
                ) from exc

            if status != "OK":
                raise RuntimeError(
                    f"Gmail IMAP sender search failed for {sender}"
                )

            raw_uids = data[0].split() if data and data[0] else []
            sender_search_counts[sender] = len(raw_uids)
            for uid in raw_uids[-per_sender_limit:]:
                uid_text = uid.decode("ascii")
                # The same UID should only exist once in the selected folder.
                uid_to_sender.setdefault(uid_text, sender)

        uids = sorted(
            uid_to_sender,
            key=lambda value: int(value),
            reverse=True,
        )

        # Phase 1: batch-fetch only lightweight headers for candidate UIDs.
        # Full RFC822 is fetched later only for messages that are not already
        # terminally processed by ingest_messages.
        headers_by_uid = {}
        batch_size = max(
            50,
            min(500, int(os.getenv("GMAIL_IMAP_HEADER_BATCH_SIZE", "500"))),
        )

        for batch_start in range(0, len(uids), batch_size):
            batch = uids[batch_start : batch_start + batch_size]
            status, header_data = imap.uid(
                "FETCH",
                ",".join(batch),
                "(UID X-GM-MSGID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID)])",
            )
            if status != "OK":
                raise RuntimeError("Gmail IMAP header fetch failed")

            for item in header_data or []:
                if not (
                    isinstance(item, tuple)
                    and len(item) == 2
                    and isinstance(item[0], bytes)
                    and isinstance(item[1], bytes)
                ):
                    continue

                uid_match = re.search(rb"\bUID\s+(\d+)", item[0])
                if not uid_match:
                    # Some Gmail responses expose the sequence number but not
                    # UID in the metadata line. Fall back to the first numeric
                    # token immediately before FETCH.
                    uid_match = re.search(rb"(\d+) FETCH", item[0])
                if not uid_match:
                    continue

                uid = uid_match.group(1).decode("ascii")
                header_message = email.message_from_bytes(item[1])
                headers_by_uid[uid] = {
                    "from": _decode_header_value(
                        header_message.get("From", "")
                    ),
                    "subject": _decode_header_value(
                        header_message.get("Subject", "")
                    ),
                }

        messages = []
        sender_counts = {}
        for uid in uids:
            item = {"id": f"{self.uidvalidity}:{uid}"}
            item.update(headers_by_uid.get(uid, {}))
            sender = parseaddr(item.get("from", ""))[1].lower()
            if sender:
                sender_counts[sender] = sender_counts.get(sender, 0) + 1
            messages.append(item)

        # Return compact fetch diagnostics even when no UIDs matched. This
        # makes a manual Gmail check distinguish "search found nothing" from
        # "header fetch returned nothing" without exposing message bodies or
        # credentials.
        return {
            "messages": messages,
            "diagnostics": {
                "folder": getattr(self, "folder", ""),
                "query": (query or "").strip(),
                "searchSuffix": search_suffix,
                "senderSearchCounts": sender_search_counts,
                "candidateUidCount": len(uids),
                "headerCount": len(headers_by_uid),
                "senderCounts": sender_counts,
            },
        }

    def _get_message(self, message_id):
        try:
            uidvalidity, uid = message_id.split(":", 1)
        except ValueError as exc:
            raise RuntimeError("Invalid Gmail IMAP message id") from exc

        if uidvalidity != self.uidvalidity:
            raise RuntimeError("Gmail IMAP UIDVALIDITY changed")

        imap = self._connect()
        status, data = imap.uid("FETCH", uid, "(RFC822)")
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
        imap = self._imap
        self._imap = None
        if imap is None:
            return
        try:
            imap.close()
        except Exception:
            pass
        try:
            imap.logout()
        except Exception:
            pass
