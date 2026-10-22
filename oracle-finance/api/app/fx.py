"""Foreign-currency spends -> INR estimate.

A card SMS or e-mail for a foreign spend only states the foreign amount; the
rupee charge is known later. To let such spends move card outstanding, bank
balance and Splitwise right away, they are converted once, on arrival, using
the market rate for the transaction's (IST) day plus the card's forex markup.
The rate is cached per currency+day, so every source that reports the same
spend (SMS, notification, e-mail) converts to the identical INR amount and the
normal duplicate matching keeps working.
"""
import json
import os
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP

from .db import connection

IST = timezone(timedelta(hours=5, minutes=30))

# Rates used only if the live lookup fails and nothing is cached yet.
FALLBACK_INR_PER_UNIT = {
    "USD": 88.0, "EUR": 95.0, "GBP": 112.0, "SGD": 68.0,
    "AUD": 58.0, "CAD": 64.0, "AED": 24.0,
}


def _markup_multiplier():
    # Typical card forex markup 3.5% + 18% GST on it = 4.13%.
    pct = float(os.environ.get("FX_MARKUP_PCT", "4.13"))
    return Decimal(str(1 + pct / 100))


def _ensure_table(conn):
    conn.execute(
        """CREATE TABLE IF NOT EXISTS fx_rates (
            currency TEXT NOT NULL, rate_date TEXT NOT NULL, inr_per_unit REAL NOT NULL,
            source TEXT NOT NULL, created_at INTEGER NOT NULL,
            PRIMARY KEY (currency, rate_date))"""
    )


def _fetch_rate(currency, rate_date):
    """Market INR per 1 unit of currency on rate_date, or None if unavailable."""
    url = f"https://api.frankfurter.dev/v1/{rate_date}?base={currency}&symbols=INR"
    try:
        with urllib.request.urlopen(url, timeout=6) as resp:
            return float(json.loads(resp.read().decode())["rates"]["INR"])
    except Exception:
        return None


def _rate_for(currency, rate_date):
    with connection() as conn:
        _ensure_table(conn)
        row = conn.execute(
            "SELECT inr_per_unit FROM fx_rates WHERE currency=? AND rate_date=?",
            (currency, rate_date),
        ).fetchone()
        if row:
            return float(row["inr_per_unit"])

        rate, source = _fetch_rate(currency, rate_date), "frankfurter"
        if rate is None:
            last = conn.execute(
                "SELECT inr_per_unit FROM fx_rates WHERE currency=? AND source='frankfurter' "
                "ORDER BY rate_date DESC LIMIT 1",
                (currency,),
            ).fetchone()
            if last:
                rate, source = float(last["inr_per_unit"]), "last-known"
            else:
                rate, source = FALLBACK_INR_PER_UNIT.get(currency), "fallback"
        if rate is None:
            return None
        # Cache even a fallback so the same day always converts the same way.
        conn.execute(
            "INSERT OR IGNORE INTO fx_rates(currency,rate_date,inr_per_unit,source,created_at) "
            "VALUES (?,?,?,?,?)",
            (currency, rate_date, rate, source, int(datetime.now().timestamp() * 1000)),
        )
        return rate


def to_inr(amount_minor, currency, timestamp_ms):
    """(inr_minor, rate_used) for a foreign amount, or None if currency is INR/unknown."""
    cur = str(currency or "").strip().upper()
    if not cur or cur == "INR":
        return None
    rate_date = datetime.fromtimestamp(int(timestamp_ms) / 1000, IST).strftime("%Y-%m-%d")
    rate = _rate_for(cur, rate_date)
    if rate is None:
        return None
    effective = Decimal(str(rate)) * _markup_multiplier()
    inr = (Decimal(int(amount_minor)) * effective).quantize(Decimal("1"), rounding=ROUND_HALF_UP)
    return int(inr), float(effective)
