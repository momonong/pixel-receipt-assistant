import { Api, ApiError, appBase, errorMessage } from "./api.mjs";
import { dashboardRouteHash, dateLabel, freshKey, integerInput, isSafeIdentifier, money, parseDashboardRoute, receiptTitle } from "./values.mjs";
import { allocationSummary, buildReview, clearItemReview, fillUnspecifiedSelf } from "./review.mjs";
import { adjustmentPositions, buildPriceReview, buildProduct, exactPrice, priceReason, productLabel, revokedMappings } from "./prices.mjs";
import { demoDrafts, demoLedger, demoOverview, demoPrices } from "./demo.mjs";

const api = new Api();
const demo = new URL(location.href).searchParams.get("demo") === "1";
const app = document.querySelector("#app");
const clone = (value) => structuredClone(value);
let fieldId = 0;
const state = {
  intakes: [], devices: [], intakeOffset: 0,
  view: "overview", drafts: [], ledger: [], draftOffset: 0, ledgerOffset: 0,
  selected: null, edit: null, intakeContext: null, routeDialog: null, dirty: false, loading: false, busy: false,
  error: null, message: null, conflict: false, saveUnknown: false,
  confirmAttempt: null, voidAttempts: new Map(), query: "", dateFrom: "", dateTo: "", session: null,
  evidenceUrl: null, evidenceController: null, draftController: null,
  overview: null, overviewFrom: "", overviewTo: "", ledgerStatus: "active", ledgerTotal: null,
  products: [], productQuery: "", productOffset: 0, productTotal: 0,
  selectedProduct: null, productPrices: null, priceFrom: "", priceTo: "", priceOffset: 0,
  productCreateAttempt: null, annotation: null,
  overviewRequest: 0, ledgerRequest: 0, productsRequest: 0, pricesRequest: 0,
};

