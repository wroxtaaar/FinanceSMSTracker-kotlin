import json, os, urllib.error, urllib.parse, urllib.request
from decimal import Decimal, ROUND_HALF_UP
from .ledger import get_splitwise_rule_for, record_splitwise_expense, splitwise_expense_for

BASE_URL="https://secure.splitwise.com/api/v3.0"

def enabled():
    return os.getenv("SPLITWISE_ENABLED","false").lower()=="true" and bool(os.getenv("SPLITWISE_ACCESS_TOKEN","").strip())

def _request(method,path,payload=None):
    token=os.getenv("SPLITWISE_ACCESS_TOKEN","").strip()
    if not token:
        raise RuntimeError("SPLITWISE_ACCESS_TOKEN is not configured")
    data=urllib.parse.urlencode(payload or {}).encode() if payload is not None else None
    headers={"Authorization":f"Bearer {token}","Accept":"application/json"}
    if payload is not None:
        headers["Content-Type"]="application/x-www-form-urlencoded"
    req=urllib.request.Request(BASE_URL+path,data=data,headers=headers,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"Splitwise HTTP {e.code}: {e.read().decode(errors='replace')}") from e

def current_user():
    return _request("GET","/get_current_user")

def groups():
    return _request("GET","/get_groups")

def friends():
    return _request("GET","/get_friends")

def _money(minor):
    return str((Decimal(minor)/Decimal(100)).quantize(Decimal("0.01"),rounding=ROUND_HALF_UP))

def create_for_transaction(tx):
    existing=splitwise_expense_for(tx["id"])
    if existing and existing["status"]=="CREATED":
        return existing
    rule=get_splitwise_rule_for(tx.get("merchant_or_payee"))
    if not rule or tx["type"]!="DEBIT" or not enabled():
        return None
    payload={
        "cost":_money(tx["amount_minor"]),
        "description":tx.get("merchant_or_payee") or "Shared expense",
        "date":__import__("datetime").datetime.fromtimestamp(
            tx["timestamp"]/1000,
            tz=__import__("datetime").timezone.utc
        ).isoformat(),
        "currency_code":tx["currency"],
        "group_id":str(rule["group_id"]),
        "split_equally":"true",
    }
    if rule["split_mode"]!="EQUAL" and rule["user_shares_json"]:
        payload.pop("split_equally",None)
        for i,share in enumerate(json.loads(rule["user_shares_json"])):
            payload[f"users__{i}__user_id"]=str(share["user_id"])
            payload[f"users__{i}__paid_share"]=str(share.get("paid_share","0.00"))
            payload[f"users__{i}__owed_share"]=str(share["owed_share"])
    response=_request("POST","/create_expense",payload)
    errors=response.get("errors") or {}
    if errors:
        record_splitwise_expense(tx["id"],None,"ERROR",json.dumps(errors))
        return {"status":"ERROR","errors":errors}
    expenses=response.get("expenses") or []
    expense_id=str(expenses[0]["id"]) if expenses else None
    record_splitwise_expense(tx["id"],expense_id,"CREATED")
    return {"status":"CREATED","expenseId":expense_id}

def sync_receivables():
    from .db import connection
    from .ledger import now_ms

    data=friends()
    items=[]
    with connection() as conn:
        conn.execute("UPDATE splitwise_receivables SET status='CLOSED' WHERE id LIKE 'splitwise:friend:%'")
        for friend in data.get("friends",[]):
            friend_id=friend.get("id")
            name=((friend.get("first_name") or "")+" "+(friend.get("last_name") or "")).strip() or str(friend_id)
            for balance in friend.get("balance",[]) or []:
                amount=Decimal(str(balance.get("amount","0")))
                currency=balance.get("currency_code") or "INR"
                if amount<=0:
                    continue
                minor=int((amount*100).quantize(Decimal("1"),rounding=ROUND_HALF_UP))
                rid=f"splitwise:friend:{friend_id}:{currency}"
                conn.execute(
                    """INSERT INTO splitwise_receivables
                       (id,description,amount_minor,currency,splitwise_expense_id,status,created_at)
                       VALUES(?,?,?,?,NULL,'OPEN',?)
                       ON CONFLICT(id) DO UPDATE SET
                       description=excluded.description,
                       amount_minor=excluded.amount_minor,
                       currency=excluded.currency,
                       status='OPEN'""",
                    (rid,"Splitwise: "+name,minor,currency,now_ms())
                )
                items.append({"id":rid,"name":name,"amountMinor":minor,"currency":currency})
    return items
