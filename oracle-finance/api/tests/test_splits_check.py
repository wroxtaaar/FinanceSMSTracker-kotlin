"""Ledger <-> Splits app bridge (synthetic data only; no network)."""
from app import db
from app import ledger as L
from app import splits_check as S
from tests.test_self_transfer import sms_debit

SINCE = S.track_from_ms("2026-11-25")
NOON = SINCE + 12 * 3_600_000
ME, DAD = "u-me", "u-dad"


def tx(id, ts, amount, merchant=None):
    return {"id": id, "timestamp": ts, "amount_minor": amount, "merchant_or_payee": merchant}


def test_one_row_becomes_one_expense_owed_in_full_by_the_other_person():
    rec = S.build_expense([tx("a", NOON, 28_500, "AABDA")], "20 ka adrak\n20 ke tamatar\n55 ki pyaz",
                          {"id": "g1", "members": [ME, DAD]}, ME, [], 111)
    assert rec["amount"] == 28_500 and rec["payer"] == ME and rec["createdBy"] == ME
    assert rec["splits"] == {DAD: 28_500}
    assert rec["description"] == "20 ka adrak, 20 ke tamatar, 55 ki pyaz"
    assert rec["notes"] == "20 ka adrak\n20 ke tamatar\n55 ki pyaz"
    assert rec["ledgerId"] == "a" and rec["ledgerIds"] == ["a"] and rec["groupId"] == "g1"
    assert rec["date"] == "2026-11-25"


def test_several_rows_are_one_expense_with_their_total_and_a_stable_id():
    rows = [tx("b", NOON + 5, 5_000, "STAR"), tx("a", NOON, 3_000, "AABDA")]
    one = S.build_expense(rows, "", None, ME, [{"id": ME, "isUser": True}, {"id": DAD, "isUser": True}], 1)
    two = S.build_expense(list(reversed(rows)), "", None, ME, [{"id": ME, "isUser": True}, {"id": DAD, "isUser": True}], 2)
    assert one["amount"] == 8_000 and one["ledgerIds"] == ["a", "b"]
    assert one["description"] == "AABDA, STAR"
    assert one["id"] == two["id"]  # re-adding the same rows updates the same expense


def test_a_long_description_is_shortened_but_kept_in_the_notes():
    text = "\n".join(f"{i}0 ki cheez {i}" for i in range(1, 40))
    rec = S.build_expense([tx("a", NOON, 100)], text, {"id": "g", "members": [ME, DAD]}, ME, [], 1)
    assert len(rec["description"]) <= S.DESCRIPTION_LIMIT and rec["description"].endswith("…")
    assert rec["notes"] == text


def test_split_errors_are_clear():
    for group, payer in (({"id": "g", "members": [ME]}, ME), ({"id": "g", "members": [DAD]}, ME)):
        try:
            S.build_expense([tx("a", NOON, 100)], "x", group, payer, [], 1)
        except S.SplitsError as e:
            assert str(e)
        else:
            raise AssertionError("expected SplitsError")


def test_three_people_share_equally_without_losing_a_paisa():
    rec = S.build_expense([tx("a", NOON, 100)], "x", {"id": "g", "members": [ME, "b", "c"]}, ME, [], 1)
    assert sorted(rec["splits"].values()) == [50, 50] and sum(rec["splits"].values()) == 100


def test_reconcile_lists_only_unlinked_unhandled_rows_since_the_start_date():
    rows = [
        {"id": "old", "timestamp": SINCE - 1000}, {"id": "linked", "timestamp": NOON},
        {"id": "skipped", "timestamp": NOON + 1}, {"id": "gone", "timestamp": NOON + 2},
    ]
    linked, handled, missing = S.reconcile(rows, {"linked"}, {"skipped"}, SINCE)
    assert (linked, handled, [m["id"] for m in missing]) == (1, 1, ["gone"])


def _configure(monkeypatch):
    for k, v in {"SPLITS_SUPABASE_URL": "https://x.example", "SPLITS_SUPABASE_KEY": "k",
                 "SPLITS_EMAIL": "bot@example.com", "SPLITS_PASSWORD": "pw"}.items():
        monkeypatch.setenv(k, v)


def test_add_then_check_end_to_end_with_a_fake_splits(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "splits.db"))
    db.init_db()
    _configure(monkeypatch)
    L.set_balance("axis", "Axis", "INR", "BANK_ACCOUNT", "AXIS", "1111", 1_000_000)
    L.set_manual_splitwise_total("INR", 0)
    for i, amount in enumerate((25_000, 31_000, 4_000)):
        L.sync_transaction(sms_debit(ts=NOON + i * 3_600_000, amount=amount, id=f"sms:{i}"))
    L.rebuild_manual_splitwise_total()

    store = {}
    monkeypatch.setattr(S, "_login", lambda s: {})
    monkeypatch.setattr(S, "_records", lambda s, h, coll: (
        [{"id": "g1", "name": "Ghar", "members": [ME, DAD]}] if coll == "groups"
        else [{"id": ME, "name": "Me", "isUser": True}, {"id": DAD, "name": "Dad", "isUser": True}] if coll == "people"
        else [{**r, "id": r["id"]} for r in store.values() if not r.get("deleted")]))
    monkeypatch.setattr(S, "_upsert", lambda s, h, rec, deleted=False: store.__setitem__(rec["id"], {**rec, "deleted": deleted}))

    assert [m["id"] for m in S.check()["missing"]] == ["sms:2", "sms:1", "sms:0"]
    S.add_expense(["sms:0", "sms:1"], "chai\nsamosa", "g1", ME)
    out = S.check()
    assert out["matched"] == 2 and [m["id"] for m in out["missing"]] == ["sms:2"]
    link = S.link_info("sms:1")
    assert link["linked"] and link["together"] == 2 and link["togetherMinor"] == 56_000
    (expense,) = store.values()
    assert expense["amount"] == 56_000 and expense["splits"] == {DAD: 56_000}

    S.mark_handled("sms:2")
    assert S.check()["missing"] == [] and S.check()["handled"] == 1

    # deleting the expense inside Splits makes its rows show up as missing again
    store[expense["id"]]["deleted"] = True
    assert [m["id"] for m in S.check()["missing"]] == ["sms:1", "sms:0"]

    # undo from the app deletes the Splits expense and unlinks every row
    S.add_expense(["sms:0", "sms:1"], "chai", "g1", ME)
    assert S.remove_expense("sms:0")["status"] == "removed"
    assert S.link_info("sms:1")["linked"] is False


def test_credits_and_unknown_rows_cannot_be_added(tmp_path, monkeypatch):
    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "splits2.db"))
    db.init_db()
    _configure(monkeypatch)
    try:
        S.add_expense(["nope"], "x", None, ME)
    except S.SplitsError as e:
        assert "not found" in str(e)
    else:
        raise AssertionError("expected SplitsError")


def test_check_is_quiet_when_not_configured_and_survives_a_splits_outage(monkeypatch):
    for k in ("SPLITS_SUPABASE_URL", "SPLITS_SUPABASE_KEY", "SPLITS_EMAIL", "SPLITS_PASSWORD"):
        monkeypatch.delenv(k, raising=False)
    assert S.check()["configured"] is False
    _configure(monkeypatch)
    monkeypatch.setattr(S, "fetch_expenses", lambda s=None: (_ for _ in ()).throw(S.SplitsError("Splits HTTP 503")))
    out = S.check()
    assert out["configured"] and "503" in out["error"] and out["missing"] == []
