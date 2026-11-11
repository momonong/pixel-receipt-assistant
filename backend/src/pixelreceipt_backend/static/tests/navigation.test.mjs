import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { ApiError, errorMessage } from "../api.mjs";
import * as values from "../values.mjs";
import * as review from "../review.mjs";

const A = "00000000-0000-4000-8000-000000000001";
const B = "00000000-0000-4000-8000-000000000002";
const L = "00000000-0000-4000-8000-000000000003";
const plain = (value) => JSON.parse(JSON.stringify(value));

async function appHarness(hash = "#overview") {
  const events = new Map(); const calls = []; const historyCalls = [];
  let url = new URL(`https://receipts.example/receipts/?sample=1${hash}`);
  const location = {
    get href() { return url.href; }, get hash() { return url.hash; },
    set hash(value) { url.hash = value; },
    replace(value) { url = new URL(value, url); },
  };
  const history = Object.fromEntries(["pushState", "replaceState"].map((method) => [method, (_state, _title, value) => {
    url = new URL(value, url); historyCalls.push([method, url.hash]);
  }]));
  const fakeNode = () => ({open: true, listeners: new Map(), append() {}, replaceChildren() {},
    addEventListener(name, callback) { this.listeners.set(name, callback); },
    close() { this.open = false; this.listeners.get("close")?.(); },
  });
  const sandbox = { ...values, ...review, ApiError, errorMessage, appBase: new URL("https://receipts.example/receipts/"),
    URL, AbortController, structuredClone, queueMicrotask, setTimeout, clearTimeout, console,
    location, history, Node: class {},
    document: {getElementById: () => null, querySelector: () => ({value: ""}), querySelectorAll: () => [],},
    window: {confirm: () => false, addEventListener(name, callback) { events.set(name, callback); }},
    Api: class {
      async session() { return {role: "reviewer"}; }
      async request(path, options) {
        calls.push([path, options]);
        if (path.endsWith("/intake-context")) return {draft_id: path.split("/")[2], intake: null};
        if (path.includes("/confirm")) return {id: L, status: "active"};
        return {id: path.split("/").at(-1), status: "needs_review", revision: 1,
          candidate: {items: [], adjustments: []}};
      }
    },
    fakeNode,
  };
  const source = (await readFile(new URL("../app.js", import.meta.url), "utf8"))
    .replace(/^import .*;\n/gm, "").replace(/^start\(\);\s*$/m, "");
  const context = vm.createContext(sandbox);
  vm.runInContext(source, context);
  vm.runInContext(`
    renderShell = () => {};
    loadOverview = async () => {}; loadLedger = async () => {};
    loadProducts = async () => {}; loadIntakes = async () => {}; loadData = async () => {};
    dialog = () => fakeNode();
    el = (tag, attributes = {}, ...children) => ({tag, attributes, children, listeners: new Map(), append(...more) {this.children.push(...more);}, replaceChildren(...more) {this.children = more;}, addEventListener(name, callback) {this.listeners.set(name, callback);}});
    const actualLedgerView = ledgerView;
    ledgerView = () => ({merchant: "Synthetic", candidate: {items: []}});
    statusBadge = () => null; calcRow = () => null;
    priceReviewSection = () => null; voidForm = () => null;
  `, context);
  return {location, calls, historyCalls, events, context, run: (script) => vm.runInContext(script, context)};
}

test("routes roundtrip views and known details and reject arbitrary hash paths", () => {
  for (const hash of ["#overview", "#review", "#ledger", "#prices", "#intakes", `#draft/${A}`, `#ledger/${L}`, "#draft/demo-market"]) {
    assert.equal(values.dashboardRouteHash(values.parseDashboardRoute(hash)), hash);
  }
  for (const hash of ["#draft/..", "#draft/https://evil.test", "#ledger/../../auth/logout", "#prices?token=bad", "#unknown", "https://evil.test", "#draft/" + "a".repeat(36)]) {
    assert.deepEqual(values.parseDashboardRoute(hash), {view: "overview"});
  }
});

test("A deep link switching to B updates the URL and refresh target without dropping query", async () => {
  const app = await appHarness(`#draft/${A}`);
  await app.run(`openDraft("${B}")`);
  assert.equal(app.location.hash, `#draft/${B}`);
  assert.equal(new URL(app.location.href).searchParams.get("sample"), "1");
  assert.equal(values.parseDashboardRoute(new URL(app.location.href).hash).draftId, B);
  assert.equal(app.run("state.selected.id"), B);
  assert.deepEqual(plain(app.run("state.intakeContext")), {status: "none", value: null});
});

