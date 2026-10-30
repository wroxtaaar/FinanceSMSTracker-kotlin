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


# --- Splitwise manual reset is a baseline, not overwritten by rebuild ---------

def _splitwise():
    from app.ledger import get_manual_splitwise_total
    return get_manual_splitwise_total("INR")


def test_manual_splitwise_reset_survives_rebuild_and_tracks_new_transactions():
    from app.ledger import rebuild_manual_splitwise_total, set_manual_splitwise_total
    ingest_sms(sms_debit(bank="AXIS", last4="3370", amount=50000, id="sms:hist1"))   # history before the reset
    set_manual_splitwise_total("INR", 0)
    rebuild_manual_splitwise_total()
    assert _splitwise() == 0
    ingest_sms(sms_debit(bank="AXIS", last4="3370", amount=500, id="sms:after1", ts=T0 + 3_600_000))
    rebuild_manual_splitwise_total()
    assert _splitwise() == 500
    rebuild_manual_splitwise_total()                         # idempotent
    assert _splitwise() == 500


def test_manual_splitwise_reset_then_self_transfer_stays_neutral():
    from app.ledger import rebuild_manual_splitwise_total, set_manual_splitwise_total
    ingest_sms(sms_debit(bank="HDFC", last4="9591", amount=12345, id="sms:hist2"))
    set_manual_splitwise_total("INR", 0)
    ingest_sms(sms_debit(ref="401032212433", bank="AXIS", last4="3370", amount=300, id="sms:xd", ts=T0 + 7_200_000))
    ingest_gmail(gmail_credit(ref="401032212433", bank="HDFC", last4="9591", amount=300, ts=T0 + 7_260_000))
    rebuild_manual_splitwise_total()
    assert _splitwise() == 0


def test_legacy_splitwise_total_without_baseline_is_kept():
    from app.db import connection
    from app.ledger import rebuild_manual_splitwise_total
    ingest_sms(sms_debit(bank="AXIS", last4="3370", amount=700, id="sms:hist3"))
    with connection() as conn:
        conn.execute("DELETE FROM manual_splitwise_total")
        conn.execute("INSERT INTO manual_splitwise_total(currency,amount_minor,updated_at) VALUES('INR',4200,1)")
    rebuild_manual_splitwise_total()
    assert _splitwise() == 4200
    ingest_sms(sms_debit(bank="AXIS", last4="3370", amount=100, id="sms:after3", ts=T0 + 3_600_000))
    rebuild_manual_splitwise_total()
    assert _splitwise() == 4300


# --- Duplicate rows seen on the device: notification + SMS for one payment ----

def _card():
    L.set_balance("card9206", "Axis Card", "INR", "CREDIT_CARD", "AXIS", "9206", 0)


def _card_sms(ts, id="sms:card", amount=182_000, merchant="RAJ"):
    return Tx(id=id, amountMinor=amount, type="DEBIT", bank="AXIS", accountLast4="9206", accountType="CREDIT_CARD",
              category="GROCERIES", merchantOrPayee=merchant, timestamp=ts)


def _card_notification(ts, id="notification:card", amount=182_000):
    return Tx(id=id, amountMinor=amount, type="DEBIT", bank=None, accountLast4="9206", accountType="CREDIT_CARD",
              paymentMethod="UNKNOWN", category="GROCERIES", merchantOrPayee=None, timestamp=ts)


def _card_outstanding():
    with connection() as conn:
        # Outstanding grows by the amount spent.
        return conn.execute(
            "SELECT COALESCE(SUM(delta_minor),0) FROM balance_adjustments WHERE account_id='card9206'"
        ).fetchone()[0]


def test_card_spend_sms_then_late_notification_is_one_transaction():
    _card()
    ingest_sms(_card_sms(T0))
    ingest_sms(_card_notification(T0 + 7 * 60_000))          # 7 minutes later, no ref, no merchant
    assert len(active_ids()) == 1
    assert _card_outstanding() == 182_000
    assert _splitwise() == 182_000


def test_card_spend_notification_then_late_sms_is_one_transaction():
    _card()
    ingest_sms(_card_notification(T0))
    ingest_sms(_card_sms(T0 + 7 * 60_000))
    assert len(active_ids()) == 1
    assert _card_outstanding() == 182_000
    assert _splitwise() == 182_000


