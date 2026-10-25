"""Duplicate detection and repair across SMS, notification and e-mail rows."""
from app.db import connection
from app.ledger import set_balance
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)


def test_exact_reference_is_primary_duplicate_identity():
    from app.ledger import repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, amount, reference):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "AXIS"
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = "3370"
            self.reference = reference
            self.timestamp = 1800000000000
            self.category = "GROCERIES"
            self.confidence = 0.95

    sync_transaction(T("sms-reference-first", 1000, "911389419630"))
    sync_transaction(T("gmail-reference-later", 1200, "911389419630"))

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    with connection() as conn:
        rows = conn.execute(
            "SELECT id,status,duplicate_of FROM transactions ORDER BY id"
        ).fetchall()

    active = [row for row in rows if row["status"] == "ACTIVE"]
    voided = [row for row in rows if row["status"] == "VOIDED"]

    assert len(active) == 1
    assert len(voided) == 1
    assert voided[0]["duplicate_of"] == active[0]["id"]


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
    assert axis["reference"] == "739593577194"


def test_repair_same_side_duplicate_keeps_referenced_hdfc_credit_only_once():
    from app.ledger import repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, reference, timestamp, last4):
            self.id = id
            self.amountMinor = 600
            self.currency = "INR"
            self.type = "CREDIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "HDFC Bank"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = timestamp
            self.category = "OTHER"
            self.confidence = 0.99

    set_balance(
        "dedupe-hdfc",
        "HDFC 9591",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "9591",
        100000,
    )

    sync_transaction(T("hdfc-credit-no-ref", None, 1_800_000_000_000, "9591"))
    # The second source knows the RRN but omits the last-four entirely.
    sync_transaction(T("hdfc-credit-with-ref", "240201254528", 1_800_000_060_000, ""))

    with connection() as conn:
        before = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='dedupe-hdfc'"
        ).fetchone()["balance_minor"]
    # The referenced source omits the last-four and therefore cannot apply a
    # second account adjustment on insertion. Only the first representation
    # has contributed to the balance so far.
    assert before == 100600

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    with connection() as conn:
        rows = conn.execute(
            """SELECT id,status,duplicate_of,reference
               FROM transactions
               WHERE amount_minor=600 AND bank='HDFC' AND type='CREDIT'
               ORDER BY id"""
        ).fetchall()
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='dedupe-hdfc'"
        ).fetchone()["balance_minor"]

    active = [row for row in rows if row["status"] == "ACTIVE" and row["duplicate_of"] is None]
    assert len(active) == 1
    assert active[0]["id"] == "hdfc-credit-with-ref"
    assert active[0]["reference"] == "240201254528"
    assert balance == 100600


def test_duplicate_repair_reverses_splitwise_for_moved_balance_adjustment():
    from app.ledger import get_manual_splitwise_total, repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, reference, timestamp, last4):
            self.id = id
            self.amountMinor = 600
            self.currency = "INR"
            self.type = "CREDIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "HDFC Bank"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance(
        "splitwise-duplicate-hdfc",
        "HDFC 9591",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "9591",
        100000,
    )

    # The first source has the account identity and therefore applies the bank
    # adjustment. The later referenced source omits last-four, so it becomes
    # canonical during duplicate repair but cannot apply a second balance
    # adjustment on insertion.
    sync_transaction(T("hdfc-credit-unreferenced-sw", None, 1_800_000_000_000, "9591"))
    sync_transaction(T("hdfc-credit-referenced-sw", "240201254528", 1_800_000_060_000, ""))

    # Both representations initially contribute to Splitwise.
    assert get_manual_splitwise_total("INR") == -1200

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    # Only the canonical HDFC credit remains in Splitwise.
    assert get_manual_splitwise_total("INR") == -600

    with connection() as conn:
        rows = conn.execute(
            """SELECT id,status,duplicate_of
               FROM transactions
               WHERE amount_minor=600 AND bank='HDFC' AND type='CREDIT'
                 AND id LIKE 'hdfc-credit-%'
               ORDER BY id"""
        ).fetchall()
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='splitwise-duplicate-hdfc'"
        ).fetchone()["balance_minor"]

    active = [row for row in rows if row["status"] == "ACTIVE" and row["duplicate_of"] is None]
    assert len(active) == 1
    assert active[0]["id"] == "hdfc-credit-referenced-sw"
    assert balance == 100600


