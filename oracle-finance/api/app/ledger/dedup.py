"""Duplicate detection: one real transaction seen from several sources (SMS, notification, e-mail)."""

from ..db import connection
from .common import _normalize_account_bank, _void_transaction_in_connection, is_provisional_row, normalize_reference
from .transfers import _apply_balance_after_identity_enrichment


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


def _mirror_kind(source_id):
    source_id = str(source_id)
    if source_id.startswith("notification:"):
        return "notification"
    if source_id.startswith("gmail:") or source_id.startswith("oracle:gmail:"):
        return "gmail"
    return "other"


def _same_amount(first, second):
    """Equal INR amount, or the same original foreign amount.

    A foreign spend whose rupee amount the user corrected no longer equals the
    estimate that another source converts to, but it is still the same charge.
    """
    if int(first["amount_minor"]) == int(second["amount_minor"]):
        return True
    keys_first, keys_second = first.keys(), second.keys()
    if "orig_amount_minor" not in keys_first or "orig_amount_minor" not in keys_second:
        return False
    return (
        first["orig_amount_minor"] is not None
        and first["orig_amount_minor"] == second["orig_amount_minor"]
        and str(first["orig_currency"] or "").upper() == str(second["orig_currency"] or "").upper()
    )


_MIRROR_PREFIXES = ("gmail:", "notification:")
_SAME_SOURCE_WINDOW_MS = 120_000
_GMAIL_WINDOW_MS = 24 * 60 * 60 * 1000
_NOTIFICATION_WINDOW_MS = 15 * 60 * 1000
_PAIR_WINDOW_MS = 2 * 60 * 60 * 1000


def _bank(row):
    return _normalize_account_bank(row["bank"])


def _last4(row):
    return str(row["account_last4"] or "").strip()


def _upper(row, key):
    return str(row[key]).strip().upper()


def _is_mirror(row):
    return str(row["id"]).startswith(_MIRROR_PREFIXES)


def _is_gmail(row):
    return str(row["id"]).startswith("gmail:")


def _apart_ms(first, second):
    return abs(int(first["timestamp"]) - int(second["timestamp"]))


def _banks_conflict(first, second):
    return bool(_bank(first) and _bank(second) and _bank(first) != _bank(second))


def _last4_conflict(first, second):
    return bool(_last4(first) and _last4(second) and _last4(first) != _last4(second))


def _same_side(first, second):
    """Same direction on the same kind of account."""
    return (
        _upper(first, "type") == _upper(second, "type")
        and _upper(first, "account_type") == _upper(second, "account_type")
    )


def _payment_methods_conflict(first, second):
    methods = [str(row["payment_method"] or "").strip().upper() for row in (first, second)]
    return all(methods) and "UNKNOWN" not in methods and methods[0] != methods[1]


def _same_account_identity(first, second, cross_source):
    """Same bank, or, for a notification/e-mail mirror that lacks the bank,
    the same account last-4 against a real row."""
    if _bank(first) and _bank(second):
        return True
    return bool(cross_source and _last4(first) and _last4(first) == _last4(second))


def _same_source_kind_window(first, second):
    """Two rows from the same kind of source (both mirrors or both real)."""
    first_ref = normalize_reference(first["reference"])
    second_ref = normalize_reference(second["reference"])
    if not _last4(first) and not _last4(second):
        # An Axis-style credit has no SMS and often no last-4: the bank e-mail
        # carries the reference, the instant notification does not. That e-mail
        # + notification pair (and only that pair, with the reference on exactly
        # one side and the same bank) is one event.
        kinds = {_mirror_kind(first["id"]), _mirror_kind(second["id"])}
        if (
            kinds == {"gmail", "notification"}
            and (first_ref is None) != (second_ref is None)
            and _bank(first)
            and _bank(second)
        ):
            return _apart_ms(first, second) <= _NOTIFICATION_WINDOW_MS
        return False
    return _apart_ms(first, second) <= _SAME_SOURCE_WINDOW_MS


def _cross_source_window(first, second):
    """A notification/e-mail mirror against a real SMS/bank row."""
    if not _last4(first) or _last4(first) != _last4(second):
        return False

    merchants_missing = any(
        not value or value == "-"
        for value in (
            str(first["merchant_or_payee"] or "").strip(),
            str(second["merchant_or_payee"] or "").strip(),
        )
    )
    both_unreferenced = (
        (normalize_reference(first["reference"]) is None)
        == (normalize_reference(second["reference"]) is None)
    )
    if merchants_missing and both_unreferenced:
        # Neither side has a reference or merchant (typical card spend: the SMS
        # and the notification both carry only amount + last-4). Accept the pair
        # only inside a short window; repair_duplicate_transactions also needs
        # exactly one candidate, so two real same-amount purchases never merge.
        # An e-mail can be stamped well away from the SMS, so allow 24h for it
        # in either arrival order. (A reference on exactly one side already
        # makes bank + account + amount + direction + time identity strong
        # enough, so that case skips this short window.)
        window = _GMAIL_WINDOW_MS if _is_gmail(first) or _is_gmail(second) else _NOTIFICATION_WINDOW_MS
        return _apart_ms(first, second) <= window

    window = _GMAIL_WINDOW_MS if _is_gmail(first) or _is_gmail(second) else _PAIR_WINDOW_MS
    return _apart_ms(first, second) <= window


