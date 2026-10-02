import time
from .db import connection

def now_ms(): return int(time.time()*1000)

def sync_transaction(t, apply_balance=True):
    with connection() as conn:
        before=conn.execute("SELECT id FROM transactions WHERE id=?",(t.id,)).fetchone()
        created_at=now_ms()
        conn.execute("""INSERT OR IGNORE INTO transactions
        (id,amount_minor,currency,type,payment_method,account_type,bank,merchant_or_payee,account_last4,reference,timestamp,category,confidence,duplicate_of,status,created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (t.id,t.amountMinor,t.currency,t.type,t.paymentMethod,t.accountType,t.bank,t.merchantOrPayee,t.accountLast4,t.reference,
         t.timestamp,t.category,t.confidence,None,"ACTIVE",created_at))

        if before is None and apply_balance and not str(t.id).startswith("gmail:"):
            apply_transaction_to_account(conn, t, created_at)

        return before is None

def apply_transaction_to_account(conn, t, applied_at):
    if t.accountType not in ("BANK_ACCOUNT", "CREDIT_CARD"):
        return

    bank=(t.bank or "").strip().upper()
    last4=(t.accountLast4 or "").strip()

    query="""
        SELECT id, balance_minor
        FROM accounts
        WHERE account_type=?
          AND currency=?
          AND UPPER(TRIM(COALESCE(bank,'')))=?
          AND TRIM(COALESCE(last4,''))=?
        ORDER BY updated_at DESC
        LIMIT 1
    """
    account=conn.execute(query,(t.accountType,t.currency,bank,last4)).fetchone()
    if not account:
        return

    if t.accountType=="BANK_ACCOUNT":
        delta=-t.amountMinor if t.type=="DEBIT" else t.amountMinor
    else:
        delta=t.amountMinor if t.type=="DEBIT" else -t.amountMinor

    inserted=conn.execute(
        """INSERT OR IGNORE INTO balance_adjustments
           (transaction_id,account_id,delta_minor,applied_at)
           VALUES(?,?,?,?)""",
        (t.id,account["id"],delta,applied_at)
    )
    if inserted.rowcount:
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
            (delta,applied_at,account["id"])
        )

def sync_evidence(e):
    with connection() as conn:
        before=conn.execute(
            "SELECT id FROM evidence WHERE source_type=? AND source_id=?",
            (e.sourceType,e.sourceId)
        ).fetchone()
        conn.execute("""
            INSERT INTO evidence
            (id,source_type,source_id,status,observed_at,transaction_id,matched_transaction_id,amount_minor,currency,direction,
             bank_provider,account_last4,reference,content_hash,confidence,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source_type, source_id) DO UPDATE SET
                status=excluded.status,
                observed_at=excluded.observed_at,
                transaction_id=excluded.transaction_id,
                matched_transaction_id=excluded.matched_transaction_id,
                amount_minor=excluded.amount_minor,
                currency=excluded.currency,
                direction=excluded.direction,
                bank_provider=excluded.bank_provider,
                account_last4=excluded.account_last4,
                reference=excluded.reference,
                content_hash=excluded.content_hash,
                confidence=excluded.confidence
        """,
        (e.id,e.sourceType,e.sourceId,e.status,e.observedAt,e.transactionId,e.matchedTransactionId,e.amountMinor,e.currency,
         e.direction,e.bankProvider,e.accountLast4,e.reference,e.contentHash,e.confidence,now_ms()))
        return before is None

def void_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute(
            "SELECT id,status FROM transactions WHERE id=?",
            (transaction_id,)
        ).fetchone()
        if not tx:
            return {"status":"NOT_FOUND","transactionId":transaction_id}
        if tx["status"]=="VOIDED":
            return {"status":"ALREADY_VOIDED","transactionId":transaction_id}

        adjustment=conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,)
        ).fetchone()

        if adjustment:
            conn.execute(
                "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                (adjustment["delta_minor"], now_ms(), adjustment["account_id"])
            )
            conn.execute(
                "DELETE FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,)
            )

        conn.execute(
            "UPDATE transactions SET status='VOIDED' WHERE id=?",
            (transaction_id,)
        )
        return {
            "status":"VOIDED",
            "transactionId":transaction_id,
            "reversedAdjustment": bool(adjustment)
        }

def balances():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM accounts ORDER BY name").fetchall()]

def set_balance(account_id,name,currency,account_type,bank,last4,balance_minor):
    with connection() as conn:
        adjustment_total=conn.execute(
            "SELECT COALESCE(SUM(delta_minor),0) value FROM balance_adjustments WHERE account_id=?",
            (account_id,)
        ).fetchone()["value"]
        opening_balance=balance_minor-adjustment_total
        conn.execute("""INSERT INTO accounts(
            id,name,currency,account_type,bank,last4,opening_balance_minor,balance_minor,updated_at
        ) VALUES(?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
            name=excluded.name,
            currency=excluded.currency,
            account_type=excluded.account_type,
            bank=excluded.bank,
            last4=excluded.last4,
            opening_balance_minor=excluded.opening_balance_minor,
            balance_minor=excluded.balance_minor,
            updated_at=excluded.updated_at""",
        (account_id,name,currency,account_type,bank,last4,opening_balance,balance_minor,now_ms()))

def add_receivable(item):
    with connection() as conn:
        conn.execute("""INSERT OR IGNORE INTO splitwise_receivables
        (id,description,amount_minor,currency,splitwise_expense_id,status,created_at)
        VALUES(?,?,?,?,?,'OPEN',?)""",
        (item["id"],item["description"],item["amount_minor"],item["currency"],item.get("splitwise_expense_id"),now_ms()))

def set_manual_splitwise_total(currency, amount_minor):
    with connection() as conn:
        conn.execute(
            """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
               VALUES(?,?,?)
               ON CONFLICT(currency) DO UPDATE SET
                 amount_minor=excluded.amount_minor,
                 updated_at=excluded.updated_at""",
            (currency, max(0, int(amount_minor)), now_ms())
        )

def get_manual_splitwise_total(currency="INR"):
    with connection() as conn:
        row=conn.execute("SELECT amount_minor FROM manual_splitwise_total WHERE currency=?",(currency,)).fetchone()
        return int(row["amount_minor"]) if row else None

def true_available(currency="INR"):
    with connection() as conn:
        cash=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='BANK_ACCOUNT' AND currency=?",(currency,)).fetchone()["value"]
        manual=get_manual_splitwise_total(currency)
        if manual is None:
            rec=conn.execute("SELECT COALESCE(SUM(amount_minor),0) value FROM splitwise_receivables WHERE status='OPEN' AND currency=?",(currency,)).fetchone()["value"]
        else:
            rec=manual
        cards=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='CREDIT_CARD' AND currency=?",(currency,)).fetchone()["value"]
        return {"currency":currency,"bankCashMinor":cash,"splitwiseReceivableMinor":rec,"creditCardOutstandingMinor":cards,"trueAvailableMinor":cash+rec-cards}

def list_transactions(limit=100):
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM transactions WHERE duplicate_of IS NULL AND status='ACTIVE' ORDER BY timestamp DESC LIMIT ?",(limit,)).fetchall()]

def list_review_queue():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM review_queue WHERE status='OPEN' ORDER BY created_at DESC").fetchall()]

def add_review(kind,reason,transaction_id=None,evidence_id=None):
    review_id=f"{kind}:{transaction_id or evidence_id or now_ms()}"
    with connection() as conn:
        conn.execute("INSERT OR IGNORE INTO review_queue(id,kind,transaction_id,evidence_id,reason,status,created_at) VALUES(?,?,?,?,?,'OPEN',?)",
                     (review_id,kind,transaction_id,evidence_id,reason,now_ms()))
    return review_id

def match_internal_transfers():
    with connection() as conn:
        txs=conn.execute("SELECT * FROM transactions WHERE account_type='BANK_ACCOUNT' AND type IN ('DEBIT','CREDIT') ORDER BY timestamp DESC").fetchall()
        matches=[]
        for debit in txs:
            if debit["type"]!="DEBIT": continue
            for credit in txs:
                if credit["type"]!="CREDIT" or debit["id"]==credit["id"]: continue
                if debit["currency"]!=credit["currency"] or debit["amount_minor"]!=credit["amount_minor"]: continue
                if abs(debit["timestamp"]-credit["timestamp"])>10*60*1000: continue
                if debit["bank"]==credit["bank"] and debit["account_last4"]==credit["account_last4"]: continue
                exists=conn.execute("SELECT 1 FROM internal_transfers WHERE debit_transaction_id=? OR credit_transaction_id=?",(debit["id"],credit["id"])).fetchone()
                if exists: continue
                reason="same amount/currency within 10 minutes across distinct bank accounts"
                transfer_id=f"transfer:{debit['id']}:{credit['id']}"
                conn.execute("""INSERT OR IGNORE INTO internal_transfers
                (id,debit_transaction_id,credit_transaction_id,currency,amount_minor,status,reason,created_at)
                VALUES(?,?,?,?,?,'MATCHED',?,?)""",
                (transfer_id,debit["id"],credit["id"],debit["currency"],debit["amount_minor"],reason,now_ms()))
                matches.append({"id":transfer_id,"debitTransactionId":debit["id"],"creditTransactionId":credit["id"],
                                "amountMinor":debit["amount_minor"],"currency":debit["currency"],"reason":reason})
        return matches

def list_transfers():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM internal_transfers ORDER BY created_at DESC").fetchall()]

def upsert_splitwise_rule(item):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_rules(id,merchant_pattern,group_id,split_mode,user_shares_json,enabled,created_at)
        VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET merchant_pattern=excluded.merchant_pattern,group_id=excluded.group_id,
        split_mode=excluded.split_mode,user_shares_json=excluded.user_shares_json,enabled=excluded.enabled""",
        (item["id"],item["merchant_pattern"],item["group_id"],item.get("split_mode","EQUAL"),item.get("user_shares_json"),
         1 if item.get("enabled",True) else 0,now_ms()))

def list_splitwise_rules():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY merchant_pattern").fetchall()]

def get_splitwise_rule_for(merchant):
    if not merchant: return None
    needle=merchant.casefold()
    with connection() as conn:
        rows=conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY LENGTH(merchant_pattern) DESC").fetchall()
    for row in rows:
        if row["merchant_pattern"].casefold() in needle: return dict(row)
    return None

def record_splitwise_expense(transaction_id,expense_id,status="CREATED",error=None):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_expenses(transaction_id,splitwise_expense_id,status,error,created_at,updated_at)
        VALUES(?,?,?,?,?,?) ON CONFLICT(transaction_id) DO UPDATE SET splitwise_expense_id=excluded.splitwise_expense_id,
        status=excluded.status,error=excluded.error,updated_at=excluded.updated_at""",
        (transaction_id,expense_id,status,error,now_ms(),now_ms()))

def splitwise_expense_for(transaction_id):
    with connection() as conn:
        row=conn.execute("SELECT * FROM splitwise_expenses WHERE transaction_id=?",(transaction_id,)).fetchone()
        return dict(row) if row else None

def reconcile_duplicate_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute("SELECT * FROM transactions WHERE id=?",(transaction_id,)).fetchone()
        if not tx or tx["duplicate_of"]: return None
        if tx["id"].startswith("gmail:") is False: return None
        candidates=conn.execute(
            """SELECT * FROM transactions WHERE id<>? AND duplicate_of IS NULL
               AND id NOT LIKE 'gmail:%' AND status='ACTIVE' AND amount_minor=? AND currency=?
               AND ABS(timestamp-?)<=86400000 ORDER BY ABS(timestamp-?) LIMIT 5""",
            (tx["id"],tx["amount_minor"],tx["currency"],tx["timestamp"],tx["timestamp"])).fetchall()
        strong=[]
        for c in candidates:
            score=0
            if tx["bank"] and c["bank"] and tx["bank"]==c["bank"]: score+=2
            if tx["account_last4"] and c["account_last4"] and tx["account_last4"]==c["account_last4"]: score+=2
            if tx["reference"] and c["reference"] and tx["reference"]==c["reference"]: score+=4
            if tx["type"]==c["type"]: score+=1
            if score>=3: strong.append((score,c))
        if len(strong)==1:
            canonical=strong[0][1]
            conn.execute("UPDATE transactions SET duplicate_of=? WHERE id=?",(canonical["id"],tx["id"]))
            conn.execute("UPDATE evidence SET matched_transaction_id=? WHERE transaction_id=?",(canonical["id"],tx["id"]))
            return {"duplicate":tx["id"],"canonical":canonical["id"],"score":strong[0][0]}
    return None
