"""Compare the ledger with the expenses in the personal Splits app (Supabase).

The finance app only reads Splits. It signs in with its own Supabase user (SPLITS_EMAIL /
SPLITS_PASSWORD in the server .env, never in the repo) and lists the expense records.

Ledger rows that raise Splitwise (delta > 0) and have no matching Splits expense are reported
as "missing", so a forgotten entry is a list instead of a guessing game. A Splits expense that
has no ledger row (a cash spend, say) is reported as "extra".

Matching is one-to-one: same amount, dates within MATCH_DAYS of each other. When several
candidates exist, a description that shares a word with the merchant wins, then the nearest
date. An expense carrying `ledgerId` is matched to that row directly.
"""
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

from .db import connection
from .ledger.splitwise import _ledger_splitwise_entries

IST = timezone(timedelta(hours=5, minutes=30))
MATCH_DAYS = 2
DEFAULT_TRACK_FROM = "2026-11-25"
PAGE = 1000


def settings():
    return {
        "url": os.getenv("SPLITS_SUPABASE_URL", "").strip().rstrip("/"),
        "key": os.getenv("SPLITS_SUPABASE_KEY", "").strip(),
        "email": os.getenv("SPLITS_EMAIL", "").strip(),
        "password": os.getenv("SPLITS_PASSWORD", ""),
        "from": os.getenv("SPLITS_TRACK_FROM", "").strip() or DEFAULT_TRACK_FROM,
    }


def configured(s=None):
    s = s or settings()
    return all(s[k] for k in ("url", "key", "email", "password"))


def track_from_ms(value):
    day = datetime.strptime(value, "%Y-%m-%d").replace(tzinfo=IST)
    return int(day.timestamp() * 1000)


def _http(method, url, headers, body=None):
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return json.loads(r.read().decode() or "null")
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Splits HTTP {e.code}: {e.read().decode(errors='replace')[:160]}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"Splits unreachable: {e.reason}") from e


def fetch_expenses(s=None):
    """Active (not deleted) expense records of the Splits app."""
    s = s or settings()
    login = _http(
        "POST",
        f"{s['url']}/auth/v1/token?grant_type=password",
        {"apikey": s["key"], "Content-Type": "application/json"},
        json.dumps({"email": s["email"], "password": s["password"]}).encode(),
    )
    headers = {"apikey": s["key"], "Authorization": f"Bearer {login['access_token']}"}
    out, offset = [], 0
    while True:
        query = urllib.parse.urlencode(
            {"select": "id,data,deleted", "coll": "eq.expenses", "order": "server_ts.asc",
             "limit": PAGE, "offset": offset}
        )
        rows = _http("GET", f"{s['url']}/rest/v1/records?{query}", headers) or []
        for r in rows:
            data = r.get("data") or {}
            if r.get("deleted") or data.get("deleted"):
                continue
            out.append({**data, "id": r["id"]})
        if len(rows) < PAGE:
            return out
        offset += PAGE


def _words(text):
    return {w for w in re.findall(r"[a-z0-9]{3,}", str(text or "").lower())}


def _day(ms):
    return datetime.fromtimestamp(ms / 1000, IST).date()


def reconcile(rows, expenses, since_ms):
    """rows: dicts with id, timestamp, amountMinor, deltaMinor, description (ledger side).
    expenses: Splits expense dicts. Returns (matched, missing, extra)."""
    rows = sorted((r for r in rows if r["timestamp"] >= since_ms), key=lambda r: r["timestamp"])
    pool = [e for e in expenses if e.get("date")]
    used, matched, missing = set(), 0, []

    for r in rows:  # direct links first
        link = next((e for e in pool if e.get("ledgerId") == r["id"] and e["id"] not in used), None)
        if link:
            used.add(link["id"])
            r["_matched"] = True
            matched += 1

    # Every possible pair, best first (shared words, then nearest date), assigned greedily so a
    # strong match is never taken by a weaker row that merely came earlier.
    pairs = []
    for ri, r in enumerate(rows):
        if r.get("_matched"):
            continue
        amounts = {int(r["amountMinor"]), int(r["deltaMinor"])}
        day = _day(r["timestamp"])
        for e in pool:
            if e["id"] in used or int(e.get("amount") or 0) not in amounts:
                continue
            gap = abs((datetime.strptime(e["date"], "%Y-%m-%d").date() - day).days)
            if gap <= MATCH_DAYS:
                shared = len(_words(e.get("description")) & _words(r["description"]))
                pairs.append((-shared, gap, ri, e["id"]))
    done = set()
    for _shared, _gap, ri, eid in sorted(pairs):
        if ri in done or eid in used:
            continue
        done.add(ri)
        used.add(eid)
        matched += 1
    missing = [r for ri, r in enumerate(rows) if not r.get("_matched") and ri not in done]

    since_day = _day(since_ms)
    extra = [
        e for e in pool
        if e["id"] not in used and datetime.strptime(e["date"], "%Y-%m-%d").date() >= since_day
    ]
    return matched, missing, extra


def ledger_rows(currency="INR"):
    with connection() as conn:
        out = []
        for r, delta in _ledger_splitwise_entries(conn, currency):
            if delta <= 0:
                continue
            out.append({
                "id": r["id"], "timestamp": int(r["timestamp"]), "amountMinor": int(r["amount_minor"]),
                "deltaMinor": int(delta), "description": r["merchant_or_payee"],
                "category": r["category"], "bank": r["bank"], "last4": r["account_last4"],
                "accountType": r["account_type"],
            })
        return out


def check(currency="INR", expenses=None):
    s = settings()
    result = {"configured": configured(s), "since": s["from"], "error": None,
              "matched": 0, "missing": [], "extra": []}
    if not result["configured"]:
        return result
    try:
        since = track_from_ms(s["from"])
        splits = fetch_expenses(s) if expenses is None else expenses
        matched, missing, extra = reconcile(ledger_rows(currency), splits, since)
    except Exception as e:  # a down or misconfigured Splits must never break the audit
        result["error"] = str(e)[:200]
        return result
    result["matched"] = matched
    result["missing"] = [{k: v for k, v in r.items() if not k.startswith("_")} for r in reversed(missing)]
    result["extra"] = [
        {"id": e["id"], "description": e.get("description"), "amountMinor": int(e.get("amount") or 0), "date": e["date"]}
        for e in extra
    ]
    return result
