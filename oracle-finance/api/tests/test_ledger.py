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


def test_new_transactions_update_seeded_account_balances():
    from app.ledger import sync_transaction
    class T:
        def __init__(self,id,typ,account_type,bank,last4,amount):
            self.id=id; self.amountMinor=amount; self.currency="INR"; self.type=typ
            self.paymentMethod="UPI"; self.accountType=account_type; self.bank=bank
            self.merchantOrPayee=None; self.accountLast4=last4; self.reference=None
            self.timestamp=1800000000000; self.category="OTHER"; self.confidence=0.95

    set_balance("auto-bank", "Auto Bank", "INR", "BANK_ACCOUNT", "HDFC", "1111", 100000)
    sync_transaction(T("auto-bank-debit", "DEBIT", "BANK_ACCOUNT", "HDFC", "1111", 2500))
    sync_transaction(T("auto-bank-credit", "CREDIT", "BANK_ACCOUNT", "HDFC", "1111", 1000))

    set_balance("auto-card", "Auto Card", "INR", "CREDIT_CARD", "AXIS", "2222", 50000)
    sync_transaction(T("auto-card-debit", "DEBIT", "CREDIT_CARD", "AXIS", "2222", 7000))
    sync_transaction(T("auto-card-credit", "CREDIT", "CREDIT_CARD", "AXIS", "2222", 1200))

    from app.db import connection
    with connection() as conn:
        bank=conn.execute("SELECT balance_minor FROM accounts WHERE id='auto-bank'").fetchone()["balance_minor"]
        card=conn.execute("SELECT balance_minor FROM accounts WHERE id='auto-card'").fetchone()["balance_minor"]

    assert bank == 98500
    assert card == 55800


def test_existing_transaction_is_not_applied_twice():
    from app.ledger import sync_transaction
    class T:
        id="no-double"; amountMinor=500; currency="INR"; type="DEBIT"
        paymentMethod="UPI"; accountType="BANK_ACCOUNT"; bank="HDFC"
        merchantOrPayee=None; accountLast4="3333"; reference=None
        timestamp=1800000000000; category="OTHER"; confidence=0.95

    set_balance("no-double-account", "No Double", "INR", "BANK_ACCOUNT", "HDFC", "3333", 10000)
    t=T()
    assert sync_transaction(t) is True
    assert sync_transaction(t) is False

    from app.db import connection
    with connection() as conn:
        balance=conn.execute("SELECT balance_minor FROM accounts WHERE id='no-double-account'").fetchone()["balance_minor"]

    assert balance == 9500
