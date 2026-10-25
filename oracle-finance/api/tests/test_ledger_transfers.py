"""Own-account transfers net to zero in Splitwise."""
from app.db import connection
from app.ledger import set_balance, true_available
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)


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


def test_self_transfer_has_zero_splitwise_net_change():
    from app.ledger import get_manual_splitwise_total, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4):
            self.id = id
            self.amountMinor = 600
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "SELF"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 1_800_000_000_000
            self.category = "TRANSFER"
            self.confidence = 1.0

    set_balance("self-axis", "Axis Bank", "INR", "BANK_ACCOUNT", "AXIS BANK", "3370", 100000)
    set_balance("self-hdfc", "HDFC Bank", "INR", "BANK_ACCOUNT", "HDFC", "9591", 50000)

    sync_transaction(T("axis-debit-6", "DEBIT", "AXIS", "3370"))
    assert get_manual_splitwise_total("INR") == 600

    sync_transaction(T("hdfc-credit-6", "CREDIT", "HDFC", "9591"))
    assert get_manual_splitwise_total("INR") == 0

    with connection() as conn:
        axis = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='self-axis'"
        ).fetchone()["balance_minor"]
        hdfc = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='self-hdfc'"
        ).fetchone()["balance_minor"]

    assert axis == 99400
    assert hdfc == 50600


