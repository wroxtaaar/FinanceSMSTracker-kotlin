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

def test_internal_transfer_match():
    from app.ledger import sync_transaction, match_internal_transfers
    class T:
        def __init__(self,id,typ,bank,last4):
            self.id=id; self.amountMinor=20000; self.currency="INR"; self.type=typ
            self.paymentMethod="UPI"; self.accountType="BANK_ACCOUNT"; self.bank=bank
            self.merchantOrPayee=None; self.accountLast4=last4; self.reference=None
            self.timestamp=1700000000000; self.category="TRANSFER"; self.confidence=0.95
    sync_transaction(T("debit-transfer","DEBIT","AXIS","3370"))
    x=T("credit-transfer","CREDIT","HDFC","9591"); x.timestamp+=120000
    sync_transaction(x)
    matches=match_internal_transfers()
    assert any(m["debitTransactionId"]=="debit-transfer" and m["creditTransactionId"]=="credit-transfer" for m in matches)
