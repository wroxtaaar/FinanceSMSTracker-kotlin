"""Bridge to the personal Expense app (Supabase): add a ledger expense to it, and list what is missing.

The server signs in with its own Supabase user (EXPENSE_EMAIL / EXPENSE_PASSWORD in the VPS .env,
never in the repo; that email must be in the Expense `allowed_users` table).

* add_expense: writes ONE Expense expense for ONE ledger row. The row id is stored in the expense as
  `ledgerId` and in the local `expense_links` table, so the link is exact and re-adding updates the same
  expense instead of creating a second one.
* check: ledger rows that raise Splitwise (delta > 0), dated on/after EXPENSE_TRACK_FROM, that are
  neither linked to a Expense expense nor marked handled. Nothing is guessed from amounts, because the
  expenses typed by hand in Expense are grouped differently from the bank rows.
"""
import hashlib
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

from .db import connection
from .ledger.common import _resolve_transaction_row
from .ledger.splitwise import _ledger_splitwise_entries

IST = timezone(timedelta(hours=5, minutes=30))
DEFAULT_TRACK_FROM = "2026-11-25"
PAGE = 1000
DESCRIPTION_LIMIT = 200


class ExpenseError(RuntimeError):
    """A problem the user can act on (bad choice, Expense unreachable, ...)."""


def _env(name, default=""):
    """EXPENSE_<name>, falling back to the old SPLITS_<name> so an existing .env keeps working."""
    return os.getenv(f"EXPENSE_{name}") or os.getenv(f"SPLITS_{name}") or default


def settings():
    return {
        "url": _env("SUPABASE_URL").strip().rstrip("/"),
        "key": _env("SUPABASE_KEY").strip(),
        "email": _env("EMAIL").strip(),
        "password": _env("PASSWORD"),
        "from": _env("TRACK_FROM").strip() or DEFAULT_TRACK_FROM,
    }


def configured(s=None):
    s = s or settings()
    return all(s[k] for k in ("url", "key", "email", "password"))


def track_from_ms(value):
    return int(datetime.strptime(value, "%Y-%m-%d").replace(tzinfo=IST).timestamp() * 1000)


def _http(method, url, headers, body=None):
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise ExpenseError(f"Expense HTTP {e.code}: {e.read().decode(errors='replace')[:160]}") from e
    except urllib.error.URLError as e:
        raise ExpenseError(f"Expense unreachable: {e.reason}") from e


def _login(s):
    if not configured(s):
        raise ExpenseError("Expense is not configured on the server")
    login = _http(
        "POST", f"{s['url']}/auth/v1/token?grant_type=password",
        {"apikey": s["key"], "Content-Type": "application/json"},
        json.dumps({"email": s["email"], "password": s["password"]}).encode(),
    )
    return {"apikey": s["key"], "Authorization": f"Bearer {login['access_token']}",
            "Content-Type": "application/json"}


def _records(s, headers, coll):
    out, offset = [], 0
    while True:
        query = urllib.parse.urlencode(
            {"select": "id,data,deleted", "coll": f"eq.{coll}", "order": "server_ts.asc",
             "limit": PAGE, "offset": offset})
        rows = _http("GET", f"{s['url']}/rest/v1/records?{query}", headers) or []
        for r in rows:
            data = r.get("data") or {}
            if r.get("deleted") or data.get("deleted"):
                continue
            out.append({**data, "id": r["id"]})
        if len(rows) < PAGE:
            return out
        offset += PAGE


def fetch_expenses(s=None):
    s = s or settings()
    return _records(s, _login(s), "expenses")


def options(s=None):
    """Groups and people of the Expense app, for the pickers on the phone."""
    s = s or settings()
    headers = _login(s)
    groups = [{"id": g["id"], "name": g.get("name") or "", "members": list(g.get("members") or [])}
              for g in _records(s, headers, "groups")]
    people = [{"id": p["id"], "name": p.get("name") or "", "isUser": bool(p.get("isUser"))}
              for p in _records(s, headers, "people")]
    return {"groups": groups, "people": people}


def _day(ms):
    return datetime.fromtimestamp(ms / 1000, IST).date()


def _allocate(amount, n):
    base, extra = divmod(amount, n)
    return [base + (1 if i < extra else 0) for i in range(n)]


