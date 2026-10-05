import time
import re
from .db import connection

def now_ms(): return int(time.time()*1000)

def normalize_reference(value):
    if value is None:
        return None
    raw = str(value).strip()
    if not raw or raw.lower() in ("null", "none"):
        return None
    raw = re.sub(r"(?i)^\s*Transaction\s+Info\s*:\s*", "", raw).strip()
    match = re.match(r"(?i)^UPI/[^/\s]+/([^/\s]+)", raw)
    if match:
        raw = match.group(1)
    raw = raw.strip(".,;:)]").strip()
    return raw.upper() or None

def splitwise_delta(account_type, transaction_type, amount_minor, category):
    """Return the signed Splitwise contribution for one ledger transaction.

    Bank accounts move opposite to Splitwise; credit cards move in the same
    direction. OTHER remains the explicit Splitwise opt-out.
    """
    if str(category or "").strip().upper() == "OTHER":
        return 0

    amount = int(amount_minor)
    account_type = str(account_type or "").strip().upper()
    transaction_type = str(transaction_type or "").strip().upper()

    if account_type == "BANK_ACCOUNT":
        return amount if transaction_type == "DEBIT" else -amount if transaction_type == "CREDIT" else 0
    if account_type == "CREDIT_CARD":
        return -amount if transaction_type == "DEBIT" else amount if transaction_type == "CREDIT" else 0
    return 0

def splitwise_delta_for_transaction(t):
    return splitwise_delta(t.accountType, t.type, t.amountMinor, t.category)


def is_provisional_unresolved_transaction(t):
    """Return True for a fast notification row that is not authoritative yet.

    These rows can have a useful account last-4 but no bank/reference and an
    UNKNOWN payment method. They are intentionally stored for later Gmail
    enrichment, but must not affect the account balance or Splitwise until the
    authoritative bank evidence arrives.
    """
    bank = str(getattr(t, "bank", None) or "").strip()
    reference = str(getattr(t, "reference", None) or "").strip()
    payment_method = str(getattr(t, "paymentMethod", None) or "").strip().upper()
    account_type = str(getattr(t, "accountType", None) or "").strip().upper()
    last4 = str(
        getattr(t, "accountLast4", None)
        or getattr(t, "accountLastFour", None)
        or ""
    ).strip()
    return (
        not bank
        and not reference
        and payment_method == "UNKNOWN"
        and account_type in ("BANK_ACCOUNT", "CREDIT_CARD")
        and bool(last4)
    )


def is_provisional_row(row):
    """Same unresolved-notification check for an existing SQLite row."""
    bank = str(row["bank"] or "").strip()
    reference = str(row["reference"] or "").strip()
    payment_method = str(row["payment_method"] or "").strip().upper()
    account_type = str(row["account_type"] or "").strip().upper()
    last4 = str(row["account_last4"] or "").strip()
    return (
        not bank
        and not reference
        and payment_method == "UNKNOWN"
        and account_type in ("BANK_ACCOUNT", "CREDIT_CARD")
        and bool(last4)
    )



def _merchant_values_compatible(first, second):
    a = str(first or "").strip().upper()
    b = str(second or "").strip().upper()
    if not a or not b or a == "-" or b == "-":
        return True

    # Bank-generated notifications sometimes use the issuer name itself as
    # the payee/merchant (for example "HDFC Bank"), while the SMS/email side
    # contains the actual counterparty ("ABDUL WASIQ"). That label is not
    # transaction identity and must not prevent same-side duplicate collapse.
    generic_merchant_labels = {
        "HDFC BANK",
        "AXIS BANK",
        "ICICI BANK",
        "SBI BANK",
        "STATE BANK OF INDIA",
        "HSBC BANK",
        "INDUSIND BANK",
    }
    if a in generic_merchant_labels or b in generic_merchant_labels:
        return True

    return a == b or a in b or b in a



def _rows_are_duplicate(first, second):
    """Return True only for a same-side transaction represented more than once.

    Exact normalized references are the strongest identity. When only one side
    has a reference, allow a conservative fallback when the same bank is known,
    account identity is compatible, payment methods do not contradict, and the
    events are within two minutes. Opposite directions are never duplicates.
    """
    if int(first["amount_minor"]) != int(second["amount_minor"]):
        return False
    if str(first["currency"]).strip().upper() != str(second["currency"]).strip().upper():
        return False
    if str(first["type"]).strip().upper() != str(second["type"]).strip().upper():
        return False
    if str(first["account_type"]).strip().upper() != str(second["account_type"]).strip().upper():
        return False

    first_bank = _normalize_account_bank(first["bank"])
    second_bank = _normalize_account_bank(second["bank"])
    if first_bank and second_bank and first_bank != second_bank:
        return False

    first_last4 = str(first["account_last4"] or "").strip()
    second_last4 = str(second["account_last4"] or "").strip()
    if first_last4 and second_last4 and first_last4 != second_last4:
        return False

    first_ref = normalize_reference(first["reference"])
    second_ref = normalize_reference(second["reference"])

    if first_ref and second_ref:
        return first_ref == second_ref

    if not first_bank or not second_bank or first_bank != second_bank:
        return False

    first_last4 = str(first["account_last4"] or "").strip()
    second_last4 = str(second["account_last4"] or "").strip()
    if first_last4 and second_last4 and first_last4 != second_last4:
        return False

    first_payment = str(first["payment_method"] or "").strip().upper()
    second_payment = str(second["payment_method"] or "").strip().upper()
    if (
        first_payment
        and second_payment
        and first_payment != "UNKNOWN"
        and second_payment != "UNKNOWN"
        and first_payment != second_payment
    ):
        return False

    merchant_first = str(first["merchant_or_payee"] or "").strip()
    merchant_second = str(second["merchant_or_payee"] or "").strip()
    if not _merchant_values_compatible(merchant_first, merchant_second):
        return False

    first_source = str(first["id"])
    second_source = str(second["id"])
    first_mirror = first_source.startswith("gmail:") or first_source.startswith("notification:")
    second_mirror = second_source.startswith("gmail:") or second_source.startswith("notification:")
    cross_source = first_mirror != second_mirror

    # Preserve the existing conservative two-minute same-side rule for normal
    # sources. This is important for parser variants where one source exposes
    # the account last-four and another does not.
    if not cross_source:
        if not first_last4 and not second_last4:
            return False
        return abs(int(first["timestamp"]) - int(second["timestamp"])) <= 120_000

    # For a Gmail/notification mirror, a delayed delivery can be much later
    # than the SMS. The wider window is allowed only with complete account
    # identity and a known compatible counterparty, preventing unrelated
    # same-value transactions from collapsing.
    if not first_last4 or not second_last4 or first_last4 != second_last4:
        return False
    if not merchant_first or merchant_first == "-" or not merchant_second or merchant_second == "-":
        return False

    if first_source.startswith("gmail:") or second_source.startswith("gmail:"):
        max_time_diff = 24 * 60 * 60 * 1000
    else:
        max_time_diff = 2 * 60 * 60 * 1000

    return abs(int(first["timestamp"]) - int(second["timestamp"])) <= max_time_diff


def _duplicate_canonical_score(row):
    ref = bool(normalize_reference(row["reference"]))
    bank_identity = bool(_normalize_account_bank(row["bank"])) and bool(str(row["account_last4"] or "").strip())
    merchant = bool(str(row["merchant_or_payee"] or "").strip()) and str(row["merchant_or_payee"] or "").strip() != "-"
    source = str(row["id"])

    # Prefer an actual canonical/non-Gmail row over notification and Gmail
    # mirrors. Reference richness then decides between rows in the same class.
    if source.startswith("gmail:"):
        source_score = 0
    elif source.startswith("notification:"):
        source_score = 2000
    else:
        source_score = 3000

    return (
        source_score,
        1000 if ref else 0,
        30 if bank_identity else 0,
        10 if merchant else 0,
        float(row["confidence"] or 0.0),
        -int(row["created_at"] or 0),
        -int(row["id"]) if source.isdigit() else 0,
    )

