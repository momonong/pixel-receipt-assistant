import { integerInput, isSafeIdentifier, nullableText } from "./values.mjs";

export function exactPrice(value) {
  // The server supplies an exact fraction string. No Number conversion or division.
  if (!value || typeof value.display_yuan !== "string") return "待確認";
  if (!/^(0|[1-9][0-9]*)( \/ [1-9][0-9]*)?$/.test(value.display_yuan)) return "價格格式待確認";
  return `NT$ ${value.display_yuan}`;
}

export function productLabel(product) {
  if (!product) return "尚未指定商品";
  return [product.display_name, product.brand, product.specification, product.purchase_unit ? `每${product.purchase_unit}` : null].filter(Boolean).join(" · ");
}

export function buildProduct(source) {
  const result = {};
  for (const field of ["display_name", "brand", "specification", "purchase_unit"]) result[field] = nullableText(source[field] ?? "");
  if (!result.display_name || !result.specification || !result.purchase_unit) throw new Error("請填入商品名稱、明確規格及一個購買單位的說明。未知品牌可留空。");
  const value = source.content_quantity?.value_decimal ?? "";
  const unit = source.content_quantity?.unit ?? "";
  result.content_quantity = null;
  if (value || unit) {
    if (!/^(0|[1-9][0-9]{0,8})(\.[0-9]{1,6})?$/.test(value) || !["g", "kg", "ml", "l", "piece"].includes(unit)) throw new Error("包裝含量需同時填入有效十進位數值與單位；未知請兩欄都留空。");
    result.content_quantity = { value_decimal: value, unit };
  }
  return result;
}

export function adjustmentPositions(candidate, adjustment) {
  if (adjustment.scope === "receipt") return candidate.items.map((_, position) => position);
  return [...adjustment.item_positions];
}

export function buildPriceReview(editor, candidate, revision) {
  const item_mappings = editor.mappings.flatMap((mapping, item_position) => {
    if (!mapping?.product_id) return [];
    if (mapping.unit_match !== "confirmed_same_product_and_unit") throw new Error(`品項 ${item_position + 1}已選商品，仍需明確確認同規格與購買單位；若還不能確認，請先解除對應。內容尚未送出。`);
    if (!isSafeIdentifier(mapping.product_id)) throw new Error("商品識別無效，請重新指定商品。");
    return [{ item_position, product_id: mapping.product_id, unit_match: "confirmed_same_product_and_unit" }];
  });
  const adjustment_decisions = [];
  candidate.adjustments.forEach((adjustment, adjustment_position) => {
    const positions = adjustmentPositions(candidate, adjustment);
    if (adjustment.amount_effect === "included_in_line_totals" || positions.length <= 1) return;
    const decision = editor.adjustments[adjustment_position];
    if (!decision?.method) return;
    if (decision.method === "proportional_line_amount") {
      adjustment_decisions.push({ adjustment_position, method: decision.method, amounts_by_item: null });
      return;
    }
    if (decision.method !== "explicit_amounts") throw new Error("價格分配方式無效，請重新核對。");
    const amounts_by_item = positions.map((item_position) => {
      const amount = integerInput(String(decision.amounts?.[item_position] ?? ""), `加減項 ${adjustment_position + 1}，品項 ${item_position + 1}金額`);
      if (amount === null) throw new Error(`加減項 ${adjustment_position + 1}的每個適用列都需填寫；沒有分配金額也請明確填 0。未填完整的內容仍留在此頁，尚未儲存。`);
      return { item_position, amount_yuan: amount };
    });
    adjustment_decisions.push({ adjustment_position, method: decision.method, amounts_by_item });
  });
  return { expected_revision: revision, item_mappings, adjustment_decisions };
}

export function revokedMappings(previous, next) {
  return previous.filter((mapping) => !next.some((entry) => entry.item_position === mapping.item_position && entry.product_id === mapping.product_id));
}

export function priceReason(code) {
  return {
    product_mapping_unconfirmed: "商品身份與購買單位尚未人工確認",
    shared_adjustment_unallocated: "共用折扣／加收尚未分配到商品列",
    negative_paid_line_total: "商品分配後金額為負，需重新核對",
    price_amount_out_of_range: "價格超出支援範圍，需重新核對",
  }[code] || "價格依據尚待確認";
}
