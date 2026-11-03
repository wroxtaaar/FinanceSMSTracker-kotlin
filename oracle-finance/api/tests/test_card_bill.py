"""Card bill evidence: corrections, newer statements, conflicts, re-read migration."""
from app.db import connection
from app.ledger import migrate_card_bill_reread, set_balance, sync_card_bill
from ledger_fixtures import isolated_database  # noqa: F401  (autouse fixture)

T0 = 1_900_000_000_000
DAY = 24 * 60 * 60 * 1000


def _card():
    set_balance("c1012", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 0)


def _bill(key, amount, observed=T0, source="GMAIL_STATEMENT_PDF"):
    return sync_card_bill({
        "sourceType": source, "sourceKey": key, "timestamp": observed,
        "amountMinor": amount, "currency": "INR", "bank": "ICICI",
        "accountLast4": "1012", "accountLast2": "12", "confidence": 1.0,
    })


def _bill_balance():
    with connection() as conn:
        return conn.execute("SELECT bill_balance_minor FROM accounts WHERE id='c1012'").fetchone()[0]


def test_rereading_the_same_source_replaces_a_misparsed_amount():
    _card()
    assert _bill("att-1", 100)["status"] == "APPLIED"
    assert _bill_balance() == 100

    # Same attachment, corrected parser.
    result = _bill("att-1", 208800)

    assert result["status"] == "APPLIED"
    assert _bill_balance() == 208800


def test_a_newer_statement_replaces_the_previous_one_even_at_the_same_priority():
    _card()
    _bill("sept", 150000, observed=T0)
    result = _bill("oct", 208800, observed=T0 + 30 * DAY)

    assert result["status"] == "APPLIED"
    assert _bill_balance() == 208800


def test_two_sources_disagreeing_about_the_same_statement_still_go_to_review():
    _card()
    _bill("att", 100, observed=T0, source="GMAIL")
    result = _bill("mail", 208800, observed=T0 + 60_000, source="GMAIL")

    assert result["status"] == "REVIEW"
    assert _bill_balance() == 100


def test_a_weaker_source_cannot_overwrite_a_stronger_one_for_the_same_statement():
    _card()
    _bill("att", 208800, observed=T0, source="GMAIL_STATEMENT_PDF")
    result = _bill("sms", 100, observed=T0 + 60_000, source="SMS")

    assert result["status"] == "RETAINED_LOWER_PRIORITY"
    assert _bill_balance() == 208800


def test_reread_migration_clears_recent_bills_and_requeues_statement_mails_once():
    _card()
    _bill("recent", 100, observed=T0)
    _bill("old", 5000, observed=T0 - 100 * DAY)
    with connection() as conn:
        for mid, subject, when in (
            ("m-recent", "Credit Card Statement for the period", T0),
            ("m-old", "Credit Card Statement for the period", T0 - 100 * DAY),
            ("m-other", "Transaction alert", T0),
        ):
            conn.execute(
                "INSERT INTO gmail_messages(id,internal_date,subject,fingerprint,status,created_at)"
                " VALUES(?,?,?,?,'PARSED',?)",
                (mid, when, subject, "fp-" + mid, when),
            )

    queued = migrate_card_bill_reread(now_ms_value=T0 + DAY)

    assert queued == 1
    with connection() as conn:
        status = dict(conn.execute("SELECT id,status FROM gmail_messages").fetchall())
        keys = {r[0] for r in conn.execute("SELECT source_key FROM card_bill_evidence").fetchall()}
    assert status == {"m-recent": "PENDING", "m-old": "PARSED", "m-other": "PARSED"}
    assert keys == {"old"}
    assert migrate_card_bill_reread(now_ms_value=T0 + DAY) == 0  # runs once


def test_bill_is_re_derived_from_an_already_parsed_pdf():
    from app.statement_sync import common, processing as ss

    _card()
    calls = {"n": 0}
    saved = (ss._parse_statement_pdf, common._pdf_text, ss._statement_keys)
    ss._statement_keys = lambda bank: ["k"]
    ss._parse_statement_pdf = lambda bank, b, key: (
        {"bank": "ICICI", "account_type": "CREDIT_CARD", "account_last4": "1012", "currency": "INR"}, [])
    def fake_text(b, key):
        calls["n"] += 1
        return "Total Amount due\n`2,088.00 = + + -"
    common._pdf_text = fake_text
    try:
        message = {"id": "m1", "internalDate": str(T0)}
        ss._refresh_card_bill_from_parsed_pdf("ICICI", b"pdf", "att-1", message)
        assert _bill_balance() == 208800
        # Re-read on every sync (idempotent): the printed bill keeps winning.
        ss._refresh_card_bill_from_parsed_pdf("ICICI", b"pdf", "att-1", message)
        assert calls["n"] == 2
        assert _bill_balance() == 208800
    finally:
        ss._parse_statement_pdf, common._pdf_text, ss._statement_keys = saved


def test_the_statement_pdf_beats_the_email_body_for_the_same_statement():
    _card()
    _bill("mail", 150, observed=T0, source="GMAIL")
    assert _bill("att", 208800, observed=T0 + 60_000, source="GMAIL_STATEMENT_PDF")["status"] == "APPLIED"
    assert _bill_balance() == 208800
    # ...and the e-mail cannot take it back on a later rescan.
    assert _bill("mail", 150, observed=T0, source="GMAIL")["status"] == "RETAINED_LOWER_PRIORITY"
    assert _bill_balance() == 208800


def test_a_rescan_overrides_a_manually_typed_bill():
    _card()
    _bill("att", 208800, observed=T0)
    set_balance("c1012", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 300000, 5000)
    assert _bill_balance() == 5000
    assert _bill("att", 208800, observed=T0)["status"] == "APPLIED"
    assert _bill_balance() == 208800


