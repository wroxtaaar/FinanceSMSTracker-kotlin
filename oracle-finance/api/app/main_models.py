from typing import Optional
from pydantic import BaseModel

class SyncTransactionModel(BaseModel):
    id: str; amountMinor: int; currency: str; type: str; paymentMethod: str; accountType: str
    bank: Optional[str]=None; merchantOrPayee: Optional[str]=None; accountLast4: Optional[str]=None
    reference: Optional[str]=None; timestamp: int; category: str="OTHER"; confidence: float=0.0

class SyncEvidenceModel(BaseModel):
    id: str; sourceType: str; sourceId: str; status: str; observedAt: int
    transactionId: Optional[str]=None; matchedTransactionId: Optional[str]=None
    amountMinor: Optional[int]=None; currency: Optional[str]=None; direction: Optional[str]=None
    bankProvider: Optional[str]=None; accountLast4: Optional[str]=None; reference: Optional[str]=None
    contentHash: Optional[str]=None; confidence: Optional[float]=None
