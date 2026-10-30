"""One-time data migrations, each recorded in applied_migrations."""

from ..db import connection
from .splitwise import _ledger_splitwise_total


def migrate_gmail_default_category():
    """One-time: e-mail debits created after OTHER became an opt-out.

    They were given OTHER by default and so silently dropped out of Splitwise.
    Rows created before the opt-out migration were already absorbed into the
    baseline, so only later ones are switched to the normal default. The
    server ignores category edits on gmail: rows, so no user choice is lost.
    """
    opt_out_migrated_at_ms = 1792296720000  # 2026-10-18T04:12Z
    with connection() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if conn.execute(
            "SELECT 1 FROM applied_migrations WHERE name='gmail_default_category'"
        ).fetchone():
            return 0
        changed = conn.execute(
            """UPDATE transactions SET category='GROCERIES'
               WHERE id LIKE 'gmail:%' AND UPPER(category)='OTHER' AND type='DEBIT'
                 AND created_at > ?""",
            (opt_out_migrated_at_ms,),
        ).rowcount
        conn.execute("INSERT INTO applied_migrations(name) VALUES('gmail_default_category')")
        return changed


def migrate_splitwise_other_optout():
    """One-time: keep the Splitwise value steady when OTHER became an opt-out.

    Before, every row counted toward Splitwise whatever its category; many
    older rows carry OTHER only as a default. Treating OTHER as an opt-out
    would silently subtract all of them. Shift the baseline by the difference
    so the shown value is unchanged; only later category edits move it.
    """
    with connection() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if conn.execute(
            "SELECT 1 FROM applied_migrations WHERE name='splitwise_other_optout'"
        ).fetchone():
            return False
        for row in conn.execute(
            """SELECT currency,baseline_ledger_minor FROM manual_splitwise_total
               WHERE baseline_amount_minor IS NOT NULL AND baseline_ledger_minor IS NOT NULL"""
        ).fetchall():
            cur = row["currency"]
            shift = _ledger_splitwise_total(conn, cur) - _ledger_splitwise_total(conn, cur, honor_other=False)
            conn.execute(
                "UPDATE manual_splitwise_total SET baseline_ledger_minor=? WHERE currency=?",
                (int(row["baseline_ledger_minor"]) + shift, cur),
            )
        conn.execute("INSERT INTO applied_migrations(name) VALUES('splitwise_other_optout')")
        return True


def migrate_card_bill_reread(window_days=45, now_ms_value=None):
    """One-time: re-derive recent card bills with the corrected parsers.

    Earlier parsers read some statements wrongly (an ICICI bill came out as
    1.00). A parsed attachment or e-mail is never revisited, and a wrong bill
    record could not be replaced. Clear the recent bill records and queue the
    recent statement e-mails to be read again; the Gmail scan then rebuilds them.
    """
    import time
    now = int(now_ms_value if now_ms_value is not None else time.time() * 1000)
    cutoff = now - window_days * 24 * 60 * 60 * 1000
    with connection() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if conn.execute(
            "SELECT 1 FROM applied_migrations WHERE name='card_bill_reread'"
        ).fetchone():
            return 0
        conn.execute(
            """DELETE FROM card_bill_evidence
               WHERE source_type IN ('GMAIL','GMAIL_STATEMENT_PDF') AND observed_at>=?""",
            (cutoff,),
        )
        queued = conn.execute(
            """UPDATE gmail_messages SET status='PENDING'
               WHERE status='PARSED' AND internal_date>=?
                 AND LOWER(COALESCE(subject,'')) LIKE '%statement%'""",
            (cutoff,),
        ).rowcount
        conn.execute("INSERT INTO applied_migrations(name) VALUES('card_bill_reread')")
        return queued


def migrate_splitwise_salary_optout():
    """One-time: keep the Splitwise value steady when SALARY credits became opt-out.

    A salary credit used to lower Splitwise like any other credit. Treating it
    as income would silently add back every salary already recorded. Shift the
    baseline by the difference so the shown value is unchanged; only salary
    received from now on stays out of it.
    """
    with connection() as conn:
        conn.execute("CREATE TABLE IF NOT EXISTS applied_migrations (name TEXT PRIMARY KEY)")
        if conn.execute(
            "SELECT 1 FROM applied_migrations WHERE name='splitwise_salary_optout'"
        ).fetchone():
            return False
        for row in conn.execute(
            """SELECT currency,baseline_ledger_minor FROM manual_splitwise_total
               WHERE baseline_amount_minor IS NOT NULL AND baseline_ledger_minor IS NOT NULL"""
        ).fetchall():
            cur = row["currency"]
            shift = _ledger_splitwise_total(conn, cur) - _ledger_splitwise_total(conn, cur, honor_salary=False)
            conn.execute(
                "UPDATE manual_splitwise_total SET baseline_ledger_minor=? WHERE currency=?",
                (int(row["baseline_ledger_minor"]) + shift, cur),
            )
        conn.execute("INSERT INTO applied_migrations(name) VALUES('splitwise_salary_optout')")
        return True
