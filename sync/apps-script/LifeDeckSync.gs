/**
 * LifeDeck ↔ Google Sheets sync bridge.
 *
 * Deploy this as a Web App from YOUR spreadsheet:
 *   1. Create a Google Sheet (or use an existing one).
 *   2. Extensions → Apps Script, paste this file, Save.
 *   3. Project Settings → Script properties → add SECRET (optional but recommended).
 *   4. Deploy → New deployment → Web app → Execute as: Me →
 *      Who has access: Anyone with the link → copy the /exec URL.
 *   5. In LifeDeck → Settings → Google Sheets sync: paste the URL and SECRET.
 *
 * Contract (matches the Android app):
 *   GET  ?action=ping                 → {"ok":true}
 *   GET  ?action=pull&since=<epochMs> → {"ok":true,"rows":[{table,id,row}],"serverTime":<ms>}
 *   POST {"action":"push","secret":"…","deviceId":"…","rows":[{table,id,op,row}]}
 *                                    → {"ok":true,"applied":N}
 *
 * Conflict rule: last-writer-wins on the row's `updatedAt` field (epoch ms),
 * so replays and retries can never duplicate or downgrade a record.
 */

var TABLES = ['entries', 'goals', 'projects', 'milestones', 'trips',
              'categories', 'reminders', 'memories', 'history'];

function doGet(e) {
  try {
    var action = (e && e.parameter && e.parameter.action) || 'ping';
    checkSecret_(e && e.parameter && e.parameter.secret);
    if (action === 'ping') return json_({ ok: true, time: Date.now() });
    if (action === 'pull') return json_({ ok: true, rows: pull_(e.parameter), serverTime: Date.now() });
    return json_({ ok: false, error: 'Unknown action: ' + action });
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  }
}

function doPost(e) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(20000);
    var body = JSON.parse(e.postData.contents);
    checkSecret_(body.secret);
    var applied = push_(body.rows || []);
    return json_({ ok: true, applied: applied });
  } catch (err) {
    return json_({ ok: false, error: String(err && err.message ? err.message : err) });
  } finally {
    try { lock.releaseLock(); } catch (ignored) {}
  }
}

function checkSecret_(provided) {
  var expected = PropertiesService.getScriptProperties().getProperty('SECRET');
  if (expected && String(provided || '') !== expected) {
    throw new Error('Invalid or missing secret');
  }
}

// ------------------------------------------------------------------ pull

function pull_(params) {
  var since = Number(params.since || 0);
  var limit = Math.min(Number(params.limit || 500), 2000);
  var rows = [];
  TABLES.forEach(function (table) {
    var sheet = sheet_(table);
    if (!sheet) return;
    var values = sheet.getDataRange().getValues();
    for (var i = 1; i < values.length; i++) { // skip header
      var id = String(values[i][0]);
      var updatedAt = Number(values[i][1]);
      var json = String(values[i][2]);
      if (!id || !json) continue;
      if (updatedAt > since) {
        try {
          rows.push({ table: table, id: id, row: JSON.parse(json) });
        } catch (ignored) {}
        if (rows.length >= limit) return;
      }
    }
  });
  return rows;
}

// ------------------------------------------------------------------ push

function push_(rows) {
  var applied = 0;
  var byTable = {};
  rows.forEach(function (r) {
    if (TABLES.indexOf(r.table) < 0) return;
    if (!byTable[r.table]) byTable[r.table] = [];
    byTable[r.table].push(r);
  });

  Object.keys(byTable).forEach(function (table) {
    var sheet = sheet_(table, true);
    var index = index_(sheet); // id → row number
    byTable[table].forEach(function (item) {
      var existing = index[item.id];
      if (item.op === 'DELETE') {
        if (existing) {
          var row = existing.row;
          sheet.getRange(row, 1, 1, 3).setValues([[item.id, Date.now(), JSON.stringify({ id: item.id, deleted: true, updatedAt: Date.now() })]]);
        }
        applied++;
        return;
      }
      var payload = item.row || {};
      var updatedAt = Number(payload.updatedAt || Date.now());
      if (existing) {
        var current = {};
        try { current = JSON.parse(existing.json); } catch (ignored) {}
        if (updatedAt < Number(current.updatedAt || 0)) {
          return; // local sheet copy is newer — keep it (LWW)
        }
        sheet.getRange(existing.row, 1, 1, 3).setValues([[item.id, updatedAt, JSON.stringify(payload)]]);
      } else {
        sheet.appendRow([item.id, updatedAt, JSON.stringify(payload)]);
      }
      applied++;
    });
  });
  return applied;
}

function index_(sheet) {
  var values = sheet.getDataRange().getValues();
  var map = {};
  for (var i = 1; i < values.length; i++) {
    var id = String(values[i][0]);
    if (!id) continue;
    map[id] = { row: i + 1, json: String(values[i][2] || '') };
  }
  return map;
}

// ------------------------------------------------------------------ utils

function sheet_(table, create) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheet = ss.getSheetByName(table);
  if (!sheet && create) {
    sheet = ss.insertSheet(table);
    sheet.appendRow(['id', 'updatedAt', 'json']);
    sheet.setFrozenRows(1);
  }
  return sheet;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
