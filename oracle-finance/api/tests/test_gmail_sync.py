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
    _repair_legacy_gmail_account_classifications,
)
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
        self._mode = "list"

    def list(self, **kwargs):
        self._mode = "list"
        return self

    def get(self, **kwargs):
        self._mode = "get"
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

def test_gmail_parser_supports_card_and_supported_banks():
    message = _message(
        "parser-1",
        "ICICI Bank Card ending 1012: INR 12,345.67 spent on merchant.",
        subject="ICICI Card Transaction Alert"
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

    set_balance("gmail-unique", "Gmail Unique", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)
    created = ingest_messages(service, query="newer_than:30d")

    assert created["parsedTransactions"] == 1
    assert created["createdEvidence"] == 1
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
    gmail_body = "HDFC Bank A/c XX7777 debited INR 250.00. Ref DUP-12345."

    gmail_message = _message(
        "duplicate-1",
        gmail_body,
        internal_date="1950000000000",
    )

    set_balance("gmail-dedupe", "Gmail Dedupe", "INR", "BANK_ACCOUNT", "HDFC", "7777", 100000)

    class T:
        id = "sms-canonical"
        amountMinor = 25000
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "TEST"
        accountLast4 = "7777"
        reference = "DUP-12345"
        timestamp = 1950000000000
        category = "OTHER"
        confidence = 0.95

    sync_transaction(T())

    # The parser/ingestor should recognize the Gmail copy as the same
    # transaction and avoid a second balance adjustment.
    service = FakeService([gmail_message])
    first = ingest_messages(service, query="newer_than:30d")
    second = ingest_messages(service, query="newer_than:30d")

    # The first pass parses and reconciles the Gmail copy. The second pass
    # must treat the now-PARSED message as terminal and do no work.
    assert first["parsedTransactions"] == 1
    assert first["duplicateTransactions"] == 1
    assert second["parsedTransactions"] == 0
    assert second["alreadyProcessed"] == 1

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-dedupe'"
        ).fetchone()["balance_minor"]
        adjustment_count = conn.execute(
            "SELECT COUNT(*) value FROM balance_adjustments WHERE account_id='gmail-dedupe'"
        ).fetchone()["value"]

    assert balance == 75000
    assert adjustment_count == 1



def test_gmail_parser_rejects_promotional_annual_fee_email():
    message = _message(
        "promo-annual-fee",
        "Dear Customer, enjoy an Annual Fee waiver for Axis Bank ACE Credit Card "
        "XX1175 on annual spends of INR 200000 by 31-07-27. Visit https://axis.example for details.",
        subject="Axis Bank Credit Card Offer",
    )
    assert parse_bank_email(message) is None


def test_gmail_parser_rejects_failed_transaction_email():
    message = _message(
        "failed-1",
        "HDFC Bank A/c XX9591 transaction of INR 500.00 failed due to network timeout.",
        subject="HDFC Bank Transaction Alert",
    )
    assert parse_bank_email(message) is None


def test_gmail_parser_rejects_future_payment_email():
    message = _message(
        "future-1",
        "HDFC Bank A/c XX9591 INR 500.00 will be debited on 10-Oct-2026.",
        subject="HDFC Bank Payment Notification",
    )
    assert parse_bank_email(message) is None


def test_gmail_parser_rejects_content_without_account_identity():
    message = _message(
        "noise-1",
        "Today: INR 55.00 spent on your card. Learn more in the references-center.",
        subject="Generic Card Newsletter",
    )

    assert parse_bank_email(message) is None


def test_gmail_parser_rejects_non_transaction_reference_text():
    message = _message(
        "noise-2",
        "HDFC Bank A/c XX9591. INR 5.00. Visit references-center for formatting.",
        subject="HDFC Account Information",
    )

    assert parse_bank_email(message) is None


def test_gmail_parser_extracts_numeric_reference_only():
    message = _message(
        "ref-1",
        "HDFC Bank A/c XX9591 debited INR 5.00. Ref UPI-12345.",
    )

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.reference == "UPI-12345"


