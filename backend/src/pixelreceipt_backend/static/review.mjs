import { integerInput, nullableText } from "./values.mjs";

const PURPOSES = ["self", "gift", "advance"];

// Normalize only observed values. Never fill unknown quantities or money with zero.
// Incomplete split decisions remain local and are explicitly returned to the caller.
export function buildReview(editor, revision) {
  const value = structuredClone(editor);
  const candidate = value.candidate;
  const unsavedLineDecisions = [];
  const unsavedAdjustmentDecisions = [];
  const warnings = [];
  candidate.merchant = nullableText(candidate.merchant ?? "");
  candidate.receipt_date = nullableText(candidate.receipt_date ?? "");
  candidate.printed_total_yuan = integerInput(String(candidate.printed_total_yuan ?? ""), "收據總額");
  candidate.items.forEach((item, index) => {
    const label = `品項 ${index + 1}`;
    for (const key of ["raw_name", "brand", "specification"]) item[key] = nullableText(item[key] ?? "");
    item.quantity = integerInput(String(item.quantity ?? ""), `${label}數量`, { minimum: 1, maximum: 2147483647 });
    for (const key of ["printed_unit_price_yuan", "printed_line_total_yuan"]) {
      item[key] = integerInput(String(item[key] ?? ""), `${label}金額`);
    }
    if (item.content_quantity) {
      if (!item.content_quantity.value_decimal && !item.content_quantity.unit) item.content_quantity = null;
      else if (!/^(0|[1-9][0-9]{0,8})(\.[0-9]{1,6})?$/.test(item.content_quantity.value_decimal) || !["g", "kg", "ml", "l", "piece"].includes(item.content_quantity.unit)) {
        throw new Error(`${label}包裝含量需同時填入有效數值與單位。未知請兩欄都留空。`);
      }
    }
  });
  candidate.adjustments.forEach((adjustment, index) => {
    adjustment.raw_label = nullableText(adjustment.raw_label ?? "");
    adjustment.amount_yuan = integerInput(String(adjustment.amount_yuan ?? ""), `加減項 ${index + 1}金額`);
    if (adjustment.scope !== "items") adjustment.item_positions = [];
    else if (!Array.isArray(adjustment.item_positions)) {
      const positions = adjustment.item_positions.split(/[,，]/).map((text) => text.trim());
      if (!positions.length || positions.some((text) => text === "")) throw new Error(`加減項 ${index + 1}請填指定品項編號。`);
      adjustment.item_positions = positions.map((text) => integerInput(text, "品項編號", { minimum: 1, maximum: candidate.items.length }) - 1);
    }
  });
  const triple = (source, label, maximum = Number.MAX_SAFE_INTEGER) => {
    if (!source) return null;
    const result = {};
    let complete = true;
    for (const purpose of PURPOSES) {
      result[purpose] = integerInput(String(source[purpose] ?? ""), label, { maximum });
      if (result[purpose] === null) complete = false;
    }
    return complete ? result : null;
  };
  value.line_decisions = value.line_decisions.flatMap((decision) => {
    const index = decision.item_position;
    if (decision.method === "whole_line") {
      if (!decision.whole_purpose) return [];
      return [{ item_position: index, method: "whole_line", whole_purpose: decision.whole_purpose, quantities: null, amounts_yuan: null }];
    }
    const quantities = triple(decision.quantities, `品項 ${index + 1}分配數量`, 2147483647);
    const amounts = decision.method === "explicit_amounts" ? triple(decision.amounts_yuan, `品項 ${index + 1}分配金額`) : null;
    if (!quantities || (decision.method === "explicit_amounts" && !amounts)) {
      unsavedLineDecisions.push(structuredClone(decision));
      warnings.push(`品項 ${index + 1}的分配尚未填完整，只保留在此頁，未送出。`);
      return [];
    }
    if (amounts && PURPOSES.some((purpose) => quantities[purpose] === 0 && amounts[purpose] > 0)) {
      throw new Error(`品項 ${index + 1}不能把金額分給數量為 0 的用途。請對照原圖修正。`);
    }
    return [{ item_position: index, method: decision.method, whole_purpose: null, quantities, amounts_yuan: amounts }];
  });
  value.adjustment_decisions = value.adjustment_decisions.flatMap((decision) => {
    const index = decision.adjustment_position;
    // An included discount is evidence only, never a second allocation.
    if (candidate.adjustments[index]?.amount_effect === "included_in_line_totals" || !decision.method) return [];
    const amounts = decision.method === "explicit_amounts" ? triple(decision.amounts_yuan, `加減項 ${index + 1}分配金額`) : null;
    if (decision.method === "explicit_amounts" && !amounts) {
      unsavedAdjustmentDecisions.push(structuredClone(decision));
      warnings.push(`加減項 ${index + 1}的分配尚未填完整，只保留在此頁，未送出。`);
      return [];
    }
    return [{ adjustment_position: index, method: decision.method, amounts_yuan: amounts }];
  });
  value.review_note = nullableText(value.review_note ?? "");
  return {
    body: { expected_revision: revision, schema: "pixelreceipt-central-review-2", ...value },
    unsavedLineDecisions, unsavedAdjustmentDecisions, warnings,
  };
}

