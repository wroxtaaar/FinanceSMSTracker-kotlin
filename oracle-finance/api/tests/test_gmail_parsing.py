"""Gmail tests: parsing."""
import gmail_test_support  # noqa: F401  (sets up the shared test database)
from app.db import connection
from app.gmail_sync import parse_bank_email, _gmail_default_category, _repair_self_transfer_gmail_merchants
from app.ledger import sync_transaction
from gmail_test_support import FakeService, _message


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


def test_gmail_parser_rejects_hdfc_bank_debit_without_reference_or_counterparty():
    message = _message(
        "hdfc-no-identity",
        "Rs.3510.00 is debited from your HDFC Bank A/c *9591. "
        "Payment successful. Your available balance is Rs.48000.00.",
        subject="HDFC Bank Transaction Alert",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "HDFC Bank Transaction Alert"},
        {"name": "From", "value": "alerts@hdfcbank.net"},
    ]

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
    assert transaction.reference == "18335801167"
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


def test_axis_transaction_info_reference_is_normalized_to_rrn():
    message = _message(
        "axis-reference-normalized",
        "Dear Customer, Here's the summary of your transaction. "
        "Amount Credited: INR 4.00 Account Number: XX3370 "
        "Transaction Info: UPI/P2A/739593577194/ABDUL WAS/HDFC/Paym",
        subject="INR 4.00 was credited to your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 4.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)
    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.reference == "739593577194"
    assert evidence.reference == "739593577194"


def test_axis_transaction_info_is_not_merchant():
    message = _message(
        "axis-no-merchant",
        "Dear Customer, Here's the summary of your transaction: "
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
    assert transaction.reference == "361639089310"
    assert transaction.merchantOrPayee == "ABDUL WASIQ"


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


def test_axis_credit_direction_ignores_footer_debit_words():
    message = _message(
        "axis-credit-footer-direction",
        "Dear Customer, Here's the summary of your transaction. "
        "Amount Credited: INR 6.00 Account Number: XX3370 "
        "Date & Time: 03-10-26, 15:23:16 IST "
        "Transaction Info: UPI/P2A/930624306800/ABDUL WAS/HDFC/Paym "
        "Payment successful. You may be charged for card services. "
        "Do not share your credit card or debit details.",
        subject="INR 6.00 was credited to your A/c.",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "INR 6.00 was credited to your A/c."},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 600
    assert transaction.type == "CREDIT"
    assert transaction.paymentMethod == "UPI"
    assert transaction.accountType == "BANK_ACCOUNT"
    assert transaction.bank == "AXIS"
    assert transaction.accountLast4 == "3370"
    assert transaction.reference == "930624306800"
    assert evidence.direction == "CREDIT"


def test_gmail_axis_credit_gets_transfer_category():
    message = _message(
        "axis-credit-category",
        "Amount Credited: INR 4.00\\n"
        "Account Number: XX3370\\n"
        "Date & Time: 03-10-26, 14:05:03 IST\\n"
        "Transaction Info: UPI/P2A/739593577194/ABDUL WAS/HDFC/Paym...",
        subject="INR 4.00 was credited to your A/c.",
    )
    message["payload"]["headers"][1]["value"] = "Axis Bank Alerts <alerts@axis.bank.in>"

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.type == "CREDIT"
    assert transaction.bank == "AXIS"
    assert transaction.accountLast4 == "3370"
    assert transaction.reference == "739593577194"
    assert transaction.category == "TRANSFER"


def test_gmail_credit_category_specific_cases():
    assert _gmail_default_category("CREDIT", "refund for UPI transaction") == "REFUND"
    assert _gmail_default_category("CREDIT", "monthly salary credited") == "SALARY"
    assert _gmail_default_category("CREDIT", "amount credited via UPI") == "TRANSFER"


def test_foreign_currency_card_email_uses_the_spend_not_the_available_limit():
    message = _message(
        "usd-1",
        "Dear Customer, Your ICICI Bank Credit Card XX1012 has been used "
        "for a transaction of USD 205.70 on Oct 21, 2026 at 22:19:26. "
        "Info: UZAIRWAYS. "
        "The Available Credit Limit on your card is INR 61312.07 and Total Credit Limit is INR 200000.00. "
        "You can pay your Credit Card bills from your bank account.",
        subject="Transaction alert for your ICICI Bank Credit Card",
        internal_date="1790491657000",
    )
    parsed = parse_bank_email(message)
    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 20570
    assert transaction.currency == "USD"
    assert evidence.currency == "USD"


def test_axis_card_received_your_payment_email_is_card_credit():
    message = _message(
        "axis-card-payment",
        "Dear Customer, we have received your payment of INR 33,136.06 on your "
        "Axis Bank Credit Card no. XX9206. Available Limit: INR 129893.14.",
        subject="Axis Bank Alerts",
        internal_date="1792000000000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Axis Bank Alerts"},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.amountMinor == 3313606
    assert transaction.type == "CREDIT"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.accountLast4 == "9206"
    assert transaction.category == "TRANSFER"


def test_hsbc_card_email_reads_usd_amount_and_merchant_without_alert_subject():
    message = _message(
        "hsbc-usd",
        "Dear Customer, We're writing to confirm that your HSBC Credit Card xx5145 was used "
        "for a transaction of USD 4.00 at HUGGINGFACE on 21/10/26. "
        "Available limit: INR 169620.11 Amount due: INR 379.89 "
        "If you did not make this transaction, please contact us promptly.",
        subject="Your HSBC Credit Card was used",
        internal_date="1792231140000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Your HSBC Credit Card was used"},
        {"name": "From", "value": "HSBC <hsbc@hsbc.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, _ = parsed
    assert transaction.amountMinor == 400
    assert transaction.currency == "USD"
    assert transaction.type == "DEBIT"
    assert transaction.bank == "HSBC"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.accountLast4 == "5145"
    assert transaction.merchantOrPayee == "HUGGINGFACE"


def test_axis_credit_card_spend_email_is_debit():
    message = _message(
        "axis-card-spend",
        "Dear Abdul Wasiq, Here's the summary of your Axis Bank Credit Card Transaction: "
        "Transaction Amount: INR 100 spent on credit card no. XX9206. "
        "Available Limit: INR 129893.14.",
        subject="Axis Bank Alerts",
        internal_date="1790951871000",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": "Axis Bank Alerts"},
        {"name": "From", "value": "Axis Bank Alerts <alerts@axis.bank.in>"},
    ]

    parsed = parse_bank_email(message)

    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.amountMinor == 10000
    assert transaction.type == "DEBIT"
    assert transaction.bank == "AXIS"
    assert transaction.accountType == "CREDIT_CARD"
    assert transaction.accountLast4 == "9206"
    assert evidence.direction == "DEBIT"


def test_icici_statement_email_with_styled_html_and_masked_card_is_a_card_bill():
    from app.gmail_parsing import parse_card_bill_email

    style = 'style="font-family:Roboto,Arial;font-size:16px;color:#ffffff;padding:8px 0;margin:0;line-height:24px"'
    html = (
        "<style>.a{color:red}</style>"
        f"<div {style}><span {style}>ICICI Bank Credit Card</span><span {style}>XX1012</span></div>"
        f"<p {style}>Minimum Amount Due: &#8377;110.00</p>"
        f"<p {style}>Total Amount Due:</p><p {style}><b {style}>&#8377;2,088.00</b></p>"
    )
    message = _message(
        "icici-bill-1",
        html,
        subject="Amazon Pay ICICI Bank Credit Card Statement for the period September 29, 2026 to October 28, 2026",
    )
    message["payload"]["headers"] = [
        {"name": "Subject", "value": message["payload"]["headers"][0]["value"]},
        {"name": "From", "value": "ICICI Bank <credit_cards@icici.bank.in>"},
    ]

    bill = parse_card_bill_email(message)

    assert bill is not None
    assert bill["amountMinor"] == 208800  # the total, not the 110.00 minimum
    assert bill["bank"] == "ICICI"
    assert bill["accountLast4"] == "1012"


def test_statement_email_without_a_card_number_is_not_guessed():
    from app.gmail_parsing import parse_card_bill_email

    message = _message("no-card", "Your statement is ready. Total Amount Due: Rs. 2,088.00",
                       subject="Your credit card statement")
    assert parse_card_bill_email(message) is None