test("all navs synchronize URL and an unsaved edit blocks browser back and preserves fields", async () => {
  const app = await appHarness();
  for (const view of ["review", "ledger", "prices", "intakes", "overview"]) {
    assert.equal(app.run(`switchView("${view}")`), true);
    assert.equal(app.location.hash, `#${view}`);
  }
  await app.run(`openDraft("${B}")`);
  app.run('state.dirty = true; state.edit.review_note = "unsaved note";');
  app.location.hash = `#draft/${A}`;
  await app.events.get("hashchange")();
  assert.equal(app.location.hash, `#draft/${B}`);
  assert.equal(app.run("state.selected.id"), B);
  assert.equal(app.run("state.edit.review_note"), "unsaved note");
});

test("confirmed draft becomes ledger route and reload does not reopen stale draft", async () => {
  const app = await appHarness();
  await app.run(`openDraft("${B}")`);
  app.run("state.selected.calculation = {ready: true};");
  app.context.freshKey = () => "synthetic-confirm-key";
  await app.run("confirmDraft()");
  assert.equal(app.location.hash, "#ledger");
  assert.equal(app.run("state.view"), "ledger");
  assert.equal(app.calls.filter(([path]) => path.endsWith("/confirm")).length, 1);
  assert.deepEqual(app.historyCalls.at(-1), ["replaceState", "#ledger"]);
});

test("ledger detail has its own route and closing it restores the prior nav", async () => {
  const app = await appHarness("#prices");
  app.run('switchView("prices")');
  await app.run(`showLedgerEntry("${L}")`);
  assert.equal(app.location.hash, `#ledger/${L}`);
  app.run("state.routeDialog.close()");
  assert.equal(app.location.hash, "#prices");
  assert.equal(app.run("state.view"), "prices");
});

test("initial unauthenticated nav sends only the validated route to login", async () => {
  for (const hash of ["#overview", "#prices", "#intakes", `#ledger/${L}`, `#draft/${A}`]) {
    const app = await appHarness(hash);
    app.run('api.session = async () => { throw new ApiError(401, "authentication_required"); };');
    await app.run("start()");
    const login = new URL(app.location.href);
    assert.equal(login.pathname, "/receipts/auth/login");
    assert.equal(login.searchParams.get("return_to"), hash);
    assert.equal(login.origin, "https://receipts.example");
  }
});

test("read-only context renders original notes as text without mutating candidate or decisions", async () => {
  const app = await appHarness();
  const text = '<img src=x onerror=alert(1)>\n' + "長備註".repeat(1000);
  app.context.syntheticContext = {status: "available", value: {user_notes: text, evidence: [
    {sha256: "a".repeat(64), role: "price_tag", notes: '<script>untrusted</script>'},
  ]}};
  app.run('state.intakeContext = syntheticContext; state.edit = {candidate: {printed_total_yuan: 100}, line_decisions: []};');
  const before = plain(app.run("state.edit"));
  app.run("panel = (title, body) => ({title, body});");
  const panel = plain(app.run("intakeContextPanel()"));
  assert.equal(panel.title, "送出時的說明");
  assert.equal(panel.body[0].children[0], text);
  assert.match(JSON.stringify(panel), /價標/);
  assert.deepEqual(plain(app.run("state.edit")), before);
});

function delayDraft(app, id) {
  const api = app.run("api"); const original = api.request.bind(api);
  api.request = (path, options) => {
    if (path !== `v1/drafts/${id}`) return original(path, options);
    return new Promise((_resolve, reject) => {
      options.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), {name: "AbortError"})));
    });
  };
}

test("an aborted history draft request cannot overwrite a newer direct navigation", async () => {
  const app = await appHarness("#prices");
  app.run('switchView("prices")'); delayDraft(app, A);
  app.location.hash = `#draft/${A}`;
  const pending = app.events.get("hashchange")();
  await app.run(`openDraft("${B}")`);
  await pending;
  assert.equal(app.location.hash, `#draft/${B}`);
  assert.equal(app.run("state.selected.id"), B);
  assert.equal(app.run("state.loading"), false);
});