export function clearItemReview(editor, itemPosition) {
  editor.line_decisions[itemPosition] = { item_position: itemPosition, method: "whole_line", whole_purpose: null, quantities: null, amounts_yuan: null };
  // Receipt-wide adjustments may depend on any item; conservatively recheck all.
  editor.adjustment_decisions = editor.candidate.adjustments.map((_, index) => ({ adjustment_position: index, method: "", amounts_yuan: null }));
  editor.assembly_status = "unknown";
  editor.tolerance_acknowledgement = "none";
}

// This shortcut requires an explicit click and only fills untouched decisions.
// Existing gifts, advances and even partial splits are never overwritten.
export function fillUnspecifiedSelf(editor, { userInitiated = false } = {}) {
  if (!userInitiated) throw new Error("用途捷徑需要使用者明確操作。");
  let changed = 0;
  editor.line_decisions.forEach((decision) => {
    if (decision.method !== "whole_line" || decision.whole_purpose || decision.quantities != null || decision.amounts_yuan != null) return;
    decision.whole_purpose = "self";
    changed += 1;
  });
  return changed;
}

export function allocationSummary(editor) {
  const counts = { self: 0, gift: 0, advance: 0, split: 0, unspecified: 0 };
  let partial = false;
  const completeTriple = (value) => value && PURPOSES.every((purpose) => value[purpose] !== null && value[purpose] !== undefined && String(value[purpose]).trim() !== "");
  for (const decision of editor.line_decisions) {
    if (decision.method === "whole_line") {
      if (PURPOSES.includes(decision.whole_purpose)) counts[decision.whole_purpose] += 1;
      else counts.unspecified += 1;
    } else {
      counts.split += 1;
      if (!completeTriple(decision.quantities) || (decision.method === "explicit_amounts" && !completeTriple(decision.amounts_yuan))) partial = true;
    }
  }
  const labels = [["self", "自己用"], ["gift", "贈送"], ["advance", "代墊"], ["split", "分開分配"], ["unspecified", "未指定"]];
  const text = !editor.candidate.items.length ? "尚無品項" :
    counts.self === editor.candidate.items.length ? `全部 ${counts.self} 個品項自己用` :
      labels.filter(([key]) => counts[key]).map(([key, label]) => `${label} ${counts[key]} 項`).join(" · ");
  let adjustmentUnspecified = 0;
  let adjustmentPartial = 0;
  editor.candidate.adjustments.forEach((adjustment, index) => {
    if (adjustment.amount_effect === "included_in_line_totals") return;
    const decision = editor.adjustment_decisions.find((entry) => entry.adjustment_position === index);
    if (!decision?.method) adjustmentUnspecified += 1;
    else if (decision.method === "explicit_amounts" && !completeTriple(decision.amounts_yuan)) adjustmentPartial += 1;
  });
  return { counts, partial, text, adjustmentUnspecified, adjustmentPartial };
}
