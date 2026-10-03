import os
import pytest

from app import db
from app.db import connection
from app.ledger import set_balance, add_receivable, true_available


@pytest.fixture(autouse=True)
def isolated_database(tmp_path, monkeypatch):
    path = str(tmp_path / "finance-test.db")
    monkeypatch.setattr(db, "DB_PATH", path)
    db.init_db()

def test_true_available_formula():
    set_balance("bank-1", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)
    set_balance("card-1", "Axis Card", "INR", "CREDIT_CARD", "AXIS", "1175", 25000)
    add_receivable({
        "id": "sw-1",
        "description": "Dinner",
        "amount_minor": 5000,
        "currency": "INR",
    })
    result = true_available("INR")
    assert result["bankCashMinor"] >= 100000
    assert result["splitwiseReceivableMinor"] >= 5000
    assert result["creditCardOutstandingMinor"] >= 25000
    assert result["trueAvailableMinor"] >= 80000


def test_internal_transfer_match():
    from app.ledger import sync_transaction, match_internal_transfers

    class T:
        def __init__(self, id, typ, bank, last4):
            self.id = id
            self.amountMinor = 20000
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = None
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 1700000000000
            self.category = "TRANSFER"
            self.confidence = 0.95

    sync_transaction(T("debit-transfer", "DEBIT", "AXIS", "3370"))
    x = T("credit-transfer", "CREDIT", "HDFC", "9591")
    x.timestamp += 120000
    sync_transaction(x)
    matches = match_internal_transfers()
    assert any(
        m["debitTransactionId"] == "debit-transfer"
        and m["creditTransactionId"] == "credit-transfer"
        for m in matches
    )


def test_new_transactions_update_seeded_account_balances():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, typ, account_type, bank, last4, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = account_type
            self.bank = bank
            self.merchantOrPayee = None
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 1800000000000
            self.category = "OTHER"
            self.confidence = 0.95

    set_balance("auto-bank", "Auto Bank", "INR", "BANK_ACCOUNT", "HDFC", "1111", 100000)
    sync_transaction(T("auto-bank-debit", "DEBIT", "BANK_ACCOUNT", "HDFC", "1111", 2500))
    sync_transaction(T("auto-bank-credit", "CREDIT", "BANK_ACCOUNT", "HDFC", "1111", 1000))

    set_balance("auto-card", "Auto Card", "INR", "CREDIT_CARD", "AXIS", "2222", 50000)
    sync_transaction(T("auto-card-debit", "DEBIT", "CREDIT_CARD", "AXIS", "2222", 7000))
    sync_transaction(T("auto-card-credit", "CREDIT", "CREDIT_CARD", "AXIS", "2222", 1200))

    with connection() as conn:
        bank_balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='auto-bank'"
        ).fetchone()["balance_minor"]
        card_balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='auto-card'"
        ).fetchone()["balance_minor"]

    assert bank_balance == 98500
    assert card_balance == 55800


def test_existing_transaction_is_not_applied_twice():
    from app.ledger import sync_transaction

    class T:
        id = "no-double"
        amountMinor = 500
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = None
        accountLast4 = "3333"
        reference = None
        timestamp = 1800000000000
        category = "OTHER"
        confidence = 0.95

    set_balance(
        "no-double-account", "No Double", "INR", "BANK_ACCOUNT", "HDFC", "3333", 10000
    )
    t = T()
    assert sync_transaction(t) is True
    assert sync_transaction(t) is False

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='no-double-account'"
        ).fetchone()["balance_minor"]

    assert balance == 9500


def test_card_direction_rules_are_opposite_of_bank_rules():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, typ, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "CARD"
            self.accountType = "CREDIT_CARD"
            self.bank = "AXIS"
            self.merchantOrPayee = None
            self.accountLast4 = "4444"
            self.reference = None
            self.timestamp = 1900000000000
            self.category = "OTHER"
            self.confidence = 0.95

    set_balance("direction-card", "Direction Card", "INR", "CREDIT_CARD", "AXIS", "4444", 50000)
    sync_transaction(T("card-charge", "DEBIT", 7000))
    sync_transaction(T("card-payment", "CREDIT", 1200))

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='direction-card'"
        ).fetchone()["balance_minor"]

    assert balance == 55800


