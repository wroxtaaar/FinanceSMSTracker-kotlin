import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.getenv("DATABASE_PATH", "/app/data/finance.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
  type TEXT NOT NULL, payment_method TEXT NOT NULL, account_type TEXT NOT NULL,
  bank TEXT, merchant_or_payee TEXT, account_last4 TEXT, reference TEXT,
  timestamp INTEGER NOT NULL, category TEXT NOT NULL, confidence REAL NOT NULL, duplicate_of TEXT, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS evidence (
  id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_id TEXT NOT NULL, status TEXT NOT NULL,
  observed_at INTEGER NOT NULL, transaction_id TEXT, matched_transaction_id TEXT,
  amount_minor INTEGER, currency TEXT, direction TEXT, bank_provider TEXT, account_last4 TEXT,
  reference TEXT, content_hash TEXT, confidence REAL, created_at INTEGER NOT NULL,
  UNIQUE(source_type, source_id)
);
CREATE TABLE IF NOT EXISTS accounts (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, currency TEXT NOT NULL, account_type TEXT NOT NULL,
  bank TEXT, last4 TEXT, opening_balance_minor INTEGER NOT NULL DEFAULT 0, balance_minor INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS splitwise_receivables (
  id TEXT PRIMARY KEY, description TEXT NOT NULL, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
  splitwise_expense_id TEXT, status TEXT NOT NULL DEFAULT 'OPEN', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS manual_splitwise_total (
  currency TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS internal_transfers (
  id TEXT PRIMARY KEY, debit_transaction_id TEXT NOT NULL, credit_transaction_id TEXT NOT NULL,
  currency TEXT NOT NULL, amount_minor INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'MATCHED',
  reason TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(debit_transaction_id, credit_transaction_id)
);
CREATE TABLE IF NOT EXISTS splitwise_rules (
  id TEXT PRIMARY KEY, merchant_pattern TEXT NOT NULL, group_id INTEGER NOT NULL,
  split_mode TEXT NOT NULL DEFAULT 'EQUAL', user_shares_json TEXT, enabled INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS splitwise_expenses (
  transaction_id TEXT PRIMARY KEY, splitwise_expense_id TEXT, status TEXT NOT NULL, error TEXT,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS review_queue (
  id TEXT PRIMARY KEY, kind TEXT NOT NULL, transaction_id TEXT, evidence_id TEXT, reason TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN', created_at INTEGER NOT NULL, resolved_at INTEGER
);
CREATE TABLE IF NOT EXISTS gmail_messages (
  id TEXT PRIMARY KEY, thread_id TEXT, internal_date INTEGER, sender TEXT, subject TEXT,
  fingerprint TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'SEEN', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS balance_adjustments (
  transaction_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, delta_minor INTEGER NOT NULL,
  applied_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp);
CREATE INDEX IF NOT EXISTS idx_transactions_match ON transactions(amount_minor,currency,timestamp);
CREATE INDEX IF NOT EXISTS idx_evidence_transaction ON evidence(transaction_id);
CREATE INDEX IF NOT EXISTS idx_evidence_match ON evidence(amount_minor,currency,observed_at);
CREATE INDEX IF NOT EXISTS idx_accounts_type ON accounts(account_type);
CREATE INDEX IF NOT EXISTS idx_splitwise_status ON splitwise_receivables(status);
CREATE INDEX IF NOT EXISTS idx_review_status ON review_queue(status);
CREATE INDEX IF NOT EXISTS idx_balance_adjustments_account ON balance_adjustments(account_id);
"""

@contextmanager
def connection():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA journal_mode=WAL")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()

def init_db():
    with connection() as conn:
        conn.executescript(SCHEMA)

        transaction_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(transactions)").fetchall()
        }
        if "duplicate_of" not in transaction_columns:
            conn.execute("ALTER TABLE transactions ADD COLUMN duplicate_of TEXT")

        account_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(accounts)").fetchall()
        }
        if "opening_balance_minor" not in account_columns:
            conn.execute("ALTER TABLE accounts ADD COLUMN opening_balance_minor INTEGER")

        # Existing accounts predate the opening-balance field. Reconstruct the
        # opening snapshot from their current balance and already-applied
        # transaction adjustments so upgrades preserve the live balance.
        conn.execute(
            """UPDATE accounts
               SET opening_balance_minor = balance_minor - COALESCE(
                   (SELECT SUM(delta_minor) FROM balance_adjustments WHERE account_id=accounts.id), 0
               )
             WHERE opening_balance_minor IS NULL"""
        )

        evidence_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(evidence)").fetchall()
        }
        migrations = {
            "duplicate_of":"TEXT",
            "amount_minor":"INTEGER",
            "currency":"TEXT",
            "direction":"TEXT",
            "bank_provider":"TEXT",
            "account_last4":"TEXT",
            "reference":"TEXT",
            "content_hash":"TEXT",
            "confidence":"REAL",
        }
        for name, sql_type in migrations.items():
            if name not in evidence_columns:
                conn.execute(f"ALTER TABLE evidence ADD COLUMN {name} {sql_type}")
