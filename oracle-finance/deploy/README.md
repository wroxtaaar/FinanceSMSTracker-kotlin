# Oracle VPS deployment

1. Install Docker Engine and Compose on the Oracle VPS.
2. Clone this repository and enter oracle-finance/.
3. Create .env from .env.example.
4. Generate a long random SYNC_API_TOKEN.
5. Start with:
   docker compose up -d --build
6. Verify locally:
   curl http://127.0.0.1:8090/health
7. If port 8090 is exposed, restrict the Oracle Cloud security-list ingress to your own IP/network where possible; the API also requires SYNC_API_TOKEN.
8. Back up the SQLite database regularly with deploy/backup.sh.

The API currently exposes:
- GET /health
- POST /api/v1/sync
- GET /api/v1/accounts
- PUT /api/v1/accounts/{account_id}/balance
- POST /api/v1/splitwise/receivables
- GET /api/v1/summary\n- GET /api/v1/transactions\n- GET /api/v1/reviews\n- GET /api/v1/transfers\n- POST /api/v1/reconcile\n- Splitwise rules/status/groups/sync endpoints\n- Gmail authorization/sync endpoints

### Tailscale private access

The finance API is intentionally bound to localhost. Tailscale Serve is used as the private HTTPS reverse proxy, so port 8090 does not need to be publicly reachable.

One-time Oracle setup:
1. Create/sign in to a Tailscale account and create the tailnet.
2. Create a reusable Tailscale auth key for this personal Oracle node.
3. Add that value to GitHub Actions as the `ORACLE_TAILSCALE_AUTHKEY` secret.
4. Enable HTTPS certificates for the tailnet in the Tailscale admin console.
5. The deployment workflow installs/authenticates Tailscale when needed and runs `tailscale serve --bg 127.0.0.1:8090`.
6. After the first successful deployment, the workflow prints the private `.ts.net` endpoint. Use that HTTPS URL in the Android sync configuration.

The endpoint is available only to devices in the same tailnet; do not use Tailscale Funnel for this finance API.

### Android

Install Tailscale on the phone, sign in to the same tailnet, and allow its VPN profile. Tailscale supports Android 8+ and can reconnect automatically; Android's Always-on VPN option can be used when you want the VPN kept continuously active. App-based split tunneling is also available if you want only the Finance Tracker to use the tailnet.

Android should use the Tailscale HTTPS URL plus the same sync token. The existing durable sync queue will retain events while the private endpoint is temporarily unreachable.
