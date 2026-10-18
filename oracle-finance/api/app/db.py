import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.getenv("DATABASE_PATH", "/app/data/finance.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS transactions (
  id TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
  type TEXT NOT NULL, payment_method TEXT NOT NULL, account_type TEXT NOT NULL,
  bank TEXT, merchant_or_payee TEXT, account_last4 TEXT, reference TEXT,
  timestamp INTEGER NOT NULL, category TEXT NOT NULL, confidence REAL NOT NULL, duplicate_of TEXT, status TEXT NOT NULL DEFAULT 'ACTIVE', created_at INTEGER NOT NULL
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
  bank TEXT, last4 TEXT, opening_balance_minor INTEGER NOT NULL DEFAULT 0, balance_minor INTEGER NOT NULL DEFAULT 0,
  bill_balance_minor INTEGER NOT NULL DEFAULT 0, balance_reconciled_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS splitwise_receivables (
  id TEXT PRIMARY KEY, description TEXT NOT NULL, amount_minor INTEGER NOT NULL, currency TEXT NOT NULL,
  splitwise_expense_id TEXT, status TEXT NOT NULL DEFAULT 'OPEN', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS manual_splitwise_total (
  currency TEXT PRIMARY KEY, amount_minor INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS splitwise_adjustments (
  transaction_id TEXT PRIMARY KEY, currency TEXT NOT NULL, delta_minor INTEGER NOT NULL,
  applied_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS internal_transfers (
  id TEXT PRIMARY KEY, debit_transaction_id TEXT NOT NULL, credit_transaction_id TEXT NOT NULL,
  currency TEXT NOT NULL, amount_minor INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'MATCHED',
  reason TEXT NOT NULL, splitwise_neutralized INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL, UNIQUE(debit_transaction_id, credit_transaction_id)
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
CREATE TABLE IF NOT EXISTS gmail_attachments (
  id TEXT PRIMARY KEY, message_id TEXT NOT NULL, filename TEXT NOT NULL, mime_type TEXT,
  content_hash TEXT NOT NULL, bank TEXT, status TEXT NOT NULL,
  transaction_count INTEGER NOT NULL DEFAULT 0, error TEXT, created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS gmail_messages (
  id TEXT PRIMARY KEY, thread_id TEXT, internal_date INTEGER, sender TEXT, subject TEXT,
  fingerprint TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'SEEN', created_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS card_bill_evidence (
  id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_key TEXT NOT NULL,
  observed_at INTEGER NOT NULL, amount_minor INTEGER, currency TEXT NOT NULL DEFAULT 'INR',
  bank TEXT, account_last4 TEXT, account_last2 TEXT, confidence REAL NOT NULL,
  applied INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
  UNIQUE(source_type, source_key)
);
CREATE INDEX IF NOT EXISTS idx_card_bill_evidence_card
  ON card_bill_evidence(bank, account_last4, account_last2, observed_at);
CREATE TABLE IF NOT EXISTS balance_adjustments (
  transaction_id TEXT PRIMARY KEY, account_id TEXT NOT NULL, delta_minor INTEGER NOT NULL,
  applied_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_gmail_attachments_message ON gmail_attachments(message_id);\nCREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(timestamp);
CREATE INDEX IF NOT EXISTS idx_transactions_match ON transactions(amount_minor,currency,timestamp);
CREATE INDEX IF NOT EXISTS idx_evidence_transaction ON evidence(transaction_id);
CREATE INDEX IF NOT EXISTS idx_evidence_match ON evidence(amount_minor,currency,observed_at);
CREATE INDEX IF NOT EXISTS idx_accounts_type ON accounts(account_type);
CREATE INDEX IF NOT EXISTS idx_splitwise_status ON splitwise_receivables(status);
CREATE INDEX IF NOT EXISTS idx_review_status ON review_queue(status);
CREATE INDEX IF NOT EXISTS idx_balance_adjustments_account ON balance_adjustments(account_id);
CREATE INDEX IF NOT EXISTS idx_splitwise_adjustments_currency ON splitwise_adjustments(currency);
"""

@contextmanager
def connection():
    os.makedirs(os.path.dirname(DB_PATH), exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")

    # The isolated Sheets service is SQL read-only, but SQLite WAL still
    # needs filesystem access to its -wal/-shm sidecar files. The Compose
    # override therefore keeps the database directory writable while this
    # connection enables SQLite query_only mode. The normal API/worker path
    # remains unchanged.
    read_only = os.getenv("DATABASE_READ_ONLY", "").strip().lower() in {"1", "true", "yes"}
    if read_only:
        conn.execute("PRAGMA query_only=ON")
    else:
        conn.execute("PRAGMA journal_mode=WAL")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()

def _card_bank_label(bank):
    return {"ICICI": "ICICI", "SBI": "SBI", "HDFC": "HDFC", "HSBC": "HSBC",
            "INDUSIND": "IndusInd"}.get(bank, bank.title())

def init_db():
    with connection() as conn:
        conn.executescript(SCHEMA)

        transaction_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(transactions)").fetchall()
        }
        if "duplicate_of" not in transaction_columns:
            conn.execute("ALTER TABLE transactions ADD COLUMN duplicate_of TEXT")

        if "status" not in transaction_columns:
            conn.execute("ALTER TABLE transactions ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")

        transfer_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(internal_transfers)").fetchall()
        }
        if "splitwise_neutralized" not in transfer_columns:
            conn.execute(
                "ALTER TABLE internal_transfers ADD COLUMN splitwise_neutralized INTEGER NOT NULL DEFAULT 0"
            )

        splitwise_total_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(manual_splitwise_total)").fetchall()
        }
        # A manual Splitwise total is a baseline: the amount the user set and
        # the ledger-wide Splitwise sum at that moment. Rebuilds then only add
        # what changed in the ledger afterwards instead of overwriting the
        # user's value with the whole history.
        if "baseline_amount_minor" not in splitwise_total_columns:
            conn.execute("ALTER TABLE manual_splitwise_total ADD COLUMN baseline_amount_minor INTEGER")
        if "baseline_ledger_minor" not in splitwise_total_columns:
            conn.execute("ALTER TABLE manual_splitwise_total ADD COLUMN baseline_ledger_minor INTEGER")

        account_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(accounts)").fetchall()
        }
        if "opening_balance_minor" not in account_columns:
            conn.execute("ALTER TABLE accounts ADD COLUMN opening_balance_minor INTEGER")

        if "bill_balance_minor" not in account_columns:
            conn.execute("ALTER TABLE accounts ADD COLUMN bill_balance_minor INTEGER NOT NULL DEFAULT 0")
            # Existing credit-card accounts only had one live outstanding balance.
            # Seed the new bill bucket from that value so the new two-column UI
            # starts without changing the existing ledger total.
            conn.execute(
                """UPDATE accounts
                   SET bill_balance_minor = balance_minor
                 WHERE account_type='CREDIT_CARD'"""
            )

        if "balance_reconciled_at" not in account_columns:
            conn.execute("ALTER TABLE accounts ADD COLUMN balance_reconciled_at INTEGER NOT NULL DEFAULT 0")

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

        # One-time repair: the live ledger used to store card spends with the
        # bank sign (lowering the amount owed) while statement imports used the
        # card sign. Fix only the adjustments whose sign disagrees with their
        # transaction, and move the card balance by the same difference.
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if not conn.execute("SELECT 1 FROM applied_migrations WHERE name='card_adjustment_sign'").fetchone():
            wrong = conn.execute(
                """SELECT ba.transaction_id AS tid, ba.account_id AS aid, ba.delta_minor AS delta,
                          t.amount_minor AS amount, t.type AS type
                   FROM balance_adjustments ba
                   JOIN accounts a ON a.id = ba.account_id AND a.account_type='CREDIT_CARD'
                   JOIN transactions t ON t.id = ba.transaction_id
                   WHERE t.type IN ('DEBIT','CREDIT')"""
            ).fetchall()
            for row in wrong:
                expected = row["amount"] if row["type"] == "DEBIT" else -row["amount"]
                if row["delta"] == expected:
                    continue
                conn.execute(
                    "UPDATE balance_adjustments SET delta_minor=? WHERE transaction_id=?",
                    (expected, row["tid"]),
                )
                conn.execute(
                    "UPDATE accounts SET balance_minor = balance_minor + ? WHERE id=?",
                    (expected - row["delta"], row["aid"]),
                )
            conn.execute("INSERT INTO applied_migrations(name) VALUES('card_adjustment_sign')")

        # Keep the configured credit-card roster available in the ledger even
        # before the user enters the opening/outstanding balance. INSERT OR
        # IGNORE makes this safe on every startup and preserves existing values.
        configured_cards = [
            ("icici-card-8001", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "8001"),
            ("hdfc-card-5304", "HDFC Credit Card", "INR", "CREDIT_CARD", "HDFC", "5304"),
            ("sbi-card-7345", "SBI Credit Card", "INR", "CREDIT_CARD", "SBI", "7345"),
        ]
        for bank, last4 in (
            ("ICICI", "1012"), ("ICICI", "0005"), ("ICICI", "6002"), ("ICICI", "9001"),
            ("ACE", "1175"), ("AIRTEL", "9206"), ("SBI", "0065"),
            ("INDUSIND", "3526"), ("HSBC", "5145"),
        ):
            configured_cards.append((
                f"{bank.lower()}-card-{last4}", f"{_card_bank_label(bank)} Credit Card",
                "INR", "CREDIT_CARD", bank, last4,
            ))
        now = __import__("time").time()
        now_ms = int(now * 1000)
        for account_id, name, currency, account_type, bank, last4 in configured_cards:
            conn.execute(
                """INSERT OR IGNORE INTO accounts(
                    id,name,currency,account_type,bank,last4,opening_balance_minor,
                    balance_minor,bill_balance_minor,balance_reconciled_at,updated_at
                ) VALUES(?,?,?,?,?,?,0,0,0,0,?)""",
                (account_id, name, currency, account_type, bank, last4, now_ms),
            )
            # Names no longer embed the last four (the app shows them separately).
            conn.execute("UPDATE accounts SET name=? WHERE id=?", (name, account_id))


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
