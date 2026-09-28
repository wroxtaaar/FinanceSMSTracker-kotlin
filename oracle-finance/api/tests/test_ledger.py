import os
import tempfile
os.environ["DATABASE_PATH"] = os.path.join(tempfile.gettempdir(), "oracle-finance-ledger-test.db")

from app.db import init_db
from app.ledger import set_balance, add_receivable, true_available

init_db()

def test_true_available_formula():
    set_balance("bank-1", "HDFC", "INR", "BANK_ACCOUNT", "HDFC", "9591", 100000)
    set_balance("card-1", "Axis Card", "INR", "CREDIT_CARD", "AXIS", "1175", 25000)
    add_receivable({
        "id": "sw-1",
        "description": "Dinner",
        "amount_minor": 5000,
        "currency": "INR",
    })
    result = true_available("INR")
    assert result["bankCashMinor"] >= 100000
    assert result["splitwiseReceivableMinor"] >= 5000
    assert result["creditCardOutstandingMinor"] >= 25000
    assert result["trueAvailableMinor"] >= 80000
