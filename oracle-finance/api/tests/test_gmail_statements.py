"""Gmail tests: statements."""
import gmail_test_support  # noqa: F401  (sets up the shared test database)
import base64
from app.db import connection
from app.ledger import set_balance
from app.statement_sync import _hdfc_metadata, _parse_hdfc_rows, parse_hdfc_credit_card_statement, _icici_metadata, parse_icici_statement, process_statement_attachments
from gmail_test_support import FakeService, _message


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
        "app.statement_sync.processing.parse_hdfc_statement",
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
        "app.statement_sync.common._pdf_text",
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
    monkeypatch.setattr("app.statement_sync.common._pdf_text", lambda pdf, key: text)

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

    from datetime import datetime, timezone
    fake_rows = [
        {"date": datetime(2026, 9, 2, 0, tzinfo=timezone.utc),
         "amount_minor": 16650, "type": "DEBIT", "merchant": "IGST",
         "reference": "09999999980902000707495", "payment_method": "CARD",
         "category": "OTHER", "narration": "IGST"},
        {"date": datetime(2026, 9, 4, 16, 51, tzinfo=timezone.utc),
         "amount_minor": 954800, "type": "CREDIT", "merchant": "BPPY CC PAYMENT",
         "reference": "ST262480083000010111274", "payment_method": "BILL_PAYMENT",
         "category": "PAYMENT", "narration": "BPPY CC PAYMENT"},
        {"date": datetime(2026, 10, 2, 0, tzinfo=timezone.utc),
         "amount_minor": 453100, "type": "DEBIT", "merchant": "OFFUS EMI,PRIN",
         "reference": "09999999981002000704649", "payment_method": "CARD",
         "category": "OTHER", "narration": "OFFUS EMI,PRIN"},
        {"date": datetime(2026, 10, 2, 0, tzinfo=timezone.utc),
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

    monkeypatch.setenv("HDFC_STATEMENT_SECRET", "fixture-secret")
    monkeypatch.setattr(
        "app.statement_sync.processing.parse_hdfc_statement",
        lambda pdf, key: (fake_metadata, fake_rows),
    )
    monkeypatch.setattr(
        "app.statement_sync.common._pdf_text",
        lambda pdf, key: "TOTAL AMOUNT DUE C5,238.00",
    )
    bill_calls = []
    monkeypatch.setattr(
        "app.statement_sync.processing.sync_card_bill",
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
            """SELECT COUNT(*) value FROM transactions
               WHERE id LIKE 'statement:%'
                 AND account_type='CREDIT_CARD'
                 AND account_last4='5304'"""
        ).fetchone()["value"]
        attachment = conn.execute(
            "SELECT status,transaction_count FROM gmail_attachments WHERE message_id='statement-hdfc-card-1'"
        ).fetchone()

    assert account["balance_minor"] == 1478550
    assert account["bill_balance_minor"] == 523800
    assert statement_rows == 0
    assert attachment["status"] == "PARSED"
    assert attachment["transaction_count"] == 0


def test_icici_statement_bill_amount_reads_the_backtick_rupee_sign():
    from app.statement_sync import _extract_statement_bill_amount

    # Text as extracted from a real ICICI statement PDF: the font maps the
    # rupee sign to a backtick, and the summary row lists the other figures.
    text = (
        "STATEMENT SUMMARY\nTotal Amount due\n`2,088.00 = + + -\n"
        "Minimum Amount due CREDIT SUMMARY\n`110.00\n"
        "Credit Limit (Including cash) Available Credit (Including cash) Cash Limit\n"
        "`3,80,000.00 `3,77,912.00 `38,000.00\n"
    )
    assert _extract_statement_bill_amount(text) == 208800


def test_pending_statement_retry_reads_the_bill_from_the_email_body_when_the_pdf_fails():
    import app.gmail_sync as gmail_sync
    from app.ledger import set_balance

    set_balance("retry-card", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 0)
    html = (
        '<div style="color:#fff"><span style="x">ICICI Bank Credit Card</span><span style="x">XX1012</span></div>'
        '<p style="x">Total Amount Due:</p><p style="x"><b style="x">&#8377;2,088.00</b></p>'
    )
    message = _message(
        "retry-1", html,
        subject="Amazon Pay ICICI Bank Credit Card Statement for the period September 29, 2026 to October 28, 2026",
        internal_date="1900000000000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": message["payload"]["headers"][0]["value"]},
        {"name": "From", "value": "ICICI Bank <credit_cards@icici.bank.in>"},
    ]
    with connection() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO gmail_messages(id,thread_id,internal_date,sender,subject,fingerprint,status,created_at)"
            " VALUES('retry-1','t',1900000000000,'credit_cards@icici.bank.in',?,'retry-fp','PENDING',1900000000000)",
            (message["payload"]["headers"][0]["value"],),
        )
    original = gmail_sync.process_statement_attachments
    gmail_sync.process_statement_attachments = lambda service, m: {
        "attachmentsScanned": 1, "attachmentsParsed": 0, "transactionsAdded": 0,
        "transactionsMatched": 0, "errors": ["ICICI statement password is not configured"],
    }
    try:
        stats = {
            "statementAttachmentsScanned": 0, "statementAttachmentsParsed": 0,
            "statementTransactionsAdded": 0, "statementTransactionsMatched": 0, "statementErrors": [],
        }
        gmail_sync._retry_pending_statement_messages(FakeService([message]), stats)
    finally:
        gmail_sync.process_statement_attachments = original

    with connection() as conn:
        bill = conn.execute(
            "SELECT bill_balance_minor FROM accounts WHERE id='retry-card'"
        ).fetchone()[0]
    assert bill == 208800
    assert stats["statementErrors"] == ["ICICI statement password is not configured"]