// DOM construction deliberately uses textContent; receipt/model text is untrusted.
function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (value === null || value === undefined || value === false) continue;
    if (name === "class") node.className = value;
    else if (name === "text") node.textContent = value;
    else if (name.startsWith("on")) node.addEventListener(name.slice(2).toLowerCase(), value);
    else if (name in node && !name.startsWith("aria") && name !== "role") node[name] = value;
    else node.setAttribute(name, value === true ? "" : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function button(text, action, style = "secondary", attributes = {}) {
  const { class: extraClass = "", ...rest } = attributes;
  return el("button", { type: "button", class: `button ${style} ${extraClass}`, onclick: action, ...rest }, text);
}

function badge(text, style = "") { return el("span", { class: `badge ${style}` }, text); }
function notice(text, style = "", action) {
  return el("div", { class: `notice ${style}`, role: style === "error" ? "alert" : "status" },
    el("p", {}, text), action ? el("div", { class: "toolbar" }, action) : null);
}
function empty(title, description, icon = "▤") {
  return el("div", { class: "empty" }, el("div", { class: "empty-icon", "aria-hidden": "true" }, icon), el("strong", {}, title), el("p", {}, description));
}
function panel(title, body, extra, attributes = {}) {
  return el("section", { class: "panel", ...attributes }, el("div", { class: "panel-header" }, el("h2", {}, title), extra), el("div", { class: "panel-body" }, body));
}
function heading(title, subtitle, extra) {
  return el("div", { class: "page-heading" }, el("div", {}, el("p", { class: "eyebrow" }, "我的收據紀錄"), el("h1", {}, title), el("p", { class: "subtitle" }, subtitle)), extra);
}
function clearEvidence() {
  state.evidenceController?.abort();
  if (state.evidenceUrl) URL.revokeObjectURL(state.evidenceUrl);
  state.evidenceUrl = null;
}
function setStatus(text, type = "success") {
  state.message = { text, type };
  refreshStatus();
}
function refreshStatus() {
  const region = document.querySelector("#status");
  if (!region) return;
  region.replaceChildren();
  if (state.error) region.append(notice(state.error, "error"));
  if (state.message) region.append(notice(state.message.text, state.message.type));
}
function markDirty(preserveTolerance = false) {
  if (state.edit && !preserveTolerance) {
    state.edit.tolerance_acknowledgement = "none";
    const acknowledgement = document.querySelector("#tolerance-check");
    if (acknowledgement) { acknowledgement.checked = false; acknowledgement.disabled = true; }
  }
  state.dirty = true;
  state.message = null;
  const status = document.querySelector("#save-state");
  if (status) { status.textContent = "尚未儲存"; status.className = "badge amber"; }
  const evaluation = document.querySelector("#evaluation");
  if (evaluation) evaluation.replaceChildren(notice("內容已修改，請先儲存並檢查。", "info"));
  refreshReviewSummaries();
}
function mayLeave() {
  if (state.busy) { setStatus("正在處理這次操作，請稍候再切換畫面。", "info"); return false; }
  if (state.confirmAttempt) {
    setStatus("這次確認結果尚未查明。請先重試同一次確認，避免另開新的入帳操作。", "error");
    return false;
  }
  return !state.dirty || window.confirm("還有未儲存的核對內容。離開會捨棄這些修改，是否繼續？");
}

function header() {
  return el("header", { class: "header" },
    el("a", { class: "brand", href: demo ? "?demo=1" : "./", "aria-label": "Pixel Receipt 收據帳本首頁" },
      el("span", { class: "brand-mark", "aria-hidden": "true" }, "▤"),
      el("span", {}, el("strong", {}, "Pixel Receipt"), el("small", {}, "每一筆，都有依據"))),
    el("div", { class: "account" },
      el("span", { class: "connection" }, el("span", { class: "dot" }), demo ? "合成示範" : "我的私人帳本"),
      !demo ? el("a", {class: "button ghost", href: new URL("v1/mobile/apk", appBase)}, "下載 APK") : null,
      button(demo ? "離開示範" : "登出", demo ? () => location.assign(appBase) : logout, "ghost")));
}

async function logout() {
  if (!mayLeave()) return;
  try {
    await api.request("auth/logout", { method: "POST" });
    clearEvidence();
    api.csrf = null;
    location.assign(new URL("auth/login", appBase));
  } catch (error) { state.error = errorMessage(error); refreshStatus(); }
}

function renderShell() {
  clearEvidence();
  app.replaceChildren();
  app.className = state.view === "review" && state.selected ? "review-focused" : "";
  app.setAttribute("aria-busy", "false");
  if (demo) app.append(el("div", { class: "demo-bar" }, el("strong", {}, "合成示範"), "以下店家、收據與金額均為虛構；不讀寫正式帳本，不能確認入帳。"));
  app.append(header());
  const main = el("main", { class: "shell", id: "main", tabindex: "-1" });
  const titles = {
    overview: ["把收據，整理成生活的帳。", "原圖留存、逐筆核對，只有親自確認的收據才會入帳。"],
    review: ["待核對收據", "對照原圖，補齊金額與用途。儲存核對後，才能確認入帳。"],
    ledger: ["我的帳本", "只列出已確認入帳的紀錄。待核對草稿不會計入支出。"],
    intakes: ["手機收件", "查看照片說明、辨識進度與裝置登入。價格參考不會形成消費。"],
    prices: ["商品與價格", "先確認商品、規格與折扣歸屬，再比較有依據的實付價格。"],
  };
  main.append(state.view === "review" && state.selected ?
    el("div", { class: "page-heading review-page-heading" }, el("h1", {}, "核對收據"), button("重新整理", refreshCurrent, "ghost small", { disabled: state.loading || state.busy })) :
    heading(...titles[state.view], el("div", { class: "toolbar" }, button("↻ 重新整理", refreshCurrent, "secondary", { disabled: state.loading || state.busy }))));
  const nav = el("nav", { class: "nav", "aria-label": "帳本功能" });
  for (const [id, label] of [["overview", "總覽"], ["review", "待核對"], ["ledger", "帳本"], ["prices", "商品價格"], ["intakes", "手機收件"]]) {
    nav.append(el("button", { type: "button", "aria-current": state.view === id ? "page" : null, onclick: () => switchView(id) }, label));
  }
  main.append(nav, el("div", { id: "status", class: "status-region", "aria-live": "polite", "aria-atomic": "true" }));
  if (state.loading) main.append(el("div", { class: "loading-line", role: "progressbar", "aria-label": "正在讀取帳本" }));
  const content = el("div", { id: "content", "aria-busy": String(state.loading) });
  main.append(content, el("p", { class: "footer-note" }, "金額為新臺幣整數元 · 空白代表未知，與零元不同 · 原始資料與修改紀錄保留於帳本"));
  app.append(main);
  refreshStatus();
  if (state.view === "overview") renderOverview(content);
  else if (state.view === "review") renderReview(content);
  else if (state.view === "ledger") renderLedger(content);
  else if (state.view === "intakes") renderIntakes(content);
  else renderPrices(content);
}

let activeRoute = parseDashboardRoute(location.hash);
let routeRequest = 0;

function writeRoute(route, {replace = false} = {}) {
  const hash = dashboardRouteHash(route);
  if (location.hash !== hash) {
    const url = new URL(location.href); url.hash = hash;
    history[replace ? "replaceState" : "pushState"](null, "", url);
  }
  routeRequest += 1;
  activeRoute = parseDashboardRoute(hash);
}

function prepareNavigation() {
  if (!mayLeave()) return false;
  const dialogs = [...document.querySelectorAll("dialog[open]")].reverse();
  if (dialogs.some((node) => node.canClose && !node.canClose())) return false;
  state.routeDialog = null;
  dialogs.forEach((node) => node.close());
  return true;
}

function switchView(view, {replace = false} = {}) {
  if (!prepareNavigation()) return false;
  state.draftController?.abort();
  state.view = view; state.error = null; state.message = null;
  state.dirty = false; state.edit = null; state.selected = null; state.intakeContext = null;
  writeRoute({view}, {replace});
  renderShell();
  if (!demo && view === "overview") loadOverview();
  if (!demo && view === "prices") { loadProducts(state.productOffset); if (state.selectedProduct) loadProductPrices(state.selectedProduct, state.priceOffset); }
  if (!demo && view === "ledger") loadLedger(state.ledgerOffset);
  if (!demo && view === "intakes") loadIntakes();
  return true;
}

async function navigateRoute(route, {replace = false, initial = false} = {}) {
  if (route.draftId) return openDraft(route.draftId, false, {replace});
  if (initial && route.view === "overview") { writeRoute(route, {replace: true}); return true; }
  if (!switchView(route.view, {replace})) return false;
  if (route.ledgerId) await showLedgerEntry(route.ledgerId, {replace: true});
  return true;
}

function stat(label, value, note, icon) {
  return el("div", { class: "stat" }, el("p", { class: "stat-label" }, label), el("div", { class: "stat-value" }, value), el("p", { class: "stat-note" }, note), el("span", { class: "stat-icon", "aria-hidden": "true" }, icon));
}

function overviewParameters() {
  return dateParameters(state.overviewFrom, state.overviewTo);
}
function dateParameters(from, to) {
  if (from && to && from > to) throw new Error("起始日期需早於或等於結束日期。");
  const parameters = new URLSearchParams();
  if (from) parameters.set("date_from", from);
  if (to) parameters.set("date_to", to);
  return parameters;
}
function dateForm(from, to, updateFrom, updateTo, action, submitText = "更新期間") {
  return el("form", { class: "search-form date-filter", onsubmit: (event) => { event.preventDefault(); action(); } },
    field("起始日期", from, updateFrom, { type: "date", oninput: (event) => updateFrom(event.target.value) }),
    field("結束日期", to, updateTo, { type: "date", oninput: (event) => updateTo(event.target.value) }),
    el("button", { type: "submit", class: "button secondary", disabled: state.loading }, submitText));
}
function scopeLabel(value) {
  return `${value.date_from ? dateLabel(value.date_from) : "最早紀錄"} — ${value.date_to ? dateLabel(value.date_to) : "最新紀錄"}`;
}
function renderOverview(container) {
  container.append(dateForm(state.overviewFrom, state.overviewTo, (value) => state.overviewFrom = value, (value) => state.overviewTo = value, loadOverview));
  const summary = state.overview;
  if (summary) {
    container.append(el("p", { class: "caption mb-16" }, `${scopeLabel(summary)} · 全範圍 ${summary.receipt_count} 筆未作廢帳本 · 依收據日期統計`),
      el("div", { class: "stats" },
        stat("期間收據付款", money(summary.printed_total_yuan), "已確認收據的印刷總額", "▤"),
        stat("個人支出", money(summary.personal_yuan), "自己用＋贈送，不含代墊", "↗"),
        stat("代墊", money(summary.advance_yuan), "獨立記錄，不併入個人支出", "◇")),
      el("div", { class: "summary-details" },
        calcRow("自己用", summary.self_yuan), calcRow("贈送", summary.gift_yuan),
        calcRow("用途分配合計", summary.allocated_total_yuan), calcRow("獨立未分配差額", summary.difference_yuan),
        calcRow("收據明列折扣", summary.explicit_discount_yuan)),
      el("p", { class: "caption mb-16" }, "明列折扣只統計收據上有根據的扣除項各一次，含已含行金額的折扣；不是與市場最低價相比省下的錢。草稿、報價與促銷資料不計入。"));
  } else container.append(panel("期間摘要", empty(state.loading ? "正在讀取期間摘要…" : "摘要尚未載入", "摘要由帳本統計完整範圍，前端不以部分頁面自行加總。", "◇")));
  const pending = state.drafts.filter((draft) => draft.status === "needs_review");
  const recent = el("section", { class: "panel" }, el("div", { class: "panel-header" }, el("h2", {}, "接著完成這幾張"), button("查看待核對 →", () => switchView("review"), "ghost small")));
  if (!pending.length) recent.append(empty("目前沒有待核對收據", "從手機對話整理收據並匯入後，就能在這裡核對。"));
  else pending.slice(0, 4).forEach((draft) => recent.append(draftRow(draft, () => { state.view = "review"; openDraft(draft.id); })));
  container.append(el("div", { class: "split-overview" }, recent, panel("從收據到帳本", [
    howStep("1", "保留原圖", "收據原件與候選內容分開保存，方便隨時回查。"),
    howStep("2", "核對金額與用途", "補齊未知資料，分清自己用、贈送與代墊。"),
    howStep("3", "親自確認入帳", "檢查計算結果後，才寫入正式帳本。"),
  ])));
}
async function loadOverview() {
  let parameters;
  try { parameters = overviewParameters(); }
  catch (error) { state.error = errorMessage(error); refreshStatus(); return; }
  const request = ++state.overviewRequest;
  state.loading = true; state.error = null; state.overview = null; renderShell();
  try {
    if (demo) {
      state.overview = { ...demoOverview, date_from: null, date_to: null, receipt_count: 8, printed_total_yuan: 2980, allocated_total_yuan: 2980, self_yuan: 2300, gift_yuan: 160, advance_yuan: 520, personal_yuan: 2460, difference_yuan: 0, explicit_discount_yuan: 118 };
      state.message = { text: "合成示範只展示固定摘要，日期篩選未連接正式資料。", type: "info" };
    } else {
      const value = await api.request(`v1/overview?${parameters}`);
      if (request === state.overviewRequest) state.overview = value;
    }
  } catch (error) { if (request === state.overviewRequest) state.error = errorMessage(error); }
  finally { if (request === state.overviewRequest) { state.loading = false; renderShell(); } }
}

function howStep(number, title, text) {
  return el("div", { class: "how-step" }, el("span", { class: "step-number" }, number), el("div", {}, el("h3", {}, title), el("p", {}, text)));
}
function statusBadge(status) {
  if (status === "confirmed" || status === "active") return badge("已入帳");
  if (status === "voided") return badge("已作廢", "red");
  return badge("待核對", "amber");
}
function draftRow(draft, action, selected = false) {
  return el("button", { type: "button", class: `list-row click-row${selected ? " selected" : ""}`, onclick: action, "aria-current": selected ? "true" : null },
    el("span", { class: "receipt-icon", "aria-hidden": "true" }, "▤"),
    el("div", { class: "row-copy" }, el("p", { class: "row-title" }, receiptTitle(draft)), el("p", { class: "row-meta" }, `${dateLabel(draft.candidate?.receipt_date)} · ${draft.candidate?.items?.length ?? 0} 個品項`)),
    el("div", { class: "row-end" }, el("p", { class: "row-money" }, money(draft.candidate?.printed_total_yuan)), statusBadge(draft.status)));
}

async function loadData() {
  state.loading = true;
  state.error = null;
  renderShell();
  if (demo) {
    state.drafts = clone(demoDrafts); state.ledger = clone(demoLedger);
    state.overview = { date_from: null, date_to: null, receipt_count: 8, printed_total_yuan: 2980, allocated_total_yuan: 2980, self_yuan: 2300, gift_yuan: 160, advance_yuan: 520, personal_yuan: 2460, difference_yuan: 0, explicit_discount_yuan: 118 };
  } else {
    let ledgerQuery, overviewQuery;
    try { ledgerQuery = ledgerParameters(state.ledgerOffset); overviewQuery = overviewParameters(); }
    catch (error) { state.loading = false; state.error = errorMessage(error); renderShell(); return; }
    const results = await Promise.allSettled([
      api.request(`v1/drafts?status=needs_review&limit=20&offset=${state.draftOffset}`),
      api.request(`v1/ledger?${ledgerQuery}`),
      api.request(`v1/overview?${overviewQuery}`),
    ]);
    if (results[0].status === "fulfilled") state.drafts = results[0].value.drafts || [];
    if (results[1].status === "fulfilled") state.ledger = results[1].value.entries || results[1].value.ledger || [];
    if (results[2].status === "fulfilled") state.overview = results[2].value;
    else state.overview = null;
    const failure = results.find((result) => result.status === "rejected");
    if (failure) state.error = errorMessage(failure.reason);
  }
  state.loading = false;
  renderShell();
}

async function refreshCurrent() {
  if (!mayLeave()) return;
  state.dirty = false;
  state.saveUnknown = false;
  state.conflict = false;
  if (state.view === "intakes") await loadIntakes();
  else if (state.view === "review" && state.selected) await openDraft(state.selected.id, true);
  else if (!demo && state.view === "prices") { await loadProducts(state.productOffset); if (state.selectedProduct) await loadProductPrices(state.selectedProduct, state.priceOffset); }
  else if (state.view === "ledger") await loadLedger(state.ledgerOffset);
  else await loadData();
}

function renderReview(container) {
  const list = el("aside", { class: "panel receipt-list", "aria-label": "待核對收據清單" },
    el("div", { class: "panel-header" }, el("h2", {}, "收據清單"), badge(`${state.drafts.length} 張`, "lavender")));
  const mobileSelect = el("select", { "aria-label": "選擇待核對收據", onchange: (event) => openDraft(event.target.value) }, el("option", { value: "" }, "選擇一張收據"));
  for (const draft of state.drafts.filter((draft) => draft.status === "needs_review")) {
    list.append(draftRow(draft, () => openDraft(draft.id), draft.id === state.selected?.id));
    mobileSelect.append(el("option", { value: draft.id, selected: draft.id === state.selected?.id }, `${receiptTitle(draft)} · ${dateLabel(draft.candidate?.receipt_date)}`));
  }
  list.append(el("div", { class: "mobile-selection" }, mobileSelect));
  if (!state.drafts.length) list.append(empty("沒有待核對收據", "匯入收據後會顯示於此。"));
  if (!demo) list.append(el("div", { class: "panel-body" }, pagination(state.draftOffset, state.drafts.length, (offset) => pageDrafts(offset))));
  const workspace = el("div", { class: "review-workspace", id: "workspace" });
  const chooser = state.selected ? el("details", { class: "receipt-chooser" }, el("summary", {}, "換一張收據"), list) : list;
  container.append(el("div", { class: `review-layout${state.selected ? " has-selection" : ""}` }, chooser, workspace));
  if (state.loading) workspace.append(panel("正在讀取", empty("正在讀取收據…", "請稍候。")));
  else if (!state.selected || !state.edit) workspace.append(panel("開始核對", empty("選擇一張收據", "對照原件，逐項確認金額與用途。")));
  else renderWorkspace(workspace);
}

function blankLineDecision(position) {
  return { item_position: position, method: "whole_line", whole_purpose: null, quantities: null, amounts_yuan: null };
}
function makeEdit(draft) {
  const document = draft.review_document || {};
  return {
    candidate: clone(draft.candidate), assembly_status: document.assembly_status || "unknown",
    line_decisions: draft.candidate.items.map((_, position) => clone(document.line_decisions?.find((decision) => decision.item_position === position) || blankLineDecision(position))),
    adjustment_decisions: draft.candidate.adjustments.map((_, position) => clone(document.adjustment_decisions?.find((decision) => decision.adjustment_position === position) || { adjustment_position: position, method: "", amounts_yuan: null })),
    review_note: "", tolerance_acknowledgement: document.tolerance_acknowledgement || "none",
  };
}
async function openDraft(id, force = false, {replace = false} = {}) {
  if (!force && !prepareNavigation()) {
    const selector = document.querySelector(".mobile-selection select");
    if (selector) selector.value = state.selected?.id || "";
    return;
  }
  if (!isSafeIdentifier(id)) return;
  state.draftController?.abort();
  const controller = new AbortController(); state.draftController = controller;
  state.loading = true; state.error = null; state.message = null;
  state.view = "review"; state.dirty = false; state.conflict = false; state.saveUnknown = false;
  state.intakeContext = {status: "loading", value: null};
  writeRoute({view: "review", draftId: id}, {replace});
  renderShell();
  try {
    const contextRequest = demo ? Promise.resolve({draft_id: id, intake: null}) : api.request(`v1/drafts/${encodeURIComponent(id)}/intake-context`, {signal: controller.signal});
    const contextResult = contextRequest.then((value) => ({value}), (error) => ({error}));
    const draft = demo ? clone(state.drafts.find((item) => item.id === id)) : await api.request(`v1/drafts/${encodeURIComponent(id)}`, { signal: controller.signal });
    if (controller.signal.aborted) return;
    if (!draft) throw new Error("找不到這張收據。");
    state.selected = draft; state.edit = makeEdit(draft);
    const context = await contextResult;
    if (controller.signal.aborted) return false;
    if (context.error || context.value?.draft_id !== id) state.intakeContext = {status: "error", value: null};
    else state.intakeContext = {status: context.value.intake ? "available" : "none", value: context.value.intake};
  } catch (error) {
    if (error.name === "AbortError") return;
    state.error = errorMessage(error); state.selected = null; state.edit = null; state.intakeContext = null;
  } finally {
    if (!controller.signal.aborted) { state.loading = false; renderShell(); }
  }
  return !controller.signal.aborted;
}
async function pageDrafts(offset) {
  if (!prepareNavigation()) return;
  state.draftController?.abort();
  state.draftOffset = offset; state.selected = null; state.edit = null; state.dirty = false;
  state.intakeContext = null; writeRoute({view: "review"});
  await loadData();
}

function field(label, value, update, { type = "text", placeholder = "待確認", wide = false, maxLength = 500, hint = null, ...attributes } = {}) {
  const id = attributes.id || `receipt-field-${++fieldId}`;
  const input = el(type === "textarea" ? "textarea" : "input", {
    id, "aria-label": label, type: type === "textarea" ? null : type, value: value ?? "", placeholder,
    maxLength, autocomplete: "off", oninput: (event) => { update(event.target.value); markDirty(); }, ...attributes,
  });
  return el("label", { class: `field${wide ? " wide" : ""}`, htmlFor: id }, el("span", { class: "field-label" }, label), input, hint ? el("small", {}, hint) : null);
}
function numeric(label, value, update, options = {}) {
  return field(label, value, update, { inputMode: "numeric", pattern: "[0-9]*", maxLength: 16, ...options });
}
function selectField(label, value, options, update, attributes = {}) {
  const id = attributes.id || `receipt-field-${++fieldId}`;
  const select = el("select", { id, "aria-label": label, value: value ?? "", onchange: (event) => { update(event.target.value); markDirty(); }, ...attributes });
  for (const [key, text] of options) select.append(el("option", { value: key, selected: key === (value ?? "") }, text));
  return el("label", { class: "field", htmlFor: id }, el("span", { class: "field-label" }, label), select);
}
function renderWorkspace(workspace) {
  const draft = state.selected;
  const candidate = state.edit.candidate;
  const locked = state.busy || !!state.confirmAttempt || state.conflict || state.saveUnknown || draft.status !== "needs_review";
  workspace.append(el("div", { class: "review-title" },
    el("div", {}, el("h2", {id: "review-merchant"}, candidate.merchant || "店家待確認"), el("p", { class: "caption", id: "review-date" }, `${dateLabel(candidate.receipt_date)} · 第 ${draft.revision} 版`)),
    badge(state.dirty ? "尚未儲存" : "已載入", state.dirty ? "amber" : "")));
  workspace.querySelector(".review-title .badge")?.setAttribute("id", "save-state");
  if (state.conflict || state.saveUnknown) {
    workspace.append(notice(state.conflict ? "版本已更新，這次修改尚未寫入。請先下載目前內容，再重新載入最新版本並核對差異。" : "儲存結果尚未查明。請先保留目前內容，再重新載入確認版本；不會自動重送。", "error",
      [button("下載目前核對內容", exportReview, "secondary small"), button("重新載入最新版本", refreshCurrent, "secondary small")]));
  }
  if (draft.status !== "needs_review") workspace.append(notice(draft.status === "confirmed" ? "這張收據已確認入帳。請到帳本查看正式紀錄。" : "這張收據目前無法修改，請查看帳本狀態。", "info"));
  const editor = el("section", { class: "panel editor compact-review" });
  const form = el("form", { class: "panel-body", onsubmit: (event) => { event.preventDefault(); saveReview(); } });
  const fields = el("fieldset", { disabled: locked, class: "editor-fields" });
  const receiptFields = el("details", {class: "review-disclosure receipt-facts", id: "receipt-facts"},
    el("summary", {}, "修改收據資料", el("span", {class: "disclosure-meta", id: "receipt-facts-status"})),
    el("div", {class: "disclosure-body form-grid"},
      field("店家", candidate.merchant, (value) => candidate.merchant = value),
      field("收據日期", candidate.receipt_date, (value) => candidate.receipt_date = value, {type: "date", placeholder: "日期待確認"}),
      numeric("收據印刷總額（元）", candidate.printed_total_yuan, (value) => candidate.printed_total_yuan = value)));
  const overview = el("div", {class: "receipt-overview", id: "receipt-overview", tabindex: "-1"},
    el("div", {}, el("p", {class: "caption"}, "票面總額"), el("strong", {class: "receipt-total", id: "review-printed-total"}, money(candidate.printed_total_yuan)),
      el("p", {class: "caption"}, "來源：收據印刷欄位，仍需對照原圖")),
    button("查看原圖", () => focusReviewSection("review-original"), "secondary small", {id: "review-original-open"}));
  fields.append(overview, receiptFields);
  const itemSection = el("section", { class: "review-section", id: "review-items" },
    el("div", {class: "review-section-heading"}, el("h3", {}, "品項"), el("span", {class: "caption"}, `${candidate.items.length} 項 · 金額依票面`)));
  candidate.items.forEach((item, index) => itemSection.append(el("details", {class: "review-item", id: `review-item-${index}`},
    el("summary", {"aria-label": `修改品項 ${index + 1}`},
      el("span", {class: "review-item-copy"}, el("strong", {id: `item-name-${index}`}, item.raw_name || "品名待確認"), el("small", {id: `item-meta-${index}`})),
      el("span", {class: "review-item-end"}, el("strong", {id: `item-amount-${index}`}, money(item.printed_line_total_yuan)), el("small", {class: "edit-affordance"}, "修改"))), itemEditor(item, index))));
  itemSection.append(button("新增品項", () => { addItem(); focusReviewSection(`review-item-${state.edit.candidate.items.length - 1}`); }, "ghost small", { disabled: candidate.items.length >= 100 }));
  const adjustmentSection = el("section", {class: "review-section", id: "review-adjustments"},
    el("h3", {}, "折扣與加減項"), el("div", {class: "adjustment-summary", id: "adjustment-summary"}));
  const adjustmentBody = el("div", {class: "disclosure-body"}, el("p", {class: "section-note"}, "已含行金額的折扣只保留資訊，不再次扣款。"));
  candidate.adjustments.forEach((adjustment, index) => adjustmentBody.append(adjustmentEditor(adjustment, index)));
  adjustmentBody.append(button("新增加減項", addAdjustment, "ghost small", { disabled: candidate.adjustments.length >= 50 }));
  adjustmentSection.append(el("details", {class: "review-disclosure", id: "adjustment-details"},
    el("summary", {}, "修改折扣與加減項", el("span", {class: "disclosure-meta"}, `${candidate.adjustments.length} 項`)), adjustmentBody));
  const allocation = el("section", { class: "review-section", id: "review-allocation" }, el("h3", {}, "這批品項的用途"),
    el("p", {class: "allocation-summary", id: "allocation-summary"}), el("p", {class: "caption", id: "allocation-warning"}),
    button("未指定的都設為自己用", applySelfShortcut, "secondary small", {id: "allocation-self-shortcut"}),
    button("設定加減項用途", () => focusReviewSection("allocation-details"), "ghost small", {id: "adjustment-allocation-link"}));
  const allocations = el("div", { id: "allocation-fields" });
  const splitDetails = el("details", {class: "review-disclosure", id: "allocation-details", open: allocationSummary(state.edit).counts.split > 0},
    el("summary", {}, "分開設定用途", el("span", {class: "disclosure-meta"}, "贈送、代墊、分拆")), allocations);
  allocation.append(splitDetails); fillAllocations(allocations);
  const assembly = el("section", {class: "review-section review-completeness", id: "review-completeness"},
    el("h3", {}, "收據是否完整？"),
    selectField("對照原圖後選擇", state.edit.assembly_status, [["unknown", "尚未確認完整性"], ["incomplete", "目前缺頁／缺少品項"], ["complete_by_user", "已對照原圖，確認完整"]], (value) => state.edit.assembly_status = value, {id: "assembly-status"}));
  const context = intakeContextPanel();
  const other = el("details", {class: "review-disclosure review-other"}, el("summary", {}, "說明、紀錄與核對檔"),
    el("div", {class: "disclosure-body"}, context,
      field("這次修改的說明（選填）", state.edit.review_note, (value) => state.edit.review_note = value, {type: "textarea", maxLength: 1000, placeholder: "例如：對照原圖補齊數量與折扣"}),
      el("div", {class: "toolbar mt-10"}, button("修改紀錄", showHistory, "ghost small"), button("下載核對內容", exportReview, "ghost small", {disabled: state.busy}))));
  const tolerance = toleranceAcknowledgement(draft);
  fields.append(itemSection, adjustmentSection, allocation, assembly, ...(tolerance ? [tolerance] : []), other,
    el("div", {class: "review-attention", id: "review-attention"}),
    el("div", { class: "form-actions review-save-bar", id: "review-save-bar" },
      el("span", {class: "caption", id: "review-save-hint"}, "儲存後檢查結果，才會進入入帳確認"),
      el("button", {class: "button", type: "submit", disabled: locked}, state.busy ? "正在處理…" : "儲存並檢查")));
  form.append(fields); editor.append(form);
  const original = el("details", {class: "review-original", id: "review-original", open: window.innerWidth >= 1100},
    el("summary", {}, "原圖與檔案", el("span", {class: "disclosure-meta"}, demo ? "合成示意" : `${draft.evidence?.length ?? 0} 份`)),
    el("div", {class: "original-return"}, button("回到核對", returnToReview, "secondary small")), evidencePanel(draft));
  workspace.append(el("div", { class: "review-columns" }, editor, original));
  if (state.intakeContext?.status === "error") workspace.append(notice("原收件說明暫時無法讀取。", "info", button("重新讀取說明", retryIntakeContext, "secondary small")));
  const evaluation = el("div", { class: "evaluation", id: "evaluation", tabindex: "-1" }); workspace.append(evaluation);
  renderCalculation(evaluation); refreshReviewSummaries();
}

function focusReviewSection(id) {
  const section = document.getElementById(id);
  if (!section) return;
  for (let node = section; node; node = node.parentElement) if (node.tagName === "DETAILS") node.open = true;
  section.scrollIntoView({behavior: "auto", block: "start"});
  const control = section.querySelector("input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, button:not(:disabled)");
  if (!control) section.tabIndex = -1;
  (control || section).focus({preventScroll: true});
}

function returnToReview() {
  const original = document.getElementById("review-original");
  if (original) original.open = false;
  const overview = document.getElementById("receipt-overview");
  overview?.scrollIntoView({behavior: "auto", block: "start"});
  document.getElementById("review-original-open")?.focus({preventScroll: true});
}

function applySelfShortcut() {
  const count = fillUnspecifiedSelf(state.edit, {userInitiated: true});
  if (!count) return;
  markDirty(); refreshAllocationFields();
  setStatus(`已將 ${count} 個未指定品項設為自己用，尚未儲存。既有贈送、代墊與分拆設定保留。`, "info");
}

function observedMoney(value) {
  try { return money(integerInput(String(value ?? ""), "金額")); }
  catch { return "金額格式待確認"; }
}

function refreshReviewSummaries() {
  if (!state.edit || state.view !== "review") return;
  const candidate = state.edit.candidate;
  const setText = (id, text) => { const node = document.getElementById(id); if (node) node.textContent = text; };
  setText("review-merchant", candidate.merchant || "店家待確認");
  setText("review-date", `${dateLabel(candidate.receipt_date)} · 第 ${state.selected.revision} 版`);
  setText("review-printed-total", observedMoney(candidate.printed_total_yuan));
  const missingFacts = [candidate.merchant, candidate.receipt_date, candidate.printed_total_yuan].filter((value) => value === null || value === undefined || String(value).trim() === "").length;
  setText("receipt-facts-status", missingFacts ? `${missingFacts} 欄待確認` : "店家、日期、票面金額");
  const labels = {self: "自己用", gift: "贈送", advance: "代墊"};
  candidate.items.forEach((item, index) => {
    const decision = state.edit.line_decisions[index];
    const purpose = decision?.method === "whole_line" ? labels[decision.whole_purpose] || "用途未指定" : "分開分配";
    setText(`item-name-${index}`, item.raw_name || "品名待確認");
    setText(`item-meta-${index}`, `數量 ${item.quantity === null || item.quantity === undefined || item.quantity === "" ? "待確認" : item.quantity} · ${purpose}`);
    setText(`item-amount-${index}`, observedMoney(item.printed_line_total_yuan));
  });
  const summary = allocationSummary(state.edit);
  setText("allocation-summary", summary.text);
  const warnings = [];
  if (summary.partial) warnings.push("有品項分拆尚未填完整，只保留在此頁。");
  else if (summary.counts.split) warnings.push("已使用分拆設定，請核對各用途的數量與金額。");
  if (summary.adjustmentUnspecified) warnings.push(`另有 ${summary.adjustmentUnspecified} 個加減項尚未指定用途。`);
  if (summary.adjustmentPartial) warnings.push(`有 ${summary.adjustmentPartial} 個加減項的分配尚未完整，只保留在此頁。`);
  setText("allocation-warning", warnings.join(" ") || "贈送計入個人支出，代墊獨立記錄。");
  const adjustmentLink = document.getElementById("adjustment-allocation-link");
  if (adjustmentLink) adjustmentLink.hidden = !summary.adjustmentUnspecified && !summary.adjustmentPartial;
  const shortcut = document.getElementById("allocation-self-shortcut");
  if (shortcut) { shortcut.hidden = summary.counts.unspecified === 0; shortcut.textContent = `未指定的 ${summary.counts.unspecified} 項都設為自己用`; }
  const adjustments = document.getElementById("adjustment-summary");
  if (adjustments) adjustments.replaceChildren(...(candidate.adjustments.length ? candidate.adjustments.map((adjustment) => el("p", {},
    el("span", {}, adjustment.raw_label || "名稱待確認"), el("strong", {}, `${adjustment.direction === "subtract" ? "−" : adjustment.direction === "add" ? "+" : "方向待確認 · "}${observedMoney(adjustment.amount_yuan)}`),
    el("small", {}, adjustment.amount_effect === "included_in_line_totals" ? "已含在行金額" : adjustment.amount_effect === "separate_from_line_totals" ? "另外加減" : "是否已含待確認"))) : [el("p", {class: "caption"}, "沒有列出折扣或加減項")]));
  const attention = document.getElementById("review-attention");
  if (attention) {
    const missingItems = candidate.items.filter((item) => !item.raw_name || item.quantity === null || item.quantity === "" || item.printed_line_total_yuan === null || item.printed_line_total_yuan === "").length;
    const actions = [];
    if (missingFacts) actions.push(button(`收據資料 ${missingFacts} 欄待確認`, () => focusReviewSection("receipt-facts"), "ghost small"));
    if (missingItems || !candidate.items.length) actions.push(button(`品項${missingItems ? ` ${missingItems} 項` : ""}待確認`, () => focusReviewSection("review-items"), "ghost small"));
    if (summary.counts.unspecified || summary.partial) actions.push(button(summary.partial ? "分拆尚未完整" : `用途 ${summary.counts.unspecified} 項未指定`, () => focusReviewSection("allocation-details"), "ghost small"));
    if (summary.adjustmentUnspecified || summary.adjustmentPartial) actions.push(button("加減項用途待確認", () => focusReviewSection("allocation-details"), "ghost small"));
    if (state.edit.assembly_status !== "complete_by_user") actions.push(button("完整性待確認", () => focusReviewSection("review-completeness"), "ghost small"));
    attention.replaceChildren(...(actions.length ? [el("p", {class: "caption"}, "還需核對"), el("div", {class: "toolbar"}, actions)] : []));
  }
  const saveBar = document.getElementById("review-save-bar");
  if (saveBar) saveBar.hidden = !state.dirty && !!state.selected.calculation?.ready;
  setText("review-save-hint", state.dirty ? "修改尚未儲存" : "儲存後檢查結果，再親自確認入帳");
}

async function retryIntakeContext() {
  const id = state.selected?.id;
  if (!id) return;
  try {
    const response = await api.request(`v1/drafts/${encodeURIComponent(id)}/intake-context`);
    if (state.selected?.id !== id) return;
    if (response.draft_id !== id) throw new Error("invalid_intake_context");
    state.intakeContext = {status: response.intake ? "available" : "none", value: response.intake};
  } catch { if (state.selected?.id === id) state.intakeContext = {status: "error", value: null}; }
  if (state.selected?.id === id) renderShell();
}

function intakeContextPanel() {
  const context = state.intakeContext;
  if (!context || context.status === "none") return null;
  if (context.status === "error") return panel("送出時的說明", notice("原收件說明暫時無法讀取，請重新讀取後對照。", "info", button("重新讀取說明", retryIntakeContext, "secondary small")), null, {class: "panel intake-context"});
  if (context.status === "loading") return panel("送出時的說明", el("p", {}, "正在讀取原收件說明…"));
  const input = context.value;
  const notes = el("p", {class: "intake-context-notes", tabindex: "0", "aria-label": "整批原始說明"}, input.user_notes || "未填整批說明");
  const photos = el("div", {class: "intake-context-images"});
  for (const [index, entry] of input.evidence.entries()) photos.append(el("div", {class: "intake-context-image"},
    el("strong", {}, `照片 ${index + 1} · ${imageRoles[entry.role] || "其他"}`),
    el("p", {class: "intake-context-notes"}, entry.notes || "未填逐圖備註"),
    button("對照這張原圖", () => loadEvidence({sha256: entry.sha256, pages: [1]}), "ghost small")));
  const details = el("details", {}, el("summary", {}, `照片用途與逐圖備註（${input.evidence.length} 張）`), photos);
  return panel("送出時的說明", [notes, details, el("p", {class: "caption mt-10"}, "這是原始輸入，仍需人工核對；不會自動分配用途或修改金額。")], null, {class: "panel intake-context"});
}

function itemEditor(item, index) {
  const card = el("div", { class: "item-card" }, el("div", { class: "item-head" }, el("strong", {}, `品項 ${index + 1}`), button("移除", () => removeItem(index), "ghost small")));
  card.append(el("div", { class: "form-grid" },
    field("收據原始品名", item.raw_name, (value) => updateItemFact(index, "raw_name", value), { wide: true }),
    numeric("購買數量", item.quantity, (value) => updateItemFact(index, "quantity", value)),
    numeric("印刷單價（元）", item.printed_unit_price_yuan, (value) => updateItemFact(index, "printed_unit_price_yuan", value)),
    numeric("印刷行金額（元）", item.printed_line_total_yuan, (value) => updateItemFact(index, "printed_line_total_yuan", value)),
    field("品牌", item.brand, (value) => item.brand = value),
    field("規格原文", item.specification, (value) => item.specification = value, { wide: true }),
    field("包裝含量", item.content_quantity?.value_decimal, (value) => { item.content_quantity ||= { value_decimal: "", unit: "" }; item.content_quantity.value_decimal = value; }, { inputMode: "decimal", maxLength: 16, placeholder: "待確認，例如 936" }),
    selectField("含量單位", item.content_quantity?.unit, [["", "待確認"], ["g", "公克（g）"], ["kg", "公斤（kg）"], ["ml", "毫升（ml）"], ["l", "公升（l）"], ["piece", "個／件"]], (value) => { item.content_quantity ||= { value_decimal: "", unit: "" }; item.content_quantity.unit = value; })));
  return card;
}
function addItem() {
  state.edit.candidate.items.push({ raw_name: null, quantity: null, printed_unit_price_yuan: null, printed_line_total_yuan: null, brand: null, specification: null, content_quantity: null });
  state.edit.line_decisions.push(blankLineDecision(state.edit.candidate.items.length - 1));
  state.edit.assembly_status = "unknown";
  state.edit.adjustment_decisions = state.edit.candidate.adjustments.map((_, index) => ({ adjustment_position: index, method: "", amounts_yuan: null }));
  markDirty(); renderShell();
}
function removeItem(index) {
  state.edit.assembly_status = "unknown";
  state.edit.candidate.items.splice(index, 1);
  state.edit.line_decisions = state.edit.candidate.items.map((_, position) => blankLineDecision(position));
  for (const adjustment of state.edit.candidate.adjustments) {
    if (adjustment.scope === "items") { adjustment.scope = null; adjustment.item_positions = []; }
  }
  markDirty(); renderShell(); setStatus("品項已從畫面移除，尚未儲存。請重新核對所有用途與指定品項折扣的對應。", "info");
}
function addAdjustment() {
  state.edit.assembly_status = "unknown";
  state.edit.candidate.adjustments.push({ raw_label: null, direction: null, amount_yuan: null, amount_effect: null, scope: null, item_positions: [] });
  state.edit.adjustment_decisions.push({ adjustment_position: state.edit.candidate.adjustments.length - 1, method: "", amounts_yuan: null }); markDirty(); renderShell();
}
function removeAdjustment(index) {
  state.edit.assembly_status = "unknown";
  state.edit.candidate.adjustments.splice(index, 1);
  state.edit.adjustment_decisions.splice(index, 1);
  state.edit.adjustment_decisions.forEach((decision, position) => decision.adjustment_position = position);
  markDirty(); renderShell();
}
function adjustmentEditor(adjustment, index) {
  return el("div", { class: "item-card", id: `review-adjustment-${index}` }, el("div", { class: "item-head" }, el("strong", {}, `加減項 ${index + 1}`), button("移除", () => removeAdjustment(index), "ghost small")),
    el("div", { class: "form-grid" },
      field("收據原始名稱", adjustment.raw_label, (value) => updateAdjustmentFact(index, "raw_label", value), { wide: true }),
      selectField("加減方向", adjustment.direction, [["", "待確認"], ["subtract", "扣除／折扣"], ["add", "加收"]], (value) => updateAdjustmentFact(index, "direction", value || null)),
      numeric("金額（元，填非負整數）", adjustment.amount_yuan, (value) => updateAdjustmentFact(index, "amount_yuan", value)),
      selectField("是否已含於行金額？", adjustment.amount_effect, [["", "待確認"], ["separate_from_line_totals", "尚未包含，需另外加減"], ["included_in_line_totals", "已包含，不重複加減"]], (value) => updateAdjustmentFact(index, "amount_effect", value || null)),
      selectField("影響範圍", adjustment.scope, [["", "待確認"], ["receipt", "整張收據"], ["items", "指定品項"]], (value) => { updateAdjustmentFact(index, "scope", value || null); if (value !== "items") adjustment.item_positions = []; }),
      field("指定品項編號（逗號分隔）", Array.isArray(adjustment.item_positions) ? adjustment.item_positions.map((position) => position + 1).join(", ") : adjustment.item_positions, (value) => updateAdjustmentFact(index, "item_positions", value), { wide: true, placeholder: "例如 1, 3；只在指定品項時填寫", maxLength: 500 })));
}
const purposeOptions = [["", "請選擇用途"], ["self", "自己用"], ["gift", "贈送"], ["advance", "代墊"]];
function tripleFields(decision, key, quantity = false) {
  decision[key] ||= { self: null, gift: null, advance: null };
  return el("div", { class: "form-grid" }, ...[["self", "自己用"], ["gift", "贈送"], ["advance", "代墊"]].map(([purpose, label]) => numeric(`${label}${quantity ? "數量" : "金額（元）"}`, decision[key][purpose], (value) => decision[key][purpose] = value, { placeholder: "需填整數，無此用途填 0" })));
}
function allocationEditor(item, index) {
  const decision = state.edit.line_decisions[index];
  const wrapper = el("div", { class: "allocation-row" });
  const controls = el("div", { class: "allocation-controls" });
  const detail = el("div", { class: "mt-10" });
  const updateDetail = () => {
    detail.replaceChildren();
    if (decision.method === "whole_line") detail.append(selectField("整個品項的用途", decision.whole_purpose, purposeOptions, (value) => decision.whole_purpose = value || null));
    else if (decision.method === "by_quantity") detail.append(tripleFields(decision, "quantities", true));
    else detail.append(tripleFields(decision, "quantities", true), el("p", { class: "caption mt-10" }, "數量與金額都需填寫；無此用途請明確填 0，不能把金額分給數量為 0 的用途。"), tripleFields(decision, "amounts_yuan"));
  };
  controls.append(selectField("分配方式", decision.method, [["whole_line", "整項同一用途"], ["by_quantity", "按數量分配"], ["explicit_amounts", "直接指定金額"]], (value) => { decision.method = value; decision.whole_purpose = null; decision.quantities = null; decision.amounts_yuan = null; updateDetail(); }));
  wrapper.append(el("p", { class: "allocation-name" }, `${index + 1}. ${item.raw_name || "品名待確認"}`), controls, detail); updateDetail();
  return wrapper;
}
function adjustmentAllocation(adjustment, index) {
  const decision = state.edit.adjustment_decisions[index];
  const wrapper = el("div", { class: "allocation-row", id: `allocation-adjustment-${index}` });
  const detail = el("div", { class: "mt-10" });
  const updateDetail = () => {
    detail.replaceChildren();
    if (decision.method === "explicit_amounts") detail.append(tripleFields(decision, "amounts_yuan"));
    else if (decision.method === "proportional_amount") detail.append(el("p", { class: "caption" }, "由帳本依受影響金額比例，使用固定規則分配；前端不自行計算。"));
    else detail.append(el("p", { class: "caption" }, "另列的加減項需確認分配；已含在行金額中的折扣不會重複計入。"));
  };
  wrapper.append(el("p", { class: "allocation-name" }, `加減項 ${index + 1}：${adjustment.raw_label || "名稱待確認"}`),
    selectField("加減項分配", decision.method, [["", "請確認分配方式"], ["proportional_amount", "依受影響金額比例"], ["explicit_amounts", "直接指定各用途金額"]], (value) => { decision.method = value; decision.amounts_yuan = null; updateDetail(); }), detail); updateDetail();
  return wrapper;
}

function readReview() {
  return buildReview(state.edit, state.selected.revision).body;
}

function fillAllocations(container) {
  container.replaceChildren();
  state.edit.candidate.items.forEach((item, index) => container.append(allocationEditor(item, index)));
  state.edit.candidate.adjustments.forEach((adjustment, index) => {
    if (adjustment.amount_effect !== "included_in_line_totals") container.append(adjustmentAllocation(adjustment, index));
    else container.append(el("p", { class: "section-note mt-12" }, `加減項 ${index + 1}已含在行金額，不另外分配。`));
  });
}
function refreshAllocationFields() {
  const container = document.querySelector("#allocation-fields");
  if (container) fillAllocations(container);
  const assembly = document.querySelector("#assembly-status");
  if (assembly) assembly.value = state.edit.assembly_status;
  refreshReviewSummaries();
}
function updateItemFact(index, key, value) {
  state.edit.candidate.items[index][key] = value;
  clearItemReview(state.edit, index);
  refreshAllocationFields();
  setStatus(`品項 ${index + 1}的事實已修改。這個品項的用途、所有加減項分配與收據完整性已清除，請重新核對。`, "info");
}
function updateAdjustmentFact(index, key, value) {
  state.edit.candidate.adjustments[index][key] = value;
  state.edit.adjustment_decisions[index] = { adjustment_position: index, method: "", amounts_yuan: null };
  state.edit.assembly_status = "unknown";
  refreshAllocationFields();
}

async function saveReview() {
  if (state.busy || state.confirmAttempt || state.conflict || state.saveUnknown) return;
  let prepared;
  try { prepared = buildReview(state.edit, state.selected.revision); }
  catch (error) { state.error = errorMessage(error); refreshStatus(); focusReviewSection("status"); return; }
  if (demo) { setStatus("這是合成示範。編輯內容只留在目前畫面，沒有送出，也沒有產生新的計算或正式紀錄。", "info"); return; }
  state.busy = true; state.error = null; renderShell();
  try {
    const draft = await api.request(`v2/drafts/${encodeURIComponent(state.selected.id)}/review`, { method: "PUT", body: prepared.body });
    state.selected = draft; state.edit = makeEdit(draft);
    for (const decision of prepared.unsavedLineDecisions) state.edit.line_decisions[decision.item_position] = decision;
    for (const decision of prepared.unsavedAdjustmentDecisions) state.edit.adjustment_decisions[decision.adjustment_position] = decision;
    state.dirty = prepared.warnings.length > 0;
    state.drafts = state.drafts.map((item) => item.id === draft.id ? draft : item);
    state.message = prepared.warnings.length ? {
      text: `收據內容與已完整填寫的分配已儲存。${prepared.warnings.join(" ")}請補齊後再次儲存，或下載目前內容留存。`, type: "info",
    } : { text: "核對內容已儲存。請檢查下方計算結果，再決定是否確認入帳。", type: "success" };
  } catch (error) {
    state.error = errorMessage(error);
    state.conflict = error instanceof ApiError && error.status === 409;
    state.saveUnknown = !(error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status));
  } finally { state.busy = false; renderShell(); focusReviewSection(state.error ? "status" : "evaluation"); }
}

