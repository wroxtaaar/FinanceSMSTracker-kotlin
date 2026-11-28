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
