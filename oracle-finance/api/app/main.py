from datetime import datetime, timezone
from typing import Optional
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

app = FastAPI(title="Oracle Finance API", version="0.1.0")

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

class SyncRequest(BaseModel):
    version: int = Field(ge=1)
    transactions: list[SyncTransaction] = []
    evidence: list[SyncEvidence] = []

class SyncResponse(BaseModel):
    acceptedTransactions: int
    acceptedEvidence: int
    serverTime: int

@app.get("/health")
def health():
    return {"status": "ok", "service": "oracle-finance"}

@app.post("/api/v1/sync", response_model=SyncResponse)
def sync(payload: SyncRequest, x_sync_token: str = Header(default="")):
    if not x_sync_token:
        raise HTTPException(status_code=401, detail="missing sync token")
    if payload.version != 1:
        raise HTTPException(status_code=400, detail="unsupported sync contract version")
    return SyncResponse(
        acceptedTransactions=len(payload.transactions),
        acceptedEvidence=len(payload.evidence),
        serverTime=int(datetime.now(timezone.utc).timestamp() * 1000),
    )