def _void_transaction_in_connection(conn, transaction_id, duplicate_of=None):
    tx = conn.execute(
        "SELECT id,status,type,account_type,category,amount_minor,currency FROM transactions WHERE id=?",
        (transaction_id,),
    ).fetchone()
    if not tx:
        return {"status": "NOT_FOUND", "transactionId": transaction_id}
    if tx["status"] == "VOIDED":
        if duplicate_of:
            conn.execute(
                "UPDATE transactions SET duplicate_of=? WHERE id=?",
                (duplicate_of, transaction_id),
            )
        return {"status": "ALREADY_VOIDED", "transactionId": transaction_id}

    adjustment = conn.execute(
        "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
        (transaction_id,),
    ).fetchone()

    if adjustment:
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
            (adjustment["delta_minor"], now_ms(), adjustment["account_id"]),
        )
        conn.execute(
            "DELETE FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,),
        )

    # Reverse any explicitly tracked Splitwise contribution. Gmail rows
    # can be authoritative and therefore may have contributed before a later
    # SMS/notification row proves them to be duplicates.
    splitwise_adjustment = conn.execute(
        "SELECT currency,delta_minor FROM splitwise_adjustments WHERE transaction_id=?",
        (transaction_id,),
    ).fetchone()
    if splitwise_adjustment:
        conn.execute(
            """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
               VALUES(?,?,?)
               ON CONFLICT(currency) DO UPDATE SET
                   amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                   updated_at=excluded.updated_at""",
            (
                splitwise_adjustment["currency"],
                -int(splitwise_adjustment["delta_minor"]),
                now_ms(),
            ),
        )
        conn.execute(
            "DELETE FROM splitwise_adjustments WHERE transaction_id=?",
            (transaction_id,),
        )
    elif not str(tx["id"]).startswith("gmail:"):
        # Legacy/local transactions predate splitwise_adjustments, so preserve
        # the existing reversal behavior for those rows.
        delta = splitwise_delta(
            tx["account_type"], tx["type"], tx["amount_minor"], tx["category"]
        )
        if delta:
            conn.execute(
                """UPDATE manual_splitwise_total
                   SET amount_minor=amount_minor-?, updated_at=?
                   WHERE currency=?""",
                (delta, now_ms(), tx["currency"]),
            )

    conn.execute(
        "UPDATE transactions SET status='VOIDED', duplicate_of=? WHERE id=?",
        (duplicate_of, transaction_id),
    )
    return {
        "status": "VOIDED",
        "transactionId": transaction_id,
        "reversedAdjustment": bool(adjustment),
    }



def repair_duplicate_transactions():
    """Collapse safe same-side duplicate ledger rows without touching transfers.

    Exact-reference rows are reconciled first. Then an unreferenced row may
    merge into a referenced same-side row only when there is exactly one
    distinct reference candidate. This prevents incomplete rows from bridging
    unrelated transactions.
    """
    with connection() as conn:
        rows = conn.execute(
            """SELECT * FROM transactions
               WHERE status='ACTIVE' AND duplicate_of IS NULL
               ORDER BY created_at, id"""
        ).fetchall()

        assigned = set()
        repaired = 0

        def is_card_bill_credit(row):
            return (
                str(row["account_type"]).strip().upper() == "CREDIT_CARD"
                and str(row["type"]).strip().upper() == "CREDIT"
            )

        def enrich_canonical(canonical, duplicate):
            updates = {}
            if not normalize_reference(canonical["reference"]) and normalize_reference(duplicate["reference"]):
                updates["reference"] = normalize_reference(duplicate["reference"])
            if not str(canonical["bank"] or "").strip() and str(duplicate["bank"] or "").strip():
                updates["bank"] = duplicate["bank"]
            if not str(canonical["account_last4"] or "").strip() and str(duplicate["account_last4"] or "").strip():
                updates["account_last4"] = duplicate["account_last4"]
            if (
                (not canonical["merchant_or_payee"] or str(canonical["merchant_or_payee"]).strip() == "-")
                and duplicate["merchant_or_payee"]
                and str(duplicate["merchant_or_payee"]).strip() != "-"
            ):
                updates["merchant_or_payee"] = duplicate["merchant_or_payee"]

            if updates:
                assignments = ", ".join(f"{column}=?" for column in updates)
                values = list(updates.values()) + [canonical["id"]]
                conn.execute(
                    f"UPDATE transactions SET {assignments} WHERE id=?",
                    values,
                )

        def repair_pair(canonical, duplicate):
            nonlocal repaired
            enrich_canonical(canonical, duplicate)

            canonical_adjustment = conn.execute(
                "SELECT account_id,delta_minor,applied_at FROM balance_adjustments WHERE transaction_id=?",
                (canonical["id"],),
            ).fetchone()
            duplicate_adjustment = conn.execute(
                "SELECT account_id,delta_minor,applied_at FROM balance_adjustments WHERE transaction_id=?",
                (duplicate["id"],),
            ).fetchone()

            if canonical_adjustment is None and duplicate_adjustment is not None:
                conn.execute(
                    "DELETE FROM balance_adjustments WHERE transaction_id=?",
                    (duplicate["id"],),
                )
                conn.execute(
                    """INSERT INTO balance_adjustments(transaction_id,account_id,delta_minor,applied_at)
                       VALUES(?,?,?,?)""",
                    (
                        canonical["id"],
                        duplicate_adjustment["account_id"],
                        duplicate_adjustment["delta_minor"],
                        duplicate_adjustment["applied_at"],
                    ),
                )
                conn.execute(
                    "UPDATE transactions SET status='VOIDED', duplicate_of=? WHERE id=?",
                    (canonical["id"], duplicate["id"]),
                )
                result_status = "VOIDED"
            else:
                result = _void_transaction_in_connection(
                    conn,
                    duplicate["id"],
                    duplicate_of=canonical["id"],
                )
                result_status = result["status"]

            if result_status != "VOIDED":
                return

            conn.execute(
                "UPDATE evidence SET matched_transaction_id=? WHERE transaction_id=?",
                (canonical["id"], duplicate["id"]),
            )
            conn.execute(
                "UPDATE review_queue SET transaction_id=? WHERE transaction_id=?",
                (canonical["id"], duplicate["id"]),
            )
            assigned.add(str(duplicate["id"]))
            repaired += 1

        referenced_groups = {}
        for row in rows:
            if is_card_bill_credit(row):
                continue
            reference = normalize_reference(row["reference"])
            if reference:
                referenced_groups.setdefault(reference, []).append(row)

        for group in referenced_groups.values():
            remaining = [row for row in group if str(row["id"]) not in assigned]

            while remaining:
                canonical = max(remaining, key=_duplicate_canonical_score)
                duplicates = [
                    row for row in remaining
                    if str(row["id"]) != str(canonical["id"])
                    and _rows_are_duplicate(canonical, row)
                    and not is_card_bill_credit(row)
                ]

                if not duplicates:
                    remaining = [
                        row for row in remaining
                        if str(row["id"]) != str(canonical["id"])
                    ]
                    continue

                for duplicate in duplicates:
                    repair_pair(canonical, duplicate)

                consumed = {str(canonical["id"])} | {str(row["id"]) for row in duplicates}
                remaining = [
                    row for row in remaining
                    if str(row["id"]) not in consumed
                ]
                assigned.add(str(canonical["id"]))

        for row in rows:
            if str(row["id"]) in assigned or normalize_reference(row["reference"]):
                continue
            if is_card_bill_credit(row):
                continue

            candidates = []
            candidate_refs = set()

            for referenced in rows:
                if str(referenced["id"]) == str(row["id"]) or str(referenced["id"]) in assigned:
                    continue
                if is_card_bill_credit(referenced):
                    continue

                reference = normalize_reference(referenced["reference"])
                if not reference:
                    continue

                if _rows_are_duplicate(row, referenced):
                    candidates.append(referenced)
                    candidate_refs.add(reference)

            # A mirror source can legitimately omit the reference. If
            # there is exactly one compatible cross-source mirror candidate,
            # it is still safe to collapse because amount/type/bank/account/
            # merchant identity are all checked by _rows_are_duplicate.
            if not candidate_refs:
                mirror_candidates_without_ref = [
                    candidate for candidate in rows
                    if (
                        str(candidate["id"]) != str(row["id"])
                        and str(candidate["id"]) not in assigned
                        and not normalize_reference(candidate["reference"])
                        and not is_card_bill_credit(candidate)
                        and (
                            str(candidate["id"]).startswith("gmail:")
                            or str(candidate["id"]).startswith("notification:")
                        )
                        and _rows_are_duplicate(row, candidate)
                    )
                ]
                if len(mirror_candidates_without_ref) == 1:
                    candidates = mirror_candidates_without_ref
                    candidate_refs.add("__MIRROR_NO_REFERENCE__")

            if len(candidate_refs) != 1:
                continue

            # When the current row is a real local/SMS transaction and
            # the only referenced candidate is a Gmail/notification mirror,
            # keep the local row canonical. Otherwise a later-arriving Gmail
            # reference could incorrectly become the ledger owner.
            row_source = str(row["id"])
            row_is_mirror = row_source.startswith("gmail:") or row_source.startswith("notification:")
            mirror_candidates = [
                candidate for candidate in candidates
                if str(candidate["id"]).startswith("gmail:")
                or str(candidate["id"]).startswith("notification:")
            ]
            if not row_is_mirror and mirror_candidates:
                canonical = row
            else:
                canonical = max(candidates, key=_duplicate_canonical_score)

            if str(canonical["id"]) == str(row["id"]):
                duplicates = candidates
            else:
                # The current unreferenced row is the duplicate when a
                # referenced candidate is selected as canonical.
                duplicates = [row]

            for duplicate in duplicates:
                repair_pair(canonical, duplicate)
            assigned.add(str(canonical["id"]))

        return repaired