def test_gmail_sms_same_side_duplicate_with_delivery_delay_is_repaired_once():
    from app.ledger import repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, reference, timestamp):
            self.id = id
            self.amountMinor = 500
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = "9591"
            self.reference = reference
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance(
        "gmail-sms-delay-dedupe",
        "HDFC 9591",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "9591",
        10000,
    )

    # SMS is the canonical row. Gmail arrives later with the same account and
    # counterparty but a reference the SMS did not expose.
    sync_transaction(T("hdfc-sms-500", None, 1_800_000_000_000))
    sync_transaction(T("gmail:hdfc-500", "UPI/ABC/898523485227/ABDUL WAS", 1_800_000_000_000 + 30 * 60 * 1000))

    with connection() as conn:
        before = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-sms-delay-dedupe'"
        ).fetchone()["balance_minor"]

    assert before == 9500

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    with connection() as conn:
        rows = conn.execute(
            """SELECT id,status,duplicate_of,reference
               FROM transactions
               WHERE amount_minor=500 AND bank='HDFC' AND type='DEBIT'
               ORDER BY id"""
        ).fetchall()
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='gmail-sms-delay-dedupe'"
        ).fetchone()["balance_minor"]

    active = [row for row in rows if row["status"] == "ACTIVE" and row["duplicate_of"] is None]
    assert len(active) == 1
    assert active[0]["id"] == "hdfc-sms-500"
    assert active[0]["reference"] == "898523485227"
    assert balance == 9500


def test_generic_bank_merchant_does_not_block_same_side_duplicate_repair():
    from app.ledger import get_manual_splitwise_total, match_internal_transfers, repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, merchant, timestamp):
            self.id = id
            self.amountMinor = 300
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = merchant
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance("dedupe-axis", "Axis 3370", "INR", "BANK_ACCOUNT", "AXIS", "3370", 10000)
    set_balance("dedupe-hdfc", "HDFC 9591", "INR", "BANK_ACCOUNT", "HDFC", "9591", 20000)

    # The receiving side is represented twice: one source has the real
    # counterparty, while the notification parser may expose the bank name.
    sync_transaction(T("axis-debit", "DEBIT", "AXIS", "3370", "ABDUL WASIQ", 1800000000000))
    sync_transaction(T("hdfc-credit-sms", "CREDIT", "HDFC", "9591", "ABDUL WASIQ", 1800000000000))
    sync_transaction(T("notification:hdfc-credit", "CREDIT", "HDFC", "9591", "HDFC Bank", 1800000005000))

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    # Only one HDFC credit remains, then the AXIS debit + HDFC credit pair is
    # treated as the user's own transfer and removed from Splitwise.
    assert match_internal_transfers()
    assert get_manual_splitwise_total("INR") == 0


def test_gmail_notification_same_side_duplicate_with_delay_is_repaired_once():
    from app.ledger import repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, timestamp):
            self.id = id
            self.amountMinor = 500
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = "HDFC"
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = "9591"
            self.reference = None
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance(
        "notification-delay-dedupe",
        "HDFC 9591",
        "INR",
        "BANK_ACCOUNT",
        "HDFC",
        "9591",
        10000,
    )

    sync_transaction(T("hdfc-sms-500b", 1_800_000_000_000))
    sync_transaction(T("notification:hdfc-500b", 1_800_000_000_000 + 45 * 60 * 1000))

    with connection() as conn:
        assert conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='notification-delay-dedupe'"
        ).fetchone()["balance_minor"] == 9000

    repaired = repair_duplicate_transactions()
    assert repaired == 1

    with connection() as conn:
        active = conn.execute(
            """SELECT COUNT(*) value FROM transactions
               WHERE amount_minor=500 AND bank='HDFC' AND type='DEBIT'
                 AND status='ACTIVE' AND duplicate_of IS NULL"""
        ).fetchone()["value"]
        balance = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='notification-delay-dedupe'"
        ).fetchone()["balance_minor"]

    assert active == 1
    assert balance == 9500


