# Oracle VPS Finance

Lightweight finance backend for the Android finance collector. It runs on the Oracle VPS with one FastAPI container and one SQLite database file. No separate database server is required.

## What it does
- Receives verified Android SMS/Truecaller evidence.
- Stores canonical transactions and source evidence idempotently.
- Detects conservative internal bank transfers.
- Tracks bank balances and credit-card outstanding balances.
- Calculates bank cash + Splitwise receivables - credit-card outstanding.
- Keeps Splitwise rules and expense IDs to prevent duplicate expenses.
- Provides a review queue.
- Includes Gmail read-only ingestion code and an optional Telegram query bot.
- Never treats unknown evidence as verified automatically.

## Run
1. Copy .env.example to .env.
2. Set a long random SYNC_API_TOKEN.
3. Run: docker compose up -d --build
4. Health: curl http://127.0.0.1:8090/health

The API listens on port 8090. Restrict Oracle Cloud ingress to your own network if exposed publicly.

## Android
Set the app Oracle base URL and the same sync token. The Android queue retries failed delivery and the server is idempotent.

## Accounts
Set current bank/card balances through the balance endpoint. Bank accounts are summed as cash; credit-card accounts are summed as liabilities and subtracted.

## Internal transfers
A transfer is only linked when both events are bank-account transactions, directions are opposite, amount/currency are identical, timestamps are within 10 minutes, and the accounts are distinct. It is deliberately conservative.

## Splitwise
Splitwise is optional. Set SPLITWISE_ACCESS_TOKEN and enable it. Add a merchant rule before automatic expense creation. Transactions without a rule are not automatically pushed.

## Gmail
The worker uses the read-only Gmail scope. Put the Google OAuth client JSON at ./secrets/credentials.json and persist the token at ./secrets/gmail-token.json. Gmail is an evidence source and parsing is conservative.

## Telegram
Enable with: docker compose --profile telegram up -d --build
Commands: /summary, /balances, /recent, /pending, /help
Set TELEGRAM_ALLOWED_CHAT_IDS for access control.

## Backup
Back up ./data/finance.db and the environment/OAuth secrets separately. SQLite WAL mode is enabled.
