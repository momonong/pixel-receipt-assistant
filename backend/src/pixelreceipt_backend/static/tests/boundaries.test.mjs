import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Api, ApiError, errorMessage } from "../api.mjs";
import { demoDrafts } from "../demo.mjs";
import { allocationSummary, buildReview, clearItemReview, fillUnspecifiedSelf } from "../review.mjs";
import { integerInput, money, nullableText, parseSafeJson, UnsafeIntegerError } from "../values.mjs";

const clone = (value) => structuredClone(value);
function editor() {
  return {
    candidate: clone(demoDrafts[0].candidate), assembly_status: "complete_by_user",
    line_decisions: demoDrafts[0].candidate.items.map((_, item_position) => ({ item_position, method: "whole_line", whole_purpose: "self", quantities: null, amounts_yuan: null })),
    adjustment_decisions: [{ adjustment_position: 0, method: "proportional_amount", amounts_yuan: null }],
    tolerance_acknowledgement: "none", review_note: "合成測試",
  };
}

test("JSON checks 64-bit numeric tokens before JavaScript can round them", () => {
  for (const token of ["9007199254740992", "9007199254740993", "9223372036854775807", "-9007199254740993", "1.5", "1e3"]) {
    assert.throws(() => parseSafeJson(`{"amount":${token}}`), UnsafeIntegerError);
  }
  assert.equal(parseSafeJson('{"amount":9007199254740991}').amount, Number.MAX_SAFE_INTEGER);
  assert.equal(parseSafeJson('{"difference_yuan":-1}').difference_yuan, -1);
});

test("digits and escapes inside untrusted text remain text", () => {
  const value = { name: '貨號 9223372036854775807 \\ " <img src=x onerror=alert(1)>', unknown: null, ready: false };
  assert.deepEqual(parseSafeJson(JSON.stringify(value)), value);
});

test("integer fields preserve unknown, zero, and exact upper boundary", () => {
  assert.equal(integerInput("", "金額"), null);
  assert.equal(integerInput("  ", "金額"), null);
  assert.equal(integerInput("0", "金額"), 0);
  assert.equal(integerInput("9007199254740991", "金額"), Number.MAX_SAFE_INTEGER);
  for (const value of ["9007199254740992", "01", "-1", "1.0", "1e2", "1,000", " 1", "NaN"]) assert.throws(() => integerInput(value, "金額"));
  assert.throws(() => integerInput("0", "數量", { minimum: 1 }));
  assert.equal(nullableText(" "), null);
  assert.equal(nullableText(" 原始品名 "), " 原始品名 ");
  assert.equal(money(null), "待確認");
  assert.equal(money(0), "NT$ 0");
});

test("unselected whole-line purpose is omitted so incomplete receipt facts can save", () => {
  const edit = editor(); edit.line_decisions[0].whole_purpose = null;
  const prepared = buildReview(edit, 7);
  assert.equal(prepared.body.expected_revision, 7);
  assert.equal(prepared.body.schema, "pixelreceipt-central-review-2");
  assert.equal(prepared.body.line_decisions.length, 2);
  assert.equal(prepared.body.line_decisions.some((decision) => decision.item_position === 0), false);
  assert.equal(edit.line_decisions[0].whole_purpose, null);
});

test("explicit amounts require both quantity and amount triples without filling zero", () => {
  const edit = editor();
  edit.line_decisions[0] = { item_position: 0, method: "explicit_amounts", whole_purpose: null, quantities: { self: "1", gift: "0", advance: "0" }, amounts_yuan: { self: "99", gift: "0", advance: "0" } };
  const prepared = buildReview(edit, 1);
  assert.deepEqual(prepared.body.line_decisions[0].quantities, { self: 1, gift: 0, advance: 0 });
  assert.deepEqual(prepared.body.line_decisions[0].amounts_yuan, { self: 99, gift: 0, advance: 0 });
  edit.line_decisions[0].quantities.advance = "";
  const partial = buildReview(edit, 1);
  assert.equal(partial.body.line_decisions.some((decision) => decision.item_position === 0), false);
  assert.equal(partial.unsavedLineDecisions[0].quantities.advance, "");
  assert.equal(partial.unsavedLineDecisions[0].amounts_yuan.self, "99");
  assert.equal(partial.warnings.length, 1);
});