def test_two_real_same_amount_card_spends_are_not_merged():
    _card()
    ingest_sms(_card_sms(T0, id="sms:a"))
    ingest_sms(_card_sms(T0 + 5 * 60_000, id="sms:b"))
    assert sorted(active_ids()) == ["sms:a", "sms:b"]
    assert _card_outstanding() == 364_000


def test_ambiguous_notification_is_left_alone_rather_than_guessed():
    _card()
    ingest_sms(_card_sms(T0, id="sms:a"))
    ingest_sms(_card_sms(T0 + 2 * 60_000, id="sms:b"))
    ingest_sms(_card_notification(T0 + 3 * 60_000))
    assert {"sms:a", "sms:b"} <= set(active_ids())            # never drops a real SMS row


def test_notification_reference_with_sentence_residue_matches_sms_reference():
    assert L.normalize_reference("665513407363.FOR") == "665513407363"
    assert L.normalize_reference("270905172601.FOR") == "270905172601"
    assert L.normalize_reference("AXIS.BANK") == "AXIS.BANK"  # not a numeric reference
    L.set_balance("hdfc9591", "HDFC 9591", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100_000)
    ingest_sms(Tx(id="notification:upi", amountMinor=30_000, type="DEBIT", bank="HDFC", accountLast4="9591",
                  reference="665513407363.FOR", timestamp=T0, merchantOrPayee="SOMEONE"))
    ingest_sms(Tx(id="sms:upi", amountMinor=30_000, type="DEBIT", bank="HDFC", accountLast4="9591",
                  reference="665513407363", timestamp=T0 + 60_000, merchantOrPayee="SOMEONE"))
    assert len(active_ids()) == 1
    with connection() as conn:
        moved = conn.execute(
            "SELECT COALESCE(SUM(delta_minor),0) FROM balance_adjustments WHERE account_id='hdfc9591'"
        ).fetchone()[0]
    assert moved == -30_000


# --- HDFC -> Axis INR 5: Axis sends no SMS; e-mail (ref) + notification (no ref, no last-4) both credit Axis ---

def _axis_credit_pair(gmail_first):
    ref = "715732131571"
    hdfc_sms = Tx(id="sms:hdfc5", amountMinor=500, type="DEBIT", bank="HDFC", accountLast4="2222",
                  reference=ref, timestamp=T0, merchantOrPayee="ABDUL WASIQ")
    email = Tx(id="gmail:axis5", amountMinor=500, type="CREDIT", bank="AXIS", accountLast4=None,
               reference=ref, timestamp=T0 + 120_000, paymentMethod="UNKNOWN", merchantOrPayee=None)
    notif = Tx(id="notification:axis5", amountMinor=500, type="CREDIT", bank="AXIS", accountLast4=None,
               reference=None, timestamp=T0 + 60_000, paymentMethod="UNKNOWN", merchantOrPayee=None)
    ingest_sms(hdfc_sms)
    first, second = (email, notif) if gmail_first else (notif, email)
    for tx in (first, second):
        (ingest_gmail if tx.id.startswith("gmail:") else ingest_sms)(tx)


def test_axis_credit_notification_then_email_is_counted_once():
    _assert_axis_credit_once(gmail_first=False)


def test_axis_credit_email_then_notification_is_counted_once():
    _assert_axis_credit_once(gmail_first=True)


def _assert_axis_credit_once(gmail_first):
    _axis_credit_pair(gmail_first)
    credits = [i for i in active_ids() if i != "sms:hdfc5"]
    assert len(credits) == 1
    b = balances()
    assert b["axis"] == 1_000_000 + 500
    assert b["hdfc"] == 500_000 - 500
    assert _splitwise() == 0


def test_two_distinct_axis_credits_with_different_references_are_kept():
    for n, ref in enumerate(("111111111111", "222222222222")):
        ingest_gmail(Tx(id=f"gmail:c{n}", amountMinor=500, type="CREDIT", bank="AXIS", accountLast4=None,
                        reference=ref, timestamp=T0 + n * 60_000, paymentMethod="UNKNOWN"))
    assert len(active_ids()) == 2