def test_card_bill_and_active_spend_are_separate():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, typ, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "CREDIT_CARD"
            self.bank = "SBI"
            self.merchantOrPayee = "TEST"
            self.accountLastFour = "0065"
            self.accountLast4 = "0065"
            self.reference = None
            self.timestamp = 1910000000000
            self.category = "TRANSFER"
            self.confidence = 0.98

    set_balance(
        "bill-card",
        "SBI Card",
        "INR",
        "CREDIT_CARD",
        "SBI",
        "0065",
        50000,
        50000,
    )

    # A new purchase belongs to active/unbilled spend; it must not grow the bill.
    sync_transaction(T("card-purchase", "DEBIT", 7000))

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor, bill_balance_minor FROM accounts WHERE id='bill-card'"
        ).fetchone()

    assert row["balance_minor"] == 57000
    assert row["bill_balance_minor"] == 50000

    # Paying the bill reduces the bill bucket while leaving the active spend.
    sync_transaction(T("card-payment", "CREDIT", 50000))

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor, bill_balance_minor FROM accounts WHERE id='bill-card'"
        ).fetchone()

    assert row["balance_minor"] == 7000
    assert row["bill_balance_minor"] == 0


def test_card_bill_evidence_updates_bill_without_touching_active_spend():
    from app.ledger import sync_card_bill, sync_transaction

    class T:
        id = "bill-card-purchase"
        amountMinor = 976700
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "CARD"
        accountType = "CREDIT_CARD"
        bank = "AXIS"
        merchantOrPayee = "ACTIVE SPEND"
        accountLast4 = "9206"
        reference = None
        timestamp = 2100000000000
        category = "OTHER"
        confidence = 0.99

    set_balance(
        "bill-axis-9206",
        "Axis 9206",
        "INR",
        "CREDIT_CARD",
        "AXIS",
        "9206",
        5313606,
        0,
    )
    sync_transaction(T())

    result = sync_card_bill({
        "sourceType": "SMS",
        "sourceKey": "sms-card-bill:test-9206",
        "timestamp": 2100000001000,
        "amountMinor": 5313606,
        "currency": "INR",
        "bank": "AXIS",
        "accountLast4": "9206",
        "confidence": 0.98,
    })

    assert result["status"] == "APPLIED"

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor,bill_balance_minor FROM accounts WHERE id='bill-axis-9206'"
        ).fetchone()

    assert row["balance_minor"] == 6290306
    assert row["bill_balance_minor"] == 5313606


def test_full_gmail_bill_overrides_lower_priority_sms_bill():
    from app.ledger import sync_card_bill

    set_balance(
        "bill-axis-1175",
        "Axis 1175",
        "INR",
        "CREDIT_CARD",
        "AXIS",
        "1175",
        4585900,
        0,
    )

    sms = sync_card_bill({
        "sourceType": "SMS",
        "sourceKey": "sms-card-bill:test-1175",
        "timestamp": 2100000010000,
        "amountMinor": 4500000,
        "currency": "INR",
        "bank": "AXIS",
        "accountLast4": "1175",
        "confidence": 0.98,
    })
    assert sms["status"] == "APPLIED"

    email = sync_card_bill({
        "sourceType": "GMAIL",
        "sourceKey": "gmail-card-bill:test-1175",
        "timestamp": 2100000011000,
        "amountMinor": 4585900,
        "currency": "INR",
        "bank": "AXIS",
        "accountLast4": "1175",
        "confidence": 0.99,
    })
    assert email["status"] == "APPLIED"

    with connection() as conn:
        bill = conn.execute(
            "SELECT bill_balance_minor FROM accounts WHERE id='bill-axis-1175'"
        ).fetchone()["bill_balance_minor"]

    assert bill == 4585900


def test_bank_credit_increases_cash():
    from app.ledger import sync_transaction

    class T:
        id = "bank-credit"
        amountMinor = 1500
        currency = "INR"
        type = "CREDIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = None
        accountLast4 = "5555"
        reference = None
        timestamp = 1900000000000
        category = "TRANSFER"
        confidence = 0.95

    set_balance("bank-credit-account", "Bank Credit", "INR", "BANK_ACCOUNT", "HDFC", "5555", 20000)
    sync_transaction(T())

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='bank-credit-account'"
        ).fetchone()["balance_minor"]

    assert balance == 21500


