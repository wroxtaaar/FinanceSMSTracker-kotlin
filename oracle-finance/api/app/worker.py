import logging
import os
import time

from .gmail_auth import sync as gmail_sync
from .splitwise import enabled as splitwise_enabled, sync_receivables

logger = logging.getLogger("oracle-finance.worker")


def main():
    logging.basicConfig(
        level=os.getenv("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    interval = max(900, int(os.getenv("FINANCE_SYNC_INTERVAL_SECONDS", "1800")))
    logger.info(
        "Finance worker started: interval=%ss gmail_enabled=%s splitwise_enabled=%s",
        interval,
        os.getenv("GMAIL_ENABLED", "false").lower() == "true",
        splitwise_enabled(),
    )

    while True:
        if os.getenv("GMAIL_ENABLED", "false").lower() == "true":
            try:
                created = gmail_sync()
                logger.info("Gmail sync completed: createdEvidence=%s", created)
            except Exception:
                logger.exception("Gmail sync failed")
        else:
            logger.debug("Gmail sync skipped: GMAIL_ENABLED is false")

        if splitwise_enabled():
            try:
                receivables = sync_receivables()
                logger.info("Splitwise sync completed: %s", receivables)
            except Exception:
                logger.exception("Splitwise sync failed")

        time.sleep(interval)


if __name__ == "__main__":
    main()
