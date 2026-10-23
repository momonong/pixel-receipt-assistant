import { parseSafeJson } from "./values.mjs";

export const appBase = new URL("./", import.meta.url);

export class ApiError extends Error {
  constructor(status, code = "request_failed") {
    super(code);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export class Api {
  constructor() {
    this.csrf = null;
  }

  url(path) {
    // All paths originate in this source; untrusted receipt text never chooses a URL.
    return new URL(path.replace(/^\//, ""), appBase);
  }

  async request(path, { method = "GET", body, key, signal } = {}) {
    const headers = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET") {
      if (!this.csrf) throw new ApiError(401, "authentication_required");
      headers["X-CSRF-Token"] = this.csrf;
    }
    if (key) headers["Idempotency-Key"] = key;
    const response = await fetch(this.url(path), {
      method, headers, credentials: "same-origin", cache: "no-store", redirect: "error",
      body: body === undefined ? undefined : JSON.stringify(body), signal,
    });
    const text = await response.text();
    let value;
    try { value = text ? parseSafeJson(text) : null; }
    catch (error) {
      if (error.name === "UnsafeIntegerError") throw error;
      throw new ApiError(response.status, "invalid_server_response");
    }
    if (!response.ok) {
      throw new ApiError(response.status, typeof value?.detail === "string" ? value.detail : "request_failed");
    }
    return value;
  }

  async session() {
    const value = await this.request("v1/session");
    this.csrf = value?.csrf_token || null;
    return value;
  }

  async evidence(sha256, { signal } = {}) {
    if (!/^[0-9a-f]{64}$/.test(sha256)) throw new ApiError(422, "invalid_evidence_reference");
    const response = await fetch(this.url(`v1/evidence/${sha256}/content`), {
      credentials: "same-origin", cache: "no-store", redirect: "error", signal,
    });
    if (!response.ok) throw new ApiError(response.status, "evidence_unavailable");
    const media = response.headers.get("content-type")?.split(";")[0];
    if (!["image/jpeg", "image/png", "image/webp", "application/pdf"].includes(media)) {
      throw new ApiError(415, "unsupported_media_type");
    }
    return { blob: await response.blob(), media };
  }
}

const errors = {
  authentication_required: "登入已逾時，請重新登入。尚未送出的修改仍保留在這個畫面。",
  csrf_required: "登入狀態已變更，請重新登入後再試。",
  csrf_invalid: "登入狀態已變更，請重新登入後再試。",
  revision_conflict: "這張收據已在其他地方更新。已停止儲存；請保留目前內容並重新載入最新版本。",
  idempotency_key_content_conflict: "這次操作的內容與上次不同，已停止重送。請重新讀取目前帳本狀態。",
  invalid_candidate_request: "收據內容有欄位不符合格式。請檢查日期、整數金額、品項與折扣歸屬。",
  invalid_review_request: "核對內容有欄位不符合格式，請檢查品項與支出歸屬。",
  invalid_server_response: "伺服器回覆無法讀取，請稍後重試。沒有自動重送任何修改。",
  database_unavailable: "帳本暫時無法讀取。請稍後重試。",
  evidence_integrity_failure: "原檔完整性核對未通過，已停止讀取這份原檔。",
  evidence_unavailable: "原檔暫時無法開啟，請稍後重試。",
  draft_not_found: "找不到這張待核對收據，請重新整理清單。",
  reconciliation_incomplete: "還有待確認的金額或歸屬，請先完成核對。",
  already_confirmed: "這張收據已入帳，請到帳本查看。",
  ledger_voided: "這筆帳本已作廢，不能修改商品或價格核對。原紀錄仍可回查。",
  invalid_product_reference: "商品對應無效，請重新選擇已建立的商品。",
  invalid_price_allocation: "價格分配不符合範圍或合計，請核對每個適用列的分配金額。",
  price_amount_out_of_range: "商品價格超出支援範圍，請核對原始金額與分配。",
  summary_amount_out_of_range: "此期間的合計超出支援範圍，請縮小日期範圍。沒有截斷或顯示部分合計。",
  invalid_date_range: "日期篩選無效，請確認起迄日期。",
  product_not_found: "找不到這項商品，請重新整理商品目錄。",
};

export function errorMessage(error) {
  if (error?.name === "UnsafeIntegerError") return error.message;
  if (error instanceof ApiError) {
    if (errors[error.code]) return errors[error.code];
    if (error.status === 401) return errors.authentication_required;
    if (error.status === 403) return errors.csrf_invalid;
    if (error.status === 404) return "這項功能或資料目前無法使用。請重新整理，或稍後再試。";
    if (error.status === 409) return "資料狀態已變更，操作已停止。請重新讀取後核對。";
    if (error.status === 422) return "送出的欄位不符合格式。請檢查內容後再試。";
    if (error.status === 413) return "檔案或內容超過可接受大小，請縮小後再試。";
    if (error.status === 429) return "操作過於頻繁，請稍後再試。";
    return "帳本暫時無法完成操作。請稍後重試。";
  }
  if (error instanceof TypeError) return "目前無法連線到帳本。請確認網路後重試；沒有自動重送任何修改。";
  return error?.message || "操作未完成，請稍後重試。";
}
