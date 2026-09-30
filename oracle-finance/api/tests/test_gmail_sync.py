import base64
import os
import tempfile

DB_FILE = os.path.join(tempfile.gettempdir(), "oracle-finance-gmail-test.db")
try:
    os.remove(DB_FILE)
except FileNotFoundError:
    pass

from app import db
db.DB_PATH = DB_FILE

from app.db import init_db, connection
from app.gmail_sync import ingest_messages, parse_bank_email
from app.ledger import set_balance, sync_transaction

init_db()


def _message(message_id, body, subject="HDFC Bank Transaction Alert", internal_date="1950000000000"):
    encoded = base64.urlsafe_b64encode(body.encode()).decode().rstrip("=")
    return {
        "id": message_id,
        "threadId": "thread-" + message_id,
        "internalDate": internal_date,
        "payload": {
            "headers": [
                {"name": "Subject", "value": subject},
                {"name": "From", "value": "alerts@example.com"},
            ],
            "body": {"data": encoded},
        },
    }


class _Messages:
    def __init__(self, messages):
        self._messages = messages

    def list(self, **kwargs):
        return self

    def get(self, **kwargs):
        return self

    def execute(self):
        if hasattr(self, "_get_message"):
            return self._get_message
        return {"messages": [{"id": m["id"]} for m in self._messages]}

    def set_get_message(self, message):
        self._get_message = message
        return self


class _Users:
    def __init__(self, messages):
        self._messages_data = messages
        self._messages = _Messages(messages)

    def messages(self):
        return self._messages


class FakeService:
    def __init__(self, messages):
        self._users = _Users(messages)

    def users(self):
        return self._users


def test_gmail_parser_supports_card_and_supported_banks():
    message = _message(
        "parser-1",
        "ICICI Bank Card ending 1012: INR 12,345.67 spent on merchant."
    )
    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 1234567
    assert transaction.type == "DEBIT"
    assert transaction.bank == "ICICI"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.accountLast4 == "1012"
    assert evidence.sourceType == "GMAIL"


def test_gmail_unique_transaction_updates_balance_once():
    message = _message(
        "unique-1",
        "HDFC Bank A/c XX9591 debited INR 5.00. Ref UPI-12345."
    )
    service = FakeService([message])

    # Patch the get() response because the tiny fake keeps list/get state
    # separate while matching Gmail's fluent API.
    service._users._messages.set_get_message(message)

    set_balance("gmail-unique", "Gmail Unique", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)
    created = ingest_messages(service, query="newer_than:30d")

    assert created == 1
    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-unique'"
        ).fetchone()["balance_minor"]
        adjustments = conn.execute(
            "SELECT COUNT(*) value FROM balance_adjustments WHERE transaction_id LIKE 'gmail:%'"
        ).fetchone()["value"]

    assert balance == 99500
    assert adjustments == 1


def test_gmail_duplicate_of_sms_does_not_reduce_balance_twice():
    sms_body = "HDFC Bank A/c XX9591 debited INR 250.00. Ref DUP-12345."
    gmail_body = "HDFC Bank A/c XX9591 debited INR 250.00. Ref DUP-12345."

    gmail_message = _message(
        "duplicate-1",
        gmail_body,
        internal_date="1950000000000",
    )

    set_balance("gmail-dedupe", "Gmail Dedupe", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)

    class T:
        id = "sms-canonical"
        amountMinor = 25000
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "TEST"
        accountLast4 = "9591"
        reference = "DUP-12345"
        timestamp = 1950000000000
        category = "OTHER"
        confidence = 0.95

    sync_transaction(T())

    # The parser/ingestor should recognize the Gmail copy as the same
    # transaction and avoid a second balance adjustment.
    service = FakeService([gmail_message])
    service._users._messages.set_get_message(gmail_message)
    ingest_messages(service, query="newer_than:30d")

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-dedupe'"
        ).fetchone()["balance_minor"]
        adjustment_count = conn.execute(
            "SELECT COUNT(*) value FROM balance_adjustments WHERE account_id='gmail-dedupe'"
        ).fetchone()["value"]

    assert balance == 97500
    assert adjustment_count == 1
