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
    _bill("att", 100, observed=T0, source="GMAIL_STATEMENT_PDF")
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
    from app import statement_sync as ss

    _card()
    calls = {"n": 0}
    saved = (ss._parse_statement_pdf, ss._pdf_text, ss._statement_keys)
    ss._statement_keys = lambda bank: ["k"]
    ss._parse_statement_pdf = lambda bank, b, key: (
        {"bank": "ICICI", "account_type": "CREDIT_CARD", "account_last4": "1012", "currency": "INR"}, [])
    def fake_text(b, key):
        calls["n"] += 1
        return "Total Amount due\n`2,088.00 = + + -"
    ss._pdf_text = fake_text
    try:
        message = {"id": "m1", "internalDate": str(T0)}
        ss._refresh_card_bill_from_parsed_pdf("ICICI", b"pdf", "att-1", message)
        assert _bill_balance() == 208800
        # Already recorded: no second parse.
        ss._refresh_card_bill_from_parsed_pdf("ICICI", b"pdf", "att-1", message)
        assert calls["n"] == 1
    finally:
        ss._parse_statement_pdf, ss._pdf_text, ss._statement_keys = saved
