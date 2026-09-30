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
- Includes Gmail ingestion and an optional Telegram query bot.
- Never treats unknown evidence as verified automatically.

## Run
1. Copy .env.example to .env.
2. Set a long random SYNC_API_TOKEN.
3. Run: docker compose up -d --build
4. Health: curl http://127.0.0.1:8090/health

The API listens on port 8090.

## Android
Set the app Oracle base URL and the same sync token. The Android queue retries failed delivery and the server is idempotent.

## Accounts
Set the current balance for each bank/card through the balance endpoint. The backend stores an opening snapshot separately from transaction adjustments. New canonical transactions for a seeded account update its live balance exactly once, and re-seeding an account recalibrates the opening snapshot without replaying existing adjustments.

Bank accounts are summed as cash; credit-card accounts are summed as liabilities and subtracted. Bank debits reduce cash and credits increase cash. Credit-card debits increase outstanding and credits reduce outstanding.

## Internal transfers
A transfer is only linked when both events are bank-account transactions, directions are opposite, amount/currency are identical, timestamps are within 10 minutes, and the accounts are distinct. It is deliberately conservative.

## Splitwise
Splitwise is optional. Set SPLITWISE_ACCESS_TOKEN and enable it. Add a merchant rule before automatic expense creation. Transactions without a rule are not automatically pushed. The receivables endpoint exposes only positive balances owed to you; those open receivables are included in true available.

## Gmail

There are two supported providers:

### Free mode: IMAP + Gmail App Password

Set:

```
GMAIL_ENABLED=true
GMAIL_PROVIDER=imap
GMAIL_USERNAME=your-gmail-address
GMAIL_APP_PASSWORD=your-16-character-app-password
GMAIL_IMAP_HOST=imap.gmail.com
GMAIL_IMAP_PORT=993
GMAIL_IMAP_FOLDER=INBOX
GMAIL_QUERY=newer_than:30d
```

This mode does not use Google Cloud, a Gmail API project, or Google Cloud billing. The worker connects directly to Gmail IMAP over TLS. The existing parser, deduplication, balance maintenance, and review-queue logic are reused.

Create the App Password from your Google Account after enabling 2-Step Verification. Never commit the App Password to GitHub.

The IMAP adapter currently understands the existing default query `newer_than:30d` and `after:YYYY/MM/DD`. Other Gmail search syntax safely falls back to scanning the INBOX and relying on the persistent `gmail_messages` table for deduplication.

Start the worker with:

```
docker compose --profile worker up -d --build finance-worker
```

You can trigger an immediate sync through the authenticated `POST /api/v1/gmail/sync` endpoint.

### OAuth mode: Gmail API

The original OAuth implementation remains available. Set:

```
GMAIL_PROVIDER=oauth
GMAIL_CREDENTIALS=/app/secrets/credentials.json
GMAIL_TOKEN=/app/secrets/gmail-token.json
GMAIL_REDIRECT_URI=http://localhost:8090/api/v1/gmail/callback
```

OAuth requires a Google Cloud OAuth client and the Gmail read-only scope. It is not required for the free IMAP setup.

Gmail transactions are reconciled against existing SMS/Truecaller transactions before their balance adjustment is applied, so one real-world payment cannot be counted twice.

## Telegram
Enable with:

```
docker compose --profile telegram up -d --build
```

Commands: /summary, /balances, /recent, /pending, /help

Set TELEGRAM_ALLOWED_CHAT_IDS for access control.

## Backup
Back up ./data/finance.db and the environment/secrets separately. SQLite WAL mode is enabled.
