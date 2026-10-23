// Money is never calculated here. The server owns reconciliation and allocation.
const MAX_SAFE_TEXT = String(Number.MAX_SAFE_INTEGER);

export class UnsafeIntegerError extends Error {
  constructor() {
    super("資料包含超出瀏覽器可安全編輯範圍的數字。已停止載入，避免金額精度遺失。");
    this.name = "UnsafeIntegerError";
  }
}

function integerIsSafe(token) {
  const digits = token.replace(/^-/, "");
  return digits.length < MAX_SAFE_TEXT.length ||
    (digits.length === MAX_SAFE_TEXT.length && digits <= MAX_SAFE_TEXT);
}

export function parseSafeJson(text) {
  // Check numeric tokens BEFORE JSON.parse can round a signed 64-bit integer.
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i += 1) {
    const character = text[i];
    if (inString) {
      if (escaped) escaped = false;
      else if (character === "\\") escaped = true;
      else if (character === '"') inString = false;
    } else if (character === '"') {
      inString = true;
    } else if (character === "-" || /[0-9]/.test(character)) {
      const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(text.slice(i));
      if (match) {
        if (!/^-?[0-9]+$/.test(match[0]) || !integerIsSafe(match[0])) {
          throw new UnsafeIntegerError();
        }
        i += match[0].length - 1;
      }
    }
  }
  return JSON.parse(text);
}

export function integerInput(value, label, { minimum = 0, maximum = Number.MAX_SAFE_INTEGER } = {}) {
  if (value.trim() === "") return null;
  if (!/^(0|[1-9][0-9]*)$/.test(value) || !integerIsSafe(value)) {
    throw new Error(`${label}請填可安全處理的非負整數；未知請留空。`);
  }
  const parsed = Number(value);
  if (parsed < minimum || parsed > maximum) throw new Error(`${label}超出允許範圍。`);
  return parsed;
}

export function nullableText(value) {
  return value.trim() === "" ? null : value;
}

export function money(value) {
  if (value === null || value === undefined) return "待確認";
  if (typeof value !== "number" || !Number.isSafeInteger(value)) return "金額無法安全顯示";
  return `NT$ ${value.toLocaleString("zh-TW")}`;
}

export function dateLabel(value) {
  if (!value) return "日期待確認";
  if (/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(value)) return value.replaceAll("-", "/");
  return "日期待確認";
}

export function receiptTitle(draft) {
  return draft?.candidate?.merchant || "店家待確認";
}

export function isSafeIdentifier(value) {
  return typeof value === "string" && /^[A-Za-z0-9._:-]{1,128}$/.test(value);
}

export function freshKey() {
  if (!globalThis.crypto?.randomUUID) throw new Error("此瀏覽器不支援安全操作識別，請更新瀏覽器後再試。");
  return globalThis.crypto.randomUUID();
}


const DASHBOARD_VIEWS = new Set(["overview", "review", "ledger", "prices", "intakes"]);
const ROUTE_ID = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|demo-[a-z0-9-]+)$/;

export function parseDashboardRoute(hash) {
  const detail = /^#(draft|ledger)\/([^/]+)$/.exec(hash || "");
  if (detail && ROUTE_ID.test(detail[2])) {
    return detail[1] === "draft" ? {view: "review", draftId: detail[2]} : {view: "ledger", ledgerId: detail[2]};
  }
  const view = (hash || "").replace(/^#/, "");
  return {view: DASHBOARD_VIEWS.has(view) ? view : "overview"};
}

export function dashboardRouteHash(route) {
  if (route.view === "review" && ROUTE_ID.test(route.draftId || "")) return `#draft/${route.draftId}`;
  if (route.view === "ledger" && ROUTE_ID.test(route.ledgerId || "")) return `#ledger/${route.ledgerId}`;
  return `#${DASHBOARD_VIEWS.has(route.view) ? route.view : "overview"}`;
}