function exportReview() {
  let body;
  try { body = { ...readReview(), unsaved_editor_values: clone(state.edit) }; }
  catch { body = { expected_revision: state.selected.revision, unsaved_editor_values: clone(state.edit), note: "尚未通過表單檢查，不可直接作為入帳資料" }; }
  const blob = new Blob([JSON.stringify({ synthetic_demo: demo, draft_id: state.selected.id, ...body }, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = el("a", { href: url, download: demo ? "synthetic-review.json" : "receipt-review-unsaved.json" });
  document.body.append(link); link.click(); link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function evidencePanel(draft) {
  const stage = el("div", { class: "evidence-stage", id: "evidence-stage" }, empty("原始收據", "選擇原檔後，在這裡對照品項與金額。", "▧"));
  const tabs = el("div", { class: "evidence-tabs" });
  const content = el("div", {}, tabs, stage, el("div", { id: "evidence-actions", class: "toolbar mt-10" }), el("p", { class: "evidence-caption" }, "原檔只供對照；候選文字與金額仍需人工核對。"));
  const evidence = panel("原始收據", content, badge(demo ? "合成" : `${draft.evidence?.length ?? 0} 份`, "lavender"), { class: "panel evidence-panel" });
  draft.evidence?.forEach((reference, index) => tabs.append(button(`原檔 ${index + 1} · 第 ${reference.pages.join("、")} 頁`, () => loadEvidence(reference), "secondary small")));
  if (demo) stage.replaceChildren(syntheticReceipt(draft));
  else if (draft.evidence?.[0]) queueMicrotask(() => loadEvidence(draft.evidence[0]));
  return evidence;
}
function syntheticReceipt(draft) {
  const candidate = draft.candidate;
  return el("div", { class: "synthetic-receipt", "aria-label": "合成收據示意，並非原始收據" },
    el("h3", {}, candidate.merchant || "店家待確認"), el("p", {}, dateLabel(candidate.receipt_date)), el("hr"),
    ...candidate.items.map((item) => el("div", { class: "receipt-line" }, el("span", {}, item.raw_name || "？"), el("span", {}, item.printed_line_total_yuan ?? "？"))),
    ...candidate.adjustments.map((item) => el("div", { class: "receipt-line" }, el("span", {}, item.raw_label || "？"), el("span", {}, `${item.direction === "subtract" ? "−" : "+"}${item.amount_yuan ?? "？"}`))),
    el("hr"), el("div", { class: "receipt-line" }, el("strong", {}, "合計"), el("strong", {}, candidate.printed_total_yuan ?? "？")), el("p", { class: "synthetic-stamp" }, "SYNTHETIC · 合成示範，非原圖"));
}
async function loadEvidence(reference) {
  if (demo) return;
  clearEvidence();
  const controller = new AbortController(); state.evidenceController = controller;
  const stage = document.querySelector("#evidence-stage");
  const actions = document.querySelector("#evidence-actions");
  if (!stage || !actions) return;
  stage.replaceChildren(empty("正在讀取原檔…", "請稍候。")); actions.replaceChildren();
  try {
    const { blob, media } = await api.evidence(reference.sha256, { signal: controller.signal });
    if (controller.signal.aborted) return;
    state.evidenceUrl = URL.createObjectURL(blob);
    if (media.startsWith("image/")) stage.replaceChildren(el("img", { src: state.evidenceUrl, alt: "收據原始圖片，請對照旁邊的候選內容", onerror: () => stage.replaceChildren(empty("圖片無法顯示", "可下載原檔後查看。")) }));
    else stage.replaceChildren(empty("PDF 原件", `已選取第 ${reference.pages.join("、")} 頁。請開啟或下載原始 PDF 對照。`, "▧"));
    actions.append(el("a", { class: "button secondary small", href: state.evidenceUrl, target: "_blank", rel: "noopener noreferrer" }, media === "application/pdf" ? `開啟原始 PDF（第 ${reference.pages.join("、")} 頁） ↗` : "放大開啟原圖 ↗"),
      el("a", { class: "button ghost small", href: state.evidenceUrl, download: `receipt-original.${media === "application/pdf" ? "pdf" : media.split("/")[1]}` }, "下載原檔"));
  } catch (error) {
    if (error.name === "AbortError") return;
    stage.replaceChildren(empty("原檔暫時無法開啟", errorMessage(error)));
    actions.append(button("重新讀取原檔", () => loadEvidence(reference), "secondary small"));
  }
}

function renderCalculation(container) {
  if (state.dirty) { container.append(notice(state.message?.text || "內容已修改，請先儲存並檢查。", "info", button("回到用途設定", () => focusReviewSection("allocation-details"), "ghost small"))); return; }
  if (demo) { container.append(panel("檢查結果", notice("合成示範不執行帳務計算，也不能入帳。", "info"))); return; }
  if (state.conflict || state.saveUnknown) { container.append(notice("這個版本的結果需重新讀取確認。請先保留目前核對內容，再重新載入。", "info")); return; }
  const calculation = state.selected.calculation;
  if (!calculation) { container.append(panel("檢查結果", el("p", {class: "caption"}, "儲存並檢查後，這裡會顯示實付、個人支出與對帳結果。"))); return; }
  const reconciliation = { balanced: "金額相符", within_tolerance: "有一元以內差額，需核對", unbalanced: "金額不符", indeterminate: "尚無法對帳", invalid: "計算無效" };
  const body = el("div", {}, el("p", {class: "caption"}, `依已儲存的第 ${state.selected.revision} 版計算`),
    el("div", {class: "calculation-summary"}, calcRow("實付（收據票面）", calculation.printed_total_yuan), calcRow("個人支出（含贈送）", calculation.personal_yuan),
      el("div", {class: "calc-row"}, el("span", {}, "對帳狀態"), el("strong", {}, reconciliation[calculation.reconciliation_status] || "對帳狀態待確認"))),
    el("details", {class: "review-disclosure"}, el("summary", {}, "計算明細"), calculationRows(calculation)));
  if (calculation.blockers?.length) {
    body.append(el("div", {class: "review-blockers"}, el("p", {}, "還有項目需要核對，現在不能入帳。"),
      el("ul", {class: "issues"}, ...calculation.blockers.map((issue) => el("li", {}, el("span", {}, blockerText(issue)), button("前往核對", () => focusBlocker(issue), "ghost small"))))));
  }
  if ((calculation.ready && state.selected.status === "needs_review") || state.confirmAttempt) {
    const check = el("input", { type: "checkbox", disabled: !!state.confirmAttempt || state.selected.status !== "needs_review" || state.busy });
    const confirm = button(state.busy ? "正在確認…" : state.confirmAttempt ? "重試同一次確認" : "確認入帳", () => confirmDraft(), "", { disabled: state.busy || (!state.confirmAttempt) });
    check.addEventListener("change", () => confirm.disabled = !check.checked || !calculation.ready || state.busy);
    if (!state.confirmAttempt) body.append(el("label", { class: "confirm-check" }, check, el("span", {}, "我已對照完整原圖，確認品項、折扣、金額與用途，同意寫入正式帳本。")));
    body.append(confirm, el("p", {class: "confirm-note"}, "按下確認入帳後，才會寫入正式帳本。"));
  }
  if (state.confirmAttempt) body.prepend(state.busy ? notice("正在確認入帳，請稍候。這次操作進行中，請勿重複送出。", "info") : notice("上次確認結果尚未查明。請重試同一次操作，系統會保留相同識別，避免重複入帳。", "error"));
  container.append(panel("檢查結果", body, badge(calculation.ready ? "可確認入帳" : "需要補齊", calculation.ready ? "" : "amber")));
}

function focusBlocker(issue) {
  const code = typeof issue === "string" ? issue : issue?.code || "";
  if (code.startsWith("adjustment_") && (code.includes("purpose") || code.includes("allocation")) && typeof issue?.adjustment_position === "number") return focusReviewSection(`allocation-adjustment-${issue.adjustment_position}`);
  if (code.includes("purpose") || code.includes("allocation") || code === "negative_purpose_burden") return focusReviewSection("allocation-details");
  if (typeof issue?.item_position === "number") return focusReviewSection(`review-item-${issue.item_position}`);
  if (typeof issue?.adjustment_position === "number") return focusReviewSection(`review-adjustment-${issue.adjustment_position}`);
  if (code.startsWith("adjustment_")) return focusReviewSection("adjustment-details");
  if (code.includes("completeness")) return focusReviewSection("review-completeness");
  if (code === "tolerance_acknowledgement_required") return focusReviewSection("tolerance-section");
  if (["merchant_unknown", "receipt_date_unknown", "printed_total_unknown"].includes(code)) return focusReviewSection("receipt-facts");
  focusReviewSection("review-items");
}

function calculationRows(calculation) {
  return el("div", {},
    calcRow("收據印刷總額", calculation.printed_total_yuan),
    calcRow("品項與加減項計算合計", calculation.computed_total_yuan),
    calcRow("自己用", calculation.self_yuan),
    calcRow("贈送", calculation.gift_yuan),
    calcRow("代墊", calculation.advance_yuan),
    calcRow("個人支出（含贈送）", calculation.personal_yuan),
    calcRow("收據對帳差額", calculation.reconciliation_difference_yuan, true),
    calcRow("用途合計與印刷總額的差額", calculation.difference_yuan),
    el("p", { class: "caption mt-10" }, "對帳差額獨立保留，不分配至任何用途；個人支出包含自己用與贈送，代墊獨立記錄。"));
}

function toleranceAcknowledgement(draft) {
  const calculation = draft.calculation;
  if (!calculation || calculation.reconciliation_status !== "within_tolerance") return null;
  if (state.dirty && state.edit.tolerance_acknowledgement === "none") return el("p", { class: "caption mt-12" }, "內容已修改，請先儲存核對以取得新的差額，再決定是否接受。");
  const checkbox = el("input", {
    type: "checkbox", id: "tolerance-check",
    checked: state.edit.tolerance_acknowledgement === "accept_within_tolerance",
    onchange: (event) => {
      state.edit.tolerance_acknowledgement = event.target.checked ? "accept_within_tolerance" : "none";
      markDirty(true);
    },
  });
  return el("div", { class: "form-section", id: "tolerance-section" }, notice(`已儲存內容的獨立對帳差額為 ${money(calculation.reconciliation_difference_yuan)}。差額不會分配至自己用、贈送或代墊。`, ""),
    el("label", { class: "confirm-check" }, checkbox, "我已對照原圖，接受上述一元以內的對帳差額。"),
    el("p", { class: "caption" }, "勾選後需再次儲存核對。修改任何內容會清除此選項，需重新核對差額。"));
}

function calcRow(label, amount, total = false) {
  return el("div", { class: `calc-row${total ? " total" : ""}` }, el("span", {}, label), el("strong", {}, money(amount)));
}
function blockerText(issue) {
  const code = typeof issue === "string" ? issue : issue?.code;
  const known = {
    receipt_completeness_unconfirmed: "請對照原圖，確認收據頁面與品項已完整。",
    merchant_unknown: "店家尚未確認。",
    receipt_date_unknown: "收據日期尚未確認。",
    printed_total_unknown: "收據印刷總額尚未確認。",
    no_items: "請至少補上一個有根據的品項。",
    item_name_unknown: "仍有品項名稱尚未確認。",
    item_quantity_unknown: "仍有品項數量尚未確認。",
    item_amount_unknown: "仍有品項行金額尚未確認。",
    adjustment_amount_unknown: "仍有折扣或加收金額尚未確認。",
    adjustment_direction_unknown: "請確認加減項為扣除或加收。",
    adjustment_scope_unknown: "請確認加減項影響整張收據或指定品項。",
    adjustment_effect_unknown: "請確認折扣是否已含在行金額內，避免重複扣除。",
    computed_total_out_of_range: "計算金額超出允許範圍，請核對原圖。",
    tolerance_acknowledgement_required: "有一元以內對帳差額，請核對後勾選接受差額，再次儲存。",
    receipt_total_mismatch: "印刷總額與計算結果不一致，請對照原圖。",
    item_purpose_unconfirmed: "仍有品項尚未確認用途。",
    item_quantity_allocation_mismatch: "用途分配數量與購買數量不一致。",
    item_amount_allocation_mismatch: "用途分配金額與品項行金額不一致。",
    item_allocation_without_quantity: "按數量分配前，需要先確認購買數量。",
    adjustment_allocation_unconfirmed: "請確認另列加減項的分配方式。",
    adjustment_item_purpose_unconfirmed: "加減項影響的品項仍有用途尚未確認。",
    zero_allocation_basis: "目前沒有可用的分配基礎，請核對相關品項與金額。",
    adjustment_allocation_mismatch: "加減項的用途分配金額不一致。",
    adjustment_allocation_outside_scope: "加減項分配到了影響範圍外的用途。",
    negative_purpose_burden: "有用途分配後變成負數，請核對折扣與分配金額。",
    expense_total_out_of_range: "用途金額超出允許範圍，請核對原圖。"
  };
  const location = typeof issue?.item_position === "number" ? `品項 ${issue.item_position + 1}：` :
    typeof issue?.adjustment_position === "number" ? `加減項 ${issue.adjustment_position + 1}：` : "";
  return location + (known[code] || "尚有資料需要確認；請檢查收據完整性、每項金額、折扣與用途。");
}

async function confirmDraft() {
  if (demo || state.busy || state.dirty || state.conflict || state.saveUnknown || state.selected.status !== "needs_review" || !state.selected.calculation?.ready) return;
  if (!state.confirmAttempt) {
    try { state.confirmAttempt = { id: state.selected.id, body: { expected_revision: state.selected.revision }, key: freshKey() }; }
    catch (error) { state.error = errorMessage(error); refreshStatus(); return; }
  }
  const attempt = state.confirmAttempt;
  state.busy = true; state.error = null; renderShell();
  try {
    const entry = await api.request(`v2/drafts/${encodeURIComponent(attempt.id)}/confirm`, { method: "POST", body: attempt.body, key: attempt.key });
    state.confirmAttempt = null; state.dirty = false;
    state.selected.status = "confirmed";
    state.ledger.unshift(entry.entry || entry);
    state.drafts = state.drafts.filter((draft) => draft.id !== attempt.id);
    state.overview = null; state.productPrices = null;
    state.view = "ledger";
    writeRoute({view: "ledger"}, {replace: true});
    state.message = { text: "收據已確認入帳。這次操作已完成，不需再次送出。", type: "success" };
    await loadLedger(0);
  } catch (error) {
    state.error = errorMessage(error);
    if (error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)) {
      state.confirmAttempt = null;
      state.conflict = error.status === 409;
    }
  } finally { state.busy = false; renderShell(); }
}

function pagination(offset, length, action) {
  return el("div", { class: "pagination" }, button("上一頁", () => action(Math.max(0, offset - 20)), "ghost small", { disabled: offset === 0 || state.loading }),
    el("span", {}, length ? `第 ${offset + 1}–${offset + length} 筆` : "沒有紀錄"),
    button("下一頁", () => action(offset + 20), "ghost small", { disabled: length < 20 || state.loading || offset >= 99980 }));
}

function ledgerView(entry) {
  const snapshot = entry.snapshot || entry;
  const candidate = snapshot.candidate || {};
  const calculation = snapshot.calculation || {};
  return {
    merchant: candidate.merchant || entry.merchant || "店家待確認",
    receipt_date: candidate.receipt_date || entry.receipt_date,
    total: calculation.printed_total_yuan ?? entry.total_paid_yuan,
    self: calculation.self_yuan ?? entry.self_yuan,
    gift: calculation.gift_yuan ?? entry.gift_yuan,
    advance: calculation.advance_yuan ?? entry.advance_yuan,
    personal: calculation.personal_yuan ?? entry.personal_expense_yuan,
    candidate, calculation: snapshot.calculation,
  };
}

function renderLedger(container) {
  const search = el("form", { class: "search-form", onsubmit: (event) => { event.preventDefault(); loadLedger(0); } },
    field("搜尋店家或原始品名", state.query, (value) => state.query = value, { placeholder: "輸入店家或品名", oninput: (event) => state.query = event.target.value }),
    field("起始日期", state.dateFrom, (value) => state.dateFrom = value, { type: "date", oninput: (event) => state.dateFrom = event.target.value }),
    field("結束日期", state.dateTo, (value) => state.dateTo = value, { type: "date", oninput: (event) => state.dateTo = event.target.value }),
    selectField("帳本狀態", state.ledgerStatus, [["active", "未作廢"], ["voided", "已作廢"], ["all", "全部狀態"]], (value) => state.ledgerStatus = value, { onchange: (event) => state.ledgerStatus = event.target.value }),
    el("button", { type: "submit", class: "button", disabled: state.loading }, "查詢帳本"));
  container.append(search, el("p", { class: "caption mb-16" }, "在完整帳本範圍搜尋，再分頁顯示；日期依收據日期篩選。"));
  const filtered = state.ledger;
  if (!filtered.length) container.append(panel("正式紀錄", empty(state.loading ? "正在讀取帳本…" : "沒有符合條件的紀錄", "可調整店家、品名、日期或帳本狀態；待核對草稿不會列入正式帳本。")));
  else {
    const table = el("table", { class: "ledger-table" }, el("thead", {}, el("tr", {}, el("th", { scope: "col" }, "店家與日期"), el("th", { scope: "col", class: "number" }, "收據實付"), el("th", { scope: "col", class: "number desktop-column" }, "個人支出"), el("th", { scope: "col", class: "number desktop-column" }, "代墊"), el("th", { scope: "col" }, "狀態"))));
    const body = el("tbody");
    for (const entry of filtered) {
      const view = ledgerView(entry);
      body.append(el("tr", {}, el("td", {}, button(view.merchant, () => showLedgerEntry(entry.id), "ghost small", { class: "button ghost small ledger-open" }),
        el("p", { class: "row-meta" }, dateLabel(view.receipt_date))),
      el("td", { class: "number" }, money(view.total)),
      el("td", { class: "number desktop-column" }, money(view.personal)),
      el("td", { class: "number desktop-column" }, money(view.advance)),
      el("td", {}, statusBadge(entry.status || "active"))));
    }
    table.append(body); container.append(el("section", { class: "panel table-scroll" }, table));
  }
  container.append(pagination(state.ledgerOffset, state.ledger.length, (offset) => loadLedger(offset)));
}
function ledgerParameters(offset) {
  const parameters = dateParameters(state.dateFrom, state.dateTo);
  parameters.set("limit", "20"); parameters.set("offset", String(offset));
  if (state.query) parameters.set("q", state.query);
  if (state.ledgerStatus !== "all") parameters.set("status", state.ledgerStatus);
  return parameters;
}
async function loadLedger(offset = 0) {
  let parameters;
  try { parameters = ledgerParameters(offset); }
  catch (error) { state.error = errorMessage(error); refreshStatus(); return; }
  const request = ++state.ledgerRequest;
  state.ledgerOffset = offset; state.loading = true; state.error = null; state.ledger = []; renderShell();
  try {
    if (demo) {
      state.ledger = clone(demoLedger).filter((entry) => (!state.query || entry.merchant.includes(state.query)) && (!state.dateFrom || entry.receipt_date >= state.dateFrom) && (!state.dateTo || entry.receipt_date <= state.dateTo) && state.ledgerStatus !== "voided");
    } else {
      const value = await api.request(`v1/ledger?${parameters}`);
      if (request !== state.ledgerRequest) return;
      state.ledger = value.entries || [];
      state.ledgerTotal = value.total_count ?? null;
    }
  } catch (error) { if (request === state.ledgerRequest) state.error = errorMessage(error); }
  finally { if (request === state.ledgerRequest) { state.loading = false; renderShell(); } }
}

function dialog(title, body, extra) {
  const close = () => { if (!node.canClose || node.canClose()) node.close(); };
  const node = el("dialog", { "aria-label": title }, el("div", { class: "panel-header" }, el("h2", {}, title), button("關閉", close, "ghost small")), el("div", { class: "panel-body" }, body, extra));
  node.addEventListener("cancel", (event) => { if (node.canClose && !node.canClose()) event.preventDefault(); });
  node.addEventListener("close", () => node.remove());
  document.body.append(node); node.showModal(); return node;
}
async function showHistory() {
  const body = el("div", {}, empty("正在讀取修改紀錄…", "請稍候。"));
  const node = dialog("修改紀錄", body);
  try {
    const value = demo ? { revisions: [{ revision: 1, actor: "initial_import", created_at: "2026-10-20T03:00:00Z", review_note: "合成示範匯入，並非人工驗收。" }] } : await api.request(`v1/drafts/${encodeURIComponent(state.selected.id)}/history`);
    if (!node.open) return;
    body.replaceChildren(...value.revisions.map((revision) => el("div", { class: "history-row" }, el("strong", {}, `第 ${revision.revision} 版`), el("p", {}, revision.created_at ? new Date(revision.created_at).toLocaleString("zh-TW") : "時間未提供"), el("p", {}, revision.review_note || (revision.actor === "initial_import" ? "初次匯入" : "人工核對修改")))));
  } catch (error) { if (node.open) body.replaceChildren(notice(errorMessage(error), "error")); }
}
async function showLedgerEntry(id, {replace = false} = {}) {
  if (!isSafeIdentifier(id) || !prepareNavigation()) return;
  const previous = activeRoute;
  writeRoute({view: "ledger", ledgerId: id}, {replace});
  const body = el("div", {}, empty("正在讀取帳本明細…", "請稍候。"));
  const node = dialog("帳本明細", body);
  state.routeDialog = node;
  node.addEventListener("close", () => {
    if (state.routeDialog !== node) return;
    state.routeDialog = null;
    writeRoute(previous.ledgerId ? {view: "ledger"} : previous, {replace: true});
  });
  try {
    const entry = demo ? clone(state.ledger.find((item) => item.id === id)) : await api.request(`v1/ledger/${encodeURIComponent(id)}`);
    if (!node.open) return;
    const view = ledgerView(entry);
    body.replaceChildren(el("h3", {}, view.merchant), el("p", { class: "caption ledger-date" }, dateLabel(view.receipt_date)), statusBadge(entry.status || "active"));
    if (view.calculation) body.append(calculationRows(view.calculation));
    else body.append(calcRow("收據實付", view.total), calcRow("個人支出", view.personal), calcRow("贈送", view.gift), calcRow("代墊", view.advance));
    if (view.candidate.items) body.append(el("div", { class: "form-section" }, el("h3", {}, "已確認品項"), ...view.candidate.items.map((item) => el("div", { class: "product-row" }, el("span", {}, item.raw_name || "品名待確認"), el("strong", {}, money(item.printed_line_total_yuan))))));
    if (entry.snapshot?.evidence?.length) {
      const references = el("div", { class: "form-section" }, el("h3", {}, "原始收據"));
      entry.snapshot.evidence.forEach((reference, index) => references.append(button(`查看原檔 ${index + 1}（第 ${reference.pages.join("、")} 頁）`, () => openLedgerEvidence(reference, references), "secondary small", { class: "button secondary small evidence-link" })));
      body.append(references);
    }
    if (!demo) body.append(priceReviewSection(entry, node));
    if (entry.status === "voided") body.append(notice(`此紀錄已作廢。${entry.events?.find((event) => event.action === "voided")?.reason || ""}`, "info"));
    else if (!demo) body.append(voidForm(entry, node));
  } catch (error) { if (node.open) body.replaceChildren(notice(errorMessage(error), "error")); }
}

async function openLedgerEvidence(reference, container) {
  const result = el("div", { class: "evidence-caption" }, "正在讀取原檔…");
  container.append(result);
  try {
    const { blob, media } = await api.evidence(reference.sha256);
    if (!result.isConnected) return;
    const url = URL.createObjectURL(blob);
    const node = container.closest("dialog");
    node?.addEventListener("close", () => URL.revokeObjectURL(url), { once: true });
    result.replaceChildren(el("a", { class: "button secondary small", href: url, target: "_blank", rel: "noopener noreferrer" }, media === "application/pdf" ? `開啟原始 PDF（第 ${reference.pages.join("、")} 頁） ↗` : "開啟原始圖片 ↗"));
    if (media.startsWith("image/")) result.append(el("img", { src: url, alt: "已入帳收據的原始圖片", class: "ledger-evidence-image" }));
  } catch (error) { if (result.isConnected) result.replaceChildren(notice(errorMessage(error), "error")); }
}

function voidForm(entry, node) {
  let attempt = state.voidAttempts.get(entry.id) || null;
  let reason = attempt?.body.reason || ""; let checked = !!attempt; let pending = false;
  const output = el("div", { "aria-live": "polite", class: "mt-12" });
  const submit = button(attempt ? "重試同一次作廢" : "作廢這筆紀錄", async () => {
    if (pending || !checked || !reason.trim()) return;
    if (state.annotation?.dirty || state.annotation?.busy) { output.replaceChildren(notice("請先儲存或捨棄目前的商品價格核對，再作廢紀錄。", "error")); return; }
    if (!attempt) {
      try { attempt = { body: { expected_revision: entry.revision, reason }, key: freshKey() }; state.voidAttempts.set(entry.id, attempt); }
      catch (error) { output.replaceChildren(notice(errorMessage(error), "error")); return; }
    }
    pending = true; submit.disabled = true; reasonInput.disabled = true; checkbox.disabled = true;
    output.replaceChildren(notice("正在處理作廢…", "info"));
    try {
      await api.request(`v1/ledger/${encodeURIComponent(entry.id)}/void`, { method: "POST", body: attempt.body, key: attempt.key });
      attempt = null; state.voidAttempts.delete(entry.id); state.overview = null; state.productPrices = null; node.close(); state.message = { text: "紀錄已作廢，原始紀錄與原因仍保留。", type: "success" };
      if (state.view === "prices" && state.selectedProduct) await loadProductPrices(state.selectedProduct, 0);
      else await loadLedger(state.ledgerOffset);
    } catch (error) {
      output.replaceChildren(notice(errorMessage(error), "error"));
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)) { attempt = null; state.voidAttempts.delete(entry.id); reasonInput.disabled = false; checkbox.disabled = false; }
      else output.append(notice("結果尚未查明。按下方按鈕會重試同一次作廢，不會改用新的操作識別。", "info"));
      submit.textContent = attempt ? "重試同一次作廢" : "作廢這筆紀錄";
    } finally { pending = false; submit.disabled = false; }
  }, "danger", { disabled: !attempt });
  const reasonInput = el("textarea", { value: reason, disabled: !!attempt, placeholder: "請填寫作廢原因", maxLength: 1000, rows: 2, oninput: (event) => { reason = event.target.value; submit.disabled = !checked || !reason.trim(); } });
  const checkbox = el("input", { type: "checkbox", checked, disabled: !!attempt, onchange: (event) => { checked = event.target.checked; submit.disabled = !checked || !reason.trim(); } });
  return el("div", { class: "form-section" }, el("h3", {}, "需要修正正式紀錄？"), el("p", { class: "section-note" }, "作廢會保留原紀錄與原因，不直接改寫已入帳的金額。"), el("label", { class: "field" }, el("span", {}, "作廢原因"), reasonInput),
    el("label", { class: "confirm-check" }, checkbox, "我確認要作廢這筆紀錄。"), submit, output);
}

function renderPrices(container) {
  container.append(el("div", { class: "price-note" }, "只在商品、規格、含量與實付金額都有根據時比較價格；整單折扣未分配或商品對應未確認時，保留待確認狀態。"));
  if (!demo) { renderProductCatalog(container); return; }
  container.append(notice("以下是商品價格模組的合成示意，尚未連接正式價格查詢。", "info"));
  const grid = el("div", { class: "price-grid" });
  for (const product of demoPrices) {
    grid.append(el("section", { class: "panel product-card" }, el("h3", {}, product.product_name), el("p", { class: "row-meta" }, product.specification), badge(product.comparable ? "同規格示意" : "尚不可比較", product.comparable ? "" : "amber"),
      ...product.observations.map((item) => el("div", { class: "product-row" }, el("div", {}, el("span", {}, item.merchant), el("p", { class: "row-meta" }, dateLabel(item.receipt_date))), el("strong", {}, money(item.paid_unit_price_yuan)))),
      product.reason ? el("p", { class: "caption mt-12" }, product.reason) : null));
  }
  container.append(grid);
}

function renderProductCatalog(container) {
  const search = el("form", { class: "search-form", onsubmit: (event) => { event.preventDefault(); loadProducts(0); } },
    field("搜尋商品名稱、品牌或規格", state.productQuery, (value) => state.productQuery = value, { placeholder: "例如：無糖茶 500mL", oninput: (event) => state.productQuery = event.target.value }),
    el("button", { type: "submit", class: "button", disabled: state.loading }, "搜尋商品"),
    button("＋ 建立商品", () => createProductDialog(() => loadProducts(0)), "secondary"));
  container.append(search, el("p", { class: "caption mb-16" }, "每個商品代表固定品牌、品項、規格及一個購買單位。不同容量或組合包分開保存，不依名稱自動合併。"));
  if (!state.products.length) container.append(panel("商品目錄", empty(state.loading ? "正在讀取商品…" : "目前沒有符合的商品", "可先建立明確的商品身份，再到已入帳收據逐列核對對應。", "◇")));
  else {
    const grid = el("div", { class: "price-grid" });
    for (const product of state.products) grid.append(el("section", { class: "panel product-card" }, el("h3", {}, product.display_name), el("p", { class: "row-meta" }, productLabel(product)),
      button("同規格價格歷史 →", () => loadProductPrices(product, 0), "secondary small")));
    container.append(grid);
  }
  container.append(el("p", { class: "caption mt-12" }, `符合搜尋的商品共 ${state.productTotal} 個。`), pagination(state.productOffset, state.products.length, (offset) => loadProducts(offset)));
  if (state.selectedProduct) renderProductHistory(container);
}
async function loadProducts(offset = 0) {
  const request = ++state.productsRequest;
  state.productOffset = offset; state.loading = true; state.error = null; state.products = []; renderShell();
  try {
    const query = new URLSearchParams({ limit: "20", offset: String(offset) });
    if (state.productQuery) query.set("query", state.productQuery);
    const value = await api.request(`v1/products?${query}`);
    if (request !== state.productsRequest) return;
    state.products = value.products || []; state.productTotal = value.total_count ?? state.products.length;
  } catch (error) { if (request === state.productsRequest) { state.error = errorMessage(error); state.productTotal = 0; } }
  finally { if (request === state.productsRequest) { state.loading = false; renderShell(); } }
}
async function loadProductPrices(product, offset = 0) {
  let parameters;
  try { parameters = dateParameters(state.priceFrom, state.priceTo); }
  catch (error) { state.error = errorMessage(error); refreshStatus(); return; }
  const request = ++state.pricesRequest;
  state.selectedProduct = product; state.priceOffset = offset; state.productPrices = null;
  state.loading = true; state.error = null; renderShell();
  try {
    parameters.set("limit", "20"); parameters.set("offset", String(offset));
    const value = await api.request(`v1/products/${encodeURIComponent(product.id)}/prices?${parameters}`);
    if (request !== state.pricesRequest) return;
    state.productPrices = value; state.selectedProduct = value.product;
  } catch (error) { if (request === state.pricesRequest) state.error = errorMessage(error); }
  finally { if (request === state.pricesRequest) { state.loading = false; renderShell(); } }
}
function renderProductHistory(container) {
  const product = state.selectedProduct;
  const data = state.productPrices;
  const section = el("section", { class: "panel product-history" }, el("div", { class: "panel-header" }, el("h2", {}, `${product.display_name} · 同規格價格`), button("收起", () => { state.pricesRequest += 1; state.selectedProduct = null; state.productPrices = null; renderShell(); }, "ghost small")));
  const body = el("div", { class: "panel-body" }, el("p", { class: "section-note" }, productLabel(product)),
    dateForm(state.priceFrom, state.priceTo, (value) => state.priceFrom = value, (value) => state.priceTo = value, () => loadProductPrices(product, 0), "查詢價格"));
  if (!data) body.append(empty(state.loading ? "正在讀取價格…" : "價格尚未載入", "只查詢未作廢且經人工確認商品對應的正式帳本。"));
  else {
    body.append(el("p", { class: "caption mb-16" }, `${scopeLabel(data)} · 全範圍 ${data.total_count} 筆觀測，分頁只影響下方列表。`));
    const lowest = data.lowest_paid_observation;
    if (lowest) body.append(el("div", { class: "lowest-price" }, el("div", {}, el("p", { class: "stat-label" }, "此期間最低實付單價觀測"), el("p", { class: "stat-value" }, exactPrice(lowest.paid_unit_price)), el("p", { class: "caption" }, `每${product.purchase_unit} · ${lowest.merchant} · ${dateLabel(lowest.receipt_date)}`)),
      button("回查原始收據", () => showLedgerEntry(lowest.ledger_id), "secondary small")), el("p", { class: "caption mb-16" }, "歷史最低價只代表已保存的觀測，不表示今天仍可買到；不比較其他規格或未購買報價。"));
    else body.append(notice("此期間尚無可確定的實付價格。商品對應或共用折扣尚未核對的列不參與最低價。", "info"));
    if (!data.observations.length) body.append(empty("此期間沒有觀測紀錄", "請核對已入帳收據的商品對應與價格分配，或調整日期。", "◇"));
    for (const observation of data.observations) {
      body.append(el("article", { class: "observation-card" }, el("div", { class: "section-heading" }, el("div", {}, el("h3", {}, observation.merchant || "店家待確認"), el("p", { class: "caption" }, `${dateLabel(observation.receipt_date)} · 原品名：${observation.raw_name}`)), badge(observation.comparison_eligible ? "可比較實付價" : "實付待確認", observation.comparison_eligible ? "" : "amber")),
        el("div", { class: "observation-values" },
          el("div", {}, el("p", { class: "caption" }, "購買數量／票面行金額"), el("strong", {}, `${observation.quantity} ${product.purchase_unit} / ${money(observation.printed_line_total_yuan)}`)),
          el("div", {}, el("p", { class: "caption" }, "行金額換算單價"), el("strong", {}, exactPrice(observation.printed_unit_price))),
          el("div", {}, el("p", { class: "caption" }, "實付單件價格"), el("strong", {}, exactPrice(observation.paid_unit_price)))),
        el("p", { class: "caption mt-12" }, allocationEvidence(observation.allocations, observation.unknown_reasons)),
        observation.unknown_reasons?.length ? el("p", { class: "caption mt-10" }, observation.unknown_reasons.map(priceReason).join("；")) : null,
        button("查看來源收據與核對紀錄", () => showLedgerEntry(observation.ledger_id), "ghost small")));
    }
    body.append(pagination(state.priceOffset, data.observations.length, (offset) => loadProductPrices(product, offset)));
  }
  section.append(body); container.append(section);
}
function allocationEvidence(allocations = [], reasons = []) {
  if (reasons.includes("shared_adjustment_unallocated")) return "有共用加減項尚未分配，此列實付金額未知；沒有套用用途分配。";
  if (!allocations.length) return "沒有另列分配影響此列；已含行金額的折扣不重複扣款。";
  return allocations.map((allocation) => `加減項 ${allocation.adjustment_position + 1}：${allocation.direction === "subtract" ? "扣除" : "加收"} ${money(allocation.amount_yuan)}（${{
    single_line_scope: "只適用單一列", proportional_line_amount: "按票面行金額比例", explicit_amounts: "人工指定列金額",
  }[allocation.method] || "已核對分配"}）`).join("；");
}

function createProductDialog(onCreated) {
  let attempt = state.productCreateAttempt;
  let source = attempt ? clone(attempt.body) : { display_name: "", brand: "", specification: "", purchase_unit: "", content_quantity: null };
  let acknowledged = !!attempt; let pending = false; let dirty = false;
  const body = el("div"); const node = dialog("建立固定規格商品", body);
  node.canClose = () => !pending && (!dirty || window.confirm("商品還未建立。關閉前請確認已保留需要的內容，是否繼續？"));
  const output = el("div", { "aria-live": "polite", class: "mt-12" });
  const mark = (key, value) => { source[key] = value; dirty = true; };
  const fields = el("fieldset", { class: "editor-fields", disabled: !!attempt });
  fields.append(el("div", { class: "form-grid" },
    field("商品名稱", source.display_name, (value) => mark("display_name", value), { wide: true, oninput: (event) => mark("display_name", event.target.value) }),
    field("品牌（未知可留空）", source.brand, (value) => mark("brand", value), { oninput: (event) => mark("brand", event.target.value) }),
    field("固定規格", source.specification, (value) => mark("specification", value), { placeholder: "例如：無糖 500mL", oninput: (event) => mark("specification", event.target.value) }),
    field("一個購買單位", source.purchase_unit, (value) => mark("purchase_unit", value), { placeholder: "例如：瓶、塊、6入組", oninput: (event) => mark("purchase_unit", event.target.value) }),
    field("每購買單位的包裝含量（選填）", source.content_quantity?.value_decimal, () => {}, { inputMode: "decimal", maxLength: 16, placeholder: "例如：500", oninput: (event) => { source.content_quantity ||= { value_decimal: "", unit: "" }; source.content_quantity.value_decimal = event.target.value; dirty = true; } }),
    selectField("包裝含量單位（選填）", source.content_quantity?.unit, [["", "未知"], ["g", "公克（g）"], ["kg", "公斤（kg）"], ["ml", "毫升（ml）"], ["l", "公升（l）"], ["piece", "個／件"]], () => {}, { onchange: (event) => { source.content_quantity ||= { value_decimal: "", unit: "" }; source.content_quantity.unit = event.target.value; dirty = true; } })));
  const check = el("input", { type: "checkbox", checked: acknowledged, disabled: !!attempt, onchange: (event) => { acknowledged = event.target.checked; submit.disabled = !acknowledged; } });
  const submit = button(attempt ? "重試同一次建立" : "建立商品", async () => {
    if (pending || !acknowledged) return;
    try { if (!attempt) { attempt = { body: buildProduct(source), key: freshKey() }; state.productCreateAttempt = attempt; } }
    catch (error) { output.replaceChildren(notice(errorMessage(error), "error")); return; }
    pending = true; fields.disabled = true; check.disabled = true; submit.disabled = true;
    output.replaceChildren(notice("正在建立商品…", "info"));
    try {
      const product = await api.request("v1/products", { method: "POST", body: attempt.body, key: attempt.key });
      state.productCreateAttempt = null; attempt = null; dirty = false; node.close(); onCreated?.(product);
    } catch (error) {
      output.replaceChildren(notice(errorMessage(error), "error"));
      if (error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status)) {
        state.productCreateAttempt = null; attempt = null; fields.disabled = false; check.disabled = false;
      } else output.append(notice("建立結果尚未查明。請重試同一次建立，會沿用相同內容與識別，避免重複商品。", "info"));
      submit.textContent = attempt ? "重試同一次建立" : "建立商品";
    } finally { pending = false; submit.disabled = false; }
  }, "", { disabled: !acknowledged });
  body.append(notice("商品建立後保留固定規格。請先搜尋已有商品；同名的不同容量或組合包，應分別建立。", "info"), fields,
    el("label", { class: "confirm-check" }, check, "我已確認這是固定品牌／品項／規格，並寫明一個購買單位。"), submit, output);
}

