# Oracle VPS deployment

1. Install Docker Engine and Compose on the Oracle VPS.
2. Clone this repository and enter oracle-finance/.
3. Create .env from .env.example.
4. Generate a long random SYNC_API_TOKEN.
5. Start with:
   docker compose up -d --build
6. Verify locally:
   curl http://127.0.0.1:8090/health
7. Do not expose port 8090 directly to the internet. Put it behind the VPS reverse proxy/TLS layer.
8. Back up the SQLite database regularly with deploy/backup.sh.

The API currently exposes:
- GET /health
- POST /api/v1/sync
- GET /api/v1/accounts
- PUT /api/v1/accounts/{account_id}/balance
- POST /api/v1/splitwise/receivables
- GET /api/v1/summary

Android must use HTTPS and the sync token once the reverse-proxy endpoint is ready.