# --- A drifted stored cache must never be shown, and is repaired after ingestion ---

def _stored_cache():
    with connection() as conn:
        return conn.execute("SELECT amount_minor FROM manual_splitwise_total WHERE currency='INR'").fetchone()[0]


def _drift_total(delta):
    with connection() as conn:
        conn.execute("UPDATE manual_splitwise_total SET amount_minor=amount_minor+? WHERE currency='INR'", (delta,))


def test_drifted_cache_is_never_shown():
    ingest_sms(sms_debit(bank="AXIS", last4="1111", amount=700, id="sms:g0"))
    _drift_total(-300)                       # what an incremental e-mail merge left behind
    assert _stored_cache() == 400
    assert _splitwise() == 700               # the displayed value ignores the cache


def test_gmail_ingestion_rebuilds_a_drifted_splitwise_cache():
    from app import gmail_sync
    ingest_sms(sms_debit(bank="AXIS", last4="1111", amount=700, id="sms:g1"))
    _drift_total(-300)
    assert _stored_cache() == 400
    original = gmail_sync._ingest_messages
    gmail_sync._ingest_messages = lambda service, query="": {"ok": True}
    try:
        assert gmail_sync.ingest_messages(object()) == {"ok": True}
    finally:
        gmail_sync._ingest_messages = original
    assert _stored_cache() == 700 and _splitwise() == 700


def test_gmail_ingestion_rebuilds_even_when_it_fails():
    from app import gmail_sync
    ingest_sms(sms_debit(bank="AXIS", last4="1111", amount=700, id="sms:g2"))
    _drift_total(-300)
    original = gmail_sync._ingest_messages

    def boom(service, query=""):
        raise RuntimeError("imap failed")

    gmail_sync._ingest_messages = boom
    try:
        try:
            gmail_sync.ingest_messages(object())
            assert False, "expected the error to propagate"
        except RuntimeError:
            pass
    finally:
        gmail_sync._ingest_messages = original
    assert _stored_cache() == 700


def test_reconcile_all_rebuilds_splitwise_cache():
    from app import reconcile
    ingest_sms(sms_debit(bank="AXIS", last4="1111", amount=700, id="sms:g3"))
    _drift_total(-300)
    reconcile.reconcile_all()
    assert _stored_cache() == 700


def test_configured_credit_roster_is_complete_and_not_duplicated():
    db.init_db(); db.init_db()
    with connection() as conn:
        rows = conn.execute("SELECT bank,last4 FROM accounts WHERE account_type='CREDIT_CARD'").fetchall()
    got = sorted((r["bank"], r["last4"]) for r in rows)
    expected = [("ICICI", x) for x in ("1012", "8001", "0005", "6002", "9001")] + [
        ("AXIS", "1175"), ("AXIS", "9206"), ("HDFC", "5304"), ("SBI", "0065"),
        ("SBI", "7345"), ("INDUSIND", "3526"), ("HSBC", "5145")]
    assert got == sorted(expected)


def test_card_spend_raises_outstanding_and_splitwise_bank_debit_raises_splitwise():
    L.set_balance("c1", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "8001", 0)
    L.set_manual_splitwise_total("INR", 0)
    card = Tx(id="card:spend", amountMinor=10_000, type="DEBIT", bank="ICICI", accountLast4="8001",
              accountType="CREDIT_CARD", paymentMethod="CARD", reference="CARDREF0001", timestamp=T0 + 10)
    ingest_sms(card)
    with connection() as conn:
        bal = conn.execute("SELECT balance_minor FROM accounts WHERE id='c1'").fetchone()["balance_minor"]
    assert bal == 10_000
    assert L.get_manual_splitwise_total("INR") == 10_000
    bank = sms_debit(ref="BANKREF0002", ts=T0 + 20, amount=4_000, id="sms:bank-spend")
    bank.paymentMethod = "UPI"; bank.category = "GROCERIES"
    ingest_sms(bank)
    assert L.get_manual_splitwise_total("INR") == 14_000