def test_reseeding_preserves_recorded_transaction_adjustments():
    from app.ledger import sync_transaction

    class T:
        id = "reseed-adjustment"
        amountMinor = 500
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = None
        accountLast4 = "6666"
        reference = None
        timestamp = 1900000000000
        category = "OTHER"
        confidence = 0.95

    set_balance("reseed-account", "Reseed", "INR", "BANK_ACCOUNT", "HDFC", "6666", 10000)
    sync_transaction(T())

    # Setting a new current balance must move the opening snapshot, not
    # re-apply the existing transaction adjustment.
    set_balance("reseed-account", "Reseed", "INR", "BANK_ACCOUNT", "HDFC", "6666", 20000)

    with connection() as conn:
        row = conn.execute(
            "SELECT opening_balance_minor, balance_minor FROM accounts WHERE id='reseed-account'"
        ).fetchone()

    assert row["opening_balance_minor"] == 20500
    assert row["balance_minor"] == 20000


def test_gmail_duplicate_does_not_double_count_balance():
    from app.ledger import sync_transaction, reconcile_duplicate_transaction

    class T:
        def __init__(self, id):
            self.id = id
            self.amountMinor = 2500
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "TEST MERCHANT"
            self.accountLast4 = "7777"
            self.reference = "REF-7777"
            self.timestamp = 1950000000000
            self.category = "OTHER"
            self.confidence = 0.95

    set_balance("gmail-dedupe-account", "Gmail Dedupe", "INR", "BANK_ACCOUNT", "HDFC", "7777", 100000)
    sync_transaction(T("sms-7777"))

    gmail = T("gmail:7777")
    sync_transaction(gmail)

    with connection() as conn:
        before_reconcile = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-dedupe-account'"
        ).fetchone()["balance_minor"]

    assert before_reconcile == 97500

    result = reconcile_duplicate_transaction("gmail:7777")
    assert result is not None
    assert result["canonical"] == "sms-7777"

    # The Gmail ingestion path is responsible for applying the deferred
    # balance only if the transaction remains canonical. A duplicate must
    # never get an adjustment.
    with connection() as conn:
        adjustments = conn.execute(
            "SELECT COUNT(*) value FROM balance_adjustments WHERE transaction_id='gmail:7777'"
        ).fetchone()["value"]

    assert adjustments == 0


def test_gmail_rrn_reconciles_to_correct_ledger_side_not_cross_account_side():
    from app.ledger import sync_transaction, reconcile_duplicate_transaction

    class T:
        def __init__(self, id, typ, bank, last4, reference, timestamp):
            self.id = id
            self.amountMinor = 400
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = timestamp
            self.category = "OTHER"
            self.confidence = 0.99

    # Both sides of the same UPI transfer share the same RRN.
    sync_transaction(T(
        "hdfc-debit-739",
        "DEBIT",
        "HDFC",
        "9591",
        "739593577194",
        1800000000000,
    ))
    sync_transaction(T(
        "axis-credit-739",
        "CREDIT",
        "AXIS",
        "3370",
        None,
        1800000000000 + 3 * 24 * 60 * 60 * 1000,
    ))

    gmail = T(
        "gmail:axis-credit-739",
        "CREDIT",
        "AXIS",
        "3370",
        "UPI/P2A/739593577194/ABDUL WAS/HDFC/Paym",
        1800000000000 + 3 * 24 * 60 * 60 * 1000 + 60000,
    )
    sync_transaction(gmail)

    result = reconcile_duplicate_transaction(gmail.id)

    assert result is not None
    assert result["canonical"] == "axis-credit-739"

    with connection() as conn:
        hdfc = conn.execute(
            "SELECT duplicate_of FROM transactions WHERE id='hdfc-debit-739'"
        ).fetchone()
        axis = conn.execute(
            "SELECT duplicate_of,reference FROM transactions WHERE id='axis-credit-739'"
        ).fetchone()

    assert hdfc["duplicate_of"] is None
    assert axis["duplicate_of"] is None
    assert axis["reference"] is None