def build_expense(txs, description, group, payer_id, people, now_ms):
    """The Expense expense record (same shape the Expense app stores). Pure, for testing.

    `txs` are one or more ledger rows paid for together; the expense is their total.
    """
    txs = sorted(txs, key=lambda t: int(t["timestamp"]))
    amount = sum(int(t["amount_minor"]) for t in txs)
    if group:
        others = [m for m in group["members"] if m != payer_id]
        if payer_id not in group["members"]:
            raise ExpenseError("The payer is not a member of that group")
    else:
        others = [p["id"] for p in people if p["isUser"] and p["id"] != payer_id]
    if not others:
        raise ExpenseError("There is nobody else to owe this amount")
    lines = [ln.strip() for ln in str(description or "").splitlines() if ln.strip()]
    if not lines:
        names = [str(t["merchant_or_payee"] or "").strip() for t in txs]
        lines = [", ".join(dict.fromkeys(n for n in names if n)) or "Expense"]
    title = ", ".join(lines)
    if len(title) > DESCRIPTION_LIMIT:
        title = title[:DESCRIPTION_LIMIT - 1].rstrip() + "…"
    ids = [str(t["id"]) for t in txs]
    shares = dict(zip(others, _allocate(amount, len(others))))
    return {
        "id": "led-" + hashlib.sha1("|".join(sorted(ids)).encode()).hexdigest()[:20],
        "createdBy": payer_id, "description": title, "amount": amount, "payer": payer_id,
        "groupId": group["id"] if group else None, "date": _day(int(txs[-1]["timestamp"])).isoformat(),
        "notes": "\n".join(lines) if len(lines) > 1 or title != lines[0] else "",
        "splitType": "equal", "splitValues": {}, "splits": shares,
        "ledgerId": ids[0], "ledgerIds": ids,
        "deleted": False, "createdAt": now_ms, "updatedAt": now_ms,
    }


def _upsert(s, headers, record, deleted=False):
    body = [{"coll": "expenses", "id": record["id"], "data": record,
             "updated_at": record["updatedAt"], "deleted": deleted}]
    _http("POST", f"{s['url']}/rest/v1/records?on_conflict=coll,id",
          {**headers, "Prefer": "resolution=merge-duplicates,return=minimal"}, json.dumps(body).encode())


def eligible_rows(currency="INR"):
    """Canonical ledger rows that raise Splitwise (a debit that is not an own-account transfer)."""
    with connection() as conn:
        out = {}
        for r, delta in _ledger_splitwise_entries(conn, currency):
            if delta > 0:
                out[r["id"]] = {
                    "id": r["id"], "timestamp": int(r["timestamp"]), "amountMinor": int(r["amount_minor"]),
                    "deltaMinor": int(delta), "description": r["merchant_or_payee"],
                    "category": r["category"], "bank": r["bank"], "last4": r["account_last4"],
                    "accountType": r["account_type"],
                }
        return out


def _canonical_row(transaction_id):
    with connection() as conn:
        row = _resolve_transaction_row(conn, transaction_id)
        return dict(row) if row else None


def link_info(transaction_id, currency="INR"):
    """Local state for one transaction: can it be added, is it already, was it skipped."""
    row = _canonical_row(transaction_id)
    out = {"configured": configured(), "eligible": False, "linked": False, "handled": False}
    if not row:
        return out
    tid = row["id"]
    out["transactionId"] = tid
    out["eligible"] = tid in eligible_rows(currency)
    with connection() as conn:
        link = conn.execute("SELECT * FROM expense_links WHERE transaction_id=?", (tid,)).fetchone()
        out["handled"] = conn.execute(
            "SELECT 1 FROM expense_handled WHERE transaction_id=?", (tid,)).fetchone() is not None
    if link:
        with connection() as conn:
            together = conn.execute(
                "SELECT COUNT(*) c, SUM(amount_minor) a FROM expense_links WHERE expense_id=?", (link["expense_id"],)).fetchone()
        out.update(linked=True, description=link["description"], groupId=link["group_id"], payerId=link["payer_id"],
                   together=int(together["c"]), togetherMinor=int(together["a"] or 0))
    return out


