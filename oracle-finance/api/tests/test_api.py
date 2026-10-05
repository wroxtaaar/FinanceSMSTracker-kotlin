import os
import tempfile
os.environ["DATABASE_PATH"] = os.path.join(tempfile.gettempdir(), "oracle-finance-test.db")
os.environ["SYNC_API_TOKEN"] = "test-token"
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

def test_sync_with_token_accepts_transaction():
    payload={"version":1,"transactions":[{
        "id":"t-api-1","amountMinor":10000,"currency":"INR","type":"DEBIT",
        "paymentMethod":"UPI","accountType":"BANK_ACCOUNT","bank":"HDFC",
        "merchantOrPayee":"TEST","accountLast4":"9591","reference":"REF100",
        "timestamp":1700000000000,"category":"OTHER","confidence":0.9
    }],"evidence":[]}
    response=client.post("/api/v1/sync",headers=HEADERS,json=payload)
    assert response.status_code==200
    assert response.json()["acceptedTransactions"]==1

def test_wrong_token_rejected():
    assert client.get("/api/v1/summary",headers={"X-Sync-Token":"wrong"}).status_code==401


def test_manual_splitwise_total():
    response = client.put("/api/v1/splitwise/manual-total", headers=HEADERS,
                          json={"amountMinor": 850000, "currency": "INR"})
    assert response.status_code == 200
    assert response.json()["amountMinor"] == 850000

    response = client.get("/api/v1/splitwise/manual-total", headers=HEADERS)
    assert response.status_code == 200
    assert response.json()["amountMinor"] == 850000

    summary = client.get("/api/v1/summary", headers=HEADERS)
    assert summary.status_code == 200
    assert summary.json()["splitwiseReceivableMinor"] == 850000


def test_sync_confirms_android_internal_transfer_candidate():
    payload = {
        "version": 1,
        "transactions": [
            {
                "id": "candidate-api-debit",
                "amountMinor": 1000,
                "currency": "INR",
                "type": "DEBIT",
                "paymentMethod": "UPI",
                "accountType": "BANK_ACCOUNT",
                "bank": "AXIS",
                "merchantOrPayee": "ABDUL WASIQ",
                "accountLast4": "3370",
                "reference": None,
                "timestamp": 2_100_000_000_000,
                "category": "TRANSFER",
                "confidence": 0.99,
            },
            {
                "id": "gmail:candidate-api-credit",
                "amountMinor": 1000,
                "currency": "INR",
                "type": "CREDIT",
                "paymentMethod": "UPI",
                "accountType": "BANK_ACCOUNT",
                "bank": "HDFC",
                "merchantOrPayee": "ABDUL WASIQ",
                "accountLast4": "9591",
                "reference": None,
                "timestamp": 2_100_000_000_000,
                "category": "TRANSFER",
                "confidence": 0.99,
            },
        ],
        "evidence": [],
        "internalTransferCandidates": [
            {
                "debitTransactionId": "candidate-api-debit",
                "creditTransactionId": "gmail:candidate-api-credit",
                "amountMinor": 1000,
                "currency": "INR",
                "timeDifferenceMillis": 0,
                "matchType": "AMOUNT_TIME",
            }
        ],
    }

    response = client.post("/api/v1/sync", headers=HEADERS, json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["internalTransferCandidates"][0]["debitTransactionId"] == "candidate-api-debit"
    assert body["internalTransferCandidates"][0]["creditTransactionId"] == "gmail:candidate-api-credit"
