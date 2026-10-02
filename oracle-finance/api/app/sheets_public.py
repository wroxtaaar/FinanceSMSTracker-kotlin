from fastapi import FastAPI, Header
from .sheets_snapshot import get_snapshot

app = FastAPI(title="Finance Sheets Snapshot", version="1.0.0")

@app.get("/health")
def health():
    return {"status": "ok", "service": "finance-sheets"}

@app.get("/snapshot")
def snapshot(x_sheets_token: str = Header(default="")):
    return get_snapshot(x_sheets_token)