def add_expense(transaction_ids, description, group_id, payer_id, now_ms=None):
    """One Expense expense for one or more ledger rows (their sum). Re-adding the same rows updates it."""
    if isinstance(transaction_ids, str):
        transaction_ids = [transaction_ids]
    s = settings()
    rows, seen = [], set()
    for tid in transaction_ids:
        row = _canonical_row(tid)
        if not row or row["status"] != "ACTIVE":
            raise ExpenseError("Transaction not found")
        if row["id"] not in seen:
            seen.add(row["id"])
            rows.append(row)
    if not rows:
        raise ExpenseError("Select at least one transaction")
    eligible = eligible_rows()
    not_counted = [r["id"] for r in rows if r["id"] not in eligible]
    if not_counted:
        raise ExpenseError("Only expenses that count toward Splitwise can be added (not credits, transfers or OTHER)")
    headers = _login(s)
    now_ms = now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)
    groups, people_raw = _records(s, headers, "groups"), _records(s, headers, "people")
    group = next((g for g in groups if g["id"] == group_id), None) if group_id else None
    if group_id and group is None:
        raise ExpenseError("That group no longer exists in Expense")
    group = {"id": group["id"], "members": list(group.get("members") or [])} if group else None
    people = [{"id": p["id"], "isUser": bool(p.get("isUser"))} for p in people_raw]
    record = build_expense(rows, description, group, payer_id, people, now_ms)
    with connection() as conn:  # rows leaving an older, different combination: drop that expense
        old_ids = {l["expense_id"] for l in conn.execute(
            "SELECT DISTINCT expense_id FROM expense_links WHERE transaction_id IN (%s)" % ",".join("?" * len(rows)),
            [r["id"] for r in rows])} - {record["id"]}
    _upsert(s, headers, record)
    for old in old_ids:
        _upsert(s, headers, {"id": old, "deleted": True, "updatedAt": now_ms}, deleted=True)
    with connection() as conn:
        for old in old_ids:
            conn.execute("DELETE FROM expense_links WHERE expense_id=?", (old,))
        for r in rows:
            conn.execute(
                "INSERT OR REPLACE INTO expense_links(transaction_id,expense_id,description,group_id,payer_id,amount_minor,created_at)"
                " VALUES(?,?,?,?,?,?,?)",
                (r["id"], record["id"], str(description or ""), group_id, payer_id, int(r["amount_minor"]), now_ms))
            conn.execute("DELETE FROM expense_handled WHERE transaction_id=?", (r["id"],))
    return {"status": "ok", "expenseId": record["id"], "description": record["description"],
            "count": len(rows), "amountMinor": record["amount"]}


def remove_expense(transaction_id, now_ms=None):
    """Undo: mark the Expense expense deleted (the Expense app syncs the deletion) and unlink all its rows."""
    s = settings()
    row = _canonical_row(transaction_id)
    if not row:
        raise ExpenseError("Transaction not found")
    with connection() as conn:
        link = conn.execute("SELECT expense_id FROM expense_links WHERE transaction_id=?", (row["id"],)).fetchone()
    if not link:
        return {"status": "not_linked"}
    now_ms = now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)
    _upsert(s, _login(s), {"id": link["expense_id"], "deleted": True, "updatedAt": now_ms}, deleted=True)
    with connection() as conn:
        conn.execute("DELETE FROM expense_links WHERE expense_id=?", (link["expense_id"],))
    return {"status": "removed"}


def mark_handled(transaction_id, handled=True, now_ms=None):
    """Keep a row out of the "not in Expense yet" list (entered by hand, or not meant for Splitwise)."""
    row = _canonical_row(transaction_id)
    if not row:
        raise ExpenseError("Transaction not found")
    with connection() as conn:
        if handled:
            conn.execute("INSERT OR REPLACE INTO expense_handled(transaction_id,created_at) VALUES(?,?)",
                         (row["id"], now_ms or int(datetime.now(timezone.utc).timestamp() * 1000)))
        else:
            conn.execute("DELETE FROM expense_handled WHERE transaction_id=?", (row["id"],))
    return {"status": "ok", "handled": bool(handled)}


def reconcile(rows, linked_ids, handled_ids, since_ms):
    """rows: eligible ledger rows. Returns (linked, handled, missing) for rows on/after since_ms."""
    linked = handled = 0
    missing = []
    for r in sorted((r for r in rows if r["timestamp"] >= since_ms), key=lambda r: r["timestamp"]):
        if r["id"] in handled_ids:
            handled += 1
        elif r["id"] in linked_ids:
            linked += 1
        else:
            missing.append(r)
    return linked, handled, missing


def check(currency="INR", expenses=None):
    s = settings()
    result = {"configured": configured(s), "since": s["from"], "error": None,
              "matched": 0, "handled": 0, "missing": []}
    if not result["configured"]:
        return result
    try:
        expense = fetch_expenses(s) if expenses is None else expenses
        linked_ids = set()
        for e in expense:
            linked_ids.update(e.get("ledgerIds") or ([e["ledgerId"]] if e.get("ledgerId") else []))
        with connection() as conn:
            handled_ids = {r["transaction_id"] for r in conn.execute("SELECT transaction_id FROM expense_handled")}
        linked, handled, missing = reconcile(
            eligible_rows(currency).values(), linked_ids, handled_ids, track_from_ms(s["from"]))
    except Exception as e:  # a down or misconfigured Expense must never break the audit
        result["error"] = str(e)[:200]
        return result
    result["matched"], result["handled"] = linked, handled
    result["missing"] = list(reversed(missing))
    return result
