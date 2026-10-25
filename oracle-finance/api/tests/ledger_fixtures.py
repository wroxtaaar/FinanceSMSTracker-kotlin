import pytest
from app import db


@pytest.fixture(autouse=True)
def isolated_database(tmp_path, monkeypatch):
    path = str(tmp_path / "finance-test.db")
    monkeypatch.setattr(db, "DB_PATH", path)
    db.init_db()
