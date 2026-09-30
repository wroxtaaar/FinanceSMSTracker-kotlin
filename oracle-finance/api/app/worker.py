import os,time
from .gmail_auth import sync as gmail_sync
from .splitwise import enabled as splitwise_enabled, sync_receivables

def main():
    interval=max(900,int(os.getenv("FINANCE_SYNC_INTERVAL_SECONDS","1800")))
    while True:
        if os.getenv("GMAIL_ENABLED","false").lower()=="true":
            try: gmail_sync()
            except Exception: pass
        if splitwise_enabled():
            try: sync_receivables()
            except Exception: pass
        time.sleep(interval)

if __name__=="__main__": main()