test("draft pagination aborts a pending draft instead of reopening it after navigation", async () => {
  const app = await appHarness(); delayDraft(app, A);
  const pending = app.run(`openDraft("${A}")`);
  await app.run("pageDrafts(20)"); await pending;
  assert.equal(app.location.hash, "#review");
  assert.equal(app.run("state.selected"), null);
  assert.equal(app.run("state.draftController.signal.aborted"), true);
});

test("context failure remains visible and retry preserves unsaved human review", async () => {
  const app = await appHarness();
  const api = app.run("api"); const original = api.request.bind(api);
  let fail = true;
  api.request = (path, options) => {
    if (path.endsWith("/intake-context") && fail) return Promise.reject(new Error("context unavailable"));
    return original(path, options);
  };
  await app.run(`openDraft("${B}")`);
  assert.equal(app.run("state.selected.id"), B);
  assert.equal(app.run("state.intakeContext.status"), "error");
  app.run('state.dirty = true; state.edit.review_note = "unsaved human note"');
  const before = plain(app.run("state.edit")); fail = false;
  await app.run("retryIntakeContext()");
  assert.equal(app.run("state.intakeContext.status"), "none");
  assert.equal(app.run("state.dirty"), true);
  assert.deepEqual(plain(app.run("state.edit")), before);
});


test("dirty or unresolved review versions cannot confirm, and unknown confirm retries keep the same operation", async () => {
  const app = await appHarness();
  await app.run(`openDraft("${B}")`);
  app.run("state.selected.calculation = {ready: true};");
  for (const field of ["dirty", "conflict", "saveUnknown"]) {
    app.run(`state.${field} = true`);
    await app.run("confirmDraft()");
    assert.equal(app.calls.filter(([path]) => path.endsWith("/confirm")).length, 0);
    app.run(`state.${field} = false`);
  }
  const api = app.run("api"); const original = api.request.bind(api); const attempts = [];
  api.request = (path, options) => {
    if (!path.endsWith("/confirm")) return original(path, options);
    attempts.push(plain(options));
    if (attempts.length === 1) return Promise.reject(new Error("response lost"));
    return Promise.resolve({id: L, status: "active"});
  };
  app.context.freshKey = () => "synthetic-persistent-operation";
  await app.run("confirmDraft()");
  assert.equal(app.run("state.confirmAttempt.key"), "synthetic-persistent-operation");
  app.run("setStatus = () => {};");
  assert.equal(app.run("mayLeave()"), false);
  await app.run("confirmDraft()");
  assert.equal(attempts.length, 2);
  assert.deepEqual(attempts[0], attempts[1]);
  assert.equal(app.run("state.confirmAttempt"), null);
  assert.equal(app.location.hash, "#ledger");
});


test("editing hides saved calculation amounts until a new server result is available", async () => {
  const app = await appHarness();
  await app.run(`openDraft("${B}")`);
  const content = plain(app.run(`
    state.selected.calculation = {ready: true, printed_total_yuan: 987654, personal_yuan: 987654};
    state.dirty = true;
    const result = el("div"); renderCalculation(result); result;
  `));
  assert.match(JSON.stringify(content), /內容已修改/);
  assert.doesNotMatch(JSON.stringify(content), /987654|987,654|確認入帳/);
});

test("initial advance suggestion separates receipt payment from personal spend and hides stale totals on edits", async () => {
  const app = await appHarness();
  await app.run('openDraft("' + B + '")');
  const preview = {hidden: true, textContent: ""};
  app.context.document.getElementById = (id) => id === "review-advance-preview" ? preview : null;
  app.context.document.querySelector = () => null;
  app.run(`
    state.selected.candidate = {
      printed_total_yuan: 300,
      items: [{raw_name: "合成炒飯", quantity: 1, printed_line_total_yuan: 270},
        {raw_name: "合成丸湯", quantity: 1, printed_line_total_yuan: 30}],
      adjustments: [],
    };
    state.selected.advance_suggestion = {item_positions: [1], personal_yuan: 270, advance_yuan: 30};
    state.edit = makeEdit(state.selected);
    refreshReviewSummaries();
  `);
  assert.deepEqual(plain(app.run("state.edit.line_decisions.map(row => row.whole_purpose)")), ["self", "advance"]);
  assert.equal(app.run("state.edit.candidate.printed_total_yuan"), 300);
  assert.equal(preview.hidden, false);
  assert.match(preview.textContent, /個人支出預估 NT\$ 270/);
  assert.match(preview.textContent, /代墊 NT\$ 30/);
  app.run('state.edit.line_decisions[1].whole_purpose = "gift"; markDirty();');
  assert.equal(preview.hidden, true);
  assert.equal(app.calls.filter(([, options]) => options?.method === "PUT").length, 0);
});


