import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.getenv("DATABASE_PATH", "/app/data/finance.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY,
  amount_minor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  type TEXT NOT NULL,
  payment_method TEXT NOT NULL,
  account_type TEXT NOT NULL,
  bank TEXT,
  merchant_or_payee TEXT,
  account_last4 TEXT,
  reference TEXT,
  timestamp INTEGER NOT NULL,
  category TEXT NOT NULL,
  confidence REAL NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence (
  id TEXT PRIMARY KEY,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  status TEXT NOT NULL,
  observed_at INTEGER NOT NULL,
  transaction_id TEXT,
  matched_transaction_id TEXT,
  amount_minor INTEGER,
  currency TEXT,
  direction TEXT,
  bank_provider TEXT,
  account_last4 TEXT,
  reference TEXT,
  content_hash TEXT,
  confidence REAL,
  created_at INTEGER NOT NULL,
  UNIQUE(source_type, source_id)
);

CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  currency TEXT NOT NULL,
  account_type TEXT NOT NULL,
  bank TEXT,
  last4 TEXT,
  balance_minor INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS splitwise_receivables (
  id TEXT PRIMARY KEY,
  description TEXT NOT NULL,
  amount_minor INTEGER NOT NULL,
  currency TEXT NOT NULL,
  splitwise_expense_id TEXT,
  status TEXT NOT NULL DEFAULT 'OPEN',
  created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp);
CREATE INDEX IF NOT EXISTS idx_evidence_transaction ON evidence(transaction_id);
CREATE INDEX IF NOT EXISTS idx_accounts_type ON accounts(account_type);
CREATE INDEX IF NOT EXISTS idx_splitwise_status ON splitwise_receivables(status);
"""

@contextmanager
def connection():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()

def init_db():
    with connection() as conn:
        conn.executescript(SCHEMA)
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(evidence)").fetchall()}
        migrations = {
            "amount_minor": "INTEGER",
            "currency": "TEXT",
            "direction": "TEXT",
            "bank_provider": "TEXT",
            "account_last4": "TEXT",
            "reference": "TEXT",
            "content_hash": "TEXT",
            "confidence": "REAL",
        }
        for name, sql_type in migrations.items():
            if name not in columns:
                conn.execute(f"ALTER TABLE evidence ADD COLUMN {name} {sql_type}")
