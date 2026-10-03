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

def test_pending_statement_is_retried_outside_incremental_date_window(monkeypatch):
    import app.gmail_sync as gmail_sync

    message = _message(
        "11:88696",
        "statement attachment placeholder",
        subject="Amazon Pay ICICI Bank Credit Card Statement for the period August 29, 2026 to September 28, 2026",
        internal_date="1788177600000",
    )

    with connection() as conn:
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                message["id"],
                "thread-pending-statement",
                1788177600000,
                "credit_cards@icici.bank.in",
                message["payload"]["headers"][0]["value"],
                "pending-statement-fingerprint",
                "PENDING",
                1788177600000,
            ),
        )

    calls = []

    def fake_statement_processor(service, fetched_message):
        calls.append(fetched_message["id"])
        return {
            "attachmentsScanned": 1,
            "attachmentsParsed": 1,
            "transactionsAdded": 1,
            "transactionsMatched": 2,
            "errors": [],
        }

    monkeypatch.setattr(gmail_sync, "process_statement_attachments", fake_statement_processor)

    stats = gmail_sync.ingest_messages(
        FakeService([message]),
        query="after:2026/10/01",
    )

    assert calls == ["11:88696"]
    assert stats["pendingStatementRetries"] == 1
    assert stats["statementAttachmentsParsed"] == 1
    assert stats["statementTransactionsAdded"] == 1
    assert stats["statementTransactionsMatched"] == 2

    with connection() as conn:
        status = conn.execute(
            "SELECT status FROM gmail_messages WHERE id=?",
            (message["id"],),
        ).fetchone()["status"]

    assert status == "PARSED"


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
                "imap:[Gmail]/All Mail:"+gmail_id,
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
        "The Available Credit Limit on your card is INR 346849.06 and Total Credit Limit is INR 380000.00. "
        "You can pay your Credit Card bills from your bank account.",
        subject="Transaction alert for your ICICI Bank Credit Card",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Transaction alert for your ICICI Bank Credit Card"},
        {"name": "From", "value": "credit_cards@icici.bank.in"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 54800
    assert transaction.type == "DEBIT"
    assert transaction.bank == "ICICI"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.paymentMethod == "CARD"
    assert transaction.accountLast4 == "1012"
    assert transaction.merchantOrPayee == "AMAZON PAY IN RECHARGE"
    assert transaction.reference is None
    assert evidence.reference is None


def test_repair_legacy_icici_credit_card_reparses_existing_row():
    message = _message(
        "imap:[Gmail]/All Mail:11:99002",
        "Dear Customer, Your ICICI Bank Credit Card XX1012 has been used "
        "for a transaction of INR 641.00 on Sep 27, 2026 at 12:17:29. "
        "Info: AMAZON PAY GROCERY. "
        "The Available Credit Limit on your card is INR 100000.00 and Total Credit Limit is INR 200000.00. "
        "You can pay your Credit Card bills from your bank account.",
        subject="Transaction alert for your ICICI Bank Credit Card",
        internal_date="1790491657000",
    )
    parsed = parse_bank_email(message)
    assert parsed is not None
    parsed_transaction, _ = parsed

    set_balance(
        "icici-repair",
        "ICICI Credit Card 1012",
        "INR",
        "CREDIT_CARD",
        "ICICI",
        "1012",
        100000,
    )
    # This fixture is intentionally unreconciled: the test verifies that
    # legacy Gmail repair can apply the historical transaction to the balance.
    with connection() as conn:
        conn.execute("UPDATE accounts SET balance_reconciled_at=0 WHERE id='icici-repair'")

    gmail_id = "11:99002"
    transaction_id = parsed_transaction.id

    with connection() as conn:
        conn.execute(
            """
            INSERT INTO transactions
            (id,amount_minor,currency,type,payment_method,account_type,bank,
             merchant_or_payee,account_last4,reference,timestamp,category,
             confidence,duplicate_of,status,created_at)            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                transaction_id,
                64100,
                "INR",
                "DEBIT",
                "UPI",
                "BANK_ACCOUNT",
                "ICICI",
                None,
                "1012",
                "alert/1",
                1790491657000,
                "OTHER",
                1.0,
                None,
                "ACTIVE",
                1790491657000,
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
                "thread-icici-legacy",
                1790491657000,
                "credit_cards@icici.bank.in",
                "Transaction alert for your ICICI Bank Credit Card",
                "icici-legacy-fingerprint",
                "PARSED",
                1790491657000,
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
                "gmail-evidence:"+message["id"],
                "GMAIL",
                message["id"],
                "UNMATCHED",
                1790491657000,
                transaction_id,
                None,
                64100,
                "INR",
                "DEBIT",
                "ICICI",
                "1012",
                "alert/1",
                "legacy-icici-hash",
                1.0,
                1790491657000,
            ),
        )

    service = FakeService([message])
    assert _repair_legacy_icici_credit_card_classifications(service) == 1
    assert service.users().messages().last_get_id == "11:99002"
    assert _repair_legacy_icici_credit_card_classifications(service) == 0

    with connection() as conn:
        tx = conn.execute(
            "SELECT account_type,payment_method,merchant_or_payee,reference FROM transactions WHERE id=?",
            (transaction_id,),
        ).fetchone()
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='icici-repair'"
        ).fetchone()["balance_minor"]
        adjustment = conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,),
        ).fetchone()
        evidence = conn.execute(
            "SELECT reference,matched_transaction_id FROM evidence WHERE source_id=?",
            (message["id"],),
        ).fetchone()

    assert tx["account_type"] == "CREDIT_CARD"
    assert tx["payment_method"] == "CARD"
    assert tx["merchant_or_payee"] == "AMAZON PAY GROCERY"
    assert tx["reference"] is None
    assert balance == 164100
    assert adjustment["account_id"] == "icici-repair"
    assert adjustment["delta_minor"] == 64100
    assert evidence["reference"] is None
    assert evidence["matched_transaction_id"] is None



def test_repair_legacy_gmail_merchant_reparses_existing_row():
    message = _message(
        "imap:[Gmail]/All Mail:11:99003",
        "Dear Customer, Your ICICI Bank Credit Card XX1012 has been used "
        "for a transaction of INR 548.00 on Oct 01, 2026 at 06:05:28. "
        "Info: AMAZON PAY IN RECHARGE. "
        "The Available Credit Limit on your card is INR 100000.00.",
        subject="Transaction alert for your ICICI Bank Credit Card",
        internal_date="1790856328000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Transaction alert for your ICICI Bank Credit Card"},
        {"name": "From", "value": "credit_cards@icici.bank.in"},
    ]

    parsed = parse_bank_email(message)
    assert parsed is not None
    parsed_transaction, _ = parsed

    transaction_id = parsed_transaction.id

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
                54800,
                "INR",
                "DEBIT",
                "CARD",
                "CREDIT_CARD",
                "ICICI",
                None,
                "1012",
                None,
                1790856328000,
                "OTHER",
                1.0,
                None,
                "ACTIVE",
                1790856328000,
            ),
        )
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                "11:99003",
                "thread-icici-merchant",
                1790856328000,
                "credit_cards@icici.bank.in",
                "Transaction alert for your ICICI Bank Credit Card",
                "icici-merchant-fingerprint",
                "PARSED",
                1790856328000,
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
                "gmail-evidence:" + message["id"],
                "GMAIL",
                message["id"],
                "UNMATCHED",
                1790856328000,
                transaction_id,
                None,
                54800,
                "INR",
                "DEBIT",
                "ICICI",
                "1012",
                None,
                "legacy-merchant-hash",
                1.0,
                1790856328000,
            ),
        )

    service = FakeService([message])
    assert _repair_legacy_gmail_merchants(service) == 1
    assert _repair_legacy_gmail_merchants(service) == 0

    with connection() as conn:
        tx = conn.execute(
            "SELECT merchant_or_payee FROM transactions WHERE id=?",
            (transaction_id,),
        ).fetchone()

    assert tx["merchant_or_payee"] == "AMAZON PAY IN RECHARGE"


def test_axis_self_transfer_merchant_can_use_matching_hdfc_entry():
    axis_message = _message(
        "imap:[Gmail]/All Mail:11:99005",
        "Dear Abdul Wasiq, Here's the summary of your transaction. "
        "Amount Credited: INR 6.00 Account Number: XX3370 "
        "Transaction Info: UPI/P2A/930624306800/ABDUL WAS/HDFC/Paym",
        subject="INR 6.00 was credited to your A/c.",
        internal_date="1790805600000",
    )
    axis_message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 6.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]
    parsed = parse_bank_email(axis_message)
    assert parsed is not None
    axis_tx, _ = parsed

    class Hdfc:
        id = "sms-hdfc-self-transfer"
        amountMinor = 600
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "ABDUL WASIQ"
        accountLast4 = "9591"
        reference = "930624306800"
        timestamp = 1790805595000
        category = "TRANSFER"
        confidence = 1.0

    sync_transaction(Hdfc())

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
                axis_tx.id, 600, "INR", "CREDIT", "UPI", "BANK_ACCOUNT",
                "AXIS", None, "3370", axis_tx.reference,
                1790805600000, "OTHER", 1.0, None, "ACTIVE", 1790805600000
            ),
        )
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                "11:99005", "thread-axis-self-transfer", 1790805600000,
                "alerts@axis.bank.in", "INR 6.00 was credited to your A/c.",
                "axis-self-transfer-fingerprint", "PARSED", 1790805600000
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
                "gmail-evidence:" + axis_message["id"], "GMAIL",
                axis_message["id"], "UNMATCHED", 1790805600000, axis_tx.id,
                None, 600, "INR", "CREDIT", "AXIS", "3370",
                axis_tx.reference, "axis-self-transfer-hash", 1.0,
                1790805600000
            ),
        )

    assert _repair_self_transfer_gmail_merchants(FakeService([axis_message])) == 1
    assert _repair_self_transfer_gmail_merchants(FakeService([axis_message])) == 0

    with connection() as conn:
        row = conn.execute(
            "SELECT merchant_or_payee FROM transactions WHERE id=?",
            (axis_tx.id,),
        ).fetchone()

    assert row["merchant_or_payee"] == "ABDUL WASIQ"


def test_axis_transaction_info_is_not_merchant():
    message = _message(
        "axis-no-merchant",        "Dear Customer, Here's the summary of your transaction: "
        "Amount Debited: INR 6.00 Account Number: XX3370 "
        "Date & Time: 30-09-26, 23:54:16 IST "
        "Transaction Info: UPI/P2A/361639089310/ABDUL WASIQ "
        "If this transaction was not initiated by you: To block UPI: SMS BLOCKUPI.",
        subject="INR 6.00 was debited from your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 6.00 was debited from your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.bank == "AXIS"
    assert transaction.accountType == "BANK_ACCOUNT"
    assert transaction.reference == "UPI/P2A/361639089310/ABDUL"
    assert transaction.merchantOrPayee == "ABDUL WASIQ"


def test_repair_legacy_gmail_merchant_values_replaces_axis_disclaimer():
    message = _message(
        "imap:[Gmail]/All Mail:11:99004",
        "Dear Customer, Here's the summary of your transaction: "
        "Amount Debited: INR 6.00 Account Number: XX3370 "
        "Date & Time: 30-09-26, 23:54:16 IST "
        "Transaction Info: UPI/P2A/361639089310/ABDUL WASIQ "
        "If this transaction was not initiated by you: To block UPI: SMS BLOCKUPI.",
        subject="INR 6.00 was debited from your A/c.",
        internal_date="1790805256000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 6.00 was debited from your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]
    parsed = parse_bank_email(message)
    assert parsed is not None
    parsed_transaction, _ = parsed

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
                parsed_transaction.id,
                600,
                "INR",
                "DEBIT",
                "UPI",
                "BANK_ACCOUNT",
                "AXIS",
                "UPI/P2A/361639089310/ABDUL WASIQ If this transaction was not initiated by you",
                "3370",
                "UPI/P2A/361639089310/ABDUL",
                1790805256000,
                "OTHER",
                1.0,
                None,
                "ACTIVE",
                1790805256000,
            ),
        )
        conn.execute(
            """
            INSERT INTO gmail_messages
            (id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)
            VALUES (?,?,?,?,?,?,?,?)
            """,
            (
                "11:99004",
                "thread-axis-merchant",
                1790805256000,
                "alerts@axis.bank.in",
                "INR 6.00 was debited from your A/c.",
                "axis-merchant-fingerprint",
                "PARSED",
                1790805256000,
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
                "gmail-evidence:" + message["id"],
                "GMAIL",
                message["id"],
                "UNMATCHED",
                1790805256000,
                parsed_transaction.id,
                None,
                600,
                "INR",
                "DEBIT",
                "AXIS",
                "3370",
                parsed_transaction.reference,
                "axis-legacy-merchant-hash",
                1.0,
                1790805256000,
            ),
        )

    service = FakeService([message])
    assert _repair_legacy_gmail_merchant_values(service) == 1
    assert _repair_legacy_gmail_merchant_values(service) == 0

    with connection() as conn:
        tx = conn.execute(
            "SELECT merchant_or_payee FROM transactions WHERE id=?",
            (parsed_transaction.id,),
        ).fetchone()

    assert tx["merchant_or_payee"] == "ABDUL WASIQ"


def test_icici_transaction_subject_is_not_a_reference():
    message = _message(
        "icici-no-fake-ref",
        "Your ICICI Bank Credit Card XX1012 has been used for a transaction "
        "of INR 641.00. Info: AMAZON PAY GROCERY. "
        "The Available Credit Limit on your card is INR 100000.00.",
        subject="Transaction alert for your ICICI Bank Credit Card",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Transaction alert for your ICICI Bank Credit Card"},
        {"name": "From", "value": "credit_cards@icici.bank.in"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.reference is None
    assert transaction.merchantOrPayee == "AMAZON PAY GROCERY"


def test_hdfc_statement_text_parser_matches_statement_layout():
    text = """HDFC Bank Ltd
Account Number : 50100534629591
Statement From : 01/08/2026 To 31/08/2026
Currency : INR
Opening Balance : 1,000.00
Txn Date Narration Withdrawals Deposits Closing Balance
02/08/2026 UPI-TEST-MERCHANT-test@upi 100.00 0.00 900.00
Value Dt 02/08/2026 Ref
111111111111
03/08/2026 UPI-SECOND-shop@upi 50.00 0.00 850.00
Value Dt 03/08/2026 Ref
222222222222
04/08/2026 FT-A2A - - - 0.00 200.00 1,050.00
WBS SALARY ACCOUNT Value Dt 04/08/2026 Ref
333333333333
Page 5 of 5"""
    metadata = _hdfc_metadata(text)
    rows = _parse_hdfc_rows(text, metadata)

    assert metadata["bank"] == "HDFC"
    assert metadata["account_last4"] == "9591"
    assert metadata["currency"] == "INR"
    assert len(rows) == 3
    assert rows[0]["amount_minor"] == 10000
    assert rows[0]["type"] == "DEBIT"
    assert rows[0]["reference"] == "111111111111"
    assert rows[1]["merchant"] == "SECOND"
    assert rows[2]["type"] == "CREDIT"
    assert rows[2]["amount_minor"] == 20000
    assert rows[2]["merchant"] == "WBS SALARY ACCOUNT"


def test_statement_attachment_is_imported_once_and_updates_balance(monkeypatch):
    import hashlib
    from datetime import datetime, timezone

    set_balance(
        "statement-hdfc",
        "HDFC Statement 9591",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "9591",
        100000,
    )
    # Statement import is testing transaction-derived balance movement,
    # so keep this fixture unreconciled.
    with connection() as conn:
        conn.execute("UPDATE accounts SET balance_reconciled_at=0 WHERE id='statement-hdfc'")

    pdf_bytes = b"test-pdf-bytes"
    encoded = base64.urlsafe_b64encode(pdf_bytes).decode().rstrip("=")
    message = {
        "id": "statement-message-1",
        "payload": {
            "headers": [
                {"name": "Subject", "value": "HDFC e-Statement"},
                {"name": "From", "value": "HDFC Bank <alerts@hdfcbank.net>"},
            ],
            "parts": [
                {
                    "filename": "HDFC_Statement_Aug_2026.pdf",
                    "mimeType": "application/pdf",
                    "body": {"data": encoded},
                }
            ],
        },
    }

    fake_rows = [{
        "date": datetime(2026, 8, 2, 12, tzinfo=timezone.utc),
        "amount_minor": 1000,
        "type": "DEBIT",
        "merchant": "TEST MERCHANT",
        "reference": "REF-1",
        "payment_method": "UPI",
        "category": "OTHER",
        "narration": "UPI-TEST MERCHANT",
    }]
    fake_metadata = {
        "bank": "HDFC",
        "account_last4": "9591",
        "currency": "INR",
        "statement_from": "01/08/2026",
        "statement_to": "31/08/2026",
        "opening_balance_minor": 100000,
    }

    monkeypatch.setenv("HDFC_STATEMENT_SECRET", "fixture-secret")
    monkeypatch.setattr(
        "app.statement_sync.parse_hdfc_statement",
        lambda pdf, key: (
            fake_metadata,
            fake_rows,
        ),
    )

    first = process_statement_attachments(FakeService([message]), message)
    second = process_statement_attachments(FakeService([message]), message)

    assert first["attachmentsParsed"] == 1
    assert first["transactionsAdded"] == 1
    assert second["attachmentsParsed"] == 0
    assert second["transactionsAdded"] == 0

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='statement-hdfc'"
        ).fetchone()["balance_minor"]
        attachment = conn.execute(
            "SELECT status,transaction_count FROM gmail_attachments WHERE message_id='statement-message-1'"
        ).fetchone()
        statements = conn.execute(
            "SELECT COUNT(*) value FROM transactions WHERE id LIKE 'statement:%'"
        ).fetchone()["value"]

    assert balance == 99000
    assert attachment["status"] == "PARSED"
    assert attachment["transaction_count"] == 1
    assert statements == 1


def test_icici_statement_text_parser_matches_uploaded_layout(monkeypatch):
    text = """STATEMENT SUMMARY
SPENDS OVERVIEW
Date SerNo. Transaction Details Reward Points Intl.# amount Amount (in₹)
4315XXXXXXXX1012
31/08/2026 14082331109 BBPS Payment received 0 1,631.00 CR
11/09/2026 14151292488 AMAZON PAY IN E COMMERC BANGALORE
IN
15 504.99
16/09/2026 14183724817 AMAZON PAY IN E COMMERC BANGALORE
IN
14 494.99
# International Spends
Credit Limit (Including cash) Available Credit (Including cash) Cash Limit Available Cash
₹3,80,000.00 ₹3,78,359.02 ₹38,000.00 ₹38,000.00
Statement period : August 29, 2026 to September 28, 2026
"""
    metadata = _icici_metadata(text)
    assert metadata["bank"] == "ICICI"
    assert metadata["account_type"] == "CREDIT_CARD"
    assert metadata["account_last4"] == "1012"
    assert metadata["currency"] == "INR"

    monkeypatch.setattr(
        "app.statement_sync._pdf_text",
        lambda pdf_bytes, key: text,
    )
    metadata, rows = parse_icici_statement(b"fixture", "fixture-secret")
    assert len(rows) == 3
    assert rows[0]["type"] == "CREDIT"
    assert rows[0]["amount_minor"] == 163100
    assert rows[0]["reference"] == "14082331109"
    assert rows[0]["merchant"] == "BBPS Payment received"

    assert rows[1]["type"] == "DEBIT"
    assert rows[1]["amount_minor"] == 50499
    assert rows[1]["merchant"] == "AMAZON PAY IN E COMMERC BANGALORE IN"
    assert rows[2]["amount_minor"] == 49499

def test_hdfc_credit_card_statement_parser_recognizes_payment_as_credit(monkeypatch):
    text = """Millennia Credit Card Statement
Credit Card No.
518159XXXXXX5304
Billing Period
03 Sep, 2026 - 02 Oct, 2026
Domestic Transactions
02/09/2026| 00:00
IGST-VPS2724613165004-RATE 18.0 -09 (Ref# 09999999980902000707495)
 C 166.50
l
04/09/2026| 16:51
BPPY CC PAYMENT DP2162474X634UHN7IH (Ref# ST262480083000010111274)
+  C 9,548.00
l
02/10/2026| 00:00
OFFUS EMI,PRIN NB:02,00000144037257 (Ref# 09999999981002000704649)
 C 4,531.00
l
02/10/2026| 00:00
OFFUS EMI,INT NBR:02,00000144037257 (Ref# 09999999981002000704656)
 C 540.00
l
"""
    monkeypatch.setattr("app.statement_sync._pdf_text", lambda pdf, key: text)

    metadata, rows = parse_hdfc_credit_card_statement(b"fixture", "fixture-secret")

    assert metadata["account_type"] == "CREDIT_CARD"
    assert metadata["account_last4"] == "5304"
    assert [(row["amount_minor"], row["type"]) for row in rows] == [
        (16650, "DEBIT"),
        (954800, "CREDIT"),
        (453100, "DEBIT"),
        (54000, "DEBIT"),
    ]
    assert rows[1]["payment_method"] == "BILL_PAYMENT"
    assert rows[1]["category"] == "PAYMENT"


def test_credit_card_statement_attachment_updates_bill_without_active_spend(monkeypatch):
    set_balance(
        "statement-hdfc-card",
        "HDFC Millennia 5304",
        "INR",
        "CREDIT_CARD",
        "HDFC",
        "5304",
        1478550,
        523800,
    )

    message = {
        "id": "statement-hdfc-card-1",
        "internalDate": "1791026220000",
        "payload": {
            "headers": [
                {
                    "name": "Subject",
                    "value": "Your HDFC Bank - Millennia Credit Card Statement - October-2026",
                },
                {
                    "name": "From",
                    "value": "HDFC Bank Cards <Emailstatements.cards@hdfcbank.bank.in>",
                },
            ],
            "parts": [
                {
                    "filename": "5181XXXXXXXXXX04_02-10-2026_306.pdf",
                    "mimeType": "application/pdf",
                    "body": {"data": base64.urlsafe_b64encode(b"fixture-pdf").decode().rstrip("=")},
                }
            ],
        },
    }

    fake_rows = [
        {"date": __import__("datetime").datetime(2026, 9, 2, 0, tzinfo=__import__("datetime").timezone.utc),
         "amount_minor": 16650, "type": "DEBIT", "merchant": "IGST",
         "reference": "09999999980902000707495", "payment_method": "CARD",
         "category": "OTHER", "narration": "IGST"},
        {"date": __import__("datetime").datetime(2026, 9, 4, 16, 51, tzinfo=__import__("datetime").timezone.utc),
         "amount_minor": 954800, "type": "CREDIT", "merchant": "BPPY CC PAYMENT",
         "reference": "ST262480083000010111274", "payment_method": "BILL_PAYMENT",
         "category": "PAYMENT", "narration": "BPPY CC PAYMENT"},
        {"date": __import__("datetime").datetime(2026, 10, 2, 0, tzinfo=__import__("datetime").timezone.utc),
         "amount_minor": 453100, "type": "DEBIT", "merchant": "OFFUS EMI,PRIN",
         "reference": "09999999981002000704649", "payment_method": "CARD",
         "category": "OTHER", "narration": "OFFUS EMI,PRIN"},
        {"date": __import__("datetime").datetime(2026, 10, 2, 0, tzinfo=__import__("datetime").timezone.utc),
         "amount_minor": 54000, "type": "DEBIT", "merchant": "OFFUS EMI,INT NBR",
         "reference": "09999999981002000704656", "payment_method": "CARD",
         "category": "OTHER", "narration": "OFFUS EMI,INT NBR"},
    ]
    fake_metadata = {
        "bank": "HDFC",
        "account_type": "CREDIT_CARD",
        "account_last4": "5304",
        "currency": "INR",
        "statement_from": "03 Sep, 2026",
        "statement_to": "02 Oct, 2026",
        "opening_balance_minor": None,
    }

    monkeypatch.setattr(
        "app.statement_sync.parse_hdfc_statement",
        lambda pdf, key: (fake_metadata, fake_rows),
    )
    bill_calls = []
    monkeypatch.setattr(
        "app.statement_sync.sync_card_bill",
        lambda bill: bill_calls.append(bill) or {"status": "APPLIED"},
    )

    result = process_statement_attachments(FakeService([message]), message)

    assert result["attachmentsParsed"] == 1
    assert result["transactionsAdded"] == 0
    assert result["transactionsMatched"] == 0
    assert len(bill_calls) == 1
    assert bill_calls[0]["amountMinor"] == 523800

    with connection() as conn:
        account = conn.execute(
            "SELECT balance_minor,bill_balance_minor FROM accounts WHERE id='statement-hdfc-card'"
        ).fetchone()
        statement_rows = conn.execute(
            "SELECT COUNT(*) value FROM transactions WHERE id LIKE 'statement:%'"
        ).fetchone()["value"]
        attachment = conn.execute(
            "SELECT status,transaction_count FROM gmail_attachments WHERE message_id='statement-hdfc-card-1'"
        ).fetchone()

    assert account["balance_minor"] == 1478550
    assert account["bill_balance_minor"] == 523800
    assert statement_rows == 0
    assert attachment["status"] == "PARSED"
    assert attachment["transaction_count"] == 0
