# Google Sheets Finance Tracker

Spreadsheet: https://docs.google.com/spreadsheets/d/1qP1uLVNwiMKu-YtkYTgfyKCYHHqPT4eLW76DwyvDtss/edit

## Apps Script

1. Open the spreadsheet.
2. Extensions -> Apps Script.
3. Replace the default Code.gs contents with `Code.gs` from this folder.
4. In Apps Script, open Project Settings -> Script Properties.
5. Add:
   - `FINANCE_SHEETS_API_URL` = the public HTTPS base URL of the isolated finance-sheets service
   - `FINANCE_SHEETS_TOKEN` = the same value as Oracle `SHEETS_SYNC_TOKEN`
6. Save and run `syncFinanceTracker` once to authorize the script.
7. Reload the spreadsheet. Use Finance Tracker -> Sync now.
8. Run Finance Tracker -> Install daily sync once.

The script only calls the read-only `/snapshot` endpoint. It does not access Gmail, the Oracle database directly, or any other finance API endpoint.

Google Apps Script uses UrlFetchApp for external HTTPS APIs and SpreadsheetApp for the current spreadsheet.
