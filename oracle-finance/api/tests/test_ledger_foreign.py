"""Foreign-currency spends: INR estimate, exact-rupee correction, dedup across sources."""
from app.db import connection
from app.ledger import set_balance
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)


class _Tx:
    """Pydantic-like holder with model_copy, as sync_transaction expects."""
    def __init__(self, **kw):
        self.__dict__.update(kw)

    def model_copy(self, update=None):
        data = dict(self.__dict__)
        data.update(update or {})
        return _Tx(**data)


def _usd_tx(tx_id, amount_minor=29246, ts=1792300000000):
    return _Tx(
        id=tx_id, amountMinor=amount_minor, currency="USD", type="DEBIT",
        paymentMethod="CARD", accountType="CREDIT_CARD", bank="AXIS",
        merchantOrPayee="OPENAI", accountLast4="9206", reference=None,
        timestamp=ts, category="SUBSCRIPTION", confidence=0.95,
    )


def test_usd_card_spend_is_converted_and_moves_card_and_splitwise(monkeypatch):
    from app import fx
    from app.ledger import sync_transaction, get_manual_splitwise_total

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    set_balance("airtel-card-9206", "Airtel Axis", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    before = get_manual_splitwise_total("INR")

    sync_transaction(_usd_tx("usd-1"))

    # 292.46 USD * 88.0 * 1.0413 = 26_799.35 INR (rounded to paise)
    expected = round(29246 * 88.0 * 1.0413)
    with connection() as conn:
        row = conn.execute(
            "SELECT amount_minor,currency,orig_amount_minor,orig_currency FROM transactions WHERE id='usd-1'"
        ).fetchone()
        card = conn.execute(
            "SELECT balance_minor FROM accounts WHERE id='airtel-card-9206'"
        ).fetchone()["balance_minor"]
    assert row["currency"] == "INR"
    assert abs(row["amount_minor"] - expected) <= 1
    assert row["orig_amount_minor"] == 29246 and row["orig_currency"] == "USD"
    assert abs(card - expected) <= 1
    after = get_manual_splitwise_total("INR")
    assert after is not None
    assert abs((after or 0) - (before or 0) - expected) <= 1


def test_same_usd_spend_from_two_sources_converts_identically(monkeypatch):
    from app import fx

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    first = fx.to_inr(20570, "USD", 1792300000000)
    second = fx.to_inr(20570, "USD", 1792300005000)
    assert first == second


def test_fx_falls_back_when_rate_lookup_fails(monkeypatch):
    from app import fx

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: None)
    inr, _ = fx.to_inr(10000, "USD", 1792300000000)
    assert inr == round(10000 * fx.FALLBACK_INR_PER_UNIT["USD"] * 1.0413)
    assert fx.to_inr(10000, "INR", 1792300000000) is None
    assert fx.to_inr(10000, "XYZ", 1792300000000) is None