def _is_card_bill_payment_credit(row):
    return (
        _upper(row, "account_type") == "CREDIT_CARD"
        and _upper(row, "type") == "CREDIT"
        and "bill payment" in str(row["merchant_or_payee"] or "").lower()
    )


def _duplicate_without_reference(first, second):
    if not _same_amount(first, second) or not _same_side(first, second):
        return False

    # Equal part payments to one card, minutes apart, are separate payments
    # (five of 5,000 in one sitting). Only an equal reference, handled before
    # this point, proves two such credits are the same event.
    if _is_card_bill_payment_credit(first) and _is_card_bill_payment_credit(second):
        return False

    cross_source = _is_mirror(first) != _is_mirror(second)
    if not _same_account_identity(first, second, cross_source):
        return False
    if _payment_methods_conflict(first, second):
        return False
    if not _merchant_values_compatible(
        str(first["merchant_or_payee"] or "").strip(),
        str(second["merchant_or_payee"] or "").strip(),
    ):
        return False

    if not cross_source:
        return _same_source_kind_window(first, second)
    return _cross_source_window(first, second)


def _rows_are_duplicate(first, second):
    """Return True only for the same ledger-side transaction represented twice.

    Exact normalized reference is the primary identity. Amount and time are not
    identity requirements when the same reference is present. Bank/account and
    direction still protect the two legitimate sides of an own-account UPI
    transfer that share the same RRN.
    """
    if str(first["currency"]).strip().upper() != str(second["currency"]).strip().upper():
        return False
    if _banks_conflict(first, second) or _last4_conflict(first, second):
        return False

    first_ref = normalize_reference(first["reference"])
    second_ref = normalize_reference(second["reference"])
    if first_ref and second_ref:
        return _same_side(first, second) and first_ref == second_ref

    return _duplicate_without_reference(first, second)


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


def _is_card_bill_credit(row):
    return (
        str(row["account_type"]).strip().upper() == "CREDIT_CARD"
        and str(row["type"]).strip().upper() == "CREDIT"
    )


def _is_mirror_id(transaction_id):
    transaction_id = str(transaction_id)
    return transaction_id.startswith("gmail:") or transaction_id.startswith("notification:")