def test_axis_bank_credit_email_is_parsed_from_trusted_sender():
    message = _message(
        "axis-credit-1",
        "Your A/c XX1234 has been credited with INR 1.00. "
        "Transaction reference: 123456789.",
        subject="Axis Bank Credit Alert",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Axis Bank Credit Alert"},
        {"name": "From", "value": "alerts@axisbank.com"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 100
    assert transaction.type == "CREDIT"
    assert transaction.bank == "AXIS"
    assert transaction.accountType == "BANK_ACCOUNT"
    assert transaction.accountLast4 == "1234"
    assert evidence.direction == "CREDIT"


def test_axis_credit_with_available_balance_is_not_rejected():
    message = _message(
        "axis-credit-balance",
        "Your A/c XX1234 has been credited with INR 1.00. "
        "Transaction reference: 987654321. Available balance is INR 101.00.",
        subject="Axis Bank Credit Alert",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Axis Bank Credit Alert"},
        {"name": "From", "value": "alerts@axisbank.com"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.amountMinor == 100
    assert transaction.type == "CREDIT"
    assert transaction.bank == "AXIS"


def test_gmail_sync_reports_axis_credit_diagnostic():
    message = _message(
        "axis-credit-diagnostic",
        "Your A/c XX1234 has been credited with INR 1.00. "
        "Transaction reference: 987654321.",
        subject="Axis Bank Credit Alert",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Axis Bank Credit Alert"},
        {"name": "From", "value": "alerts@axisbank.com"},
    ]

    result = ingest_messages(FakeService([message]), query="newer_than:30d")

    assert result["messagesScanned"] == 1
    assert result["parsedTransactions"] == 1
    assert result["axisCredits"] == 1
    assert result["duplicateTransactions"] == 0
    assert result["reviewCount"] == 0

def test_axis_real_bank_in_sender_and_html_style_credit_alert():
    message = _message(
        "axis-real-format",
        "01-10-2026 Dear Customer, Here's the summary of your transaction: "
        "Amount Credited: INR 1.00 Account Number: XX3370 "
        "Date & Time: 01-10-26, 17:15:42 IST "
        "Transaction Info: UPI/P2A/18335801167/ABDUL WAS/HDFC/Paym",
        subject="INR 1.00 was credited to your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 1.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.amountMinor == 100
    assert transaction.type == "CREDIT"
    assert transaction.bank == "AXIS"
    assert transaction.accountLast4 == "3370"
    assert transaction.reference == "UPI/P2A/18335801167/ABDUL"
    assert transaction.accountType == "BANK_ACCOUNT"


def test_axis_account_alert_takes_priority_over_card_footer_text():
    message = _message(
        "axis-account-priority",
        "Here's the summary of your transaction: "
        "Amount Credited: INR 5.00 Account Number: XX3370. "
        "Never share your OTP or CVV. Apply Now for a credit card.",
        subject="INR 5.00 was credited to your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 5.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.bank == "AXIS"
    assert transaction.type == "CREDIT"
    assert transaction.accountType == "BANK_ACCOUNT"
    assert transaction.accountLast4 == "3370"


def test_repair_legacy_axis_credit_moves_balance_to_bank_account():
    set_balance(
        "axis-repair",
        "Axis Bank 3370",
        "INR",
        "BANK_ACCOUNT",
        "AXIS",
        "3370",
        100000,
    )

    transaction_id = "gmail:legacy-axis-credit"
    gmail_id = "11:99001"

    with connection() as conn:
        conn.execute(
            """
            INSERT INTO transactions
            (id,amount_minor,currency,type,payment_method,account_type,bank,
             merchant_or_payee,account_last4,reference,timestamp,category,
             confidence,duplicate_of,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                transaction_id,
                500,
                "INR",
                "CREDIT",
                "CARD",
                "CREDIT_CARD",
                "AXIS",
                None,
                "3370",
                "LEGACY-AXIS",
                1950000000000,
                "OTHER",
                0.95,
                None,
                "ACTIVE",
                1950000000000,
            ),
        )
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                gmail_id,
                "thread-legacy-axis",
                1950000000000,
                "alerts@axis.bank.in",
                "INR 5.00 was credited to your A/c.",
                "legacy-fingerprint",
                "PARSED",
                1950000000000,
            ),
        )
        conn.execute(
            """
            INSERT INTO evidence
            (id,source_type,source_id,status,observed_at,transaction_id,
             matched_transaction_id,amount_minor,currency,direction,
             bank_provider,account_last4,reference,content_hash,confidence,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                "gmail-evidence:"+gmail_id,
                "GMAIL",
                gmail_id,
                "UNMATCHED",
                1950000000000,
                transaction_id,
                None,
                500,
                "INR",
                "CREDIT",
                "AXIS",
                "3370",
                "LEGACY-AXIS",
                "legacy-hash",
                0.95,
                1950000000000,
            ),
        )

    assert _repair_legacy_gmail_account_classifications() == 1
    assert _repair_legacy_gmail_account_classifications() == 0

    with connection() as conn:
        tx = conn.execute(
            "SELECT account_type,payment_method FROM transactions WHERE id=?",
            (transaction_id,),
        ).fetchone()
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='axis-repair'"
        ).fetchone()["balance_minor"]
        adjustment = conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,),
        ).fetchone()

    assert tx["account_type"] == "BANK_ACCOUNT"
    assert tx["payment_method"] == "UPI"
    assert balance == 100500
    assert adjustment["account_id"] == "axis-repair"
    assert adjustment["delta_minor"] == 500


def test_icici_real_credit_card_alert_from_bank_in_sender():
    message = _message(
        "icici-real-format",
        "Dear Customer, Your ICICI Bank Credit Card XX1012 has been used "
        "for a transaction of INR 548.00 on Oct 01, 2026 at 06:05:28. "
        "Info: AMAZON PAY IN RECHARGE. "
        "The Available Credit Limit on your card is INR 346849.06 and Total Credit Limit is INR 380000.00.",
        subject="Transaction alert for your ICICI Bank Credit Card",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Transaction alert for your ICICI Bank Credit Card"},
        {"name": "From", "value": "credit_cards@icici.bank.in"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.amountMinor == 54800
    assert transaction.type == "DEBIT"
    assert transaction.bank == "ICICI"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.accountLast4 == "1012"
