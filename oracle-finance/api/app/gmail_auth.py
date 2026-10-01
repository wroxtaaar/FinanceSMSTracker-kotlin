import json
import os

from .gmail_sync import GMAIL_READONLY_SCOPE, ingest_messages


def _provider():
    return os.getenv("GMAIL_PROVIDER", "oauth").strip().lower()


def _paths():
    return (
        os.getenv("GMAIL_CREDENTIALS", "/app/secrets/credentials.json"),
        os.getenv("GMAIL_TOKEN", "/app/secrets/gmail-token.json"),
    )


def _redirect():
    value = os.getenv("GMAIL_REDIRECT_URI", "").strip()
    if not value:
        raise RuntimeError("GMAIL_REDIRECT_URI is not configured")
    return value


def _google_flow(*, state=None):
    try:
        from google_auth_oauthlib.flow import Flow
    except ImportError as exc:
        raise RuntimeError(
            "Google OAuth dependencies are not installed. Use GMAIL_PROVIDER=imap "
            "or install the optional Google OAuth packages."
        ) from exc

    credentials_file, _ = _paths()
    flow_kwargs = {"scopes": [GMAIL_READONLY_SCOPE]}
    if state:
        flow_kwargs["state"] = state
    return Flow.from_client_secrets_file(credentials_file, **flow_kwargs)


def auth_url():
    if _provider() != "oauth":
        raise RuntimeError("OAuth authorization is disabled in IMAP mode")

    flow = _google_flow()
    flow.redirect_uri = _redirect()
    url, state = flow.authorization_url(
        access_type="offline",
        include_granted_scopes="true",
        prompt="consent",
    )
    with open("/app/data/gmail-oauth-state.json", "w") as f:
        json.dump({"state": state}, f)
    return url


def finish_callback(authorization_response):
    if _provider() != "oauth":
        raise RuntimeError("OAuth callback is disabled in IMAP mode")

    _, token_file = _paths()
    with open("/app/data/gmail-oauth-state.json") as f:
        state = json.load(f)["state"]

    flow = _google_flow(state=state)
    flow.redirect_uri = _redirect()
    flow.fetch_token(authorization_response=authorization_response)

    os.makedirs(os.path.dirname(token_file), exist_ok=True)
    with open(token_file, "w") as f:
        f.write(flow.credentials.to_json())

    return True


def _google_service():
    try:
        from google.oauth2.credentials import Credentials
        from googleapiclient.discovery import build
    except ImportError as exc:
        raise RuntimeError(
            "Google OAuth dependencies are not installed. Use GMAIL_PROVIDER=imap "
            "or install the optional Google OAuth packages."
        ) from exc

    _, token_file = _paths()
    if not os.path.exists(token_file):
        raise RuntimeError("Gmail is not authorized")

    creds = Credentials.from_authorized_user_file(
        token_file,
        [GMAIL_READONLY_SCOPE],
    )
    return build("gmail", "v1", credentials=creds)


def service(historical=False):
    if _provider() == "imap":
        from .gmail_imap import IMAPService
        return IMAPService(historical=historical)

    if _provider() == "oauth":
        return _google_service()

    raise RuntimeError(f"Unsupported GMAIL_PROVIDER: {_provider()}")


def sync(query=None, historical=False):
    gmail_service = service(historical=historical)
    try:
        return ingest_messages(
            gmail_service,
            query or os.getenv("GMAIL_QUERY", "newer_than:30d"),
        )
    finally:
        close = getattr(gmail_service, "close", None)
        if callable(close):
            close()
