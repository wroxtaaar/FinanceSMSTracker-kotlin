import hmac, os
from datetime import datetime, timezone
from typing import Optional
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from .db import init_db
from .ledger import *
from .reconcile import reconcile_all
from .splitwise import (
    create_for_transaction,
    enabled as splitwise_enabled,
    current_user as splitwise_user,
    groups as splitwise_groups,
    sync_receivables,
)
from .sheets_snapshot import get_snapshot
from .gmail_auth import (
    auth_url as gmail_auth_url,
    finish_callback as gmail_finish,
    sync as gmail_sync,
)

app=FastAPI(title="Oracle Finance API",version="1.0.0")
init_db()
repair_missing_balance_adjustments()

class SyncTransaction(BaseModel):
    id:str; amountMinor:int; currency:str; type:str; paymentMethod:str; accountType:str
    bank:Optional[str]=None; merchantOrPayee:Optional[str]=None; accountLast4:Optional[str]=None
    reference:Optional[str]=None; timestamp:int; category:str; confidence:float

class SyncEvidence(BaseModel):
    id:str; sourceType:str; sourceId:str; status:str; observedAt:int
    transactionId:Optional[str]=None; matchedTransactionId:Optional[str]=None
    amountMinor:Optional[int]=None; currency:Optional[str]=None; direction:Optional[str]=None
    bankProvider:Optional[str]=None; accountLast4:Optional[str]=None; reference:Optional[str]=None
    contentHash:Optional[str]=None; confidence:Optional[float]=None

class SyncCardBill(BaseModel):
    sourceType:str
    sourceKey:str
    timestamp:int
    amountMinor:Optional[int]=None
    currency:str="INR"
    bank:Optional[str]=None
    accountLast4:Optional[str]=None
    accountLast2:Optional[str]=None
    confidence:float=0.0

class SyncRequest(BaseModel):
    version:int=Field(ge=1); transactions:list[SyncTransaction]=[]; evidence:list[SyncEvidence]=[]
    voidedTransactionIds:list[str]=[]
    cardBills:list[SyncCardBill]=[]

class BalanceRequest(BaseModel):
    id:str; name:str; currency:str="INR"; accountType:str; bank:Optional[str]=None; last4:Optional[str]=None
    balanceMinor:int
    billBalanceMinor:Optional[int]=None

class ReceivableRequest(BaseModel):
    id:str; description:str; amountMinor:int; currency:str="INR"; splitwiseExpenseId:Optional[str]=None

class ManualSplitwiseTotalRequest(BaseModel):
    amountMinor:int=Field(ge=0)
    currency:str="INR"

class RuleRequest(BaseModel):
    id:str; merchantPattern:str; groupId:int; splitMode:str="EQUAL"; userSharesJson:Optional[str]=None; enabled:bool=True

def require_token(token:str):
    expected=os.getenv("SYNC_API_TOKEN","").strip()
    if not expected or not token or not hmac.compare_digest(token,expected):
        raise HTTPException(status_code=401,detail="unauthorized")

@app.get("/health")
def health(): return {"status":"ok","service":"oracle-finance","version":"1.0.0"}

