from app.expense_check import compute_balances

W, P = "w", "p"
PEOPLE = [{"id": W, "name": "Wasiq", "isUser": True}, {"id": P, "name": "Papa", "isUser": True}, {"id": "a", "name": "Aaqib", "isUser": False}]
GROUPS = [{"id": "g1", "name": "Home"}, {"id": "g2", "name": "Empty"}]


def test_balances_per_group_overall_and_for_each_user():
    expenses = [
        {"groupId": "g1", "payer": W, "amount": 10000, "splits": {W: 5000, P: 5000}},
        {"groupId": "g1", "payer": P, "amount": 2000, "splits": {W: 1000, P: 1000}},
        {"groupId": None, "payer": W, "amount": 300, "splits": {P: 300}},
    ]
    settlements = [{"groupId": "g1", "from": P, "to": W, "amount": 1000}]
    out = {p["id"]: p for p in compute_balances(PEOPLE, GROUPS, expenses, settlements)["people"]}
    assert [g["name"] for g in out[W]["groups"]] == ["Home", "Non-group expenses"]  # empty groups are left out
    assert out[W]["groups"][0]["netMinor"] == 5000 - 1000 - 1000  # lent 5000, borrowed 1000, got paid back 1000
    assert out[W]["groups"][1]["netMinor"] == 300
    assert out[W]["totalMinor"] == 3300
    assert out[P]["totalMinor"] == -3300  # the two accounts mirror each other
    assert "Aaqib" not in [p["name"] for p in out.values()]  # friends without a login are not accounts


def test_local_copy_is_served_instantly_and_updated_by_a_new_expense(tmp_path, monkeypatch):
    from app import db
    from app import expense_check as S

    monkeypatch.setattr(db, "DB_PATH", str(tmp_path / "bal.db"))
    db.init_db()
    # Nothing in the Expense app is read when a fresh copy exists.
    fetched = []
    monkeypatch.setattr(S, "balances", lambda s=None: fetched.append(1) or compute_balances(PEOPLE, GROUPS, [], []))
    first = S.cached_balances()
    assert fetched == [1] and first["people"]
    again = S.cached_balances()
    assert fetched == [1]  # served from the local copy, no second read

    record = {"id": "e1", "groupId": "g1", "payer": W, "amount": 9000, "splits": {P: 9000}}
    S.apply_expense_to_cache(record, "Home", 1)
    out = {p["id"]: p for p in S.cached_balances()["people"]}
    assert out[W]["totalMinor"] == 9000 and out[P]["totalMinor"] == -9000
    assert out[W]["groups"][0]["name"] == "Home" and out[W]["groups"][0]["netMinor"] == 9000
