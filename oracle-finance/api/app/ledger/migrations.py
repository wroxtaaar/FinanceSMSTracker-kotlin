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