@app.post("/api/v1/sync")
def sync(payload:SyncRequest,x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if payload.version!=1: raise HTTPException(400,"unsupported sync contract version")
    new_t=sum(sync_transaction(t) for t in payload.transactions)
    new_e=sum(sync_evidence(e) for e in payload.evidence)
    bill_results=[]
    for bill in payload.cardBills:
        bill_results.append(sync_card_bill({
            "sourceType": bill.sourceType,
            "sourceKey": bill.sourceKey,
            "timestamp": bill.timestamp,
            "amountMinor": bill.amountMinor,
            "currency": bill.currency,
            "bank": bill.bank,
            "accountLast4": bill.accountLast4,
            "accountLast2": bill.accountLast2,
            "confidence": bill.confidence,
        }))

    voided=0
    for transaction_id in payload.voidedTransactionIds:
        result=void_transaction(transaction_id)
        if result.get("status") in ("VOIDED","ALREADY_VOIDED"):
            voided += 1
    for t in payload.transactions:
        reconcile_duplicate_transaction(t.id)
    # Collapse safe same-side duplicates before downstream Splitwise/transfer
    # processing, so a duplicate can never create a second expense or transfer.
    repair_duplicate_transactions()
    for t in payload.transactions:
        row=next((x for x in list_transactions(1000) if x["id"]==t.id),None)
        if row and not row.get("duplicate_of"): create_for_transaction(row)
    reconcile_all()
    return {"acceptedTransactions":new_t,"acceptedEvidence":new_e,
            "duplicateTransactions":len(payload.transactions)-new_t,
            "duplicateEvidence":len(payload.evidence)-new_e,
            "voidedTransactions":voided,
            "cardBillResults":bill_results,
            "serverTime":int(datetime.now(timezone.utc).timestamp()*1000)}

@app.get("/api/v1/sheets/snapshot")
def sheets_snapshot(x_sheets_token: str = Header(default="")):
    return get_snapshot(x_sheets_token)

@app.get("/api/v1/accounts")
def get_accounts(x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return {"accounts":balances()}

@app.put("/api/v1/accounts/{account_id}/balance")
def update_balance(account_id:str,payload:BalanceRequest,x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if account_id!=payload.id: raise HTTPException(400,"account id mismatch")
    if payload.accountType == "CREDIT_CARD" and payload.billBalanceMinor is not None:
        if payload.billBalanceMinor < 0 or payload.billBalanceMinor > payload.balanceMinor:
            raise HTTPException(400,"bill balance must be between 0 and current card outstanding")
    set_balance(
        payload.id,
        payload.name,
        payload.currency,
        payload.accountType,
        payload.bank,
        payload.last4,
        payload.balanceMinor,
        payload.billBalanceMinor
    )
    return {"status":"ok"}

@app.post("/api/v1/splitwise/receivables")
def create_receivable(payload:ReceivableRequest,x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    add_receivable({"id":payload.id,"description":payload.description,"amount_minor":payload.amountMinor,
                    "currency":payload.currency,"splitwise_expense_id":payload.splitwiseExpenseId})
    return {"status":"ok"}

@app.get("/api/v1/splitwise/manual-total")
def get_manual_splitwise_total_api(currency:str="INR",x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    value=get_manual_splitwise_total(currency)
    return {"currency":currency,"amountMinor":0 if value is None else value}

@app.put("/api/v1/splitwise/manual-total")
def update_manual_splitwise_total(payload:ManualSplitwiseTotalRequest,x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    set_manual_splitwise_total(payload.currency, payload.amountMinor)
    return {"status":"ok","currency":payload.currency,"amountMinor":payload.amountMinor}

@app.get("/api/v1/splitwise/receivables")
def get_receivables(currency:str="INR",x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    with connection() as conn:
        rows=conn.execute(
            """SELECT id,description,amount_minor,currency,splitwise_expense_id,status,created_at
               FROM splitwise_receivables
               WHERE status='OPEN' AND currency=?
               ORDER BY created_at DESC""",
            (currency,)
        ).fetchall()
    return {"receivables":[dict(row) for row in rows]}

@app.get("/api/v1/summary")
def summary(currency:str="INR",x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return true_available(currency)

@app.get("/api/v1/transactions")
def transactions(limit:int=100,x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return {"transactions":list_transactions(max(1,min(limit,1000)))}

@app.get("/api/v1/reviews")
def reviews(x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return {"reviews":list_review_queue()}

@app.get("/api/v1/transfers")
def transfers(x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return {"transfers":list_transfers()}

@app.post("/api/v1/reconcile")
def reconcile(x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return reconcile_all()

@app.post("/api/v1/splitwise/rules")
def save_rule(payload:RuleRequest,x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    upsert_splitwise_rule({"id":payload.id,"merchant_pattern":payload.merchantPattern,"group_id":payload.groupId,
                           "split_mode":payload.splitMode,"user_shares_json":payload.userSharesJson,"enabled":payload.enabled})
    return {"status":"ok"}

@app.get("/api/v1/splitwise/rules")
def rules(x_sync_token:str=Header(default="")):
    require_token(x_sync_token); return {"rules":list_splitwise_rules()}

@app.get("/api/v1/splitwise/status")
def splitwise_status(x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if not splitwise_enabled(): return {"enabled":False}
    return {"enabled":True,"user":splitwise_user()}

@app.get("/api/v1/gmail/auth-url")
def gmail_auth(x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if os.getenv("GMAIL_ENABLED", "false").lower() != "true":
        raise HTTPException(403, "Gmail is disabled")
    try: return {"authorizationUrl":gmail_auth_url()}
    except Exception as exc: raise HTTPException(400,str(exc))

@app.get("/api/v1/gmail/callback")
def gmail_callback(code:str="",state:str="",error:str="",x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if os.getenv("GMAIL_ENABLED", "false").lower() != "true":
        raise HTTPException(403, "Gmail is disabled")
    if error: raise HTTPException(400,error)
    try:
        gmail_finish(str(os.environ.get("GMAIL_REDIRECT_URI","")) + "?code=" + code + "&state=" + state)
        return {"status":"authorized"}
    except Exception as exc: raise HTTPException(400,str(exc))

@app.post("/api/v1/gmail/sync")
def gmail_sync_now(
    query:Optional[str]=None,
    historical:bool=False,
    x_sync_token:str=Header(default="")
):
    require_token(x_sync_token)
    if os.getenv("GMAIL_ENABLED", "false").lower() != "true":
        raise HTTPException(403, "Gmail is disabled")
    try: return gmail_sync(query, historical=historical)
    except Exception as exc: raise HTTPException(400,str(exc))

@app.post("/api/v1/splitwise/sync")
def sync_splitwise(x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if not splitwise_enabled(): raise HTTPException(400,"Splitwise is not enabled")
    try: return {"receivables":sync_receivables()}
    except Exception as exc: raise HTTPException(400,str(exc))

@app.get("/api/v1/splitwise/groups")
def get_splitwise_groups(x_sync_token:str=Header(default="")):
    require_token(x_sync_token)
    if not splitwise_enabled(): raise HTTPException(400,"Splitwise is not enabled")
    return splitwise_groups()
