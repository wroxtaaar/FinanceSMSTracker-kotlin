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
