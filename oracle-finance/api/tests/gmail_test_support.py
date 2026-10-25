"""Shared Gmail test setup: isolated DB file (created on first import) and fake Gmail service."""
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

from app.gmail_sync import (
    ingest_messages,
    parse_bank_email,
    _gmail_default_category,
    _repair_legacy_gmail_account_classifications,
    _repair_legacy_axis_credit_direction_conflicts,
    _repair_legacy_unidentified_hdfc_gmail_transactions,
    _repair_legacy_icici_credit_card_classifications,
    _repair_legacy_gmail_merchants,
    _repair_legacy_gmail_merchant_values,
    _repair_self_transfer_gmail_merchants,
)

from app.ledger import set_balance, sync_transaction

from app.statement_sync import (
    _hdfc_metadata,
    _parse_hdfc_rows,
    parse_hdfc_credit_card_statement,
    _icici_metadata,
    parse_icici_statement,
    process_statement_attachments,
)

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
        self._mode = "list"
        self.last_get_id = None

    def list(self, **kwargs):
        self._mode = "list"
        return self

    def get(self, **kwargs):
        self._mode = "get"
        self.last_get_id = kwargs.get("id")
        return self

    def execute(self):
        if self._mode == "get":
            return self._messages[0]
        return {"messages": [{"id": m["id"]} for m in self._messages]}

class _Users:
    def __init__(self, messages):
        self._messages = _Messages(messages)

    def messages(self):
        return self._messages

class FakeService:
    def __init__(self, messages):
        self._users = _Users(messages)

    def users(self):
        return self._users
