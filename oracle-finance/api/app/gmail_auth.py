import json, os
from google_auth_oauthlib.flow import Flow
from google.oauth2.credentials import Credentials
from googleapiclient.discovery import build
from .gmail_sync import GMAIL_READONLY_SCOPE, ingest_messages

def _paths():
    return os.getenv("GMAIL_CREDENTIALS","/app/secrets/credentials.json"), os.getenv("GMAIL_TOKEN","/app/secrets/gmail-token.json")

def _redirect():
    value=os.getenv("GMAIL_REDIRECT_URI","").strip()
    if not value: raise RuntimeError("GMAIL_REDIRECT_URI is not configured")
    return value

def auth_url():
    credentials_file,_=_paths()
    flow=Flow.from_client_secrets_file(credentials_file,scopes=[GMAIL_READONLY_SCOPE])
    flow.redirect_uri=_redirect()
    url,state=flow.authorization_url(access_type="offline",include_granted_scopes="true",prompt="consent")
    with open("/app/data/gmail-oauth-state.json","w") as f: json.dump({"state":state},f)
    return url

def finish_callback(authorization_response):
    credentials_file,token_file=_paths()
    with open("/app/data/gmail-oauth-state.json") as f: state=json.load(f)["state"]
    flow=Flow.from_client_secrets_file(credentials_file,scopes=[GMAIL_READONLY_SCOPE],state=state)
    flow.redirect_uri=_redirect()
    flow.fetch_token(authorization_response=authorization_response)
    os.makedirs(os.path.dirname(token_file),exist_ok=True)
    with open(token_file,"w") as f: f.write(flow.credentials.to_json())
    return True

def service():
    _,token_file=_paths()
    if not os.path.exists(token_file): raise RuntimeError("Gmail is not authorized")
    creds=Credentials.from_authorized_user_file(token_file,[GMAIL_READONLY_SCOPE])
    return build("gmail","v1",credentials=creds)

def sync(query=None):
    return ingest_messages(service(),query or os.getenv("GMAIL_QUERY","newer_than:30d"))