def test_repair_does_not_merge_opposite_sides_with_same_rrn():
    from app.ledger import repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, reference):
            self.id = id
            self.amountMinor = 600
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "TRANSFER"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = 1_800_000_010_000
            self.category = "TRANSFER"
            self.confidence = 0.99

    sync_transaction(T("axis-debit-rrn", "DEBIT", "AXIS", "3370", "240201254528"))
    sync_transaction(T("hdfc-credit-rrn", "CREDIT", "HDFC", "9591", "240201254528"))

    repaired = repair_duplicate_transactions()
    assert repaired == 0

    with connection() as conn:
        count = conn.execute(
            "SELECT COUNT(*) value FROM transactions WHERE duplicate_of IS NULL AND reference='240201254528'"
        ).fetchone()["value"]
    assert count == 2


def test_referenced_mirror_with_missing_merchant_is_collapsed_and_splitwise_reversed():
    from app.ledger import get_manual_splitwise_total, repair_duplicate_transactions, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, reference, merchant, payment):
            self.id = id
            self.amountMinor = 600
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = payment
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = merchant
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = 1_950_000_000_000
            self.category = "GROCERIES"
            self.confidence = 1.0

    set_balance("mirror-axis", "Axis Bank", "INR", "BANK_ACCOUNT", "AXIS", "3370", 0)

    sync_transaction(
        T("axis-local-6", "DEBIT", "AXIS", "3370", None, "-", "UNKNOWN")
    )
    sync_transaction(
        T(
            "notification:axis-detailed-6",
            "DEBIT",
            "AXIS",
            "3370",
            "299284702774",
            "ABDUL WASIQ",
            "UPI",
        )
    )

    # Both local rows initially contribute because the duplicate is only
    # collapsed by the repair pass.
    assert get_manual_splitwise_total("INR") == 1200

    repaired = repair_duplicate_transactions()

    with connection() as conn:
        active = conn.execute(
            "SELECT id,reference,merchant_or_payee,status,duplicate_of "
            "FROM transactions WHERE status='ACTIVE' ORDER BY id"
        ).fetchall()
        voided = conn.execute(
            "SELECT id,duplicate_of,status FROM transactions WHERE status='VOIDED'"
        ).fetchall()

    assert repaired == 1
    assert len(active) == 1
    assert active[0]["reference"] == "299284702774"
    assert active[0]["merchant_or_payee"] == "ABDUL WASIQ"
    assert len(voided) == 1
    assert voided[0]["duplicate_of"] == active[0]["id"]
    assert get_manual_splitwise_total("INR") == 600

    # The remaining canonical Axis debit is the legitimate transfer leg.
    sync_transaction(
        T(
            "hdfc-credit-6",
            "CREDIT",
            "HDFC",
            "9591",
            "299284702774",
            "HDFC Bank",
            "UPI",
        )
    )

    assert get_manual_splitwise_total("INR") == 0


def test_card_spend_email_first_then_sms_is_not_double_counted():
    from app.ledger import (
        get_manual_splitwise_total,
        reconcile_duplicate_transaction,
        repair_duplicate_transactions,
        sync_transaction,
    )

    class T:
        def __init__(self, id, merchant, timestamp):
            self.id = id
            self.amountMinor = 5000
            self.currency = "INR"
            self.type = "DEBIT"
            self.paymentMethod = "CARD"
            self.accountType = "CREDIT_CARD"
            self.bank = "AXIS"
            self.merchantOrPayee = merchant
            self.accountLast4 = "9206"
            self.reference = None
            self.timestamp = timestamp
            self.category = "GROCERIES"
            self.confidence = 0.95

    set_balance("card-order", "Axis 9206", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    before = get_manual_splitwise_total("INR") or 0

    # The e-mail row lands first; the SMS (with a merchant) arrives 30 minutes
    # away from the e-mail's timestamp. Arrival order must not change the result.
    email = T("gmail:order-1", None, 1_800_000_000_000 + 30 * 60 * 1000)
    sync_transaction(email)
    reconcile_duplicate_transaction(email.id)
    repair_duplicate_transactions()

    sms = T("sms-order-1", "Mr ISRAR", 1_800_000_000_000)
    sync_transaction(sms)
    reconcile_duplicate_transaction(sms.id)
    repair_duplicate_transactions()

    with connection() as conn:
        card = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='card-order'"
        ).fetchone()["balance_minor"]
    assert card == 5000
    assert (get_manual_splitwise_total("INR") or 0) - before == 5000
