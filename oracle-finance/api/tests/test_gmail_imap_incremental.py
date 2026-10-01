import base64
from datetime import datetime, timezone

from app.gmail_imap import IMAPService
from app.gmail_sync import parse_bank_email


class FakeIMAP:
    def __init__(self):
        self.searches = []
        self.fetches = []

    def uid(self, command, charset, *args):
        if command == "SEARCH":
            criteria = args[0]
            self.searches.append(criteria)
            return "OK", [b"101 102"] if "alerts@axis.bank.in" in criteria else [b""]
        if command == "FETCH":
            uid_set = args[0]
            self.fetches.append(uid_set)
            items = []
            for uid in uid_set.split(","):
                items.append(
                    (
                        f"{uid} FETCH (UID {uid} X-GM-MSGID 900{uid})".encode(),
                        b"From: Axis Bank Alerts <alerts@axis.bank.in>\r\n"
                        b"Subject: INR 1.00 was credited to your A/c.\r\n"
                        b"Date: Thu, 01 Oct 2026 17:15:42 +0530\r\n"
                        b"Message-ID: <test-%b@example.com>\r\n"
                        b"\r\n",
                    )
                )
            return "OK", items
        raise AssertionError(command)


def test_incremental_imap_uses_sender_scoped_searches_only():
    service = IMAPService.__new__(IMAPService)
    service._imap = FakeIMAP()
    service.uidvalidity = "7"
    service._last_synced_since = lambda: datetime(
        2026, 10, 1, tzinfo=timezone.utc
    )

    result = service._list_messages("newer_than:30d", 100)

    searches = service._imap.searches
    assert searches
    assert all("SINCE 01-Oct-2026" in s for s in searches)
    assert any('FROM "alerts@axis.bank.in"' in s for s in searches)
    assert not any("FROM \"hdfcbank" in s.lower() for s in searches)
    assert not any("@axis.bank.in" in s and s.count("FROM") > 1 for s in searches)
    assert len(result["messages"]) == 2


def _payload(sender, subject, body):
    def enc(value):
        return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")

    return {
        "id": "imap:INBOX:1:1",
        "internalDate": str(int(datetime(2026, 10, 1, tzinfo=timezone.utc).timestamp() * 1000)),
        "payload": {
            "headers": [
                {"name": "From", "value": sender},
                {"name": "Subject", "value": subject},
            ],
            "body": {"data": enc(body)},
        },
    }


def test_axis_credit_email_parser_handles_real_format():
    message = _payload(
        "Axis Bank Alerts <alerts@axis.bank.in>",
        "INR 1.00 was credited to your A/c.",
        """
        01-10-2026 Dear Abdul Wasiq,
        Amount Credited: INR 1.00
        Account Number: XX3370
        Date & Time: 01-10-26, 17:15:42 IST
        Transaction Info: UPI/P2A/18335801167/ABDUL WAS/HDFC/Paym
        """,
    )

    parsed = parse_bank_email(message)
    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.bank == "AXIS"
    assert transaction.type == "CREDIT"
    assert transaction.accountLast4 == "3370"
    assert transaction.amountMinor == 100
    assert evidence.sourceType == "GMAIL"


def test_icici_credit_card_email_parser_handles_real_format():
    message = _payload(
        "ICICI Bank <credit_cards@icici.bank.in>",
        "Transaction alert for your ICICI Bank Credit Card",
        """
        Dear Customer, Your ICICI Bank Credit Card XX1012 has been used
        for a transaction of INR 548.00 on Oct 01, 2026 at 06:05:28.
        Info: AMAZON PAY IN RECHARGE.
        The Available Credit Limit on your card is INR 3,46,849.06 and
        Total Credit Limit is INR 3,80,000.00.
        """,
    )

    parsed = parse_bank_email(message)
    assert parsed is not None
    transaction, evidence = parsed
    assert transaction.bank == "ICICI"
    assert transaction.type == "DEBIT"
    assert transaction.accountLast4 == "1012"
    assert transaction.amountMinor == 54800
    assert transaction.accountType == "CREDIT_CARD"
    assert evidence.sourceType == "GMAIL"