def sync_transaction(t, apply_balance=True):
    with connection() as conn:
        before=conn.execute(
            """SELECT * FROM transactions WHERE id=?""",
            (t.id,)
        ).fetchone()
        created_at=now_ms()
        provisional = is_provisional_unresolved_transaction(t)
        conn.execute("""INSERT OR IGNORE INTO transactions
        (id,amount_minor,currency,type,payment_method,account_type,bank,merchant_or_payee,account_last4,reference,timestamp,category,confidence,duplicate_of,status,created_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (t.id,t.amountMinor,t.currency,t.type,t.paymentMethod,t.accountType,t.bank,t.merchantOrPayee,t.accountLast4,normalize_reference(t.reference),
         t.timestamp,t.category,t.confidence,None,"ACTIVE",created_at))

        if (
            before is None
            and apply_balance
            and not str(t.id).startswith("gmail:")
            and not provisional
        ):
            apply_transaction_to_account(conn, t, created_at, transaction_created_at=created_at)

        if before is not None and before["status"] == "ACTIVE":
            # An Android notification can be synced before Gmail/IMAP clarifies
            # it. When the same canonical ID is later enriched, update the
            # existing ledger row and repair any balance/Splitwise contribution
            # that was based on the provisional direction/account identity.
            old_delta = 0 if is_provisional_row(before) else splitwise_delta(
                before["account_type"], before["type"], before["amount_minor"], before["category"]
            )
            new_delta = splitwise_delta_for_transaction(t)

            balance_adjustment = conn.execute(
                "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
                (t.id,),
            ).fetchone()

            materially_changed = (
                int(before["amount_minor"]) != int(t.amountMinor)
                or str(before["currency"]) != str(t.currency)
                or str(before["type"]) != str(t.type)
                or str(before["account_type"]) != str(t.accountType)
                or str(before["bank"] or "").strip().upper() != str(t.bank or "").strip().upper()
                or str(before["account_last4"] or "").strip() != str(t.accountLast4 or "").strip()
            )

            if materially_changed:
                if balance_adjustment is not None:
                    conn.execute(
                        "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                        (int(balance_adjustment["delta_minor"]), created_at, balance_adjustment["account_id"]),
                    )
                    conn.execute(
                        "DELETE FROM balance_adjustments WHERE transaction_id=?",
                        (t.id,),
                    )

                conn.execute(
                    """UPDATE transactions SET
                       amount_minor=?, currency=?, type=?, payment_method=?,
                       account_type=?, bank=?, merchant_or_payee=?,
                       account_last4=?, reference=?, timestamp=?, confidence=?,
                       status='ACTIVE'
                       WHERE id=?""",
                    (
                        t.amountMinor, t.currency, t.type, t.paymentMethod,
                        t.accountType, t.bank, t.merchantOrPayee,
                        t.accountLast4, normalize_reference(t.reference),
                        t.timestamp, t.confidence, t.id,
                    ),
                )

                if apply_balance and not str(t.id).startswith("gmail:"):
                    apply_transaction_to_account(
                        conn, t, created_at, transaction_created_at=created_at
                    )

                if old_delta != new_delta and not str(t.id).startswith("gmail:"):
                    splitwise_change = new_delta - old_delta
                    conn.execute(
                        """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
                           VALUES(?,?,?)
                           ON CONFLICT(currency) DO UPDATE SET
                               amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                               updated_at=excluded.updated_at""",
                        (t.currency, splitwise_change, created_at),
                    )

        # A transaction can have been stored before its bank account existed
        # (for example after a fresh database reset). On a later sync, repair the
        # missing balance adjustment exactly once. Manual reconciliation timestamps
        # still protect verified balances from replaying genuinely old transactions.
        if (
            before is not None
            and before["status"] == "ACTIVE"
            and apply_balance
            and not str(t.id).startswith("gmail:")
            and not provisional
        ):
            missing_adjustment = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (t.id,),
            ).fetchone()
            if missing_adjustment is None:
                apply_transaction_to_account(
                    conn, t, created_at, transaction_created_at=created_at
                )

        # Splitwise is a signed ledger component. Bank debits increase the
        # amount owed to the user, bank credits decrease it, card debits
        # decrease it, and card credits increase it. Gmail rows are never
        # allowed to create a Splitwise contribution until reconciliation makes
        # the canonical non-Gmail transaction authoritative.
        if (
            before is None
            and apply_balance
            and not str(t.id).startswith("gmail:")
            and not provisional
        ):
            delta = splitwise_delta_for_transaction(t)
            if delta:
                conn.execute(
                    """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
                       VALUES(?,?,?)
                       ON CONFLICT(currency) DO UPDATE SET
                           amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                           updated_at=excluded.updated_at""",
                    (t.currency, delta, created_at),
                )

        # Category edits are sent as the same transaction ID. They must update
        # the ledger metadata and Splitwise contribution without reapplying the
        # bank/card balance. OTHER is the explicit Splitwise opt-out; every
        # other debit category contributes its full amount.
        if (
            before is not None
            and before["status"] == "ACTIVE"
            and not str(t.id).startswith("gmail:")
            and str(before["category"] or "").strip().upper()
                != str(t.category or "").strip().upper()
        ):
            old_delta = 0 if is_provisional_row(before) else splitwise_delta(
                before["account_type"], before["type"], before["amount_minor"], before["category"]
            )
            new_delta = splitwise_delta_for_transaction(t)
            conn.execute(
                "UPDATE transactions SET category=? WHERE id=?",
                (t.category, t.id),
            )

            delta = new_delta - old_delta
            if delta:
                conn.execute(
                    """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
                       VALUES(?,?,?)
                       ON CONFLICT(currency) DO UPDATE SET
                           amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                           updated_at=excluded.updated_at""",
                    (t.currency, delta, created_at),
                )

        return before is None

def _normalize_account_bank(value):
    raw=(value or "").strip().upper()
    if not raw:
        return ""
    for suffix in (" BANK", " LTD", " LIMITED"):
        if raw.endswith(suffix):
            raw=raw[:-len(suffix)].strip()
    return raw

def repair_missing_balance_adjustments():
    """Backfill account balances for active transactions missing an adjustment.

    This is safe to run at API startup: each transaction gets at most one
    balance_adjustments row, Gmail/provisional rows are excluded, and manual
    reconciliation timestamps prevent replaying old events into a verified
    balance.
    """
    from types import SimpleNamespace

    with connection() as conn:
        rows = conn.execute(
            """SELECT t.*
               FROM transactions t
               LEFT JOIN balance_adjustments ba ON ba.transaction_id=t.id
               WHERE t.status='ACTIVE'
                 AND ba.transaction_id IS NULL
               ORDER BY t.created_at, t.id"""
        ).fetchall()

        repaired = 0
        for row in rows:
            transaction_id = str(row["id"])
            if transaction_id.startswith("gmail:"):
                continue

            provisional = is_provisional_row(row)
            if provisional:
                continue

            transaction = SimpleNamespace(
                id=transaction_id,
                amountMinor=int(row["amount_minor"]),
                currency=row["currency"],
                type=row["type"],
                paymentMethod=row["payment_method"],
                accountType=row["account_type"],
                bank=row["bank"],
                merchantOrPayee=row["merchant_or_payee"],
                accountLast4=row["account_last4"],
                reference=row["reference"],
                timestamp=int(row["timestamp"]),
                category=row["category"],
                confidence=float(row["confidence"] or 0.0),
            )
            before = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,),
            ).fetchone()
            if before is not None:
                continue

            apply_transaction_to_account(
                conn,
                transaction,
                now_ms(),
                transaction_created_at=int(row["created_at"]),
            )
            after = conn.execute(
                "SELECT 1 FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,),
            ).fetchone()
            if after is not None:
                repaired += 1

        return repaired


def _auto_provision_bank_account(conn, t, bank, last4, now_ms):
    """Create or complete an unambiguous bank account discovered from a transaction.

    A fresh database has no bank roster because bank identities are user-specific.
    When the first trusted bank transaction arrives, provision the bank account
    only when its identity is unambiguous. Never create a second account merely
    because a later source supplies a last-four for an existing bank account.
    """
    if t.accountType != "BANK_ACCOUNT" or not bank:
        return None

    rows = conn.execute(
        """SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
           FROM accounts
           WHERE account_type=? AND currency=?
           ORDER BY updated_at DESC""",
        (t.accountType, t.currency),
    ).fetchall()

    bank_rows = [
        row for row in rows
        if _normalize_account_bank(row["bank"]) == bank
    ]

    # Prefer an exact last-four match.
    if last4:
        for row in bank_rows:
            if str(row["last4"] or "").strip() == last4:
                return row

        # If there is exactly one existing account for this bank and it has no
        # last-four yet, enrich that account rather than creating another one.
        blank_identity = [
            row for row in bank_rows
            if not str(row["last4"] or "").strip()
        ]
        if len(blank_identity) == 1:
            account = blank_identity[0]
            conn.execute(
                "UPDATE accounts SET last4=?, updated_at=? WHERE id=?",
                (last4, now_ms, account["id"]),
            )
            return conn.execute(
                """SELECT id, balance_minor, bill_balance_minor,
                          balance_reconciled_at, bank, last4
                   FROM accounts WHERE id=?""",
                (account["id"],),
            ).fetchone()

        # Multiple accounts exist and none identifies this last-four: do not
        # guess which account should receive the transaction.
        if bank_rows:
            return None

    # Without a last-four, do not guess for a transaction that already has
    # a concrete payment method. The legacy balance-matching path only allowed
    # this fallback for UNKNOWN notification rows. Keep that boundary here too:
    # a known UPI/card transaction without a last-four must wait for a stronger
    # account identity rather than applying a second balance adjustment.
    if bank_rows:
        if (
            len(bank_rows) == 1
            and str(getattr(t, "paymentMethod", "") or "").strip().upper() == "UNKNOWN"
        ):
            return bank_rows[0]
        return None

    suffix = last4 or "primary"
    account_id = "bank-" + re.sub(r"[^a-z0-9]+", "-", bank.lower()).strip("-")
    if last4:
        account_id += "-" + last4
    elif suffix != "primary":
        account_id += "-" + suffix

    name = (str(t.bank or "").strip().title() + " Bank").strip()
    conn.execute(
        """INSERT INTO accounts(
            id,name,currency,account_type,bank,last4,opening_balance_minor,
            balance_minor,bill_balance_minor,balance_reconciled_at,updated_at
        ) VALUES(?,?,?,?,?,?,0,0,0,0,?)""",
        (
            account_id,
            name,
            t.currency,
            "BANK_ACCOUNT",
            t.bank,
            last4 or None,
            now_ms,
        ),
    )
    return conn.execute(
        """SELECT id, balance_minor, bill_balance_minor,
                  balance_reconciled_at, bank, last4
           FROM accounts WHERE id=?""",
        (account_id,),
    ).fetchone()


def apply_transaction_to_account(conn, t, applied_at, transaction_created_at=None):
    if t.accountType not in ("BANK_ACCOUNT", "CREDIT_CARD"):
        return

    bank=_normalize_account_bank(t.bank)
    last4=(getattr(t, "accountLast4", None) or getattr(t, "accountLastFour", None) or "").strip()

    # Bank names arrive from different sources as "AXIS", "AXIS BANK", etc.
    # Match by normalized identity instead of requiring byte-for-byte equality.
    query="""
        SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
        FROM accounts
        WHERE account_type=?
          AND currency=?
          AND TRIM(COALESCE(last4,''))=?
        ORDER BY updated_at DESC
    """
    candidates=conn.execute(query,(t.accountType,t.currency,last4)).fetchall()

    account=None
    for candidate in candidates:
        candidate_bank=_normalize_account_bank(candidate["bank"])
        if candidate_bank == bank:
            account=candidate
            break

    # Gmail/notification alerts can sometimes identify the issuing bank but
    # omit the account last-four. When there is exactly one configured account
    # for that bank/type/currency, use that unambiguous identity instead of
    # dropping the balance adjustment. Never guess when multiple matching
    # accounts exist.
    if (
        account is None
        and not last4
        and bank
        and str(getattr(t, "paymentMethod", "") or "").strip().upper() == "UNKNOWN"
    ):
        fallback = conn.execute(
            """
            SELECT id, balance_minor, bill_balance_minor, balance_reconciled_at, bank, last4
            FROM accounts
            WHERE account_type=?
              AND currency=?
            ORDER BY updated_at DESC
            """,
            (t.accountType, t.currency),
        ).fetchall()
        bank_matches = [
            row for row in fallback
            if _normalize_account_bank(row["bank"]) == bank
        ]
        if len(bank_matches) == 1:
            account = bank_matches[0]

    # If the transaction has no bank name, only use an unambiguous account
    # with the same type/currency/last4.
    if account is None and not bank and len(candidates) == 1:
        account=candidates[0]

    if not account:
        account = _auto_provision_bank_account(conn, t, bank, last4, applied_at)

    if not account:
        return

    # A manual balance edit is a reconciliation point. Transactions that
    # already existed before that reconciliation are assumed to be included in
    # the manually verified balance. A genuinely new transaction discovered
    # after reconciliation must still affect the balance even when its bank
    # event timestamp is older because the notification/email arrived late.
    reconciled_at = int(account["balance_reconciled_at"] or 0)
    created_at = int(transaction_created_at or applied_at)
    event_at = int(t.timestamp)
    # A manual reconciliation must ignore genuinely historical rows discovered
    # later. We still allow a recent bank event whose notification/email arrived
    # after reconciliation; this covers the real late-Gmail case without
    # replaying arbitrarily old transactions into a verified balance.
    # A real bank event timestamp is epoch milliseconds. Keep the
    # "discovered after reconciliation" exception for plausible transaction
    # timestamps, while rejecting obviously synthetic/invalid ancient values.
    # This also preserves late-email/SMS ingestion of legitimate older events.
    min_plausible_event_ms = 946684800000  # 2000-01-01
    if reconciled_at and event_at <= reconciled_at:
        if created_at <= reconciled_at:
            return
        if event_at < min_plausible_event_ms:
            return

    # Both bank accounts and credit cards use the transaction's natural sign:
    # DEBIT decreases the tracked balance; CREDIT increases it.
    if t.type == "DEBIT":
        delta = -t.amountMinor
    elif t.type == "CREDIT":
        delta = t.amountMinor
    else:
        return

    inserted=conn.execute(
        """INSERT OR IGNORE INTO balance_adjustments
           (transaction_id,account_id,delta_minor,applied_at)
           VALUES(?,?,?,?)""",
        (t.id,account["id"],delta,applied_at)
    )
    if inserted.rowcount:
        conn.execute(
            "UPDATE accounts SET balance_minor=balance_minor+?, updated_at=? WHERE id=?",
            (delta,applied_at,account["id"])
        )

        # Keep the statement bill separate from live/unbilled card spend.
        # Card debits are new spend and therefore do not increase the bill
        # bucket. Card credits (bill payments, refunds, etc.) reduce the bill
        # bucket first; any amount beyond the bill reduces live spend.
        if t.accountType == "CREDIT_CARD" and t.type == "CREDIT":
            conn.execute(
                """UPDATE accounts
                   SET bill_balance_minor=MAX(0, COALESCE(bill_balance_minor,0)-?)
                 WHERE id=?""",
                (t.amountMinor, account["id"])
            )

def sync_evidence(e):
    with connection() as conn:
        before=conn.execute(
            "SELECT id FROM evidence WHERE source_type=? AND source_id=?",
            (e.sourceType,e.sourceId)
        ).fetchone()
        conn.execute("""
            INSERT INTO evidence
            (id,source_type,source_id,status,observed_at,transaction_id,matched_transaction_id,amount_minor,currency,direction,
             bank_provider,account_last4,reference,content_hash,confidence,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source_type, source_id) DO UPDATE SET
                status=excluded.status,
                observed_at=excluded.observed_at,
                transaction_id=excluded.transaction_id,
                matched_transaction_id=excluded.matched_transaction_id,
                amount_minor=excluded.amount_minor,
                currency=excluded.currency,
                direction=excluded.direction,
                bank_provider=excluded.bank_provider,
                account_last4=excluded.account_last4,
                reference=excluded.reference,
                content_hash=excluded.content_hash,
                confidence=excluded.confidence
        """,
        (e.id,e.sourceType,e.sourceId,e.status,e.observedAt,e.transactionId,e.matchedTransactionId,e.amountMinor,e.currency,
         e.direction,e.bankProvider,e.accountLast4,normalize_reference(e.reference),e.contentHash,e.confidence,now_ms()))
        return before is None

def sync_card_bill(bill):
    """Record bill evidence and apply only the strongest confirmed bill amount.

    Source precedence:
      Gmail/full statement email or PDF > SMS > notification.
    Lower-priority evidence is retained but cannot overwrite a stronger
    conflicting bill amount.
    """
    source_type = str(bill.get("sourceType") or "").upper()
    source_key = str(bill.get("sourceKey") or "")
    bank = (bill.get("bank") or "").strip().upper() or None
    last4 = (bill.get("accountLast4") or "").strip() or None
    last2 = (bill.get("accountLast2") or "").strip() or None
    amount = bill.get("amountMinor")
    amount = int(amount) if amount is not None else None
    observed_at = int(bill.get("timestamp") or now_ms())
    confidence = float(bill.get("confidence") or 0.0)
    if not source_key or amount is None or amount < 0:
        return {"status":"IGNORED","reason":"missing_bill_amount_or_source"}

    priority = {
        "GMAIL": 300,
        "GMAIL_STATEMENT_PDF": 300,
        "SMS": 200,
        "GMAIL_NOTIFICATION": 100,
    }.get(source_type, 0)

    with connection() as conn:
        inserted = conn.execute(
            """INSERT OR IGNORE INTO card_bill_evidence
               (id,source_type,source_key,observed_at,amount_minor,currency,bank,
                account_last4,account_last2,confidence,applied,created_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,0,?)""",
            (
                f"card-bill:{source_type}:{source_key}",
                source_type,
                source_key,
                observed_at,
                amount,
                bill.get("currency") or "INR",
                bank,
                last4,
                last2,
                confidence,
                now_ms(),
            ),
        ).rowcount

        # Find the configured card. Prefer a full last-4 match; fall back to
        # a unique visible last-two suffix from statement notifications.
        account = None
        if last4:
            account = conn.execute(
                """SELECT id,balance_minor,bill_balance_minor,balance_reconciled_at
                   FROM accounts
                   WHERE account_type='CREDIT_CARD' AND currency=?
                     AND UPPER(TRIM(COALESCE(bank,'')))=?
                     AND TRIM(COALESCE(last4,''))=?
                   ORDER BY updated_at DESC LIMIT 1""",
                (bill.get("currency") or "INR", bank or "", last4),
            ).fetchone()

        if not account and last2:
            candidates = conn.execute(
                """SELECT id,balance_minor,bill_balance_minor,balance_reconciled_at,last4
                   FROM accounts
                   WHERE account_type='CREDIT_CARD' AND currency=?
                     AND UPPER(TRIM(COALESCE(bank,'')))=?
                     AND substr(TRIM(COALESCE(last4,'')),-2)=?
                   ORDER BY updated_at DESC""",
                (bill.get("currency") or "INR", bank or "", last2),
            ).fetchall()
            if len(candidates) == 1:
                account = candidates[0]

        if not account:
            return {"status":"REVIEW","reason":"card_not_found","bank":bank,"last4":last4,"last2":last2}

        # A manual card balance is a reconciliation point. Older statement
        # evidence discovered later must not rewrite the manually verified
        # Bill/Active Spend split. A statement observed after reconciliation
        # is allowed to establish the new bill bucket.
        reconciled_at = int(account["balance_reconciled_at"] or 0)
        if reconciled_at and observed_at <= reconciled_at:
            return {
                "status":"RETAINED_MANUAL_RECONCILIATION",
                "accountId":account["id"],
                "sourceType":source_type,
            }

        # Compare the strongest already-applied evidence for this exact card.
        previous = conn.execute(
            """SELECT e.source_type,e.amount_minor
               FROM card_bill_evidence e
               JOIN accounts a ON a.id=?
               WHERE e.applied=1
                 AND e.currency=?
                 AND UPPER(TRIM(COALESCE(e.bank,'')))=?
                 AND (
                      (e.account_last4 IS NOT NULL AND TRIM(e.account_last4)=TRIM(a.last4))
                      OR (e.account_last2 IS NOT NULL AND substr(TRIM(COALESCE(a.last4,'')),-2)=TRIM(e.account_last2))
                 )
               ORDER BY e.observed_at DESC
               LIMIT 1""",
            (account["id"], bill.get("currency") or "INR", bank or ""),
        ).fetchone()

        if previous:
            previous_priority = {
                "GMAIL": 300,
                "GMAIL_STATEMENT_PDF": 300,
                "SMS": 200,
                "GMAIL_NOTIFICATION": 100,
            }.get(str(previous["source_type"]).upper(), 0)

            if amount != previous["amount_minor"] and priority < previous_priority:
                return {
                    "status":"RETAINED_LOWER_PRIORITY",
                    "accountId":account["id"],
                    "currentBillMinor":previous["amount_minor"],
                    "receivedBillMinor":amount,
                    "sourceType":source_type,
                }

            if amount != previous["amount_minor"] and priority == previous_priority:
                # Same-tier disagreement is a review condition. Do not silently
                # choose one source.
                review_id = "CARD_BILL_CONFLICT:" + account["id"] + ":" + str(observed_at)
                conn.execute(
                    """INSERT OR IGNORE INTO review_queue
                       (id,kind,transaction_id,evidence_id,reason,status,created_at)
                       VALUES(?,?,NULL,NULL,?,'OPEN',?)""",
                    (
                        review_id,
                        "CARD_BILL_CONFLICT",
                        f"Card bill disagreement for {bank} {account['id']}: {previous['amount_minor']} vs {amount}",
                        now_ms(),
                    ),
                )
                return {
                    "status":"REVIEW",
                    "reason":"same_priority_bill_conflict",
                    "accountId":account["id"],
                }

        # Bill balance is a separate statement/debt bucket. It must not be
        # capped by the signed live card balance because the live card balance
        # follows the application's invariant sign convention.
        bill_value = amount
        conn.execute(
            """UPDATE accounts
               SET bill_balance_minor=?, updated_at=?
             WHERE id=?""",
            (bill_value, now_ms(), account["id"]),
        )
        conn.execute(
            """UPDATE card_bill_evidence
               SET applied=1
             WHERE source_type=? AND source_key=?""",
            (source_type, source_key),
        )

        return {
            "status":"APPLIED",
            "accountId":account["id"],
            "billBalanceMinor":bill_value,
            "sourceType":source_type,
        }


def void_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute(
            "SELECT id,status,type,account_type,category,amount_minor,currency FROM transactions WHERE id=?",
            (transaction_id,)
        ).fetchone()
        if not tx:
            return {"status":"NOT_FOUND","transactionId":transaction_id}
        if tx["status"]=="VOIDED":
            return {"status":"ALREADY_VOIDED","transactionId":transaction_id}

        adjustment=conn.execute(
            "SELECT account_id,delta_minor FROM balance_adjustments WHERE transaction_id=?",
            (transaction_id,)
        ).fetchone()

        if adjustment:
            conn.execute(
                "UPDATE accounts SET balance_minor=balance_minor-?, updated_at=? WHERE id=?",
                (adjustment["delta_minor"], now_ms(), adjustment["account_id"])
            )
            conn.execute(
                "DELETE FROM balance_adjustments WHERE transaction_id=?",
                (transaction_id,)
            )

        # Reverse the exact signed Splitwise contribution that this
        # transaction created. Do not clamp the aggregate: the ledger may need
        # to move through zero when credits/card activity are reversed.
        if not str(tx["id"]).startswith("gmail:"):
            delta = splitwise_delta(
                tx["account_type"], tx["type"], tx["amount_minor"], tx["category"]
            )
            if delta:
                conn.execute(
                    """UPDATE manual_splitwise_total
                       SET amount_minor=amount_minor-?, updated_at=?
                       WHERE currency=?""",
                    (delta, now_ms(), tx["currency"]),
                )

        conn.execute(
            "UPDATE transactions SET status='VOIDED' WHERE id=?",
            (transaction_id,)
        )
        return {
            "status":"VOIDED",
            "transactionId":transaction_id,
            "reversedAdjustment": bool(adjustment)
        }

def balances():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM accounts ORDER BY name").fetchall()]

def set_balance(account_id,name,currency,account_type,bank,last4,balance_minor,bill_balance_minor=None):
    with connection() as conn:
        adjustment_total=conn.execute(
            "SELECT COALESCE(SUM(delta_minor),0) value FROM balance_adjustments WHERE account_id=?",
            (account_id,)
        ).fetchone()["value"]

        existing=conn.execute(
            "SELECT bill_balance_minor FROM accounts WHERE id=?",
            (account_id,)
        ).fetchone()

        if account_type == "CREDIT_CARD":
            if bill_balance_minor is None:
                bill_value = (
                    int(existing["bill_balance_minor"])
                    if existing and existing["bill_balance_minor"] is not None
                    else int(balance_minor)
                )
            else:
                bill_value = max(0, min(int(bill_balance_minor), int(balance_minor)))
        else:
            bill_value = 0

        # Manual entry is an explicit reconciliation of the live balance.
        # Preserve the transaction-derived opening snapshot, but establish a
        # timestamp so transactions discovered later with older event times do
        # not retroactively change this manually verified balance.
        opening_balance=balance_minor-adjustment_total
        reconciled_at=now_ms()
        conn.execute("""INSERT INTO accounts(
            id,name,currency,account_type,bank,last4,opening_balance_minor,balance_minor,bill_balance_minor,balance_reconciled_at,updated_at
        ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
            name=excluded.name,
            currency=excluded.currency,
            account_type=excluded.account_type,
            bank=excluded.bank,
            last4=excluded.last4,
            opening_balance_minor=excluded.opening_balance_minor,
            balance_minor=excluded.balance_minor,
            bill_balance_minor=excluded.bill_balance_minor,
            balance_reconciled_at=excluded.balance_reconciled_at,
            updated_at=excluded.updated_at""",
        (account_id,name,currency,account_type,bank,last4,opening_balance,balance_minor,bill_value,reconciled_at,reconciled_at))

def add_receivable(item):
    with connection() as conn:
        conn.execute("""INSERT OR IGNORE INTO splitwise_receivables
        (id,description,amount_minor,currency,splitwise_expense_id,status,created_at)
        VALUES(?,?,?,?,?,'OPEN',?)""",
        (item["id"],item["description"],item["amount_minor"],item["currency"],item.get("splitwise_expense_id"),now_ms()))

def set_manual_splitwise_total(currency, amount_minor):
    with connection() as conn:
        conn.execute(
            """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
               VALUES(?,?,?)
               ON CONFLICT(currency) DO UPDATE SET
                 amount_minor=excluded.amount_minor,
                 updated_at=excluded.updated_at""",
            (currency, max(0, int(amount_minor)), now_ms())
        )

def apply_splitwise_contribution(conn, t, applied_at=None):
    """Apply a transaction's Splitwise delta exactly once.

    Gmail transactions are authoritative when no SMS/notification canonical
    row exists. They therefore need the same signed Splitwise effect as local
    transactions. A separate adjustment row makes that effect reversible if a
    later SMS/notification proves the Gmail row was a duplicate.
    """
    transaction_id = str(getattr(t, "id", None) or "")
    if not transaction_id:
        return False

    delta = splitwise_delta_for_transaction(t)
    if not delta:
        return False

    applied_at = int(applied_at or now_ms())
    inserted = conn.execute(
        """INSERT OR IGNORE INTO splitwise_adjustments
           (transaction_id,currency,delta_minor,applied_at)
           VALUES(?,?,?,?)""",
        (transaction_id, t.currency, delta, applied_at),
    )
    if not inserted.rowcount:
        return False

    conn.execute(
        """INSERT INTO manual_splitwise_total(currency, amount_minor, updated_at)
           VALUES(?,?,?)
           ON CONFLICT(currency) DO UPDATE SET
               amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
               updated_at=excluded.updated_at""",
        (t.currency, delta, applied_at),
    )
    return True

def get_manual_splitwise_total(currency="INR"):
    with connection() as conn:
        row=conn.execute("SELECT amount_minor FROM manual_splitwise_total WHERE currency=?",(currency,)).fetchone()
        return int(row["amount_minor"]) if row else None

def true_available(currency="INR"):
    with connection() as conn:
        cash=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='BANK_ACCOUNT' AND currency=?",(currency,)).fetchone()["value"]
        manual=get_manual_splitwise_total(currency)
        if manual is None:
            rec=conn.execute("SELECT COALESCE(SUM(amount_minor),0) value FROM splitwise_receivables WHERE status='OPEN' AND currency=?",(currency,)).fetchone()["value"]
        else:
            rec=manual
        cards=conn.execute("SELECT COALESCE(SUM(balance_minor),0) value FROM accounts WHERE account_type='CREDIT_CARD' AND currency=?",(currency,)).fetchone()["value"]
        return {"currency":currency,"bankCashMinor":cash,"splitwiseReceivableMinor":rec,"creditCardOutstandingMinor":cards,"trueAvailableMinor":cash+rec-cards}

def list_transactions(limit=100):
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM transactions WHERE duplicate_of IS NULL AND status='ACTIVE' ORDER BY timestamp DESC LIMIT ?",(limit,)).fetchall()]

def list_review_queue():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM review_queue WHERE status='OPEN' ORDER BY created_at DESC").fetchall()]

def add_review(kind,reason,transaction_id=None,evidence_id=None):
    review_id=f"{kind}:{transaction_id or evidence_id or now_ms()}"
    with connection() as conn:
        conn.execute("INSERT OR IGNORE INTO review_queue(id,kind,transaction_id,evidence_id,reason,status,created_at) VALUES(?,?,?,?,?,'OPEN',?)",
                     (review_id,kind,transaction_id,evidence_id,reason,now_ms()))
    return review_id

def _neutralize_internal_transfer_splitwise(conn, transfer_id, debit_id, credit_id):
    """Make a matched internal transfer pair invisible to Splitwise.

    Moving money between two of the user's own bank accounts changes bank
    balances but is not an expense or receivable. The operation is persisted
    with an idempotency marker because the pair may be encountered repeatedly
    during reconciliation.
    """
    marker = conn.execute(
        "SELECT splitwise_neutralized FROM internal_transfers WHERE id=?",
        (transfer_id,),
    ).fetchone()
    if marker is None or int(marker["splitwise_neutralized"] or 0) == 1:
        return False

    for transaction_id in (debit_id, credit_id):
        tx = conn.execute(
            "SELECT * FROM transactions WHERE id=?",
            (transaction_id,),
        ).fetchone()
        if not tx or tx["status"] != "ACTIVE" or tx["duplicate_of"] is not None:
            return False

        adjustment = conn.execute(
            "SELECT currency,delta_minor FROM splitwise_adjustments WHERE transaction_id=?",
            (transaction_id,),
        ).fetchone()

        if adjustment is not None:
            # Gmail-derived rows keep an exact Splitwise adjustment record.
            delta = int(adjustment["delta_minor"])
            currency = adjustment["currency"]
            conn.execute(
                """INSERT INTO manual_splitwise_total(currency,amount_minor,updated_at)
                   VALUES(?,?,?)
                   ON CONFLICT(currency) DO UPDATE SET
                       amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                       updated_at=excluded.updated_at""",
                (currency, -delta, now_ms()),
            )
            conn.execute(
                "DELETE FROM splitwise_adjustments WHERE transaction_id=?",
                (transaction_id,),
            )
        elif not str(transaction_id).startswith("gmail:"):
            # Local/SMS rows use the signed Splitwise delta directly.
            delta = splitwise_delta(
                tx["account_type"], tx["type"], tx["amount_minor"], tx["category"]
            )
            if delta:
                conn.execute(
                    """INSERT INTO manual_splitwise_total(currency,amount_minor,updated_at)
                       VALUES(?,?,?)
                       ON CONFLICT(currency) DO UPDATE SET
                           amount_minor=manual_splitwise_total.amount_minor + excluded.amount_minor,
                           updated_at=excluded.updated_at""",
                    (tx["currency"], -delta, now_ms()),
                )

    conn.execute(
        "UPDATE internal_transfers SET splitwise_neutralized=1 WHERE id=?",
        (transfer_id,),
    )
    return True


def match_internal_transfers():
    with connection() as conn:
        existing_unneutralized = conn.execute(
            """SELECT id,debit_transaction_id,credit_transaction_id
               FROM internal_transfers
               WHERE status='MATCHED'
                 AND COALESCE(splitwise_neutralized,0)=0"""
        ).fetchall()

        for transfer in existing_unneutralized:
            _neutralize_internal_transfer_splitwise(
                conn,
                transfer["id"],
                transfer["debit_transaction_id"],
                transfer["credit_transaction_id"],
            )

        txs=conn.execute(
            """SELECT * FROM transactions
               WHERE account_type='BANK_ACCOUNT'
                 AND type IN ('DEBIT','CREDIT')
                 AND status='ACTIVE'
                 AND duplicate_of IS NULL
               ORDER BY timestamp DESC"""
        ).fetchall()
        matches=[]
        for debit in txs:
            if debit["type"]!="DEBIT": continue
            for credit in txs:
                if credit["type"]!="CREDIT" or debit["id"]==credit["id"]: continue
                if debit["currency"]!=credit["currency"] or debit["amount_minor"]!=credit["amount_minor"]: continue
                if abs(debit["timestamp"]-credit["timestamp"])>10*60*1000: continue
                if debit["bank"]==credit["bank"] and debit["account_last4"]==credit["account_last4"]: continue
                exists=conn.execute("SELECT 1 FROM internal_transfers WHERE debit_transaction_id=? OR credit_transaction_id=?",(debit["id"],credit["id"])).fetchone()
                if exists: continue
                reason="same amount/currency within 10 minutes across distinct bank accounts"
                transfer_id=f"transfer:{debit['id']}:{credit['id']}"
                conn.execute("""INSERT OR IGNORE INTO internal_transfers
                (id,debit_transaction_id,credit_transaction_id,currency,amount_minor,status,reason,created_at)
                VALUES(?,?,?,?,?,'MATCHED',?,?)""",
                (transfer_id,debit["id"],credit["id"],debit["currency"],debit["amount_minor"],reason,now_ms()))
                _neutralize_internal_transfer_splitwise(
                    conn,
                    transfer_id,
                    debit["id"],
                    credit["id"],
                )
                matches.append({"id":transfer_id,"debitTransactionId":debit["id"],"creditTransactionId":credit["id"],
                                "amountMinor":debit["amount_minor"],"currency":debit["currency"],"reason":reason})
        return matches

def list_transfers():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM internal_transfers ORDER BY created_at DESC").fetchall()]

def upsert_splitwise_rule(item):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_rules(id,merchant_pattern,group_id,split_mode,user_shares_json,enabled,created_at)
        VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET merchant_pattern=excluded.merchant_pattern,group_id=excluded.group_id,
        split_mode=excluded.split_mode,user_shares_json=excluded.user_shares_json,enabled=excluded.enabled""",
        (item["id"],item["merchant_pattern"],item["group_id"],item.get("split_mode","EQUAL"),item.get("user_shares_json"),
         1 if item.get("enabled",True) else 0,now_ms()))

def list_splitwise_rules():
    with connection() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY merchant_pattern").fetchall()]

def get_splitwise_rule_for(merchant):
    if not merchant: return None
    needle=merchant.casefold()
    with connection() as conn:
        rows=conn.execute("SELECT * FROM splitwise_rules WHERE enabled=1 ORDER BY LENGTH(merchant_pattern) DESC").fetchall()
    for row in rows:
        if row["merchant_pattern"].casefold() in needle: return dict(row)
    return None

def record_splitwise_expense(transaction_id,expense_id,status="CREATED",error=None):
    with connection() as conn:
        conn.execute("""INSERT INTO splitwise_expenses(transaction_id,splitwise_expense_id,status,error,created_at,updated_at)
        VALUES(?,?,?,?,?,?) ON CONFLICT(transaction_id) DO UPDATE SET splitwise_expense_id=excluded.splitwise_expense_id,
        status=excluded.status,error=excluded.error,updated_at=excluded.updated_at""",
        (transaction_id,expense_id,status,error,now_ms(),now_ms()))

def splitwise_expense_for(transaction_id):
    with connection() as conn:
        row=conn.execute("SELECT * FROM splitwise_expenses WHERE transaction_id=?",(transaction_id,)).fetchone()
        return dict(row) if row else None

def reconcile_duplicate_transaction(transaction_id):
    with connection() as conn:
        tx=conn.execute("SELECT * FROM transactions WHERE id=?",(transaction_id,)).fetchone()
        if not tx or tx["duplicate_of"]: return None
        if not tx["id"].startswith("gmail:"): return None

        tx_ref = normalize_reference(tx["reference"])

        if tx_ref:
            # Reference-first reconciliation searches the full ledger. The same
            # UPI RRN can exist on both sides of an internal transfer, so bank,
            # account and direction remain mandatory side-of-ledger constraints.
            # Reference-first reconciliation must still see a provisional
            # notification/SMS row on the destination side when that row has
            # not received the RRN yet. Both sides of an internal UPI transfer
            # can share the same RRN, so direction + bank + account remain
            # mandatory side-of-ledger constraints.
            candidates=conn.execute(
                """SELECT * FROM transactions
                   WHERE id<>?
                     AND duplicate_of IS NULL
                     AND id NOT LIKE 'gmail:%'
                     AND status='ACTIVE'
                   ORDER BY ABS(timestamp-?)""",
                (tx["id"],tx["timestamp"])
            ).fetchall()

            strong=[]
            for c in candidates:
                candidate_ref = normalize_reference(c["reference"])
                if candidate_ref and candidate_ref != tx_ref:
                    continue
                if tx["amount_minor"] != c["amount_minor"] or tx["currency"] != c["currency"]:
                    continue
                if tx["type"] != c["type"]:
                    continue
                if tx["bank"] and c["bank"] and _normalize_account_bank(tx["bank"]) != _normalize_account_bank(c["bank"]):
                    continue
                if tx["account_last4"] and c["account_last4"] and str(tx["account_last4"]).strip() != str(c["account_last4"]).strip():
                    continue

                # An exact RRN is stronger than a provisional row without one.
                # A missing RRN is acceptable only when all side identity
                # constraints match, allowing Gmail to enrich that row.
                score = 1000 if candidate_ref == tx_ref else 100
                if tx["bank"] and c["bank"]: score += 25
                if tx["account_last4"] and c["account_last4"]: score += 30
                strong.append((score,c))

            if strong:
                best_score = max(score for score, _ in strong)
                best = [(score, c) for score, c in strong if score == best_score]
                if len(best) == 1:
                    score, canonical = best[0]
                    conn.execute("UPDATE transactions SET duplicate_of=? WHERE id=?",(canonical["id"],tx["id"]))

                    # Gmail can arrive after a notification/SMS has already
                    # created the canonical ledger row. Preserve the stronger
                    # Gmail transaction identity on that canonical row instead
                    # of leaving the RRN stranded on the duplicate Gmail row.
                    enrichment = {}
                    if not canonical["reference"] and tx["reference"]:
                        enrichment["reference"] = normalize_reference(tx["reference"])
                    if (
                        (not canonical["merchant_or_payee"] or str(canonical["merchant_or_payee"]).strip() == "-")
                        and tx["merchant_or_payee"]
                        and str(tx["merchant_or_payee"]).strip() != "-"
                    ):
                        enrichment["merchant_or_payee"] = tx["merchant_or_payee"]

                    if enrichment:
                        assignments = ", ".join(f"{column}=?"
                                                for column in enrichment)
                        values = list(enrichment.values()) + [canonical["id"]]
                        conn.execute(
                            f"UPDATE transactions SET {assignments} WHERE id=?",
                            values,
                        )

                    conn.execute(
                        "UPDATE evidence SET matched_transaction_id=? WHERE transaction_id=?",
                        (canonical["id"],tx["id"])
                    )
                    return {
                        "duplicate":tx["id"],
                        "canonical":canonical["id"],
                        "score":score,
                        "enriched":bool(enrichment),
                    }

            # A notification often has no RRN. It can still be the provisional
            # destination-side row for the Gmail transaction, so retain the
            # bounded amount/time fallback only when no exact-reference ledger
            # side matched.
            candidates=conn.execute(
                """SELECT * FROM transactions
                   WHERE id<>? AND duplicate_of IS NULL
                     AND id NOT LIKE 'gmail:%' AND status='ACTIVE'
                     AND amount_minor=? AND currency=? AND type=?
                     AND ABS(timestamp-?)<=86400000
                   ORDER BY ABS(timestamp-?) LIMIT 5""",
                (tx["id"],tx["amount_minor"],tx["currency"],tx["type"],tx["timestamp"],tx["timestamp"])
            ).fetchall()
        else:
            candidates=conn.execute(
                """SELECT * FROM transactions WHERE id<>? AND duplicate_of IS NULL
                   AND id NOT LIKE 'gmail:%' AND status='ACTIVE'
                   AND amount_minor=? AND currency=? AND type=?
                   AND ABS(timestamp-?)<=86400000
                   ORDER BY ABS(timestamp-?) LIMIT 5""",
                (tx["id"],tx["amount_minor"],tx["currency"],tx["type"],tx["timestamp"],tx["timestamp"])
            ).fetchall()

        strong=[]
        for c in candidates:
            score=0
            if tx["bank"] and c["bank"] and tx["bank"]==c["bank"]: score+=2
            if tx["account_last4"] and c["account_last4"] and tx["account_last4"]==c["account_last4"]: score+=2
            if tx["type"]==c["type"]: score+=1
            if score>=3: strong.append((score,c))
        if len(strong)==1:
            canonical=strong[0][1]
            conn.execute("UPDATE transactions SET duplicate_of=? WHERE id=?",(canonical["id"],tx["id"]))

            # Gmail can arrive after a notification/SMS has already created
            # the canonical ledger row. Preserve the stronger Gmail
            # transaction identity on that canonical row instead of leaving
            # the RRN stranded on the duplicate Gmail row.
            enrichment = {}
            if not canonical["reference"] and tx["reference"]:
                enrichment["reference"] = normalize_reference(tx["reference"])
            if (
                (not canonical["merchant_or_payee"] or str(canonical["merchant_or_payee"]).strip() == "-")
                and tx["merchant_or_payee"]
                and str(tx["merchant_or_payee"]).strip() != "-"
            ):
                enrichment["merchant_or_payee"] = tx["merchant_or_payee"]

            if enrichment:
                assignments = ", ".join(f"{column}=?" for column in enrichment)
                values = list(enrichment.values()) + [canonical["id"]]
                conn.execute(
                    f"UPDATE transactions SET {assignments} WHERE id=?",
                    values,
                )

            conn.execute("UPDATE evidence SET matched_transaction_id=? WHERE transaction_id=?",(canonical["id"],tx["id"]))
            return {
                "duplicate":tx["id"],
                "canonical":canonical["id"],
                "score":strong[0][0],
                "enriched":bool(enrichment),
            }
    return None