def test_migration_converts_existing_usd_rows_once(monkeypatch):
    from app import fx
    from app.ledger import migrate_foreign_currency_rows

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    set_balance("airtel-card-9206", "Airtel Axis", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    import time
    now = int(time.time() * 1000) + 60_000  # created after the card's last reconciliation
    with connection() as conn:
        conn.execute(
            """INSERT INTO transactions
               (id,amount_minor,currency,type,payment_method,account_type,bank,merchant_or_payee,
                account_last4,reference,timestamp,category,confidence,duplicate_of,status,created_at)
               VALUES ('old-usd',20570,'USD','DEBIT','CARD','CREDIT_CARD','AXIS','OPENAI','9206',
                       NULL,?,'SUBSCRIPTION',0.95,NULL,'ACTIVE',?)""",
            (now, now),
        )

    assert migrate_foreign_currency_rows() == 1
    assert migrate_foreign_currency_rows() == 0  # one-time

    expected = round(20570 * 88.0 * 1.0413)
    with connection() as conn:
        row = conn.execute("SELECT amount_minor,currency,orig_currency FROM transactions WHERE id='old-usd'").fetchone()
        card = conn.execute("SELECT balance_minor FROM accounts WHERE id='airtel-card-9206'").fetchone()["balance_minor"]
        adj = conn.execute("SELECT COUNT(*) v FROM balance_adjustments WHERE transaction_id='old-usd'").fetchone()["v"]
    assert row["currency"] == "INR" and row["orig_currency"] == "USD"
    assert abs(row["amount_minor"] - expected) <= 1
    assert abs(card - expected) <= 1
    assert adj == 1


def test_foreign_row_inr_correction_moves_card_and_splitwise(monkeypatch):
    from app import fx
    from app.ledger import (
        sync_transaction, set_foreign_inr_amount, foreign_amount_info,
        get_manual_splitwise_total,
    )

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    set_balance("airtel-card-9206", "Airtel Axis", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    sync_transaction(_usd_tx("usd-fix"))
    estimate = foreign_amount_info("usd-fix")
    assert estimate["foreign"] and not estimate["exact"] and estimate["origCurrency"] == "USD"
    splitwise_before = get_manual_splitwise_total("INR") or 0

    result = set_foreign_inr_amount("usd-fix", 2_500_000)  # exact charge ₹25,000.00
    assert result["status"] == "UPDATED"

    with connection() as conn:
        card = conn.execute("SELECT balance_minor FROM accounts WHERE id='airtel-card-9206'").fetchone()["balance_minor"]
        adj = conn.execute("SELECT delta_minor FROM balance_adjustments WHERE transaction_id='usd-fix'").fetchone()["delta_minor"]
    assert card == 2_500_000 and adj == 2_500_000
    splitwise_after = get_manual_splitwise_total("INR") or 0
    assert splitwise_after - splitwise_before == 2_500_000 - estimate["inrMinor"]
    assert foreign_amount_info("usd-fix")["exact"] is True

    # Re-syncing the same row (e.g. the phone resending it) must keep the exact amount.
    sync_transaction(_usd_tx("usd-fix"))
    assert foreign_amount_info("usd-fix")["inrMinor"] == 2_500_000
    with connection() as conn:
        card = conn.execute("SELECT balance_minor FROM accounts WHERE id='airtel-card-9206'").fetchone()["balance_minor"]
    assert card == 2_500_000


def test_inr_correction_refuses_rupee_rows_and_bad_amounts():
    from app.ledger import sync_transaction, set_foreign_inr_amount, foreign_amount_info

    inr = _usd_tx("inr-row")
    inr = inr.model_copy({"currency": "INR", "amountMinor": 5000})
    sync_transaction(inr)
    assert foreign_amount_info("inr-row") == {"foreign": False, "transactionId": "inr-row"}
    assert set_foreign_inr_amount("inr-row", 6000)["status"] == "NOT_FOREIGN"
    assert set_foreign_inr_amount("missing", 6000)["status"] == "NOT_FOUND"
    assert set_foreign_inr_amount("inr-row", 0)["status"] == "INVALID_AMOUNT"


def test_corrected_foreign_row_still_matches_same_spend_from_another_source(monkeypatch):
    from app import fx
    from app.ledger import _rows_are_duplicate, sync_transaction, set_foreign_inr_amount

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    set_balance("airtel-card-9206", "Airtel Axis", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    sync_transaction(_usd_tx("src-a"))
    sync_transaction(_usd_tx("gmail:src-b", ts=1792300060000))
    set_foreign_inr_amount("src-a", 2_500_000)
    with connection() as conn:
        a = conn.execute("SELECT * FROM transactions WHERE id='src-a'").fetchone()
        b = conn.execute("SELECT * FROM transactions WHERE id='gmail:src-b'").fetchone()
    assert a["amount_minor"] != b["amount_minor"]
    assert _rows_are_duplicate(a, b)


def test_foreign_amount_map_includes_duplicates_and_skips_rupee_rows(monkeypatch):
    from app import fx
    from app.ledger import sync_transaction, set_foreign_inr_amount, foreign_amount_map

    monkeypatch.setattr(fx, "_fetch_rate", lambda currency, rate_date: 88.0)
    set_balance("airtel-card-9206", "Airtel Axis", "INR", "CREDIT_CARD", "AXIS", "9206", 0)
    sync_transaction(_usd_tx("map-a"))
    sync_transaction(_usd_tx("map-inr").model_copy({"currency": "INR", "amountMinor": 5000, "merchantOrPayee": "X", "timestamp": 1790000000000}))
    with connection() as conn:
        conn.execute("INSERT INTO transactions (id,amount_minor,currency,type,payment_method,account_type,account_last4,timestamp,category,confidence,status,created_at,duplicate_of) "
                     "VALUES ('map-dup',1,'INR','DEBIT','CARD','CREDIT_CARD','9206',1,'OTHER',0.9,'VOIDED',1,'map-a')")
    set_foreign_inr_amount("map-a", 2_500_000)
    result = foreign_amount_map()
    assert "map-inr" not in result
    assert result["map-a"]["inrMinor"] == 2_500_000 and result["map-a"]["exact"] is True
    assert result["map-dup"] == result["map-a"]