test("deleted draft deep link renders a final read-only notice without editing controls", async () => {
  const app = await appHarness(`#draft/${A}`);
  await app.run(`openDraft("${A}")`);
  const rendered = app.run(`
    state.selected.status = "deleted";
    const workspace = el("main"); workspace.querySelector = () => null;
    renderWorkspace(workspace);
    workspace;
  `);
  const text = JSON.stringify(plain(rendered));
  assert.match(text, /已刪除/);
  assert.doesNotMatch(text, /這張收據目前無法修改，請查看帳本狀態/);
  const tags = [];
  function visit(node) {
    if (!node || typeof node !== "object") return;
    tags.push(node.tag);
    for (const child of node.children || []) visit(child);
  }
  visit(rendered);
  assert.equal(tags.includes("form"), false);
  assert.equal(tags.includes("fieldset"), false);
});


function noteDraft({saved = false, status = "matched", purpose = "advance"} = {}) {
  return {id: B, status: "needs_review", revision: saved ? 2 : 1,
    candidate: {merchant: "合成商店", receipt_date: null, printed_total_yuan: 100,
      items: [{raw_name: "牙膏", quantity: 2, printed_unit_price_yuan: 50, printed_line_total_yuan: 100}], adjustments: []},
    calculation: {ready: true, printed_total_yuan: 100},
    review_document: saved ? {assembly_status: "complete_by_user", line_decisions: [{item_position: 0, method: "whole_line", whole_purpose: "self", quantities: null, amounts_yuan: null}], adjustment_decisions: []} : null,
    note_review: {status, decisions: [{item_position: 0, purpose, note_quote: "牙膏兩條全部幫小美買"}], unresolved_notes: []}};
}
function findNode(node, predicate) {
  if (!node || typeof node !== "object") return null;
  if (predicate(node)) return node;
  for (const child of (Array.isArray(node) ? node : node.children || [])) {
    const match = findNode(child, predicate); if (match) return match;
  }
  return null;
}
function buttonNamed(node, name) {
  return findNode(node, (item) => item.tag === "button" && item.children.includes(name));
}
async function reviewHarness(options) {
  const app = await appHarness(); app.context.fixtureDraft = noteDraft(options);
  app.context.document.querySelector = () => null;
  app.run('state.view = "review"; state.selected = fixtureDraft; state.edit = makeEdit(fixtureDraft);');
  return app;
}

test("fresh semantic purposes and browser-local date default preserve original receipt facts", async () => {
  const app = await appHarness(); const draft = noteDraft({purpose: "gift"}); draft.revision = 2;
  app.context.fixtureDraft = draft;
  app.run(`Date = class {getFullYear() {return 2026;} getMonth() {return 0;} getDate() {return 2;} toISOString() {return "2026-01-01T16:30:00Z";}}; state.edit = makeEdit(fixtureDraft);`);
  assert.equal(app.run('state.edit.line_decisions[0].whole_purpose'), "gift");
  assert.equal(app.run('state.edit.candidate.receipt_date'), "2026-01-02");
  assert.equal(draft.candidate.receipt_date, null);
  const saved = noteDraft({saved: true}); app.context.savedDraft = saved;
  assert.equal(app.run('makeEdit(savedDraft).candidate.receipt_date'), null);
});

test("saved self purposes show exact source and explicit apply changes no receipt facts or money", async () => {
  const app = await reviewHarness({saved: true});
  const before = plain(app.run('state.edit.candidate'));
  assert.equal(app.run('state.edit.line_decisions[0].whole_purpose'), "self");
  const panel = app.run('noteReviewPanel(false)');
  assert.match(JSON.stringify(plain(panel)), /來源敘述 · 牙膏兩條全部幫小美買/);
  const apply = buttonNamed(panel, "依補充敘述套用用途"); assert.ok(apply);
  apply.attributes.onclick();
  assert.equal(app.run('state.edit.line_decisions[0].whole_purpose'), "advance");
  assert.deepEqual(plain(app.run('state.edit.candidate')), before);
  assert.equal(app.run('state.dirty'), true);
  assert.equal(app.calls.length, 0);
});

