"""Gmail tests: ingest."""
import gmail_test_support  # noqa: F401  (sets up the shared test database)
from app.db import connection
from app.gmail_sync import ingest_messages
from app.ledger import set_balance, sync_transaction
from gmail_test_support import FakeService, _message


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


def test_parsed_gmail_message_without_active_transaction_is_reprocessed():
    message = _message(
        "axis-orphaned-parsed",
        "Amount Credited: INR 5.00 Account Number: XX3370 "
        "Transaction Info: UPI/P2A/123456789012/ABDUL WAS/HDFC/Paym",
        subject="INR 5.00 was credited to your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 5.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    with connection() as conn:
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                message["id"],
                "thread-axis-orphaned",
                1950000000000,
                "alerts@axis.bank.in",
                "INR 5.00 was credited to your A/c.",
                "axis-orphaned-fingerprint",
                "PARSED",
                1950000000000,
            ),
        )

    result = ingest_messages(FakeService([message]), query="newer_than:30d")

    assert result["parsedTransactions"] == 1
    assert result["axisCredits"] == 1

    with connection() as conn:
        tx = conn.execute(
            """
            SELECT type,bank,account_type,account_last4,status
            FROM transactions
            WHERE id LIKE 'gmail:%'
              AND amount_minor=500
              AND bank='AXIS'
              AND account_last4='3370'
            ORDER BY created_at DESC
            LIMIT 1
            """
        ).fetchone()
        status = conn.execute(
            "SELECT status FROM gmail_messages WHERE id=?",
            (message["id"],),
        ).fetchone()["status"]

    assert tx["type"] == "CREDIT"
    assert tx["bank"] == "AXIS"
    assert tx["account_type"] == "BANK_ACCOUNT"
    assert tx["account_last4"] == "3370"
    assert tx["status"] == "ACTIVE"
    assert status == "PARSED"


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


def test_ingest_records_a_log_line_per_message():
    message = _message(
        "log-1",
        "HDFC Bank A/c XX9591 debited INR 5.00. Ref UPI-LOG-1.",
    )
    service = FakeService([message])
    set_balance("gmail-log", "Gmail Log", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)

    stats = ingest_messages(service, query="newer_than:30d")

    log = stats["messageLog"]
    assert len(log) == 1
    assert log[0]["subject"] == "HDFC Bank Transaction Alert"
    assert "Read as DEBIT 5.00 INR HDFC" in log[0]["outcome"]