def test_void_transaction_reverses_account_adjustment_once():
    from app.ledger import sync_transaction, void_transaction

    class T:
        id = "void-test"
        amountMinor = 2500
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "VOID TEST"
        accountLast4 = "8888"
        reference = None
        timestamp = 2000000000000
        category = "OTHER"
        confidence = 0.95

    set_balance("void-account", "Void Account", "INR", "BANK_ACCOUNT", "HDFC", "8888", 10000)
    sync_transaction(T())

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='void-account'"
        ).fetchone()["balance_minor"]
    assert balance == 7500

    result = void_transaction("void-test")
    assert result["status"] == "VOIDED"
    assert result["reversedAdjustment"] is True

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='void-account'"
        ).fetchone()["balance_minor"]
        tx = conn.execute(
            "SELECT status FROM transactions WHERE id='void-test'"
        ).fetchone()
        adjustments = conn.execute(
            "SELECT COUNT(*) value FROM balance_adjustments WHERE transaction_id='void-test'"
        ).fetchone()["value"]

    assert balance == 10000
    assert tx["status"] == "VOIDED"
    assert adjustments == 0

    again = void_transaction("void-test")
    assert again["status"] == "ALREADY_VOIDED"

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='void-account'"
        ).fetchone()["balance_minor"]

    assert balance == 10000


def test_voided_transactions_are_hidden_from_list():
    from app.ledger import sync_transaction, void_transaction, list_transactions

    class T:
        id = "void-hidden"
        amountMinor = 1000
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = None
        accountLast4 = "9999"
        reference = None
        timestamp = 2000000001000
        category = "OTHER"
        confidence = 0.95

    set_balance("void-hidden-account", "Void Hidden", "INR", "BANK_ACCOUNT", "HDFC", "9999", 10000)
    sync_transaction(T())
    assert any(row["id"] == "void-hidden" for row in list_transactions())

    void_transaction("void-hidden")

    assert not any(row["id"] == "void-hidden" for row in list_transactions())

def test_splitwise_increases_for_non_other_debits_only():
    from app.ledger import sync_transaction, get_manual_splitwise_total

    class T:
        def __init__(self, id, typ, category, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "TEST"
            self.accountLast4 = "1234"
            self.reference = None
            self.timestamp = 2200000000000
            self.category = category
            self.confidence = 0.95

    sync_transaction(T("splitwise-food", "DEBIT", "FOOD", 2500))
    sync_transaction(T("splitwise-other", "DEBIT", "OTHER", 9000))
    sync_transaction(T("splitwise-credit", "CREDIT", "FOOD", 7000))

    assert get_manual_splitwise_total("INR") == 2500


def test_voiding_splitwise_debit_reverses_its_contribution():
    from app.ledger import sync_transaction, void_transaction, get_manual_splitwise_total

    class T:
        id = "splitwise-void"
        amountMinor = 3300
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "SELF TRANSFER"
        accountLast4 = "1234"
        reference = None
        timestamp = 2200000001000
        category = "TRANSFER"
        confidence = 0.95

    sync_transaction(T())
    assert get_manual_splitwise_total("INR") == 3300

    result = void_transaction("splitwise-void")
    assert result["status"] == "VOIDED"
    assert get_manual_splitwise_total("INR") == 0


def test_category_edit_updates_splitwise_contribution():
    from app.ledger import sync_transaction, get_manual_splitwise_total

    class T:
        id = "category-edit"
        amountMinor = 4200
        currency = "INR"
        type = "DEBIT"
        paymentMethod = "CARD"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "TEST"
        accountLast4 = "4321"
        reference = None
        timestamp = 2300000000000
        category = "OTHER"
        confidence = 0.95

    set_balance("category-edit-account", "Category Edit", "INR", "BANK_ACCOUNT", "HDFC", "4321", 100000)

    # Original transaction is explicitly excluded from Splitwise.
    sync_transaction(T())
    assert get_manual_splitwise_total("INR") is None

    # Changing it to any non-OTHER debit category must add its full amount.
    T.category = "GROCERIES"
    assert sync_transaction(T()) is False
    assert get_manual_splitwise_total("INR") == 4200

    # Changing it back to OTHER must remove that contribution.
    T.category = "OTHER"
    assert sync_transaction(T()) is False
    assert get_manual_splitwise_total("INR") == 0