test("unresolved notes cannot confirm until explicit keep, whose acknowledgement is frozen on retry", async () => {
  const app = await reviewHarness({saved: true, status: "needs_review"});
  app.context.freshKey = () => "synthetic-note-confirm";
  await app.run('confirmDraft()');
  assert.equal(app.calls.filter(([path]) => path.endsWith('/confirm')).length, 0);
  const panel = app.run('noteReviewPanel(false)');
  assert.match(JSON.stringify(plain(panel)), /仍有無法對應/);
  const keep = buttonNamed(panel, "保留目前用途，已核對敘述"); assert.ok(keep);
  keep.attributes.onclick();
  assert.equal(app.run('state.dirty'), false);
  const api = app.run('api'); const attempts = [];
  api.request = (path, options) => {
    attempts.push(plain(options));
    if (attempts.length === 1) return Promise.reject(new Error("response lost"));
    return Promise.resolve({id: L, status: "active"});
  };
  await app.run('confirmDraft()');
  assert.deepEqual(attempts[0].body, {expected_revision: 2, notes_acknowledgement: "checked"});
  app.run('state.notesAcknowledged = false');
  await app.run('confirmDraft()');
  assert.deepEqual(attempts[1], attempts[0]);
});

test("purpose mismatch blocks confirm and editing invalidates a previous keep acknowledgement", async () => {
  const app = await reviewHarness({saved: true});
  app.context.freshKey = () => "synthetic-note-edit";
  await app.run('confirmDraft()');
  assert.equal(app.calls.length, 0);
  buttonNamed(app.run('noteReviewPanel(false)'), "保留目前用途，已核對敘述").attributes.onclick();
  assert.equal(app.run('state.notesAcknowledged'), true);
  app.run('state.edit.candidate.items[0].quantity = "3"; markDirty();');
  assert.equal(app.run('state.notesAcknowledged'), false);
});

test("explicit note apply never replaces partial split or changed item identity", async () => {
  const app = await reviewHarness({saved: true});
  const split = {item_position: 0, method: "explicit_amounts", whole_purpose: null, quantities: {self: 1, gift: 0, advance: 1}, amounts_yuan: {self: 50, gift: 0, advance: 50}};
  app.context.partial = split; app.run('state.edit.line_decisions[0] = partial');
  let apply = buttonNamed(app.run('noteReviewPanel(false)'), "依補充敘述套用用途");
  assert.ok(apply.attributes.disabled);
  assert.deepEqual(plain(app.run('state.edit.line_decisions[0]')), split);
  app.run('state.edit.line_decisions[0] = {...blankLineDecision(0), whole_purpose: "self"}; state.edit.candidate.items[0].raw_name = "不同品項";');
  apply = buttonNamed(app.run('noteReviewPanel(false)'), "依補充敘述套用用途");
  assert.ok(apply.attributes.disabled);
});

test("ledger display name overlay preserves original snapshot and all financial values", async () => {
  const app = await appHarness();
  const entry = {id: L, snapshot: {candidate: {merchant: "合成", items: [{raw_name: null, quantity: 2, printed_line_total_yuan: 100}, {raw_name: null, quantity: 1, printed_line_total_yuan: 20}]}, calculation: {printed_total_yuan: 120, personal_yuan: 120}}, display_item_names: ["已補牙膏", null]};
  app.context.overlayEntry = entry; const before = structuredClone(entry);
  const view = plain(app.run('actualLedgerView(overlayEntry)'));
  assert.equal(view.candidate.items[0].raw_name, "已補牙膏");
  assert.equal(view.candidate.items[1].raw_name, null);
  assert.equal(view.total, 120);
  assert.deepEqual(entry, before);
});

test("overview surfaces missing-name counts without inventing a completion action", async () => {
  const app = await appHarness();
  const rendered = app.run('state.overview = {receipt_count:2, missing_item_name_count:3, receipts_missing_item_names:2}; const view=el("main"); renderOverview(view); view;');
  assert.match(JSON.stringify(plain(rendered)), /待補品名 3 項/);
  assert.match(JSON.stringify(plain(rendered)), /2 張收據/);
});