def test_internal_transfer_neutralizes_splitwise_even_when_credit_category_differs():
    from app.ledger import (
        get_manual_splitwise_total,
        match_internal_transfers,
        rebuild_manual_splitwise_total,
        sync_transaction,
    )

    class T:
        def __init__(self, id, typ, bank, last4, category):
            self.id = id
            self.amountMinor = 300
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 4_200_000_000_000
            self.category = category
            self.confidence = 1.0

    set_balance("transfer-axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "3370", 1000)
    set_balance("transfer-hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 2000)

    # Reproduce the device pattern: the debit is TRANSFER while the receiving
    # side was classified independently as REFUND.
    sync_transaction(T("axis-debit-300", "DEBIT", "AXIS", "3370", "TRANSFER"))
    sync_transaction(T("hdfc-credit-300", "CREDIT", "HDFC", "9591", "REFUND"))

    # Reproduce the observed asymmetric state: only the receiving credit
    # has affected the Splitwise aggregate.
    with connection() as conn:
        conn.execute(
            """INSERT INTO manual_splitwise_total
                   (currency,amount_minor,updated_at,baseline_amount_minor,baseline_ledger_minor)
               VALUES('INR',-300,4200000001000,0,0)
               ON CONFLICT(currency) DO UPDATE SET
                   amount_minor=excluded.amount_minor,
                   updated_at=excluded.updated_at,
                   baseline_amount_minor=excluded.baseline_amount_minor,
                   baseline_ledger_minor=excluded.baseline_ledger_minor"""
        )

    # The shown value is derived from the ledger, so the stale -300 cache above
    # can never be displayed: the unmatched legs already net to zero.
    assert get_manual_splitwise_total("INR") == 0
    matches = match_internal_transfers()
    assert len(matches) == 1

    # The matcher classifies the transfer; rebuild the materialized Splitwise
    # value from the canonical ledger before checking the final aggregate.
    rebuild_manual_splitwise_total("INR")

    # Bank balances remain real, but the matched own-account transfer is
    # completely excluded from Splitwise.
    assert get_manual_splitwise_total("INR") == 0
    assert match_internal_transfers() == []
    assert get_manual_splitwise_total("INR") == 0


def test_self_transfer_cancels_splitwise_and_preserves_true_available():
    from app.ledger import sync_transaction, set_manual_splitwise_total, true_available

    class T:
        def __init__(self, id, typ, bank, last4):
            self.id = id
            self.amountMinor = 500
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 4102444800000
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance("self-hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 10000)
    set_balance("self-axis", "AXIS", "INR", "BANK_ACCOUNT", "AXIS", "3370", 20000)
    set_manual_splitwise_total("INR", 3000)

    before = true_available("INR")
    sync_transaction(T("self-hdfc-debit", "DEBIT", "HDFC", "9591"))
    sync_transaction(T("self-axis-credit", "CREDIT", "AXIS", "3370"))
    after = true_available("INR")

    with connection() as conn:
        hdfc = conn.execute("SELECT balance_minor FROM accounts WHERE id='self-hdfc'").fetchone()["balance_minor"]
        axis = conn.execute("SELECT balance_minor FROM accounts WHERE id='self-axis'").fetchone()["balance_minor"]

    assert hdfc == 9500
    assert axis == 20500
    assert after["splitwiseReceivableMinor"] == before["splitwiseReceivableMinor"]
    assert after["bankCashMinor"] == before["bankCashMinor"]
    assert after["creditCardOutstandingMinor"] == before["creditCardOutstandingMinor"]
    assert after["trueAvailableMinor"] == before["trueAvailableMinor"]


def test_gmail_credit_and_sms_debit_self_transfer_neutralizes_splitwise():
    from app.ledger import get_manual_splitwise_total, match_internal_transfers, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, reference):
            self.id = id
            self.amountMinor = 500
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI" if typ == "DEBIT" else "UNKNOWN"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = 1_800_000_000_000
            self.category = "TRANSFER"
            self.confidence = 0.99

    set_balance("observed-axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "3370", 10000)
    set_balance("observed-hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 10000)

    # This reproduces the observed device state: the Axis credit is a Gmail
    # mirror, so it does not contribute to Splitwise; the HDFC SMS debit does.
    sync_transaction(T("gmail:axis-credit-500", "CREDIT", "AXIS", None, None))
    sync_transaction(T("hdfc-debit-500", "DEBIT", "HDFC", "9591", "228327297579"))

    # The shown value is derived from the ledger, so both unmatched legs count
    # (+500 debit, -500 credit) and the pair already nets to zero.
    assert get_manual_splitwise_total("INR") == 0

    matches = match_internal_transfers()

    assert any(
        m["debitTransactionId"] == "hdfc-debit-500"
        and m["creditTransactionId"] == "gmail:axis-credit-500"
        for m in matches
    )
    assert get_manual_splitwise_total("INR") == 0


def test_same_transfer_reference_matches_even_when_more_than_ten_minutes_apart():
    from app.ledger import match_internal_transfers, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, timestamp):
            self.id = id
            self.amountMinor = 1000
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = "UPI/P2A/185534369134/ABDUL WASIQ"
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 1.0

    sync_transaction(T("axis-ref-debit", "DEBIT", "AXIS", "3370", 1_900_000_000_000))
    sync_transaction(T("hdfc-ref-credit", "CREDIT", "HDFC", "9591", 1_900_000_900_000))

    matches = match_internal_transfers()

    assert any(
        m["debitTransactionId"] == "axis-ref-debit"
        and m["creditTransactionId"] == "hdfc-ref-credit"
        and m["reason"] == "same normalized transfer reference"
        for m in matches
    )


def test_android_internal_transfer_candidate_is_confirmed_and_neutralized():
    from app.ledger import (
        apply_internal_transfer_candidates,
        get_manual_splitwise_total,
        sync_transaction,
    )

    class T:
        def __init__(self, id, typ, bank, last4):
            self.id = id
            self.amountMinor = 1000
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = None
            self.timestamp = 2_000_000_000_000
            self.category = "TRANSFER"
            self.confidence = 1.0

    set_balance("candidate-axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "3370", 10000)
    set_balance("candidate-hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 10000)

    # The receiving Gmail row is already known to Oracle but has not contributed
    # to Splitwise. The local/SMS debit has contributed +1000.
    sync_transaction(T("candidate-axis-debit", "DEBIT", "AXIS", "3370"))
    sync_transaction(T("gmail:candidate-hdfc-credit", "CREDIT", "HDFC", "9591"))

    assert get_manual_splitwise_total("INR") == 0

    result = apply_internal_transfer_candidates([
        {
            "debitTransactionId": "candidate-axis-debit",
            "creditTransactionId": "gmail:candidate-hdfc-credit",
            "amountMinor": 1000,
            "currency": "INR",
            "timeDifferenceMillis": 0,
            "matchType": "AMOUNT_TIME",
        }
    ])

    assert len(result) == 1
    assert result[0]["debitTransactionId"] == "candidate-axis-debit"
    assert result[0]["creditTransactionId"] == "gmail:candidate-hdfc-credit"
    assert get_manual_splitwise_total("INR") == 0


def test_self_transfer_sequence_with_nonzero_state_repairs_missing_leg_and_stays_neutral():
    from app.ledger import get_manual_splitwise_total, match_internal_transfers, sync_transaction

    class T:
        def __init__(self, id, typ, bank, last4, amount, timestamp, reference):
            self.id = id
            self.amountMinor = amount
            self.currency = "INR"
            self.type = typ
            self.paymentMethod = "UPI"
            self.accountType = "BANK_ACCOUNT"
            self.bank = bank
            self.merchantOrPayee = "ABDUL WASIQ"
            self.accountLast4 = last4
            self.reference = reference
            self.timestamp = timestamp
            self.category = "TRANSFER"
            self.confidence = 1.0

    set_balance("seq-axis", "Axis Bank", "INR", "BANK_ACCOUNT", "AXIS", "3370", 0)
    set_balance("seq-hdfc", "HDFC Bank", "INR", "BANK_ACCOUNT", "HDFC", "9591", 0)

    # First transfer: HDFC -> Axis ₹10.
    sync_transaction(T("hdfc-debit-10", "DEBIT", "HDFC", "9591", 1000, 1_900_000_000_000, "528855314160"))
    sync_transaction(T("axis-credit-10", "CREDIT", "AXIS", "3370", 1000, 1_900_000_100_000, "528855314160"))
    match_internal_transfers()

    # Do not reset anything. The second transfer is Axis -> HDFC ₹3.
    # Simulate the observed asymmetric ledger state: the Axis transaction and
    # its Splitwise contribution exist, but its bank balance adjustment is
    # missing. The transfer matcher must restore only that missing balance leg.
    axis_debit = T(
        "axis-debit-3",
        "DEBIT",
        "AXIS",
        "3370",
        300,
        1_900_000_200_000,
        "797836991220",
    )
    sync_transaction(axis_debit)

    with connection() as conn:
        conn.execute(
            "DELETE FROM balance_adjustments WHERE transaction_id=?",
            ("axis-debit-3",),
        )
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor+300 WHERE id=?",
            ("seq-axis",),
        )
    sync_transaction(
        T(
            "hdfc-credit-3",
            "CREDIT",
            "HDFC",
            "9591",
            300,
            1_900_000_206_000,
            "797836991220",
        )
    )

    match_internal_transfers()

    with connection() as conn:
        axis = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='seq-axis'"
        ).fetchone()["balance_minor"]
        hdfc = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='seq-hdfc'"
        ).fetchone()["balance_minor"]
        adjustment = conn.execute(
            "SELECT delta_minor FROM balance_adjustments WHERE transaction_id='axis-debit-3'"
        ).fetchone()

    assert axis == 700
    assert hdfc == -700
    assert adjustment["delta_minor"] == -300
    assert get_manual_splitwise_total("INR") == 0
