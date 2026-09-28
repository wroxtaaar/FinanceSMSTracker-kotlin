# Oracle Finance Integration

Central finance service for the Android finance collector.

## Responsibilities
- Receive verified Android source evidence/transactions.
- Idempotently persist canonical transactions and source evidence.
- Reconcile bank/card events and internal transfers.
- Track bank accounts and credit-card liabilities.
- Store Splitwise receivables separately from cash balances.
- Provide a single true-available-money calculation.
- Later ingest Gmail read-only evidence and expose Telegram queries.

## Design rules
- Money is integer minor units; never use floating point.
- Currency is explicit.
- Source evidence is immutable/auditable.
- Repeated SMS/Truecaller/Gmail evidence must be idempotent.
- Unknown evidence never becomes a canonical transaction automatically.
- Internal account-to-account movement is a transfer, not income/expense.
- Credit-card spending increases liability; card payment reduces liability and bank cash.
- Splitwise receivables are assets owed to the user, not bank cash.
- No external integration may silently invent a transaction.

Android contract version 1 is defined in app/src/main/java/com/example/financesmstracker/integration/FinanceSyncContract.kt.
