from datetime import datetime, timezone
from typing import Optional
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from .db import init_db
from .ledger import sync_transaction, sync_evidence, balances, set_balance, add_receivable, true_available

app = FastAPI(title="Oracle Finance API", version="0.2.0")
init_db()

class SyncTransaction(BaseModel):
    id: str
    amountMinor: int
    currency: str
    type: str
    paymentMethod: str
    accountType: str
    bank: Optional[str] = None
    merchantOrPayee: Optional[str] = None
    accountLast4: Optional[str] = None
    reference: Optional[str] = None
    timestamp: int
    category: str
    confidence: float

class SyncEvidence(BaseModel):
    id: str
    sourceType: str
    sourceId: str
    status: str
    observedAt: int
    transactionId: Optional[str] = None
    matchedTransactionId: Optional[str] = None
    amountMinor: Optional[int] = None
    currency: Optional[str] = None
    direction: Optional[str] = None
    bankProvider: Optional[str] = None
    accountLast4: Optional[str] = None
    reference: Optional[str] = None
    contentHash: Optional[str] = None
    confidence: Optional[float] = None

class SyncRequest(BaseModel):
    version: int = Field(ge=1)
    transactions: list[SyncTransaction] = []
    evidence: list[SyncEvidence] = []

class SyncResponse(BaseModel):
    acceptedTransactions: int
    acceptedEvidence: int
    duplicateTransactions: int
    duplicateEvidence: int
    serverTime: int

class BalanceRequest(BaseModel):
    id: str
    name: str
    currency: str = "INR"
    accountType: str
    bank: Optional[str] = None
    last4: Optional[str] = None
    balanceMinor: int

class ReceivableRequest(BaseModel):
    id: str
    description: str
    amountMinor: int
    currency: str = "INR"
    splitwiseExpenseId: Optional[str] = None

def require_sync_token(token: str):
    if not token:
        raise HTTPException(status_code=401, detail="missing sync token")

@app.get("/health")
def health():
    return {"status": "ok", "service": "oracle-finance"}

@app.post("/api/v1/sync", response_model=SyncResponse)
def sync(payload: SyncRequest, x_sync_token: str = Header(default="")):
    require_sync_token(x_sync_token)
    if payload.version != 1:
        raise HTTPException(status_code=400, detail="unsupported sync contract version")
    new_t = sum(sync_transaction(t) for t in payload.transactions)
    new_e = sum(sync_evidence(e) for e in payload.evidence)
    return SyncResponse(
        acceptedTransactions=new_t,
        acceptedEvidence=new_e,
        duplicateTransactions=len(payload.transactions)-new_t,
        duplicateEvidence=len(payload.evidence)-new_e,
        serverTime=int(datetime.now(timezone.utc).timestamp() * 1000),
    )

@app.get("/api/v1/accounts")
def get_accounts(x_sync_token: str = Header(default="")):
    require_sync_token(x_sync_token)
    return {"accounts": balances()}

@app.put("/api/v1/accounts/{account_id}/balance")
def update_balance(account_id: str, payload: BalanceRequest, x_sync_token: str = Header(default="")):
    require_sync_token(x_sync_token)
    if account_id != payload.id:
        raise HTTPException(status_code=400, detail="account id mismatch")
    set_balance(payload.id, payload.name, payload.currency, payload.accountType,
                payload.bank, payload.last4, payload.balanceMinor)
    return {"status": "ok"}

@app.post("/api/v1/splitwise/receivables")
def create_receivable(payload: ReceivableRequest, x_sync_token: str = Header(default="")):
    require_sync_token(x_sync_token)
    add_receivable({
        "id": payload.id,
        "description": payload.description,
        "amount_minor": payload.amountMinor,
        "currency": payload.currency,
        "splitwise_expense_id": payload.splitwiseExpenseId,
    })
    return {"status": "ok"}

@app.get("/api/v1/summary")
def summary(currency: str = "INR", x_sync_token: str = Header(default="")):
    require_sync_token(x_sync_token)
    return true_available(currency)