def test_card_sign_migration_fixes_only_wrong_adjustments():
    L.set_balance("mig-card", "Card", "INR", "CREDIT_CARD", "AXIS", "7777", 10_000)
    with connection() as conn:
        for tid, typ, delta in (("m1", "DEBIT", -500), ("m2", "DEBIT", 300), ("m3", "CREDIT", 200)):
            conn.execute(
                """INSERT INTO transactions(id,amount_minor,currency,type,payment_method,account_type,
                   bank,account_last4,timestamp,category,confidence,created_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (tid, abs(delta), "INR", typ, "CARD", "CREDIT_CARD", "AXIS", "7777", T0, "FOOD", 0.9, T0))
            conn.execute("INSERT INTO balance_adjustments(transaction_id,account_id,delta_minor,applied_at) VALUES(?,?,?,?)",
                         (tid, "mig-card", delta, T0))
        conn.execute("UPDATE accounts SET balance_minor=10000-500+300+200 WHERE id='mig-card'")
        conn.execute("DELETE FROM applied_migrations WHERE name='card_adjustment_sign'")
    db.init_db()
    with connection() as conn:
        deltas = {r["transaction_id"]: r["delta_minor"] for r in conn.execute(
            "SELECT transaction_id,delta_minor FROM balance_adjustments WHERE account_id='mig-card'")}
        balance = conn.execute("SELECT balance_minor FROM accounts WHERE id='mig-card'").fetchone()[0]
    assert deltas == {"m1": 500, "m2": 300, "m3": -200}
    assert balance == 10_000 + 500 + 300 - 200
    db.init_db()
    with connection() as conn:
        assert conn.execute("SELECT balance_minor FROM accounts WHERE id='mig-card'").fetchone()[0] == 10_600


def test_other_optout_migration_keeps_displayed_splitwise_value():
    from app.ledger import sync_transaction, get_manual_splitwise_total
    # Legacy rows carry OTHER as a default and used to count toward Splitwise.
    for tid, amount, cat in (("o1", 5_000, "OTHER"), ("o2", 7_000, "OTHER"), ("g1", 1_000, "GROCERIES")):
        sync_transaction(Tx(id=tid, amountMinor=amount, type="DEBIT", bank="AXIS", accountLast4="1111",
                            timestamp=T0 + 10**9, category=cat, accountType="BANK_ACCOUNT"))
    with connection() as conn:
        conn.execute("DELETE FROM applied_migrations WHERE name='splitwise_other_optout'")
        # Simulate the pre-change baseline: ledger counted every row.
        old = L._ledger_splitwise_total(conn, "INR", honor_other=False)
        conn.execute("UPDATE manual_splitwise_total SET baseline_amount_minor=100000, baseline_ledger_minor=? WHERE currency='INR'", (old,))
    before = 100_000
    assert L.migrate_splitwise_other_optout() is True
    assert get_manual_splitwise_total("INR") == before
    # A later explicit opt-out then moves it as expected.
    sync_transaction(Tx(id="g1", amountMinor=1_000, type="DEBIT", bank="AXIS", accountLast4="1111",
                        timestamp=T0 + 10**9, category="OTHER", accountType="BANK_ACCOUNT"))
    assert get_manual_splitwise_total("INR") == before - 1_000
    assert L.migrate_splitwise_other_optout() is False


def test_axis_card_spend_reaches_the_airtel_axis_card_and_old_seed_is_corrected():
    # Production had this card seeded under the wrong bank (AIRTEL).
    with connection() as conn:
        conn.execute("UPDATE accounts SET bank='AIRTEL' WHERE id='airtel-card-9206'")
    db.init_db()
    with connection() as conn:
        assert conn.execute("SELECT bank FROM accounts WHERE id='airtel-card-9206'").fetchone()[0] == "AXIS"
    L.sync_transaction(Tx(id="sms:fareed", amountMinor=12_000, type="DEBIT", bank="AXIS", accountLast4="9206",
                          paymentMethod="CARD", accountType="CREDIT_CARD", timestamp=T0 + 10**9,
                          merchantOrPayee="FAREED", category="GROCERIES"))
    with connection() as conn:
        assert conn.execute("SELECT balance_minor FROM accounts WHERE id='airtel-card-9206'").fetchone()[0] == 12_000


def test_gmail_debit_default_is_not_the_splitwise_opt_out():
    from app.gmail_sync import _gmail_default_category
    assert _gmail_default_category("DEBIT", "debited from a/c") == "GROCERIES"
    assert _gmail_default_category("CREDIT", "credited") == "TRANSFER"


def test_gmail_default_category_migration_only_touches_recent_gmail_debits():
    with connection() as conn:
        for tid, created, cat in (("gmail:old", 1_000, "OTHER"), ("gmail:new", 1_792_300_000_000, "OTHER"),
                                  ("sms:new", 1_792_300_000_000, "OTHER")):
            conn.execute(
                """INSERT INTO transactions(id,amount_minor,currency,type,payment_method,account_type,bank,
                   account_last4,timestamp,category,confidence,created_at,status)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')""",
                (tid, 100, "INR", "DEBIT", "UPI", "BANK_ACCOUNT", "AXIS", "1111", created, cat, 0.9, created))
        conn.execute("DELETE FROM applied_migrations WHERE name='gmail_default_category'")
    assert L.migrate_gmail_default_category() == 1
    with connection() as conn:
        cats = {r["id"]: r["category"] for r in conn.execute("SELECT id,category FROM transactions")}
    assert cats["gmail:new"] == "GROCERIES"
    assert cats["gmail:old"] == "OTHER"
    assert cats["sms:new"] == "OTHER"
    assert L.migrate_gmail_default_category() == 0


