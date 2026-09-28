import os
import tempfile
os.environ["DATABASE_PATH"] = os.path.join(tempfile.gettempdir(), "oracle-finance-test.db")
from fastapi.testclient import TestClient
from app.main import app
client = TestClient(app)
HEADERS = {"X-Sync-Token": "test-token"}

def test_health():
    assert client.get("/health").status_code == 200

def test_sync_requires_token():
    assert client.post("/api/v1/sync", json={"version": 1}).status_code == 401

def test_sync_is_idempotent():
    payload = {"version": 1, "transactions": [], "evidence": []}
    response = client.post("/api/v1/sync", headers=HEADERS, json=payload)
    assert response.status_code == 200
    assert response.json()["duplicateTransactions"] == 0

def test_summary_requires_token():
    assert client.get("/api/v1/summary").status_code == 401

def test_summary_shape():
    response = client.get("/api/v1/summary", headers=HEADERS)
    assert response.status_code == 200
    assert "trueAvailableMinor" in response.json()
