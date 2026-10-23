// Deliberately synthetic and never used as a silent fallback for real API data.
export const demoDrafts = [
  {
    id: "demo-market", revision: 1, status: "needs_review", created_at: "2026-10-20T03:00:00Z", updated_at: "2026-10-20T03:00:00Z",
    evidence: [{ sha256: "a".repeat(64), pages: [1] }],
    source: { kind: "imported", label: "合成示範資料", provider: null, model: null },
    candidate: {
      schema: "pixelreceipt-central-candidate-1", currency: "TWD", amount_unit: "TWD_YUAN",
      merchant: "日常生活超市", receipt_date: "2026-10-20", printed_total_yuan: 289,
      items: [
        { raw_name: "鮮乳 936ml", quantity: 1, printed_unit_price_yuan: 99, printed_line_total_yuan: 99, brand: "牧場日記", specification: "936ml", content_quantity: { value_decimal: "936", unit: "ml" } },
        { raw_name: "原味無加糖優格・家庭分享包 400g", quantity: 1, printed_unit_price_yuan: 149, printed_line_total_yuan: 149, brand: null, specification: "400g", content_quantity: { value_decimal: "400", unit: "g" } },
        { raw_name: "全麥吐司", quantity: 1, printed_unit_price_yuan: 59, printed_line_total_yuan: 59, brand: null, specification: null, content_quantity: null },
      ],
      adjustments: [{ raw_label: "會員折抵", direction: "subtract", amount_yuan: 18, amount_effect: "separate_from_line_totals", scope: "receipt", item_positions: [] }],
    },
  },
  {
    id: "demo-coffee", revision: 2, status: "needs_review", created_at: "2026-10-19T08:00:00Z", updated_at: "2026-10-19T08:10:00Z",
    evidence: [{ sha256: "b".repeat(64), pages: [1] }],
    source: { kind: "manual", label: "合成示範資料", provider: null, model: null },
    candidate: {
      schema: "pixelreceipt-central-candidate-1", currency: "TWD", amount_unit: "TWD_YUAN",
      merchant: "轉角咖啡", receipt_date: "2026-10-19", printed_total_yuan: 160,
      items: [{ raw_name: "冰拿鐵", quantity: null, printed_unit_price_yuan: null, printed_line_total_yuan: 160, brand: null, specification: null, content_quantity: null }], adjustments: [],
    },
  },
  {
    id: "demo-unknown", revision: 1, status: "needs_review", created_at: "2026-10-18T07:00:00Z", updated_at: "2026-10-18T07:00:00Z",
    evidence: [{ sha256: "c".repeat(64), pages: [1] }],
    source: { kind: "ai_candidate", label: "合成示範資料，並非真實辨識", provider: null, model: null },
    candidate: {
      schema: "pixelreceipt-central-candidate-1", currency: "TWD", amount_unit: "TWD_YUAN",
      merchant: null, receipt_date: null, printed_total_yuan: null,
      items: [{ raw_name: "品名未辨識完整，請對照原圖", quantity: null, printed_unit_price_yuan: null, printed_line_total_yuan: null, brand: null, specification: null, content_quantity: null }], adjustments: [],
    },
  },
];

export const demoOverview = {
  pending_count: 3, confirmed_count: 8, month_personal_expense_yuan: 2460,
  month_total_paid_yuan: 2980, month_advance_yuan: 360, month_gift_yuan: 160,
  month: "2026-10", currency: "TWD", amount_unit: "TWD_YUAN",
};

export const demoLedger = [
  { id: "demo-ledger-1", draft_id: "demo-previous-1", merchant: "日常生活超市", receipt_date: "2026-10-17", total_paid_yuan: 568, personal_expense_yuan: 408, gift_yuan: 160, advance_yuan: 0, confirmed_at: "2026-10-17T10:30:00Z", status: "confirmed" },
  { id: "demo-ledger-2", draft_id: "demo-previous-2", merchant: "晨光雜貨", receipt_date: "2026-10-16", total_paid_yuan: 720, personal_expense_yuan: 360, gift_yuan: 0, advance_yuan: 360, confirmed_at: "2026-10-16T10:30:00Z", status: "confirmed" },
  { id: "demo-ledger-3", draft_id: "demo-previous-3", merchant: "轉角咖啡", receipt_date: "2026-10-15", total_paid_yuan: 135, personal_expense_yuan: 135, gift_yuan: 0, advance_yuan: 0, confirmed_at: "2026-10-15T10:30:00Z", status: "confirmed" },
];

export const demoPrices = [
  { product_name: "牧場日記 鮮乳", specification: "936ml", comparable: true, observations: [{ merchant: "日常生活超市", receipt_date: "2026-10-17", paid_unit_price_yuan: 99 }, { merchant: "晨光雜貨", receipt_date: "2026-10-10", paid_unit_price_yuan: 105 }] },
  { product_name: "原味無加糖優格", specification: "400g", comparable: false, reason: "整單折扣尚未分配，實付單價待確認。", observations: [{ merchant: "日常生活超市", receipt_date: "2026-10-17", paid_unit_price_yuan: null }] },
];
