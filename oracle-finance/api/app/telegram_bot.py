import json,os,time,urllib.parse,urllib.request
from .ledger import true_available,balances,list_review_queue,list_transactions
TOKEN=os.getenv("TELEGRAM_BOT_TOKEN","").strip()
ALLOWED={x.strip() for x in os.getenv("TELEGRAM_ALLOWED_CHAT_IDS","").split(",") if x.strip()}

def tg(method,params=None):
    req=urllib.request.Request(f"https://api.telegram.org/bot{TOKEN}/{method}",
        data=urllib.parse.urlencode(params or {}).encode(),method="POST")
    with urllib.request.urlopen(req,timeout=60) as r:return json.loads(r.read().decode())

def send(cid,text): tg("sendMessage",{"chat_id":cid,"text":text})
def allowed(cid): return not ALLOWED or str(cid) in ALLOWED

def main():
    if not TOKEN: raise SystemExit("TELEGRAM_BOT_TOKEN is not configured")
    offset=0
    while True:
        try:
            for u in tg("getUpdates",{"timeout":50,"offset":offset}).get("result",[]):
                offset=u["update_id"]+1; m=u.get("message",{}); cid=m.get("chat",{}).get("id")
                if cid is None or not allowed(cid): continue
                cmd=(m.get("text") or "").strip().split()[0].lower()
                if cmd=="/summary":
                    s=true_available("INR"); send(cid,f"Net available: ₹{s['trueAvailableMinor']/100:,.2f}\nBank cash: ₹{s['bankCashMinor']/100:,.2f}\nOthers owe you: ₹{s['splitwiseReceivableMinor']/100:,.2f}\nCard outstanding: ₹{s['creditCardOutstandingMinor']/100:,.2f}")
                elif cmd=="/balances":
                    rows=balances(); send(cid,"No account balances configured." if not rows else "\n".join(f"{r['name']}: ₹{r['balance_minor']/100:,.2f}" for r in rows))
                elif cmd=="/pending":
                    rows=list_review_queue(); send(cid,"No pending reviews." if not rows else "\n".join(f"{r['kind']}: {r['reason']}" for r in rows[:10]))
                elif cmd=="/recent":
                    rows=list_transactions(10); send(cid,"No transactions." if not rows else "\n".join(f"{r['type']} ₹{r['amount_minor']/100:,.2f} {r.get('merchant_or_payee') or ''}".strip() for r in rows))
                elif cmd=="/help": send(cid,"/summary\n/balances\n/recent\n/pending\n/help")
        except Exception: time.sleep(5)

if __name__=="__main__": main()
