"""Read-only ledger audit (synthetic data only)."""
from app import db
from app import ledger as L
from tests.test_self_transfer import sms_debit


def test_audit_explains_balances_and_changes_nothing(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "audit.db"))
    db.init_db()
    L.set_balance("axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "1111", 1_000_000)
    L.set_manual_splitwise_total("INR", 0)
    t = sms_debit(ref=None)
    L.sync_transaction(t)
    L.rebuild_manual_splitwise_total()
    before = L.balances()
    out = L.ledger_audit(5)
    assert L.balances() == before
    acc = [a for a in out["accounts"] if a["id"] == "axis"][0]
    assert acc["driftMinor"] == 0
    assert acc["balanceMinor"] == 1_000_000 - 250_000
    assert out["recentTransactions"][0]["bank_delta"] == -250_000
    assert out["splitwise"]["ledgerSumMinor"] == 250_000
    assert out["splitwise"]["shownMinor"] == 250_000


def test_old_statement_rows_do_not_change_a_manually_set_balance(tmp_path, monkeypatch):
    import time
    from app.db import connection
    from app.ledger import migrations
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "stmt.db"))
    db.init_db()
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 500_000)
    L.set_manual_splitwise_total("INR", 0)
    old = int(time.time() * 1000) - 5 * 86_400_000
    t = sms_debit(bank="HDFC", last4="2222", ts=old, id="statement:abc", amount=100_000)
    L.sync_transaction(t)
    with connection() as conn:
        L.apply_transaction_to_account(conn, t, int(time.time() * 1000))
    L.rebuild_manual_splitwise_total()
    assert [a for a in L.balances() if a["id"] == "hdfc"][0]["balance_minor"] == 500_000
    assert L.get_manual_splitwise_total("INR") == 0


def test_migration_reverses_statement_rows_replayed_after_manual_edit(tmp_path, monkeypatch):
    import time
    from app.db import connection
    from app.ledger import migrations
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "mig.db"))
    db.init_db()
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 500_000)
    L.set_manual_splitwise_total("INR", 0)
    now = int(time.time() * 1000)
    t = sms_debit(bank="HDFC", last4="2222", ts=now - 86_400_000, id="statement:old", amount=100_000)
    L.sync_transaction(t)
    with connection() as conn:  # simulate the old behaviour: replayed with a delta
        acc = conn.execute("SELECT id FROM accounts WHERE last4='2222'").fetchone()["id"]
        conn.execute("INSERT INTO balance_adjustments VALUES('statement:old',?,-100000,?)", (acc, now))
        conn.execute("UPDATE accounts SET balance_minor=400000")
    assert migrations.migrate_statement_reconciled_reversal() == 1
    assert [a for a in L.balances() if a["id"] == "hdfc"][0]["balance_minor"] == 500_000
    assert migrations.migrate_statement_reconciled_reversal() == 0


def test_editing_the_bank_balance_again_does_not_change_splitwise(tmp_path, monkeypatch):
    import time
    from app.db import connection
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "sw.db"))
    db.init_db()
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 500_000)
    L.set_manual_splitwise_total("INR", 0)
    t = sms_debit(bank="HDFC", last4="2222", ts=int(time.time() * 1000) - 86_400_000,
                  id="statement:old", amount=100_000)
    L.sync_transaction(t)  # stored after the edit, dated before it: no balance effect
    L.rebuild_manual_splitwise_total()
    assert L.get_manual_splitwise_total("INR") == 0
    time.sleep(0.01)
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 450_000)  # second manual edit
    L.rebuild_manual_splitwise_total()
    assert L.get_manual_splitwise_total("INR") == 0


def test_splitwise_rule_migration_keeps_the_shown_value(tmp_path, monkeypatch):
    import time
    from app.ledger import migrations
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "swm.db"))
    db.init_db()
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 500_000)
    t = sms_debit(bank="HDFC", last4="2222", ts=int(time.time() * 1000) - 86_400_000,
                  id="statement:old", amount=100_000)
    L.sync_transaction(t)
    time.sleep(0.01)
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 450_000)
    # State left by the old bug: the row counted once the account was edited again,
    # and the user typed the Splitwise total they wanted on top of it (baseline
    # recorded under the legacy rule).
    from app.db import connection
    from app.ledger.splitwise import _ledger_splitwise_total
    L.set_manual_splitwise_total("INR", 777_000)
    with connection() as conn:
        legacy = _ledger_splitwise_total(conn, "INR", legacy_statement_rule=True)
        assert legacy != _ledger_splitwise_total(conn, "INR")
        conn.execute("UPDATE manual_splitwise_total SET baseline_ledger_minor=?", (legacy,))
    L.rebuild_manual_splitwise_total()
    assert L.get_manual_splitwise_total("INR") != 777_000  # the new rule alone would move it
    assert migrations.migrate_splitwise_statement_rule() is True
    L.rebuild_manual_splitwise_total()
    assert L.get_manual_splitwise_total("INR") == 777_000
    assert migrations.migrate_splitwise_statement_rule() is False


def test_manually_marked_card_payment_pairs_with_the_bank_debit(tmp_path, monkeypatch):
    """The phone's 'this was a card bill payment' button creates this card credit."""
    from types import SimpleNamespace
    from app.db import connection
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "mark.db"))
    db.init_db()
    L.set_balance("hdfc", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "2222", 1_000_000)
    L.set_balance("c8001", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "8001", 300_000, 300_000)
    L.set_manual_splitwise_total("INR", 0)
    ts = 1_800_000_000_000
    debit = sms_debit(bank="HDFC", last4="2222", ts=ts, id="sms:pay", amount=300_000, ref="RRN123456")
    credit = SimpleNamespace(
        id="manual-card-payment:7", amountMinor=300_000, currency="INR", type="CREDIT",
        paymentMethod="UPI", accountType="CREDIT_CARD", bank="ICICI", merchantOrPayee="Credit Card Bill Payment",
        accountLast4="8001", reference="RRN123456", timestamp=ts, category="TRANSFER", confidence=1.0)
    for t in (debit, credit):
        L.sync_transaction(t)
    L.match_internal_transfers()
    L.rebuild_manual_splitwise_total()
    with connection() as conn:
        card = conn.execute("SELECT balance_minor,bill_balance_minor,bill_locked_at FROM accounts WHERE id='c8001'").fetchone()
    assert card["bill_balance_minor"] == 0 and card["balance_minor"] == 0
    assert card["bill_locked_at"] > 0
    assert L.get_manual_splitwise_total("INR") == 0
