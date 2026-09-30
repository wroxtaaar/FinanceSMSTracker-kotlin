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
