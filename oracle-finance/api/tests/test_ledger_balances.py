"""Account balances, card bills, voiding and reconciliation."""
from app.db import connection
from app.ledger import set_balance
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)


def test_bank_only_alert_without_last4_updates_unique_account_balance():
    from app.ledger import get_manual_splitwise_total, set_manual_splitwise_total, sync_transaction

    class T:
        id = "notification:axis-credit-without-last4"
        amountMinor = 300
        currency = "INR"
        type = "CREDIT"
        paymentMethod = "UNKNOWN"
        accountType = "BANK_ACCOUNT"
        bank = "AXIS"
        merchantOrPayee = None
        accountLast4 = None
        reference = None
        timestamp = 1800000000000
        category = "TRANSFER"
        confidence = 0.95

    set_balance(
        "axis-bank-only",
        "Axis Bank",
        "INR",
        "BANK_ACCOUNT",
        "AXIS",
        "3370",
        10000,
    )
    set_manual_splitwise_total("INR", 10000)

    sync_transaction(T())

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='axis-bank-only'"
        ).fetchone()["balance_minor"]

    assert balance == 10300
    # Bank credits reduce the signed Splitwise contribution.
    assert get_manual_splitwise_total("INR") == 9700


def test_new_transactions_update_seeded_account_balances():
    from app.ledger import get_manual_splitwise_total, sync_transaction

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


def test_provisional_bankless_debit_is_corrected_to_axis_credit():
    from app.ledger import get_manual_splitwise_total, sync_transaction

    class T:
        def __init__(self, typ, bank, category):
            self.id = "axis-provisional"
            self.amountMinor = 200
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UNKNOWN" if bank is None else "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = None
            self.accountLast4 = "3370"
            self.reference = None if bank is None else "898523485227"
            self.timestamp = 1_800_000_000_000
            self.category = category
            self.confidence = 0.70 if bank is None else 1.0

    set_balance(
        "axis-provisional-account",
        "Axis Bank",
        "INR",
        "BANK_ACCOUNT",
        "AXIS BANK",
        "3370",
        100000,
    )

    # Fast notification: missing bank, wrong/ambiguous direction. It must not
    # change the Axis balance or become a Splitwise debit contribution.
    sync_transaction(T("DEBIT", None, "GROCERIES"))

    with connection() as conn:
        before = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='axis-provisional-account'"
        ).fetchone()["balance_minor"]
    assert before == 100000
    # A provisional notification row never contributes to Splitwise.
    assert get_manual_splitwise_total("INR") == 0

    # Gmail is authoritative and clarifies this as an Axis credit.
    sync_transaction(T("CREDIT", "AXIS", "GROCERIES"))

    with connection() as conn:
        after = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='axis-provisional-account'"
        ).fetchone()["balance_minor"]
        tx = conn.execute(
            "SELECT type,bank,reference FROM transactions WHERE id='axis-provisional'"
        ).fetchone()

    assert after == 100200
    assert get_manual_splitwise_total("INR") == -200
    assert tx["type"] == "CREDIT"
    assert tx["bank"] == "AXIS"
    assert tx["reference"] == "898523485227"


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


def test_card_direction_rules_match_bank_sign_convention():
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

    assert row["balance_minor"] == 6290306  # bill 5313606 + active spend 976700
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


def test_manual_reconciliation_blocks_late_historical_transactions_but_allows_newer_ones():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, timestamp, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "RECON TEST"
            self.accountLast4 = "1212"
            self.reference = None
            self.timestamp = timestamp
            self.category = "OTHER"
            self.confidence = 0.99

    set_balance(
        "reconciliation-bank",
        "Reconciliation Bank",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "1212",
        100000,
    )

    # A historical Gmail/SMS row discovered after reconciliation must not
    # retroactively alter the manually verified current balance.
    sync_transaction(T("historical", 1_000, 2500))

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor, balance_reconciled_at FROM accounts WHERE id='reconciliation-bank'"
        ).fetchone()

    assert row["balance_minor"] == 100000
    assert row["balance_reconciled_at"] > 0

    # A genuinely newer transaction still moves the balance normally.
    sync_transaction(T("newer", 4_102_444_800_000, 1500))

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='reconciliation-bank'"
        ).fetchone()

    assert row["balance_minor"] == 98500


def test_manual_card_reconciliation_retains_bill_split_for_historical_statement():
    from app.ledger import sync_card_bill

    set_balance(
        "reconciliation-card",
        "Reconciliation Card",
        "INR",
        "CREDIT_CARD",
        "AXIS",
        "3434",
        50000,
        30000,
    )

    result = sync_card_bill({
        "sourceType": "GMAIL_STATEMENT_PDF",
        "sourceKey": "historical-statement",
        "timestamp": 1_000,
        "amountMinor": 42000,
        "currency": "INR",
        "bank": "AXIS",
        "accountLast4": "3434",
        "confidence": 0.99,
    })

    assert result["status"] == "RETAINED_MANUAL_RECONCILIATION"

    with connection() as conn:
        row = conn.execute(
            "SELECT balance_minor, bill_balance_minor FROM accounts WHERE id='reconciliation-card'"
        ).fetchone()

    assert row["balance_minor"] == 50000
    assert row["bill_balance_minor"] == 30000


def test_axis_bank_alias_and_late_transaction_update_balance():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, typ, timestamp):
            self.id = id
            self.amountMinor = 300
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "AXIS"
            self.merchantOrPayee = "ABDUL WAS"
            self.accountLast4 = "3370"
            self.reference = "898523485227"
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 0.98

    # The configured account may contain the display name "AXIS BANK" while
    # transaction parsers normalize the provider to "AXIS".
    set_balance(
        "axis-bank",
        "Axis Bank",
        "INR",
        "BANK_ACCOUNT",
        "AXIS BANK",
        "3370",
        100000,
    )

    # Simulate a late notification: the bank event timestamp is before the
    # manual reconciliation point, but the transaction itself is discovered
    # after that reconciliation.
    sync_transaction(T("axis-late-credit", "CREDIT", 1700000000000))

    with connection() as conn:
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='axis-bank'"
        ).fetchone()["balance_minor"]

    assert balance == 100300


def test_fresh_database_auto_provisions_unambiguous_bank_accounts():
    from app.ledger import sync_transaction

    class T:
        def __init__(self, id, typ, bank, amount):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UNKNOWN"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "SELF"
            self.accountLast4 = None
            self.reference = None
            self.timestamp = 1800000000000
            self.category = "TRANSFER"
            self.confidence = 0.99

    sync_transaction(T("hdfc-debit-fresh", "DEBIT", "HDFC", 300))
    sync_transaction(T("axis-credit-fresh", "CREDIT", "AXIS", 300))

    with connection() as conn:
        hdfc = conn.execute(
            "SELECT name,bank,account_type,balance_minor FROM accounts WHERE bank='HDFC' AND account_type='BANK_ACCOUNT'"
        ).fetchone()
        axis = conn.execute(
            "SELECT name,bank,account_type,balance_minor FROM accounts WHERE bank='AXIS' AND account_type='BANK_ACCOUNT'"
        ).fetchone()

    assert hdfc["account_type"] == "BANK_ACCOUNT"
    assert hdfc["balance_minor"] == -300
    assert axis["account_type"] == "BANK_ACCOUNT"
    assert axis["balance_minor"] == 300
