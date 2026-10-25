"""Splitwise: signed deltas, derived total, category opt-out, true available."""
from app.db import connection
from app.ledger import set_balance, add_receivable, true_available
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)


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


def test_gmail_only_bank_credit_changes_splitwise():
    from app.ledger import apply_splitwise_contribution, get_manual_splitwise_total, sync_transaction

    class T:
        id = "gmail:axis-credit-only"
        amountMinor = 500
        currency = "INR"
        type = "CREDIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "AXIS"
        merchantOrPayee = "ABDUL WAS"
        accountLast4 = "3370"
        reference = "240201254528"
        timestamp = 1800000000000
        category = "GROCERIES"
        confidence = 0.99

    from app.ledger import set_manual_splitwise_total
    set_manual_splitwise_total("INR", 10000)

    sync_transaction(T())
    # The Splitwise value is derived from the ledger, so a canonical Gmail-only
    # credit counts as soon as it is stored.
    assert get_manual_splitwise_total("INR") == 9500

    with connection() as conn:
        assert apply_splitwise_contribution(conn, T(), 1800000001000) is True
        assert apply_splitwise_contribution(conn, T(), 1800000002000) is False

    assert get_manual_splitwise_total("INR") == 9500


def test_gmail_splitwise_contribution_is_reversed_when_later_sms_is_canonical():
    from app.ledger import (
        apply_splitwise_contribution,
        get_manual_splitwise_total,
        repair_duplicate_transactions,
        set_manual_splitwise_total,
        sync_transaction,
    )

    class T:
        def __init__(self, id, gmail):
            self.id = id
            self.amountMinor = 500
            self.currency = "INR"
            self.type = "CREDIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "AXIS"
            self.merchantOrPayee = "ABDUL WAS"
            self.accountLast4 = "3370"
            self.reference = "240201254528"
            self.timestamp = 1800000000000
            self.category = "GROCERIES"
            self.confidence = 0.99

    set_manual_splitwise_total("INR", 10000)

    gmail = T("gmail:axis-credit-late", True)
    sms = T("axis-credit-sms", False)

    sync_transaction(gmail)
    with connection() as conn:
        apply_splitwise_contribution(conn, gmail, 1800000001000)

    # The SMS arrives later and is the preferred canonical source.
    sync_transaction(sms)
    repaired = repair_duplicate_transactions()

    assert repaired == 1
    assert get_manual_splitwise_total("INR") == 9500

    with connection() as conn:
        gmail_row = conn.execute(
            "SELECT status,duplicate_of FROM transactions WHERE id=?",
            (gmail.id,),
        ).fetchone()
        sms_row = conn.execute(
            "SELECT status,duplicate_of FROM transactions WHERE id=?",
            (sms.id,),
        ).fetchone()
    assert gmail_row["status"] == "VOIDED"
    assert gmail_row["duplicate_of"] == sms.id
    assert sms_row["status"] == "ACTIVE"


