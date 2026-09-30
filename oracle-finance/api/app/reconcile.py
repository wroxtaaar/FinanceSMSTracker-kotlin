from .db import connection
from .ledger import add_review, match_internal_transfers

def reconcile_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute("SELECT * FROM transactions WHERE id=?",(transaction_id,)).fetchone()
        if not tx: return {"status":"NOT_FOUND"}
        ev=conn.execute("SELECT * FROM evidence WHERE transaction_id=? OR matched_transaction_id=?",(transaction_id,transaction_id)).fetchall()
        matched=sum(1 for e in ev if e["status"]=="MATCHED")
    if matched==0:
        add_review("TRANSACTION","canonical transaction has no matched source evidence",transaction_id=transaction_id)
        return {"status":"REVIEW","reason":"no matched evidence"}
    return {"status":"OK","matchedEvidence":matched}

def reconcile_all():
    transfers=match_internal_transfers()
    with connection() as conn:
        ids=[r["id"] for r in conn.execute("SELECT id FROM transactions").fetchall()]
    reviews=[]
    for tx_id in ids:
        if reconcile_transaction(tx_id)["status"]=="REVIEW": reviews.append(tx_id)
    return {"internalTransfers":transfers,"reviewTransactionIds":reviews}