def test_a_bill_larger_than_the_tracked_outstanding_raises_it_so_active_spend_is_zero():
    _card()
    assert _bill("att", 208800)["status"] == "APPLIED"
    with connection() as conn:
        row = conn.execute("SELECT balance_minor,bill_balance_minor,opening_balance_minor FROM accounts WHERE id='c1012'").fetchone()
        applied = conn.execute("SELECT COALESCE(SUM(delta_minor),0) FROM balance_adjustments WHERE account_id='c1012'").fetchone()[0]
    assert row["bill_balance_minor"] == 208800
    assert row["balance_minor"] == 208800  # active spend 0, not negative
    assert row["balance_minor"] == row["opening_balance_minor"] + applied  # audit stays clean


def _payment(ts, amount=100000):
    from types import SimpleNamespace
    from app.ledger import sync_transaction
    t = SimpleNamespace(
        id=f"pay-{ts}", amountMinor=amount, currency="INR", type="CREDIT", paymentMethod="BANK_TRANSFER",
        accountType="CREDIT_CARD", bank="ICICI", merchantOrPayee="Credit Card Bill Payment",
        accountLast4="1012", reference=None, timestamp=ts, category="BILL_PAYMENT", confidence=0.95)
    sync_transaction(t)


def test_a_bill_payment_locks_the_bill_against_rescans_of_the_paid_statement():
    import time
    _card()
    now = int(time.time() * 1000)
    _bill("att", 208800, observed=now - 2 * DAY)
    _payment(now - DAY)
    assert _bill_balance() == 108800
    assert _bill("att", 208800, observed=now - 2 * DAY)["status"] == "RETAINED_BILL_LOCKED"
    assert _bill_balance() == 108800
    # The next cycle's statement is newer than the payment, so it still applies.
    assert _bill("next", 5000, observed=now)["status"] == "APPLIED"
    assert _bill_balance() == 5000


def test_pdf_bill_extraction_ignores_years_and_reads_label_value_tables():
    from app.statement_sync.common import _extract_statement_bill_amount as extract

    # A year after the label must never be taken as the bill.
    assert extract("Total Amount Due Statement Date 02 Nov 2026 Payment Due Date 21 Nov 2026") is None
    # Label cells followed by value cells (no currency sign).
    assert extract("Total Amount Due (Rs) Minimum Amount Due (Rs) Payment Due Date 1,770.00 200.00 21-Nov-2026") == 177000
    # HDFC cards call it "Total Dues"; Indian digit grouping.
    assert extract("Total Dues Minimum Amount Due 1,79,761.31 5,000.00") == 17976131
    # Existing ICICI form still works.
    assert extract("Total Amount due\n`2,088.00 = + + -") == 208800


def test_pdf_rederive_migration_drops_only_recent_pdf_evidence_once():
    from app.ledger import migrations

    _card()
    _bill("recent-pdf", 202600, observed=T0, source="GMAIL_STATEMENT_PDF")
    _bill("old-pdf", 5000, observed=T0 - 30 * DAY, source="GMAIL_STATEMENT_PDF")
    _bill("mail", 177000, observed=T0 + 1000, source="GMAIL")
    assert migrations.migrate_card_bill_pdf_rederive(now_ms_value=T0 + DAY) == 1
    with connection() as conn:
        keys = {r[0] for r in conn.execute("SELECT source_key FROM card_bill_evidence").fetchall()}
    assert keys == {"old-pdf", "mail"}
    assert migrations.migrate_card_bill_pdf_rederive(now_ms_value=T0 + DAY) == 0


def test_paying_more_than_the_bill_drives_active_spend_negative():
    set_balance("c1012", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 300_000, 200_000)
    _payment(1_900_000_000_000 + DAY, amount=500_000)
    with connection() as conn:
        row = conn.execute("SELECT balance_minor,bill_balance_minor FROM accounts WHERE id='c1012'").fetchone()
    assert row["bill_balance_minor"] == 0
    assert row["balance_minor"] == -200_000  # active spend -2,000: a credit


def test_manual_save_keeps_a_negative_active_spend():
    set_balance("c1012", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 50_000, 200_000)
    with connection() as conn:
        row = conn.execute("SELECT balance_minor,bill_balance_minor FROM accounts WHERE id='c1012'").fetchone()
    assert (row["balance_minor"], row["bill_balance_minor"]) == (50_000, 200_000)  # active -1,500


def test_spend_after_the_bill_stays_in_active_spend_when_balance_is_raised():
    from types import SimpleNamespace
    from app.ledger import sync_transaction

    set_balance("c1012", "ICICI Credit Card", "INR", "CREDIT_CARD", "ICICI", "1012", 0)
    sync_transaction(SimpleNamespace(
        id="spend-1", amountMinor=1_079_600, currency="INR", type="DEBIT", paymentMethod="CARD",
        accountType="CREDIT_CARD", bank="ICICI", merchantOrPayee="GPAY UTILIT", accountLast4="1012",
        reference=None, timestamp=T0 + DAY, category="BILLS", confidence=0.99))
    assert _bill("stmt", 10_000_000, observed=T0)["status"] == "APPLIED"
    with connection() as conn:
        assert conn.execute("SELECT balance_minor FROM accounts WHERE id='c1012'").fetchone()[0] == 11_079_600
    _bill("stmt", 10_000_000, observed=T0)  # re-reading is stable
    with connection() as conn:
        assert conn.execute("SELECT balance_minor FROM accounts WHERE id='c1012'").fetchone()[0] == 11_079_600