function productPicker(onSelect) {
  const body = el("div"); const node = dialog("手動選擇同規格商品", body);
  let query = ""; let offset = 0; let pending = false;
  async function load() {
    if (pending) return; pending = true;
    const parameters = new URLSearchParams({ query, limit: "20", offset: String(offset) });
    body.replaceChildren(empty("正在讀取商品…", "請稍候。"));
    try {
      const value = await api.request(`v1/products?${parameters}`);
      if (!node.open) return;
      body.replaceChildren(el("p", { class: "section-note" }, "請人工比對品牌、規格及購買單位。相似名稱不表示同一商品。"),
        el("form", { class: "search-form", onsubmit: (event) => { event.preventDefault(); offset = 0; load(); } },
          field("搜尋商品", query, () => {}, { oninput: (event) => query = event.target.value, placeholder: "名稱、品牌或規格" }), el("button", { class: "button", type: "submit" }, "搜尋")));
      for (const product of value.products) body.append(el("div", { class: "picker-row" }, el("div", {}, el("strong", {}, product.display_name), el("p", { class: "caption" }, productLabel(product))), button("選擇此商品", () => { node.close(); onSelect(product); }, "secondary small")));
      if (!value.products.length) body.append(empty("沒有符合的商品", "可修改搜尋條件，或明確建立新的固定規格商品。", "◇"));
      body.append(pagination(offset, value.products.length, (next) => { offset = next; load(); }),
        button("＋ 建立新的固定規格商品", () => createProductDialog((product) => { node.close(); onSelect(product); }), "ghost small"));
    } catch (error) { if (node.open) body.replaceChildren(notice(errorMessage(error), "error"), button("重新讀取", load, "secondary")); }
    finally { pending = false; }
  }
  load();
}

