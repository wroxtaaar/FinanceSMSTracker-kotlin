from email.message import EmailMessage

from app.gmail_imap import message_to_api_shape, query_to_imap_search


def test_imap_newer_than_query_maps_to_since():
    result = query_to_imap_search("newer_than:30d")
    assert result.startswith('(SINCE "')


def test_imap_unsupported_gmail_query_falls_back_to_all():
    assert query_to_imap_search("from:bank@example.com") == "ALL"


def test_imap_message_becomes_gmail_like_payload():
    message = EmailMessage()
    message["From"] = "alerts@example.com"
    message["Subject"] = "HDFC Bank Transaction Alert"
    message["Date"] = "Wed, 30 Sep 2026 12:00:00 +0530"
    message.set_content(
        "HDFC Bank A/c XX9591 debited INR 5.00. Ref UPI-12345."
    )

    payload = message_to_api_shape(
        message.as_bytes(),
        uid="123",
        uidvalidity="456",
        folder="INBOX",
    )

    assert payload["id"] == "imap:INBOX:456:123"
    assert payload["payload"]["headers"]
    assert payload["internalDate"] != "0"
    assert payload["payload"]["body"]["data"]


def test_imap_message_keeps_pdf_attachment_data():
    from email.message import EmailMessage

    message = EmailMessage()
    message["From"] = "HDFC Bank <alerts@hdfcbank.net>"
    message["Subject"] = "HDFC e-Statement"
    message.set_content("Your statement is attached.")
    message.add_attachment(
        b"%PDF-test",
        maintype="application",
        subtype="pdf",
        filename="HDFC_Statement_Aug_2026.pdf",
    )

    payload = message_to_api_shape(
        message.as_bytes(),
        uid="321",
        uidvalidity="654",
        folder="INBOX",
    )

    pdf_parts = [
        part for part in payload["payload"]["parts"]
        if part.get("filename") == "HDFC_Statement_Aug_2026.pdf"
    ]
    assert len(pdf_parts) == 1
    assert pdf_parts[0]["mimeType"] == "application/pdf"
    assert pdf_parts[0]["body"]["data"]
