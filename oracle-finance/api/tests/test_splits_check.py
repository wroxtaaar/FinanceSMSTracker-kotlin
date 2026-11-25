"""Ledger vs Splits app comparison (synthetic data only)."""
from datetime import datetime
from app import db
from app import ledger as L
from app import splits_check as S
from tests.test_self_transfer import sms_debit

DAY = 86_400_000
SINCE = S.track_from_ms("2026-11-25")
NOON = SINCE + 12 * 3_600_000


def row(id, ts, amount, desc=None):
    return {"id": id, "timestamp": ts, "amountMinor": amount, "deltaMinor": amount, "description": desc}


def exp(id, date, amount, desc="", **extra):
    return {"id": id, "date": date, "amount": amount, "description": desc, **extra}


def test_matching_is_one_to_one_by_amount_and_date():
    rows = [row("a", NOON, 5000), row("b", NOON + 1000, 5000), row("c", NOON, 7000)]
    expenses = [exp("e1", "2026-11-25", 5000, "snacks"), exp("e2", "2026-11-26", 7000)]
    matched, missing, extra = S.reconcile(rows, expenses, SINCE)
    assert matched == 2
    assert [m["id"] for m in missing] == ["b"] or [m["id"] for m in missing] == ["a"]  # one 5000 is left over
    assert extra == []


def test_the_expense_with_a_shared_word_wins_between_equal_amounts():
    rows = [row("a", NOON, 5000, "ROYAL CHICK"), row("b", NOON, 5000, "NAUSHAD")]
    expenses = [exp("e1", "2026-11-25", 5000, "naushad bhai")]
    _, missing, _ = S.reconcile(rows, expenses, SINCE)
    assert [m["id"] for m in missing] == ["a"]


def test_dates_more_than_two_days_apart_do_not_match_and_old_rows_are_ignored():
    rows = [row("a", NOON, 5000), row("old", SINCE - 3 * DAY, 9000)]
    expenses = [exp("e1", "2026-11-29", 5000)]
    matched, missing, extra = S.reconcile(rows, expenses, SINCE)
    assert matched == 0
    assert [m["id"] for m in missing] == ["a"]
    assert [e["id"] for e in extra] == ["e1"]


def test_a_ledger_id_link_matches_directly_even_when_the_amount_differs():
    rows = [row("a", NOON, 5000)]
    expenses = [exp("e1", "2026-12-20", 4000, ledgerId="a")]
    matched, missing, _ = S.reconcile(rows, expenses, SINCE)
    assert (matched, missing) == (1, [])


def test_check_reports_missing_rows_from_the_real_ledger(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "splits.db"))
    db.init_db()
    for k, v in {"SPLITS_SUPABASE_URL": "https://x.example", "SPLITS_SUPABASE_KEY": "k",
                 "SPLITS_EMAIL": "bot@example.com", "SPLITS_PASSWORD": "pw"}.items():
        monkeypatch.setenv(k, v)
    L.set_balance("axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "1111", 1_000_000)
    L.set_manual_splitwise_total("INR", 0)
    L.sync_transaction(sms_debit(ts=NOON, amount=25_000, id="sms:one"))
    L.sync_transaction(sms_debit(ts=NOON + 3_600_000, amount=31_000, id="sms:two"))
    L.rebuild_manual_splitwise_total()
    date = datetime.fromtimestamp(NOON / 1000, S.IST).strftime("%Y-%m-%d")
    result = S.check("INR", expenses=[exp("e1", date, 25_000, "tea")])
    assert result["error"] is None and result["matched"] == 1
    assert [m["id"] for m in result["missing"]] == ["sms:two"]
    assert result["missing"][0]["amountMinor"] == 31_000


def test_check_is_quiet_when_not_configured_and_survives_a_splits_outage(monkeypatch):
    for k in ("SPLITS_SUPABASE_URL", "SPLITS_SUPABASE_KEY", "SPLITS_EMAIL", "SPLITS_PASSWORD"):
        monkeypatch.delenv(k, raising=False)
    assert S.check()["configured"] is False
    for k in ("SPLITS_SUPABASE_URL", "SPLITS_SUPABASE_KEY", "SPLITS_EMAIL", "SPLITS_PASSWORD"):
        monkeypatch.setenv(k, "x")
    monkeypatch.setattr(S, "fetch_expenses", lambda s=None: (_ for _ in ()).throw(RuntimeError("Splits HTTP 503")))
    out = S.check()
    assert out["configured"] and "503" in out["error"] and out["missing"] == []
