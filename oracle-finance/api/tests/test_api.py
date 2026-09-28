from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"

def test_sync_requires_token():
    response = client.post("/api/v1/sync", json={"version": 1})
    assert response.status_code == 401

def test_sync_validates_contract():
    response = client.post(
        "/api/v1/sync",
        headers={"X-Sync-Token": "test-token"},
        json={"version": 1, "transactions": [], "evidence": []},
    )
    assert response.status_code == 200

def test_sync_rejects_unknown_version():
    response = client.post(
        "/api/v1/sync",
        headers={"X-Sync-Token": "test-token"},
        json={"version": 999},
    )
    assert response.status_code == 400