test("a purpose with zero quantity cannot receive explicit money", () => {
  const edit = editor();
  edit.line_decisions[0] = { item_position: 0, method: "explicit_amounts", whole_purpose: null, quantities: { self: 1, gift: 0, advance: 0 }, amounts_yuan: { self: 89, gift: 10, advance: 0 } };
  assert.throws(() => buildReview(edit, 1), /數量為 0/);
});

test("included discounts never produce an adjustment allocation", () => {
  const edit = editor();
  edit.candidate.adjustments[0].amount_effect = "included_in_line_totals";
  assert.deepEqual(buildReview(edit, 1).body.adjustment_decisions, []);
  assert.equal(edit.adjustment_decisions.length, 1);
});

test("partial adjustment amounts remain local and are not converted to zero", () => {
  const edit = editor();
  edit.adjustment_decisions[0] = { adjustment_position: 0, method: "explicit_amounts", amounts_yuan: { self: "18", gift: "", advance: "0" } };
  const prepared = buildReview(edit, 1);
  assert.deepEqual(prepared.body.adjustment_decisions, []);
  assert.equal(prepared.unsavedAdjustmentDecisions[0].amounts_yuan.gift, "");
});

test("editing item facts clears stale item purpose, discount allocation, completeness, and tolerance", () => {
  const edit = editor(); edit.tolerance_acknowledgement = "accept_within_tolerance";
  clearItemReview(edit, 0);
  assert.equal(edit.line_decisions[0].whole_purpose, null);
  assert.equal(edit.line_decisions[1].whole_purpose, "self");
  assert.equal(edit.adjustment_decisions[0].method, "");
  assert.equal(edit.assembly_status, "unknown");
  assert.equal(edit.tolerance_acknowledgement, "none");
});

test("candidate normalization never multiplies line totals or guesses quantity", () => {
  const edit = editor(); edit.candidate.items[0].quantity = "2";
  edit.candidate.items[0].printed_line_total_yuan = "189";
  edit.candidate.items[1].quantity = null;
  const body = buildReview(edit, 1).body;
  assert.equal(body.candidate.items[0].printed_line_total_yuan, 189);
  assert.equal(body.candidate.items[1].quantity, null);
});

test("mutations need an in-memory session CSRF token before network access", async () => {
  const api = new Api(); let calls = 0;
  const original = globalThis.fetch;
  globalThis.fetch = async () => { calls += 1; throw new Error("should not call"); };
  try {
    await assert.rejects(api.request("v2/drafts/example/confirm", { method: "POST", body: { expected_revision: 1 } }), (error) => error instanceof ApiError && error.status === 401);
    assert.equal(calls, 0);
  } finally { globalThis.fetch = original; }
});

test("retry uses the supplied identical idempotency key and same-origin credentials", async () => {
  const api = new Api(); api.csrf = "SYNTHETIC-CSRF"; const calls = [];
  const original = globalThis.fetch;
  globalThis.fetch = async (url, options) => { calls.push({ url, options }); return new Response('{"status":"active"}', { status: 200 }); };
  try {
    const attempt = { method: "POST", body: { expected_revision: 2 }, key: "synthetic-same-operation" };
    await api.request("v2/drafts/synthetic/confirm", attempt);
    await api.request("v2/drafts/synthetic/confirm", attempt);
    assert.equal(calls[0].options.headers["Idempotency-Key"], calls[1].options.headers["Idempotency-Key"]);
    assert.equal(calls[0].options.body, calls[1].options.body);
    assert.equal(calls[0].options.headers["X-CSRF-Token"], "SYNTHETIC-CSRF");
    assert.equal(calls[0].options.credentials, "same-origin");
    assert.equal(calls[0].options.redirect, "error");
    assert.equal(calls[0].url.href.endsWith("/static/v2/drafts/synthetic/confirm"), true);
  } finally { globalThis.fetch = original; }
});

test("server unsafe integers stop the response and revision conflict remains explicit", async () => {
  const api = new Api(); const original = globalThis.fetch;
  try {
    globalThis.fetch = async () => new Response('{"printed_total_yuan":9223372036854775807}', { status: 200 });
    await assert.rejects(api.request("v1/drafts"), UnsafeIntegerError);
    globalThis.fetch = async () => new Response('{"detail":"revision_conflict"}', { status: 409 });
    await assert.rejects(api.request("v1/drafts"), (error) => error instanceof ApiError && error.status === 409 && /已停止儲存/.test(errorMessage(error)));
  } finally { globalThis.fetch = original; }
});