function priceReviewSection(entry, ledgerDialog) {
  const section = el("section", { class: "form-section price-review" });
  const ctx = { ledgerId: entry.id, candidate: entry.snapshot.candidate, response: null, editor: null, dirty: false, busy: false, failed: false, error: null, message: null, revocationAck: false, readOnly: entry.status === "voided" };
  state.annotation = ctx;
  ledgerDialog.canClose = () => !ctx.busy && (!ctx.dirty || window.confirm("商品價格核對尚未儲存。關閉會捨棄這些修改，是否繼續？"));
  ledgerDialog.addEventListener("close", () => { if (state.annotation === ctx) state.annotation = null; });
  async function load(force = false) {
    if (!force && ctx.dirty && !window.confirm("重新載入會捨棄尚未儲存的商品價格核對，是否繼續？")) return;
    ctx.busy = true; ctx.error = null; render();
    try {
      ctx.response = await api.request(`v1/ledger/${encodeURIComponent(entry.id)}/price-review`);
      ctx.editor = {
        mappings: ctx.candidate.items.map((_, position) => {
          const mapping = ctx.response.item_mappings.find((row) => row.item_position === position);
          return mapping ? { ...mapping, product: ctx.response.line_prices.find((row) => row.item_position === position)?.product } : { product_id: null, product: null, unit_match: null };
        }),
        adjustments: ctx.candidate.adjustments.map((_, position) => {
          const decision = ctx.response.adjustment_decisions.find((row) => row.adjustment_position === position);
          return { method: decision?.method || "", amounts: Object.fromEntries((decision?.amounts_by_item || []).map((row) => [row.item_position, row.amount_yuan])) };
        }),
      };
      ctx.dirty = false; ctx.failed = false; ctx.revocationAck = false;
    } catch (error) { ctx.error = errorMessage(error); }
    finally { ctx.busy = false; if (section.isConnected) render(); }
  }
  function mark() {
    ctx.dirty = true; ctx.message = null;
    const results = section.querySelector(".price-results");
    results?.replaceChildren(notice("商品對應或價格分配已修改。請儲存後，再查看新的實付價格。", "info"));
  }
  async function save() {
    if (ctx.busy || ctx.failed || ctx.readOnly) return;
    let body;
    try {
      body = buildPriceReview(ctx.editor, ctx.candidate, ctx.response.revision);
      if (revokedMappings(ctx.response.item_mappings, body.item_mappings).length && !ctx.revocationAck) throw new Error("這次會撤銷或替換已儲存的商品對應。請先勾選了解撤銷影響，再儲存。");
    } catch (error) { ctx.error = errorMessage(error); render(); return; }
    ctx.busy = true; ctx.error = null; render();
    try {
      ctx.response = await api.request(`v1/ledger/${encodeURIComponent(entry.id)}/price-review`, { method: "PUT", body });
      ctx.dirty = false; ctx.revocationAck = false;
      ctx.message = "商品對應與價格分配已儲存；原始帳務金額與用途沒有改寫。";
      state.productPrices = null;
      await load(true);
    } catch (error) {
      ctx.error = errorMessage(error);
      ctx.failed = error.status === 409 || !(error instanceof ApiError && error.status >= 400 && error.status < 500 && ![408, 429].includes(error.status));
    } finally { ctx.busy = false; if (section.isConnected) render(); }
  }
  function exportPriceEdit() {
    const blob = new Blob([JSON.stringify({ ledger_id: ctx.ledgerId, expected_revision: ctx.response?.revision, unsaved_price_editor: ctx.editor }, null, 2)], { type: "application/json" });
    const url = URL.createObjectURL(blob); const anchor = el("a", { href: url, download: "receipt-price-review-unsaved.json" });
    document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  function render() {
    section.replaceChildren(el("div", { class: "section-heading" }, el("h3", {}, "商品與折扣價格核對"), badge(ctx.response ? `價格核對第 ${ctx.response.revision} 版` : "讀取中", "lavender")),
      el("p", { class: "section-note" }, "這裡只核對商品身份與每列實付價格，與自己用、贈送、代墊的用途分配分開保存。"));
    if (ctx.error) section.append(notice(ctx.error, "error"));
    if (ctx.message) section.append(notice(ctx.message, "success"));
    if (!ctx.editor) { section.append(button("讀取商品價格核對", () => load(true), "secondary small", { disabled: ctx.busy })); return; }
    const fields = el("fieldset", { class: "editor-fields", disabled: ctx.busy || ctx.failed || ctx.readOnly });
    ctx.candidate.items.forEach((item, index) => {
      const mapping = ctx.editor.mappings[index];
      const card = el("div", { class: "item-card" }, el("p", { class: "allocation-name" }, `${index + 1}. ${item.raw_name}`), el("p", { class: "caption" }, `收據數量 ${item.quantity} · 票面行金額 ${money(item.printed_line_total_yuan)}`),
        el("p", { class: "mapping-product" }, productLabel(mapping.product)),
        el("div", { class: "toolbar" }, button(mapping.product_id ? "更換商品" : "指定商品", () => productPicker((product) => { mapping.product = product; mapping.product_id = product.id; mapping.unit_match = null; ctx.revocationAck = false; mark(); render(); }), "secondary small"),
          mapping.product_id ? button("解除對應", () => { mapping.product = null; mapping.product_id = null; mapping.unit_match = null; ctx.revocationAck = false; mark(); render(); }, "ghost small") : null));
      const check = el("input", { type: "checkbox", disabled: !mapping.product_id, checked: mapping.unit_match === "confirmed_same_product_and_unit", onchange: (event) => { mapping.unit_match = event.target.checked ? "confirmed_same_product_and_unit" : null; ctx.revocationAck = false; mark(); } });
      card.append(el("label", { class: "confirm-check" }, check, `我已人工確認品項 ${index + 1}與所選商品的品牌、規格一致，收據數量 1 對應商品的 1 個購買單位。`));
      fields.append(card);
    });
    if (ctx.candidate.adjustments.length) fields.append(el("h3", { class: "mt-20" }, "獨立的商品價格分配"));
    ctx.candidate.adjustments.forEach((adjustment, index) => {
      const positions = adjustmentPositions(ctx.candidate, adjustment);
      const card = el("div", { class: "item-card" }, el("strong", {}, `加減項 ${index + 1}：${adjustment.raw_label}`), el("p", { class: "caption mb-16" }, `${adjustment.direction === "subtract" ? "扣除" : "加收"} ${money(adjustment.amount_yuan)} · ${adjustment.scope === "receipt" ? "整張收據" : `品項 ${positions.map((position) => position + 1).join("、")}`}`));
      if (adjustment.amount_effect === "included_in_line_totals") { card.append(notice("已含在行金額，只保留折扣證據，不再次分配或扣款。", "info")); fields.append(card); return; }
      if (positions.length === 1) { card.append(el("p", { class: "caption" }, "只適用單一商品列，由帳本直接歸屬，不需要共用分配。")); fields.append(card); return; }
      const decision = ctx.editor.adjustments[index]; const amounts = el("div", { class: "mt-12" });
      function renderAmounts() {
        amounts.replaceChildren();
        if (decision.method === "explicit_amounts") positions.forEach((position) => amounts.append(numeric(`品項 ${position + 1}分配金額（元）`, decision.amounts[position], () => {}, { placeholder: "需明確填整數，未分配填 0", oninput: (event) => { decision.amounts[position] = event.target.value; mark(); } })));
        else if (decision.method === "proportional_line_amount") amounts.append(el("p", { class: "caption" }, "由帳本按適用列的票面行金額，以固定整數規則分配，前端不自行計算。"));
        else amounts.append(el("p", { class: "caption" }, "尚未分配時，受影響列的實付價格保持未知，不參與最低價。"));
      }
      card.append(selectField(`加減項 ${index + 1}的商品價格分配方式`, decision.method, [["", "尚未核對"], ["proportional_line_amount", "按適用列的票面行金額比例"], ["explicit_amounts", "人工指定每列金額"]], () => {}, { onchange: (event) => { decision.method = event.target.value; decision.amounts = {}; mark(); renderAmounts(); } }), amounts);
      renderAmounts(); fields.append(card);
    });
    fields.append(el("p", { class: "section-note mt-12" }, "解除對應或改選商品並儲存後，之前的對應會撤銷並保留歷史。沒有核對的共用折扣不會自動套用用途分配。"),
      el("label", { class: "confirm-check" }, el("input", { type: "checkbox", checked: ctx.revocationAck, onchange: (event) => ctx.revocationAck = event.target.checked }), "若此次撤銷或替換既有商品對應，我了解相關價格比較將重新計算。"),
      button(ctx.busy ? "正在儲存…" : "儲存商品與價格核對", save, "", { disabled: ctx.busy }));
    section.append(fields, el("div", { class: "toolbar mt-12" }, button("重新載入價格核對", () => load(), "secondary small", { disabled: ctx.busy }), button("下載目前價格核對", exportPriceEdit, "ghost small"), button("價格核對歷史", () => showPriceHistory(ctx.ledgerId), "ghost small")));
    const results = el("div", { class: "price-results mt-20" }); section.append(results);
    if (ctx.dirty) results.append(notice("商品對應或價格分配已修改。請儲存後，再查看新的實付價格。", "info"));
    else renderLinePriceResults(results, ctx.response.line_prices, ctx.readOnly);
    if (ctx.failed) section.append(notice("已停止再次送出。請先下載目前內容，再重新載入最新價格核對版本並檢查差異。", "error"));
    if (ctx.readOnly) section.append(notice("這筆帳本已作廢，保留價格核對歷史供回查；不參與摘要或價格比較。", "info"));
  }
  queueMicrotask(() => load(true)); return section;
}
function renderLinePriceResults(container, lines, archived = false) {
  container.append(el("h3", {}, "已儲存版本的每列價格"));
  for (const line of lines) container.append(el("article", { class: "observation-card" }, el("div", { class: "section-heading" }, el("strong", {}, `${line.item_position + 1}. ${line.raw_name}`), badge(archived ? "已作廢，供回查" : line.comparison_eligible ? "可比較" : "待核對", archived ? "red" : line.comparison_eligible ? "" : "amber")),
    el("p", { class: "caption" }, productLabel(line.product)),
    el("div", { class: "observation-values mt-12" }, el("div", {}, el("p", { class: "caption" }, "行金額換算單價"), el("strong", {}, exactPrice(line.printed_unit_price))), el("div", {}, el("p", { class: "caption" }, "實付行金額"), el("strong", {}, money(line.paid_line_total_yuan))), el("div", {}, el("p", { class: "caption" }, "實付單件價"), el("strong", {}, exactPrice(line.paid_unit_price)))),
    el("p", { class: "caption mt-12" }, allocationEvidence(line.allocations, line.unknown_reasons)),
    line.unknown_reasons?.length ? el("p", { class: "caption mt-10" }, line.unknown_reasons.map(priceReason).join("；")) : null));
}
async function showPriceHistory(ledgerId) {
  const body = el("div", {}, empty("正在讀取價格核對歷史…", "請稍候。")); const node = dialog("價格核對歷史", body);
  try {
    const value = await api.request(`v1/ledger/${encodeURIComponent(ledgerId)}/price-review/history`);
    if (!node.open) return;
    if (!value.revisions.length) { body.replaceChildren(empty("尚無商品價格核對歷史", "第一次儲存後，商品對應與分配會保留版本紀錄。")); return; }
    body.replaceChildren(...value.revisions.map((revision) => el("article", { class: "history-row" }, el("strong", {}, `價格核對第 ${revision.revision} 版`), el("p", {}, `${revision.actor || "核對者"} · ${new Date(revision.created_at).toLocaleString("zh-TW")}`),
      el("p", {}, revision.item_mappings.length ? revision.item_mappings.map((mapping) => `品項 ${mapping.item_position + 1} → 商品 ${mapping.product_id}`).join("\n") : "此版本沒有已確認商品對應。"),
      el("p", {}, revision.adjustment_decisions.length ? revision.adjustment_decisions.map((decision) => `加減項 ${decision.adjustment_position + 1}：${decision.method === "proportional_line_amount" ? "按票面行金額比例" : "人工指定列金額"}`).join("；") : "此版本沒有共用價格分配。"))));
  } catch (error) { if (node.open) body.replaceChildren(notice(errorMessage(error), "error")); }
}

const intakeStatuses = {
  queued: "已收件", preparing: "準備辨識", submitting: "送出中", processing: "辨識中",
  unknown: "結果待查明", succeeded: "辨識完成", failed: "辨識未完成", unavailable: "辨識來源暫不可用",
};
const imageRoles = {receipt: "收據", price_tag: "價標", promotion: "促銷", menu: "菜單", other: "其他"};

async function loadIntakes(offset = state.intakeOffset) {
  if (demo) return;
  state.loading = true; state.error = null; renderShell();
  try {
    const [intakes, devices] = await Promise.all([
      api.request(`v1/intakes?limit=20&offset=${offset}`), api.request("v1/devices"),
    ]);
    state.intakes = intakes.intakes; state.devices = devices.devices; state.intakeOffset = offset;
  } catch (error) { state.error = errorMessage(error); }
  state.loading = false; renderShell();
}

function renderIntakes(container) {
  const records = el("div", {});
  if (!state.intakes.length) records.append(empty("尚無手機收件", "在 PixelReceipt 選取照片、說明與用途後送出。"));
  for (const intake of state.intakes) {
    records.append(el("article", {class: "history-row"},
      el("div", {class: "section-heading"}, el("strong", {}, intake.input.purpose === "reference" ? "價格參考" : "消費收據"),
        badge(intakeStatuses[intake.status] || "狀態待查明", intake.status === "succeeded" ? "" : "amber")),
      el("p", {}, intake.input.user_notes || "沒有附加說明"),
      el("p", {class: "caption"}, new Date(intake.created_at).toLocaleString("zh-TW")),
      button("查看收件與原圖", () => showIntake(intake.id)),
      intake.draft_id ? button("核對草稿", () => openDraft(intake.draft_id)) : null));
  }
  records.append(el("div", {class: "toolbar"},
    button("上一頁", () => loadIntakes(Math.max(0, state.intakeOffset - 20)), "secondary", {disabled: state.intakeOffset === 0}),
    button("下一頁", () => loadIntakes(state.intakeOffset + 20), "secondary", {disabled: state.intakes.length < 20})));
  const devices = el("div", {});
  if (!state.devices.length) devices.append(el("p", {}, "尚無手機登入裝置。"));
  for (const device of state.devices) {
    const active = device.revoked_at === null && device.expires_at * 1000 > Date.now();
    devices.append(el("article", {class: "history-row"}, el("strong", {}, device.device_name),
      el("p", {class: "caption"}, `${active ? "已登入" : "已失效"} · 到期 ${new Date(device.expires_at * 1000).toLocaleString("zh-TW")}`),
      active ? button("撤銷裝置登入", async () => {
        try {
          await api.request(`v1/devices/${encodeURIComponent(device.device_id)}`, {method: "DELETE"});
          await loadIntakes();
        } catch (error) { state.error = errorMessage(error); refreshStatus(); }
      }) : null));
  }
  container.append(panel("收件進度", records), panel("手機裝置", devices));
}

async function showIntake(id) {
  const content = el("div", {}, el("p", {}, "正在讀取…"));
  const node = dialog("手機收件", content);
  try {
    const intake = await api.request(`v1/intakes/${encodeURIComponent(id)}`);
    if (!node.open) return;
    content.replaceChildren(el("p", {}, intakeStatuses[intake.status] || "狀態待查明"),
      el("h3", {}, "原始說明"), el("p", {}, intake.input.user_notes || "沒有附加說明"));
    if (intake.error_code) content.append(notice(
      intake.status === "unknown" ? "原工作結果尚未查明，沒有重新送出辨識。請保留此收件，稍後核對。" : "辨識未完成；原圖與說明已保留。", "info"));
    for (const [index, entry] of intake.evidence_snapshot.entries()) {
      content.append(el("div", {class: "history-row"}, el("strong", {}, `${index + 1}. ${imageRoles[entry.role]}`),
        el("p", {}, entry.notes || "沒有逐圖備註"), button("開啟原圖", () => openLedgerEvidence({sha256: entry.sha256, pages: [1]}, content))));
    }
    if (intake.reference_candidate) {
      content.append(el("h3", {}, "價格參考（未入帳）"));
      for (const item of intake.reference_candidate.items) content.append(el("p", {},
        `${item.raw_name || "品名待確認"} · ${money(item.printed_line_total_yuan)}`));
    }
    if (intake.result) content.append(el("p", {class: "caption"},
      `${intake.result.provider || "來源待確認"} / ${intake.result.model || "模型待確認"} · 原始輸出已保存`));
    if (intake.draft_id) content.append(button("開啟待核對草稿", () => { node.close(); openDraft(intake.draft_id); }));
  } catch (error) { if (node.open) content.replaceChildren(notice(errorMessage(error), "error")); }
}


function renderUnavailable(error) {
  app.setAttribute("aria-busy", "false");
  app.replaceChildren(header(), el("main", { id: "main", class: "login-layout" },
    el("div", { class: "login-copy" }, el("p", { class: "eyebrow" }, "每張收據，都有清楚紀錄"), el("h1", {}, "每一筆生活，", el("br"), "都清楚留存。"), el("p", { class: "subtitle" }, "在手機與電腦核對同一本收據帳本，從原圖到支出，都有據可查。")),
    el("section", { class: "panel login-card" }, el("h2", {}, "開啟我的帳本"), el("p", {}, "請登入後查看私人收據與正式紀錄。"), notice(errorMessage(error), "error"),
      el("a", { class: "button full", href: new URL("auth/login", appBase) }, "前往登入"), button("重新連線", start, "secondary full", { class: "mt-10" }),
      el("p", { class: "login-note" }, el("a", { href: "?demo=1" }, "先看合成示範")))));
}

async function start() {
  const target = parseDashboardRoute(location.hash);
  if (!demo) {
    try { state.session = await api.session(); }
    catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        const login = new URL("auth/login", appBase);
        login.searchParams.set("return_to", dashboardRouteHash(target));
        location.replace(login); return;
      }
      renderUnavailable(error); return;
    }
  }
  await loadData();
  if (dashboardRouteHash(target) === dashboardRouteHash(activeRoute)) await navigateRoute(target, {replace: true, initial: true});
}

async function routeChanged() {
  const target = parseDashboardRoute(location.hash);
  if (dashboardRouteHash(target) === dashboardRouteHash(activeRoute)) return;
  const previous = activeRoute;
  const request = ++routeRequest;
  const moved = await navigateRoute(target, {replace: true});
  if (!moved && request === routeRequest) writeRoute(previous, {replace: true});
}
window.addEventListener("hashchange", routeChanged);
window.addEventListener("popstate", routeChanged);
window.addEventListener("beforeunload", (event) => {
  if (state.dirty || state.confirmAttempt || state.voidAttempts.size || state.annotation?.dirty || state.productCreateAttempt) { event.preventDefault(); event.returnValue = ""; }
});
window.addEventListener("pagehide", clearEvidence);
start();
