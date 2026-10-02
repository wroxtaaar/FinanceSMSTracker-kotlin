const SHEET_ID = '1qP1uLVNwiMKu-YtkYTgfyKCYHHqPT4eLW76DwyvDtss';
const API_URL_PROPERTY = 'FINANCE_SHEETS_API_URL';
const TOKEN_PROPERTY = 'FINANCE_SHEETS_TOKEN';

function onOpen() {
  SpreadsheetApp.getUi()
    .createMenu('Finance Tracker')
    .addItem('Sync now', 'syncFinanceTracker')
    .addItem('Install daily sync', 'installDailySync')
    .addToUi();
}

function syncFinanceTracker() {
  const props = PropertiesService.getScriptProperties();
  const apiUrl = props.getProperty(API_URL_PROPERTY);
  const token = props.getProperty(TOKEN_PROPERTY);

  if (!apiUrl || !token) {
    throw new Error('Set FINANCE_SHEETS_API_URL and FINANCE_SHEETS_TOKEN in Script Properties first.');
  }

  const response = UrlFetchApp.fetch(apiUrl.replace(/\/$/, '') + '/snapshot', {
    method: 'get',
    headers: { 'X-Sheets-Token': token },
    muteHttpExceptions: true
  });

  if (response.getResponseCode() !== 200) {
    throw new Error('Finance API returned HTTP ' + response.getResponseCode() + ': ' + response.getContentText());
  }

  const data = JSON.parse(response.getContentText());
  const ss = SpreadsheetApp.openById(SHEET_ID);

  writeDashboard_(ss, data);
  writeHistory_(ss, data);
  writeAccounts_(ss, data);
  writeTransactions_(ss, data);

  SpreadsheetApp.flush();
}

function installDailySync() {
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'syncFinanceTracker')
    .forEach(t => ScriptApp.deleteTrigger(t));

  ScriptApp.newTrigger('syncFinanceTracker')
    .timeBased()
    .everyDays(1)
    .atHour(1)
    .create();
}

function writeDashboard_(ss, data) {
  const sheet = getOrCreateSheet_(ss, 'Dashboard');
  sheet.clear();

  const s = data.summary;
  const rows = [
    ['Finance Tracker', ''],
    ['Generated', new Date(data.generatedAt)],
    ['Bank Cash', minorToRupees_(s.bankCashMinor)],
    ['Card Outstanding', minorToRupees_(s.creditCardOutstandingMinor)],
    ['Splitwise Owed', minorToRupees_(s.splitwiseReceivableMinor)],
    ['True Available', minorToRupees_(s.trueAvailableMinor)],
  ];

  sheet.getRange(1, 1, rows.length, 2).setValues(rows);
  sheet.getRange('A1').setFontSize(18).setFontWeight('bold');
  sheet.getRange('A3:A6').setFontWeight('bold');
  sheet.getRange('B3:B6').setNumberFormat('₹#,##0.00');
  sheet.autoResizeColumns(1, 2);
}

function writeHistory_(ss, data) {
  const sheet = getOrCreateSheet_(ss, 'Daily History');
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(['Date', 'Bank Cash', 'Card Outstanding', 'Splitwise Owed', 'True Available']);
  }

  const date = startOfDay_(new Date(data.generatedAt));
  const rows = sheet.getDataRange().getValues();
  let targetRow = -1;

  for (let i = 1; i < rows.length; i++) {
    if (rows[i][0] instanceof Date && startOfDay_(rows[i][0]).getTime() === date.getTime()) {
      targetRow = i + 1;
      break;
    }
  }

  const values = [[
    date,
    minorToRupees_(data.summary.bankCashMinor),
    minorToRupees_(data.summary.creditCardOutstandingMinor),
    minorToRupees_(data.summary.splitwiseReceivableMinor),
    minorToRupees_(data.summary.trueAvailableMinor)
  ]];

  if (targetRow === -1) {
    sheet.getRange(sheet.getLastRow() + 1, 1, 1, 5).setValues(values);
  } else {
    sheet.getRange(targetRow, 1, 1, 5).setValues(values);
  }

  sheet.getRange('A:A').setNumberFormat('dd-mmm-yyyy');
  sheet.getRange('B:E').setNumberFormat('₹#,##0.00');
  sheet.autoResizeColumns(1, 5);
}

function writeAccounts_(ss, data) {
  const sheet = getOrCreateSheet_(ss, 'Accounts');
  sheet.clear();

  const values = [['Account', 'Type', 'Bank', 'Last 4', 'Current Balance', 'Bill Balance', 'Active Spend', 'Total Outstanding']];

  data.accounts.forEach(a => {
    const balance = minorToRupees_(a.balanceMinor);
    const bill = minorToRupees_(a.billBalanceMinor || 0);
    const active = minorToRupees_(a.activeSpendMinor || 0);
    values.push([a.name, a.accountType, a.bank || '', a.last4 || '', balance, bill, active,
                 a.accountType === 'CREDIT_CARD' ? bill + active : 0]);
  });

  sheet.getRange(1, 1, values.length, values[0].length).setValues(values);
  sheet.getRange(1, 1, 1, 8).setFontWeight('bold');
  sheet.getRange(2, 5, Math.max(values.length - 1, 1), 4).setNumberFormat('₹#,##0.00');
  sheet.autoResizeColumns(1, 8);
}

function writeTransactions_(ss, data) {
  const sheet = getOrCreateSheet_(ss, 'Transactions');
  sheet.clear();

  const values = [['Date', 'Source', 'Account', 'Type', 'Amount', 'Category', 'Merchant', 'Reference', 'Status']];

  data.transactions.forEach(t => {
    values.push([
      new Date(t.timestamp),
      t.source,
      (t.bank || '') + (t.last4 ? ' ' + t.last4 : ''),
      t.type,
      minorToRupees_(t.amountMinor),
      t.category || '',
      t.merchant || '',
      t.reference || '',
      t.status || ''
    ]);
  });

  sheet.getRange(1, 1, values.length, values[0].length).setValues(values);
  sheet.getRange(1, 1, 1, 9).setFontWeight('bold');
  if (values.length > 1) {
    sheet.getRange(2, 1, values.length - 1, 1).setNumberFormat('dd-mmm-yyyy hh:mm');
    sheet.getRange(2, 5, values.length - 1, 1).setNumberFormat('₹#,##0.00');
  }
  sheet.autoResizeColumns(1, 9);
}

function getOrCreateSheet_(ss, name) {
  return ss.getSheetByName(name) || ss.insertSheet(name);
}

function minorToRupees_(minor) {
  return Number(minor || 0) / 100;
}

function startOfDay_(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}