def _bank_cash(account_id="hdfc"):
    with connection() as conn:
        return conn.execute("SELECT balance_minor FROM accounts WHERE id=?", (account_id,)).fetchone()[0]


def test_salary_credit_raises_the_bank_but_stays_out_of_splitwise():
    before_cash = _bank_cash()
    before_splitwise = L.get_manual_splitwise_total("INR")

    ingest_sms(Tx(id="sms:salary", amountMinor=10_187_900, type="CREDIT", bank="HDFC", accountLast4="2222",
                  paymentMethod="UNKNOWN", merchantOrPayee="WBS SALARY ACCOUNT",
                  timestamp=T0 + 10**9, category="SALARY"))

    assert _bank_cash() == before_cash + 10_187_900
    assert L.get_manual_splitwise_total("INR") == before_splitwise


def test_other_credits_still_lower_splitwise_and_a_salary_labelled_debit_still_counts():
    ingest_sms(Tx(id="sms:repay", amountMinor=5_000, type="CREDIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9, category="TRANSFER"))
    assert L.get_manual_splitwise_total("INR") == -5_000

    ingest_sms(Tx(id="sms:odd", amountMinor=2_000, type="DEBIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9 + 1, category="SALARY"))
    assert L.get_manual_splitwise_total("INR") == -3_000


def test_changing_a_credit_to_salary_removes_its_splitwise_share():
    ingest_sms(Tx(id="sms:late-salary", amountMinor=8_000, type="CREDIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9, category="TRANSFER"))
    assert L.get_manual_splitwise_total("INR") == -8_000

    ingest_sms(Tx(id="sms:late-salary", amountMinor=8_000, type="CREDIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9, category="SALARY"))
    assert L.get_manual_splitwise_total("INR") == 0


def test_salary_optout_migration_keeps_the_displayed_splitwise_value():
    # A salary recorded before this rule lowered Splitwise like any credit.
    ingest_sms(Tx(id="sms:old-salary", amountMinor=50_000, type="CREDIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9, category="SALARY"))
    with connection() as conn:
        conn.execute("DELETE FROM applied_migrations WHERE name='splitwise_salary_optout'")
        old = L._ledger_splitwise_total(conn, "INR", honor_salary=False)  # pre-change ledger
        conn.execute(
            "UPDATE manual_splitwise_total SET baseline_amount_minor=100000, baseline_ledger_minor=? WHERE currency='INR'",
            (old,),
        )
    L.rebuild_manual_splitwise_total()

    assert L.migrate_splitwise_salary_optout() is True
    assert L.get_manual_splitwise_total("INR") == 100_000          # shown value unchanged
    ingest_sms(Tx(id="sms:new-salary", amountMinor=70_000, type="CREDIT", bank="HDFC", accountLast4="2222",
                  timestamp=T0 + 10**9 + 5, category="SALARY"))
    assert L.get_manual_splitwise_total("INR") == 100_000          # later salary stays out
    assert L.migrate_splitwise_salary_optout() is False
