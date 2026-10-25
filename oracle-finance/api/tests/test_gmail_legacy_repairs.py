"""Gmail tests: legacy repairs."""
import gmail_test_support  # noqa: F401  (sets up the shared test database)
from app.db import connection
from app.gmail_sync import ingest_messages, parse_bank_email, _repair_legacy_gmail_account_classifications, _repair_legacy_axis_credit_direction_conflicts, _repair_legacy_unidentified_hdfc_gmail_transactions, _repair_legacy_icici_credit_card_classifications, _repair_legacy_gmail_merchants, _repair_legacy_gmail_merchant_values
from app.ledger import set_balance
from gmail_test_support import FakeService, _message


def test_legacy_unidentified_hdfc_gmail_debit_is_voided():
    message = _message(
        "hdfc-legacy-3510",
        "Rs.3510.00 is debited from your HDFC Bank A/c *9591. Payment successful.",
        subject="HDFC Bank Transaction Alert",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "HDFC Bank Transaction Alert"},
        {"name": "From", "value": "alerts@hdfcbank.net"},
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
                "thread-hdfc-3510",
                1950000000000,
                "alerts@hdfcbank.net",
                "HDFC Bank Transaction Alert",
                "hdfc-3510-fingerprint",
                "PARSED",
                1950000000000,
            ),
        )
        conn.execute(
            """
            INSERT INTO transactions
            (id,amount_minor,currency,type,payment_method,account_type,bank,
             merchant_or_payee,account_last4,reference,timestamp,category,
             confidence,duplicate_of,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                "gmail:hdfc-legacy-3510",
                351000,
                "INR",
                "DEBIT",
                "UPI",
                "BANK_ACCOUNT",
                "HDFC",
                None,
                "9591",
                None,
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
            INSERT INTO evidence
            (id,source_type,source_id,status,observed_at,transaction_id,
             matched_transaction_id,amount_minor,currency,direction,
             bank_provider,account_last4,reference,content_hash,confidence,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                "gmail-evidence:hdfc-legacy-3510",
                "GMAIL",
                message["id"],
                "UNMATCHED",
                1950000000000,
                "gmail:hdfc-legacy-3510",
                None,
                351000,
                "INR",
                "DEBIT",
                "HDFC",
                "9591",
                None,
                "hdfc-3510-hash",
                0.95,
                1950000000000,
            ),
        )

    repaired = _repair_legacy_unidentified_hdfc_gmail_transactions()
    assert repaired == 1

    with connection() as conn:
        row = conn.execute(
            "SELECT status FROM transactions WHERE id='gmail:hdfc-legacy-3510'"
        ).fetchone()
        status = conn.execute(
            "SELECT status FROM gmail_messages WHERE id=?",
            (message["id"],),
        ).fetchone()["status"]

    assert row["status"] == "VOIDED"
    assert status == "PENDING"


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
             confidence,duplicate_of,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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


def test_legacy_axis_false_debit_is_voided_and_reparsed_as_credit():
    message = _message(
        "axis-legacy-false-debit",
        "Dear Customer, Here's the summary of your transaction. "
        "Amount Credited: INR 4.00 Account Number: XX3370 "
        "Transaction Info: UPI/P2A/739593577194/ABDUL WAS/HDFC/Paym",
        subject="INR 4.00 was credited to your A/c.",
        internal_date="1791029103000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 4.00 was credited to your A/c."},
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
                "axis-legacy-thread",
                1791029103000,
                "alerts@axis.bank.in",
                message["payload"]["headers"][0]["value"],
                "axis-legacy-fingerprint",
                "PARSED",
                1791029103000,
            ),
        )
        conn.execute(
            """
            INSERT INTO transactions
            (id,amount_minor,currency,type,payment_method,account_type,bank,
             merchant_or_payee,account_last4,reference,timestamp,category,
             confidence,duplicate_of,status,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                "gmail:axis-legacy-false-debit",
                400,
                "INR",
                "DEBIT",
                "UPI",
                "BANK_ACCOUNT",
                "AXIS",
                "ABDUL WAS",
                "3370",
                "739593577194",
                1791029103000,
                "OTHER",
                1.0,
                None,
                "ACTIVE",
                1791029103000,
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
                1791029103000,
                "gmail:axis-legacy-false-debit",
                None,
                400,
                "INR",
                "DEBIT",
                "AXIS",
                "3370",
                "739593577194",
                "axis-legacy-false-debit-hash",
                1.0,
                1791029103000,
            ),
        )

    repaired = _repair_legacy_axis_credit_direction_conflicts()
    assert repaired == 1

    with connection() as conn:
        old = conn.execute(
            "SELECT status FROM transactions WHERE id='gmail:axis-legacy-false-debit'"
        ).fetchone()
        gmail_status = conn.execute(
            "SELECT status FROM gmail_messages WHERE id=?",
            (message["id"],),
        ).fetchone()["status"]

    assert old["status"] == "VOIDED"
    assert gmail_status == "PENDING"

    stats = ingest_messages(FakeService([message]), query="newer_than:30d")
    assert stats["parsedTransactions"] == 1

    with connection() as conn:
        rows = conn.execute(
            """
            SELECT id,type,amount_minor,bank,account_type,account_last4,reference,status
            FROM transactions
            WHERE id LIKE 'gmail:%' AND amount_minor=400
            ORDER BY id
            """
        ).fetchall()

    assert any(
        row["type"] == "CREDIT"
        and row["bank"] == "AXIS"
        and row["account_type"] == "BANK_ACCOUNT"
        and row["account_last4"] == "3370"
        and row["reference"] == "739593577194"
        and row["status"] == "ACTIVE"
        for row in rows
    )


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
                "361639089310",
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
