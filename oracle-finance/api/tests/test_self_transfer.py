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


# --- Pattern from the real-world report: HDFC ...9591 receives INR 2 from Axis ...3370, one UPI reference ---
UPI_REF = "401032212433"
TWO_RUPEES = 200


def _example_accounts():
    L.set_balance("axis3370", "Axis 3370", "INR", "BANK_ACCOUNT", "AXIS", "3370", 100_000)
    L.set_balance("hdfc9591", "HDFC 9591", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100_000)


def _legs():
    axis_sms = Tx(id="sms:axis", amountMinor=TWO_RUPEES, type="DEBIT", bank="AXIS", accountLast4="3370",
                  reference=UPI_REF, timestamp=T0, merchantOrPayee="HDFC Bank")
    hdfc_sms = Tx(id="sms:hdfc", amountMinor=TWO_RUPEES, type="CREDIT", bank="HDFC", accountLast4="9591",
                  reference=UPI_REF, timestamp=T0 + 20_000, merchantOrPayee="HDFC Bank")
    axis_mail = Tx(id="gmail:axis", amountMinor=TWO_RUPEES, type="DEBIT", bank="AXIS", accountLast4="3370",
                   reference=UPI_REF, timestamp=T0 + 90_000, merchantOrPayee="HDFC Bank")
    hdfc_mail = Tx(id="gmail:hdfc", amountMinor=TWO_RUPEES, type="CREDIT", bank="HDFC", accountLast4="9591",
                   reference=UPI_REF, timestamp=T0 + 100_000, merchantOrPayee="HDFC Bank")
    return axis_sms, hdfc_sms, axis_mail, hdfc_mail


def _assert_example_settled():
    with connection() as conn:
        bal = {r["id"]: r["balance_minor"] for r in conn.execute(
            "SELECT id,balance_minor FROM accounts WHERE id IN ('axis3370','hdfc9591')")}
    assert bal == {"axis3370": 100_000 - TWO_RUPEES, "hdfc9591": 100_000 + TWO_RUPEES}
    assert sorted(r["type"] for r in L.list_transactions(100)) == ["CREDIT", "DEBIT"]
    assert len(L.list_transfers()) == 1
    assert L.get_manual_splitwise_total("INR") == 0


def test_example_sms_both_sides_then_emails():
    _example_accounts()
    axis_sms, hdfc_sms, axis_mail, hdfc_mail = _legs()
    ingest_sms(axis_sms); ingest_sms(hdfc_sms); ingest_gmail(axis_mail); ingest_gmail(hdfc_mail)
    _assert_example_settled()


def test_example_emails_before_sms():
    _example_accounts()
    axis_sms, hdfc_sms, axis_mail, hdfc_mail = _legs()
    ingest_gmail(hdfc_mail); ingest_gmail(axis_mail); ingest_sms(hdfc_sms); ingest_sms(axis_sms)
    _assert_example_settled()


def test_example_axis_sms_only_with_both_emails_and_replay():
    """Matches the user's setup: one bank texts, the other only e-mails; everything is replayed."""
    _example_accounts()
    axis_sms, _hdfc_sms, axis_mail, hdfc_mail = _legs()
    for _ in range(2):
        ingest_sms(axis_sms); ingest_gmail(axis_mail); ingest_gmail(hdfc_mail)
    _assert_example_settled()


def test_example_bank_name_as_counterparty_label_does_not_merge_the_two_sides():
    """'HDFC Bank' appears as the payee label on both legs; opposite directions must stay separate."""
    _example_accounts()
    axis_sms, hdfc_sms, _axis_mail, _hdfc_mail = _legs()
    ingest_sms(axis_sms); ingest_sms(hdfc_sms)
    _assert_example_settled()


def test_example_second_same_amount_payment_to_a_stranger_is_not_swallowed():
    _example_accounts()
    axis_sms, hdfc_sms, _am, _hm = _legs()
    ingest_sms(axis_sms); ingest_sms(hdfc_sms)
    stranger = Tx(id="sms:stranger", amountMinor=TWO_RUPEES, type="DEBIT", bank="AXIS", accountLast4="3370",
                  reference="777000111222", timestamp=T0 + 30 * 60_000, merchantOrPayee="HDFC Bank")
    ingest_sms(stranger)
    assert len(L.list_transactions(100)) == 3
    assert len(L.list_transfers()) == 1


# --- Provisional debit (Gmail notification: last-four only, no bank/reference, UNKNOWN method) ---
# Real report: after resetting balances and Splitwise to 0, a INR 3 Axis -> HDFC transfer left
# bank balances right (-3 / +3) but Splitwise at -3, because only the credit ever contributed.
def _provisional_debit(bank=""):
    return Tx(id="notification:debit", amountMinor=300, type="DEBIT", paymentMethod="UNKNOWN", bank=bank,
              accountLast4="3370", timestamp=T0, category="GROCERIES")


def _credit_sms():
    return Tx(id="sms:credit", amountMinor=300, type="CREDIT", bank="HDFC", accountLast4="9591",
              reference=UPI_REF, timestamp=T0 + 30_000)


def _sync_like_endpoint(*txs, rebuild):
    for t in txs:
        L.sync_transaction(t)
    for t in txs:
        L.reconcile_duplicate_transaction(t.id)
    L.repair_duplicate_transactions()
    L.match_internal_transfers()
    if rebuild:
        L.rebuild_manual_splitwise_total()


def _provisional_setup():
    _example_accounts()
    L.set_balance("axis3370", "Axis 3370", "INR", "BANK_ACCOUNT", "AXIS", "3370", 0)
    L.set_balance("hdfc9591", "HDFC 9591", "INR", "BANK_ACCOUNT", "HDFC", "9591", 0)
    L.set_manual_splitwise_total("INR", 0)


def _assert_provisional_transfer_neutral():
    with connection() as conn:
        bal = {r["id"]: r["balance_minor"] for r in conn.execute(
            "SELECT id,balance_minor FROM accounts WHERE id IN ('axis3370','hdfc9591')")}
    assert bal == {"axis3370": -300, "hdfc9591": 300}
    assert len(L.list_transfers()) == 1
    assert L.get_manual_splitwise_total("INR") == 0


def test_provisional_debit_then_credit_keeps_splitwise_neutral():
    _provisional_setup()
    _sync_like_endpoint(_provisional_debit(), rebuild=False)
    _sync_like_endpoint(_credit_sms(), rebuild=False)
    _assert_provisional_transfer_neutral()


def test_credit_then_provisional_debit_keeps_splitwise_neutral():
    _provisional_setup()
    _sync_like_endpoint(_credit_sms(), rebuild=False)
    _sync_like_endpoint(_provisional_debit(), rebuild=False)
    _assert_provisional_transfer_neutral()


def test_provisional_debit_splitwise_stays_neutral_after_rebuild():
    _provisional_setup()
    _sync_like_endpoint(_provisional_debit(), rebuild=True)
    _sync_like_endpoint(_credit_sms(), rebuild=True)
    _assert_provisional_transfer_neutral()


def test_provisional_debit_later_enriched_does_not_reintroduce_splitwise_amount():
    _provisional_setup()
    _sync_like_endpoint(_provisional_debit(), rebuild=False)
    _sync_like_endpoint(_credit_sms(), rebuild=False)
    enriched = Tx(id="notification:debit", amountMinor=300, type="DEBIT", paymentMethod="UPI", bank="AXIS",
                  accountLast4="3370", reference=UPI_REF, timestamp=T0, category="TRANSFER")
    _sync_like_endpoint(enriched, rebuild=False)
    _assert_provisional_transfer_neutral()