test("static sources forbid text-to-HTML injection, persistent credentials, and inline CSP styles", async () => {
  for (const file of ["app.js", "api.mjs", "index.html"]) {
    const source = await readFile(new URL(`../${file}`, import.meta.url), "utf8");
    assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|document\.write|localStorage|sessionStorage|eval\s*\(/);
    assert.doesNotMatch(source, /\bstyle\s*:\s*["'`]|setAttribute\(["']style|<[^>]+\sstyle=/);
  }
});


test("self shortcut requires a human action and preserves gifts, advances and partial splits", () => {
  const edit = editor();
  edit.candidate.items.push(clone(edit.candidate.items[0]), clone(edit.candidate.items[0]));
  edit.line_decisions = [
    {item_position: 0, method: "whole_line", whole_purpose: null, quantities: null, amounts_yuan: null},
    {item_position: 1, method: "whole_line", whole_purpose: "gift", quantities: null, amounts_yuan: null},
    {item_position: 2, method: "whole_line", whole_purpose: "advance", quantities: null, amounts_yuan: null},
    {item_position: 3, method: "explicit_amounts", whole_purpose: null, quantities: {self: "1", gift: "", advance: "0"}, amounts_yuan: {self: "99", gift: "", advance: "0"}},
    {item_position: 4, method: "by_quantity", whole_purpose: null, quantities: {self: "1", gift: "0", advance: "1"}, amounts_yuan: null},
  ];
  const before = clone(edit);
  assert.throws(() => fillUnspecifiedSelf(edit), /明確操作/);
  assert.deepEqual(edit, before);
  assert.equal(fillUnspecifiedSelf(edit, {userInitiated: true}), 1);
  assert.equal(edit.line_decisions[0].whole_purpose, "self");
  assert.deepEqual(edit.line_decisions.slice(1), before.line_decisions.slice(1));
  assert.deepEqual(edit.candidate, before.candidate);
  assert.equal(fillUnspecifiedSelf(edit, {userInitiated: true}), 0);
  const prepared = buildReview(edit, 3);
  assert.equal(prepared.body.line_decisions[0].whole_purpose, "self");
  assert.equal(prepared.unsavedLineDecisions.length, 1);
  assert.equal(prepared.unsavedLineDecisions[0].quantities.gift, "");
});

test("allocation summary exposes mixed and partial decisions without calculating money", () => {
  const edit = editor();
  edit.line_decisions[0].whole_purpose = null;
  edit.line_decisions[1].whole_purpose = "gift";
  edit.line_decisions[2] = {item_position: 2, method: "explicit_amounts", quantities: {self: "1", gift: "", advance: "0"}, amounts_yuan: {self: "99", gift: "", advance: "0"}};
  const before = clone(edit);
  const summary = allocationSummary(edit);
  assert.deepEqual(summary.counts, {self: 0, gift: 1, advance: 0, split: 1, unspecified: 1});
  assert.equal(summary.partial, true);
  assert.match(summary.text, /贈送 1 項.*分開分配 1 項.*未指定 1 項/);
  assert.deepEqual(edit, before);
  const allSelf = editor();
  assert.equal(allocationSummary(allSelf).text, "全部 3 個品項自己用");
});


test("allocation summary keeps unresolved separate adjustments visible and excludes included discounts", () => {
  const edit = editor();
  edit.adjustment_decisions[0].method = "";
  let summary = allocationSummary(edit);
  assert.equal(summary.adjustmentUnspecified, 1);
  edit.adjustment_decisions[0] = {adjustment_position: 0, method: "explicit_amounts", amounts_yuan: {self: "", gift: "0", advance: "0"}};
  summary = allocationSummary(edit);
  assert.equal(summary.adjustmentUnspecified, 0);
  assert.equal(summary.adjustmentPartial, 1);
  edit.candidate.adjustments[0].amount_effect = "included_in_line_totals";
  summary = allocationSummary(edit);
  assert.equal(summary.adjustmentUnspecified, 0);
  assert.equal(summary.adjustmentPartial, 0);
});
