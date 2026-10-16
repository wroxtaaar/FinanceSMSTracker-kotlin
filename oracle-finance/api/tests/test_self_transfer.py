"""Self-transfer regression tests (synthetic data only)."""
import pytest

from app import db
from app import ledger as L
from app.db import connection

T0 = 1_800_000_000_000
AMOUNT = 250_000  # INR 2,500.00
RRN = "123456789012"


@pytest.fixture(autouse=True)
def isolated_database(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "finance-test.db"))
    db.init_db()
    L.set_balance("axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "1111", 1_000_000)
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 500_000)
    L.set_manual_splitwise_total("INR", 0)


class Tx:
    def __init__(self, **kw):
        values = dict(currency="INR", paymentMethod="UPI", accountType="BANK_ACCOUNT",
                      category="TRANSFER", confidence=0.95, merchantOrPayee=None, reference=None)
        values.update(kw)
        self.__dict__.update(values)


def sms_debit(ref=None, bank="AXIS", last4="1111", ts=T0, amount=AMOUNT, id="sms:debit"):
    return Tx(id=id, amountMinor=amount, type="DEBIT", bank=bank, accountLast4=last4, reference=ref, timestamp=ts)


def gmail_debit(ref=RRN, bank="AXIS", last4="1111", ts=T0 + 60_000, amount=AMOUNT):
    return Tx(id="gmail:debit", amountMinor=amount, type="DEBIT", bank=bank, accountLast4=last4, reference=ref, timestamp=ts)


def gmail_credit(ref=RRN, bank="HDFC", last4="2222", ts=T0 + 90_000, amount=AMOUNT):
    return Tx(id="gmail:credit", amountMinor=amount, type="CREDIT", bank=bank, accountLast4=last4, reference=ref, timestamp=ts)


def ingest_sms(t):
    L.sync_transaction(t)
    L.reconcile_duplicate_transaction(t.id)
    L.repair_duplicate_transactions()
    L.match_internal_transfers()
    L.rebuild_manual_splitwise_total()


def ingest_gmail(t):
    """Mirror gmail_sync: store, dedupe, then apply balance/Splitwise to a surviving Gmail-only row."""
    L.sync_transaction(t)
    L.reconcile_duplicate_transaction(t.id)
    with connection() as conn:
        row = conn.execute("SELECT * FROM transactions WHERE id=?", (t.id,)).fetchone()
        if row and not row["duplicate_of"]:
            L.apply_transaction_to_account(conn, t, T0 + 1)
            L.apply_splitwise_contribution(conn, t, T0 + 1)
    L.repair_duplicate_transactions()
    L.match_internal_transfers()
    L.rebuild_manual_splitwise_total()


def balances():
    with connection() as conn:
        return {r["id"]: r["balance_minor"] for r in conn.execute(
            "SELECT id,balance_minor FROM accounts WHERE id IN ('axis','hdfc')")}


def active_ids():
    return sorted(r["id"] for r in L.list_transactions(1000))


def assert_settled_self_transfer():
    # Two ledger legs (debit + credit); the e-mail duplicate of the SMS debit is hidden.
    assert len(active_ids()) == 2
    assert len(L.list_transfers()) == 1
    assert balances() == {"axis": 1_000_000 - AMOUNT, "hdfc": 500_000 + AMOUNT}
    # Own-account transfer is neutral for consolidated Splitwise/true-available reporting.
    assert L.get_manual_splitwise_total("INR") == 0
    assert L.true_available("INR")["bankCashMinor"] == 1_500_000


def test_sms_debit_gmail_debit_gmail_credit_is_one_transfer():
    ingest_sms(sms_debit(ref=RRN))
    ingest_gmail(gmail_debit())
    ingest_gmail(gmail_credit())
    assert_settled_self_transfer()


def test_sms_without_reference_is_enriched_by_gmail():
    ingest_sms(sms_debit(ref=None))
    ingest_gmail(gmail_debit())
    ingest_gmail(gmail_credit())
    assert_settled_self_transfer()


def test_credit_arrives_before_debit():
    ingest_gmail(gmail_credit())
    ingest_sms(sms_debit(ref=RRN))
    ingest_gmail(gmail_debit())
    assert_settled_self_transfer()


def test_credit_gmail_then_gmail_debit_then_sms():
    ingest_gmail(gmail_credit())
    ingest_gmail(gmail_debit())
    ingest_sms(sms_debit(ref=None))
    assert_settled_self_transfer()


def test_repeated_ingestion_is_idempotent():
    for _ in range(2):
        ingest_sms(sms_debit(ref=RRN))
        ingest_gmail(gmail_debit())
        ingest_gmail(gmail_credit())
    assert_settled_self_transfer()


def test_sms_missing_last4_gets_identity_and_balance_from_gmail_duplicate():
    """Regression: the debit was hidden as a duplicate but never reduced the source balance."""
    ingest_sms(sms_debit(ref=None, last4=""))
    ingest_gmail(gmail_debit())
    ingest_gmail(gmail_credit())
    assert_settled_self_transfer()


def test_different_references_on_each_leg_still_link_by_time_window():
    ingest_sms(sms_debit(ref="999"))
    ingest_gmail(gmail_debit(ref="999", ts=T0 + 60_000))
    ingest_gmail(gmail_credit(ref="888"))
    assert_settled_self_transfer()


def test_same_amount_unrelated_transactions_are_not_linked():
    ingest_sms(sms_debit(ref="111"))
    ingest_gmail(gmail_credit(ref="222", ts=T0 + 3 * 60 * 60 * 1000))  # 3h later, different RRN
    assert L.list_transfers() == []


def test_payment_to_another_person_is_not_a_self_transfer():
    ingest_sms(sms_debit(ref=RRN))
    assert L.list_transfers() == []
    assert balances()["axis"] == 1_000_000 - AMOUNT
    assert L.get_manual_splitwise_total("INR") == AMOUNT  # ordinary expense, not neutralized


def test_credit_on_same_account_is_not_a_self_transfer():
    ingest_sms(sms_debit(ref="111"))
    ingest_gmail(gmail_credit(ref="222", bank="AXIS", last4="1111", ts=T0 + 30_000))
    assert L.list_transfers() == []


def test_lone_leg_is_not_discarded_and_links_when_counterpart_arrives():
    ingest_gmail(gmail_credit())
    assert L.list_transfers() == []
    assert active_ids() == ["gmail:credit"]
    ingest_sms(sms_debit(ref=RRN))
    assert len(L.list_transfers()) == 1
    assert len(active_ids()) == 2
