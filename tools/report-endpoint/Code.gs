/**
 * PUB content-report endpoint: a Google Apps Script web app bound to a Google Sheet.
 *
 * PUB's Report content form POSTs one JSON report (see ContentReport.java). This script validates it, appends a row
 * to the "Reports" sheet and, optionally, emails the script owner. Deployment steps are in README.md next to this file.
 *
 * The web app must be deployed with "Execute as: Me" and "Who has access: Anyone", because PUB has no Google sign-in.
 * Anyone who finds the URL can post, so every field is validated and size-limited, and email notifications are capped
 * per day.
 */

const SHEET_NAME = 'Reports';
/** Email the script owner about each new report, up to MAX_EMAILS_PER_DAY. */
const NOTIFY_BY_EMAIL = true;
const MAX_EMAILS_PER_DAY = 20;
const MAX_BODY_CHARS = 100000;

const SUBJECTS = ['build', 'research', 'app'];
const REASONS = ['offensive', 'sexual', 'harmful', 'misleading', 'other'];
const HEADERS = ['Received', 'Subject', 'Reason', 'Details', 'PUB version', 'App name', 'App description',
  'Research summary', 'Sources', 'Code', 'Code truncated'];

function doPost(e) {
  let report;
  try {
    const body = e && e.postData && e.postData.contents;
    if (typeof body !== 'string' || body.length > MAX_BODY_CHARS) throw new Error('size');
    report = parseReport(JSON.parse(body));
  } catch (err) {
    return reply({ ok: false, error: 'invalid report' });
  }
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    reportSheet().appendRow([new Date(), report.subject, report.reason, report.details, report.version, report.name,
      report.description, report.research, report.sources, report.code, report.codeTruncated].map(cell));
  } finally {
    lock.releaseLock();
  }
  if (NOTIFY_BY_EMAIL) {
    try { notify(report); } catch (err) { console.warn('Report notification failed: ' + err); }
  }
  return reply({ ok: true });
}

/** Keeps only the known fields, at PUB's own limits; throws when the report is not a PUB report. */
function parseReport(o) {
  if (!o || typeof o !== 'object' || o.kind !== 'pub-content-report' || o.version !== 1) throw new Error('kind');
  if (SUBJECTS.indexOf(o.subject) < 0 || REASONS.indexOf(o.reason) < 0) throw new Error('enum');
  const c = o.content && typeof o.content === 'object' ? o.content : {};
  const sources = Array.isArray(c.sources) ? c.sources.slice(0, 12)
    .map(function (s) { return s && typeof s === 'object' ? (text(s.title, 200) + ' ' + text(s.url, 2000)).trim() : ''; })
    .filter(function (s) { return s; }).join('\n') : '';
  return {
    subject: o.subject,
    reason: o.reason,
    details: text(o.details, 2000),
    version: text(o.pubVersion, 40),
    name: text(c.name, 80),
    description: text(c.description, 500),
    research: text(c.research, 4000),
    sources: sources,
    code: text(c.code, 40000),
    codeTruncated: c.codeTruncated === true
  };
}

function text(value, max) {
  return typeof value === 'string' ? value.slice(0, max) : '';
}

/** Sheets would treat text starting with = + - or @ as a formula; store it as plain text instead. */
function cell(value) {
  return typeof value === 'string' && /^[=+\-@]/.test(value) ? "'" + value : value;
}

function reportSheet() {
  const book = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = book.getSheetByName(SHEET_NAME);
  if (!sheet) sheet = book.insertSheet(SHEET_NAME);
  if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADERS);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function notify(report) {
  const properties = PropertiesService.getScriptProperties();
  const key = 'emails-' + Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
  const sent = Number(properties.getProperty(key) || '0');
  if (sent >= MAX_EMAILS_PER_DAY) return;
  properties.setProperty(key, String(sent + 1));
  const owner = Session.getEffectiveUser().getEmail();
  if (!owner) return;
  const what = report.name ? ' about "' + report.name + '"' : '';
  MailApp.sendEmail(owner, 'PUB content report: ' + report.reason + what,
    'Subject: ' + report.subject + '\nReason: ' + report.reason + '\nPUB version: ' + report.version +
    '\n\nDetails:\n' + (report.details || '(none)') +
    '\n\nThe full report, including any reported content, is in the "' + SHEET_NAME + '" sheet:\n' +
    SpreadsheetApp.getActiveSpreadsheet().getUrl());
}

function reply(object) {
  return ContentService.createTextOutput(JSON.stringify(object)).setMimeType(ContentService.MimeType.JSON);
}