class _DuplicateRepair:
    """One pass of same-side duplicate collapsing over the active ledger rows."""

    def __init__(self, conn, rows):
        self.conn = conn
        self.rows = rows
        self.assigned = set()
        self.repaired = 0

    def run(self):
        self._collapse_referenced_groups()
        self._collapse_unreferenced_rows()
        return self.repaired

    def _enrich_canonical(self, canonical, duplicate):
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
            self.conn.execute(
                f"UPDATE transactions SET {assignments} WHERE id=?",
                values,
            )

    def _repair_pair(self, canonical, duplicate):
        conn = self.conn
        self._enrich_canonical(canonical, duplicate)

        canonical_adjustment = conn.execute(
            "SELECT account_id,delta_minor,applied_at FROM balance_adjustments WHERE transaction_id=?",
            (canonical["id"],),
        ).fetchone()
        duplicate_adjustment = conn.execute(
            "SELECT account_id,delta_minor,applied_at FROM balance_adjustments WHERE transaction_id=?",
            (duplicate["id"],),
        ).fetchone()

        if canonical_adjustment is None and duplicate_adjustment is not None:
            # Move the already-applied bank adjustment to the canonical row,
            # but still reverse any Splitwise contribution belonging to the
            # duplicate. The old fast path marked the duplicate VOIDED
            # directly, which left its Splitwise delta behind.
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

            # The Splitwise value is derived from the ledger, and the
            # duplicate drops out of it once voided below.
            conn.execute(
                "DELETE FROM splitwise_adjustments WHERE transaction_id=?",
                (duplicate["id"],),
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
        self.assigned.add(str(duplicate["id"]))
        self.repaired += 1

    def _collapse_referenced_groups(self):
        """Rows sharing a normalized reference are reconciled first."""
        referenced_groups = {}
        for row in self.rows:
            if _is_card_bill_credit(row):
                continue
            reference = normalize_reference(row["reference"])
            if reference:
                referenced_groups.setdefault(reference, []).append(row)

        for group in referenced_groups.values():
            remaining = [row for row in group if str(row["id"]) not in self.assigned]

            while remaining:
                canonical = max(remaining, key=_duplicate_canonical_score)
                duplicates = [
                    row for row in remaining
                    if str(row["id"]) != str(canonical["id"])
                    and _rows_are_duplicate(canonical, row)
                    and not _is_card_bill_credit(row)
                ]

                if not duplicates:
                    remaining = [
                        row for row in remaining
                        if str(row["id"]) != str(canonical["id"])
                    ]
                    continue

                for duplicate in duplicates:
                    self._repair_pair(canonical, duplicate)

                consumed = {str(canonical["id"])} | {str(row["id"]) for row in duplicates}
                remaining = [
                    row for row in remaining
                    if str(row["id"]) not in consumed
                ]
                self.assigned.add(str(canonical["id"]))

    def _referenced_candidates(self, row):
        """Referenced rows that look like `row`, plus their distinct references."""
        candidates = []
        candidate_refs = set()
        for referenced in self.rows:
            if str(referenced["id"]) == str(row["id"]) or str(referenced["id"]) in self.assigned:
                continue
            if _is_card_bill_credit(referenced):
                continue

            reference = normalize_reference(referenced["reference"])
            if not reference:
                continue

            if _rows_are_duplicate(row, referenced):
                candidates.append(referenced)
                candidate_refs.add(reference)
        return candidates, candidate_refs

    def _unreferenced_mirror_candidates(self, row):
        return [
            candidate for candidate in self.rows
            if (
                str(candidate["id"]) != str(row["id"])
                and str(candidate["id"]) not in self.assigned
                and not normalize_reference(candidate["reference"])
                and not _is_card_bill_credit(candidate)
                and _is_mirror_id(candidate["id"])
                and _rows_are_duplicate(row, candidate)
            )
        ]

    def _collapse_unreferenced_rows(self):
        """An unreferenced row may merge into exactly one distinct reference."""
        for row in self.rows:
            if str(row["id"]) in self.assigned or normalize_reference(row["reference"]):
                continue
            if _is_card_bill_credit(row):
                continue

            candidates, candidate_refs = self._referenced_candidates(row)

            # A mirror source can legitimately omit the reference. If
            # there is exactly one compatible cross-source mirror candidate,
            # it is still safe to collapse because amount/type/bank/account/
            # merchant identity are all checked by _rows_are_duplicate.
            if not candidate_refs:
                mirror_candidates_without_ref = self._unreferenced_mirror_candidates(row)
                if len(mirror_candidates_without_ref) == 1:
                    candidates = mirror_candidates_without_ref
                    candidate_refs.add("__MIRROR_NO_REFERENCE__")

            if len(candidate_refs) != 1:
                continue

            # When the current row is a real local/SMS transaction and
            # the only referenced candidate is a Gmail/notification mirror,
            # keep the local row canonical. Otherwise a later-arriving Gmail
            # reference could incorrectly become the ledger owner.
            row_is_mirror = _is_mirror_id(row["id"])
            mirror_candidates = [
                candidate for candidate in candidates
                if _is_mirror_id(candidate["id"])
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
                self._repair_pair(canonical, duplicate)
            self.assigned.add(str(canonical["id"]))


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
        return _DuplicateRepair(conn, rows).run()


def _identity_enrichment(canonical, duplicate):
    """Bank/account identity a duplicate can supply to a canonical row lacking it."""
    updates = {}
    if not str(canonical["bank"] or "").strip() and str(duplicate["bank"] or "").strip():
        updates["bank"] = duplicate["bank"]
    if not str(canonical["account_last4"] or "").strip() and str(duplicate["account_last4"] or "").strip():
        updates["account_last4"] = duplicate["account_last4"]
    return updates


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
                if tx["currency"] != c["currency"] or not _same_amount(tx, c):
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
                    was_provisional = is_provisional_row(canonical)
                    enrichment = _identity_enrichment(canonical, tx)
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
                        _apply_balance_after_identity_enrichment(
                            conn, canonical["id"], was_provisional, enrichment
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
                     AND (amount_minor=? OR (orig_amount_minor IS NOT NULL AND orig_amount_minor=? AND orig_currency IS ?)) AND currency=? AND type=?
                     AND ABS(timestamp-?)<=86400000
                   ORDER BY ABS(timestamp-?) LIMIT 5""",
                (tx["id"],tx["amount_minor"],tx["orig_amount_minor"],tx["orig_currency"],tx["currency"],tx["type"],tx["timestamp"],tx["timestamp"])
            ).fetchall()
        else:
            candidates=conn.execute(
                """SELECT * FROM transactions WHERE id<>? AND duplicate_of IS NULL
                   AND id NOT LIKE 'gmail:%' AND status='ACTIVE'
                   AND (amount_minor=? OR (orig_amount_minor IS NOT NULL AND orig_amount_minor=? AND orig_currency IS ?)) AND currency=? AND type=?
                   AND ABS(timestamp-?)<=86400000
                   ORDER BY ABS(timestamp-?) LIMIT 5""",
                (tx["id"],tx["amount_minor"],tx["orig_amount_minor"],tx["orig_currency"],tx["currency"],tx["type"],tx["timestamp"],tx["timestamp"])
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
            was_provisional = is_provisional_row(canonical)
            enrichment = _identity_enrichment(canonical, tx)
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
                _apply_balance_after_identity_enrichment(
                    conn, canonical["id"], was_provisional, enrichment
                )

            conn.execute("UPDATE evidence SET matched_transaction_id=? WHERE transaction_id=?",(canonical["id"],tx["id"]))
            return {
                "duplicate":tx["id"],
                "canonical":canonical["id"],
                "score":strong[0][0],
                "enriched":bool(enrichment),
            }
    return None