def test_true_available_reflects_signed_splitwise_and_card_movements():
    from app.ledger import sync_transaction, set_manual_splitwise_total, true_available

    class T:
        def __init__(self, id, typ, account_type, bank, last4, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "CARD" if account_type == "CREDIT_CARD" else "UPI"
            self.accountType = account_type
            self.bank = bank
            self.merchantOrPayee = "INVARIANT TEST"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 4102444800000
            self.category = "FOOD"
            self.confidence = 0.99

    set_balance("invariant-bank", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "1111", 10000)
    set_balance("invariant-card", "AXIS Card", "INR", "CREDIT_CARD", "AXIS", "2222", 20000)
    set_manual_splitwise_total("INR", 15000)

    baseline = true_available("INR")["trueAvailableMinor"]
    operations = [
        T("inv-bank-debit", "DEBIT", "BANK_ACCOUNT", "HDFC", "1111", 500),
        T("inv-bank-credit", "CREDIT", "BANK_ACCOUNT", "HDFC", "1111", 700),
        T("inv-card-debit", "DEBIT", "CREDIT_CARD", "AXIS", "2222", 800),
        T("inv-card-credit", "CREDIT", "CREDIT_CARD", "AXIS", "2222", 300),
    ]

    # A spend or payment only moves money between buckets, so true available stays put.
    expected_values = [5000, 5000, 5000, 5000]

    for tx, expected in zip(operations, expected_values):
        sync_transaction(tx)
        assert true_available("INR")["trueAvailableMinor"] == expected


def test_splitwise_uses_signed_bank_and_card_rules():
    from app.ledger import sync_transaction, get_manual_splitwise_total

    class T:
        def __init__(self, id, typ, account_type, bank, category, amount, last4):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "CARD" if account_type == "CREDIT_CARD" else "UPI"
            self.accountType = account_type
            self.bank = bank
            self.merchantOrPayee = "TEST"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 2200000000000
            self.category = category
            self.confidence = 0.95

    # Bank debit +2500, bank credit -7000, card debit +1000, card credit -600.
    sync_transaction(T("splitwise-bank-debit", "DEBIT", "BANK_ACCOUNT", "HDFC", "FOOD", 2500, "1234"))
    sync_transaction(T("splitwise-other", "DEBIT", "BANK_ACCOUNT", "HDFC", "TRANSFER", 9000, "1234"))
    sync_transaction(T("splitwise-bank-credit", "CREDIT", "BANK_ACCOUNT", "HDFC", "FOOD", 7000, "1234"))
    sync_transaction(T("splitwise-card-debit", "DEBIT", "CREDIT_CARD", "AXIS", "FOOD", 1000, "5678"))
    sync_transaction(T("splitwise-card-credit", "CREDIT", "CREDIT_CARD", "AXIS", "FOOD", 600, "5678"))

    assert get_manual_splitwise_total("INR") == 4900


def test_voiding_splitwise_contribution_reverses_its_signed_delta():
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

    class Credit:
        id = "splitwise-credit-void"
        amountMinor = 1200
        currency = "INR"
        type = "CREDIT"
        paymentMethod = "UPI"
        accountType = "BANK_ACCOUNT"
        bank = "HDFC"
        merchantOrPayee = "CREDIT TEST"
        accountLast4 = "1234"
        reference = None
        timestamp = 2200000002000
        category = "TRANSFER"
        confidence = 0.95

    sync_transaction(Credit())
    assert get_manual_splitwise_total("INR") == -1200
    result = void_transaction("splitwise-credit-void")
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
        category = "GROCERIES"
        confidence = 0.95

    set_balance("category-edit-account", "Category Edit", "INR", "BANK_ACCOUNT", "HDFC", "4321", 100000)

    # Splitwise follows the canonical bank/card movement; only OTHER opts out.
    sync_transaction(T())
    assert get_manual_splitwise_total("INR") == 4200

    # Category changes must not alter the signed ledger contribution.
    T.category = "GROCERIES"
    assert sync_transaction(T()) is False
    assert get_manual_splitwise_total("INR") == 4200

    # OTHER is the explicit opt-out: the share leaves Splitwise...
    T.category = "OTHER"
    assert sync_transaction(T()) is False
    assert get_manual_splitwise_total("INR") == 0

    # ...and comes back when the category is changed again.
    T.category = "FOOD"
    assert sync_transaction(T()) is False
    assert get_manual_splitwise_total("INR") == 4200


def test_splitwise_is_rebuilt_from_final_bank_and_card_ledger_movements():
    from app.ledger import (
        get_manual_splitwise_total,
        rebuild_manual_splitwise_total,
        set_manual_splitwise_total,
        sync_transaction,
    )

    class T:
        def __init__(self, id, typ, account_type, bank, last4, amount, category="GROCERIES"):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = account_type
            self.bank = bank
            self.merchantOrPayee = "TEST"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 2_100_000_000_000 + amount
            self.category = category
            self.confidence = 1.0

    set_balance("sw-axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "3370", 0)
    set_balance("sw-hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 0)
    set_balance("sw-card", "Axis Card", "INR", "CREDIT_CARD", "AXIS", "9206", 0)

    # Bank movement is negated into Splitwise:
    #   -100 bank => +100 Splitwise
    #    +40 bank => -40 Splitwise
    sync_transaction(T("sw-bank-debit", "DEBIT", "BANK_ACCOUNT", "AXIS", "3370", 10000))
    sync_transaction(T("sw-bank-credit", "CREDIT", "BANK_ACCOUNT", "HDFC", "9591", 4000))

    # Credit-card outstanding movement has the same sign in Splitwise:
    #   +70 outstanding => +70 Splitwise
    #   -20 outstanding => -20 Splitwise
    sync_transaction(T("sw-card-spend", "DEBIT", "CREDIT_CARD", "AXIS", "9206", 7000))
    sync_transaction(T("sw-card-credit", "CREDIT", "CREDIT_CARD", "AXIS", "9206", 2000))

    # Category does not alter the ledger-derived Splitwise amount.
    sync_transaction(T("sw-other", "DEBIT", "BANK_ACCOUNT", "AXIS", "3370", 500, "TRANSFER"))

    # A bank -> card payment is a +20 bank effect in Splitwise and a -20
    # card-outstanding effect, so it cancels completely.
    sync_transaction(T("sw-card-payment-bank", "DEBIT", "BANK_ACCOUNT", "AXIS", "3370", 2000))
    sync_transaction(T("sw-card-payment-card", "CREDIT", "CREDIT_CARD", "AXIS", "9206", 2000))

    rebuild_manual_splitwise_total()

    assert get_manual_splitwise_total("INR") == 11500

    # Rebuilding again must produce exactly the same value.
    rebuild_manual_splitwise_total()
    assert get_manual_splitwise_total("INR") == 11500

    # A drifted materialized value is repaired from baseline + ledger change.
    with connection() as conn:
        conn.execute("UPDATE manual_splitwise_total SET amount_minor=999999")
    rebuild_manual_splitwise_total()
    assert get_manual_splitwise_total("INR") == 11500

    # A manual reset is a baseline: rebuilds keep it and add only later changes.
    set_manual_splitwise_total("INR", 0)
    rebuild_manual_splitwise_total()
    assert get_manual_splitwise_total("INR") == 0
    sync_transaction(T("sw-after-reset", "DEBIT", "BANK_ACCOUNT", "AXIS", "3370", 500))
    rebuild_manual_splitwise_total()
    assert get_manual_splitwise_total("INR") == 500
