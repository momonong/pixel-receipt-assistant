import assert from "node:assert/strict";
import test from "node:test";
import { adjustmentPositions, buildPriceReview, buildProduct, exactPrice, revokedMappings } from "../prices.mjs";

const first = "10000000-0000-4000-8000-000000000001";
const second = "10000000-0000-4000-8000-000000000002";
const mapping = (product_id) => ({ product_id, unit_match: "confirmed_same_product_and_unit" });
function candidate() {
  return {
    items: [{ raw_name: "合成茶飲", quantity: 2, printed_line_total_yuan: 120 }, { raw_name: "合成肥皂", quantity: 1, printed_line_total_yuan: 80 }],
    adjustments: [{ raw_label: "整單折扣", amount_yuan: 20, direction: "subtract", scope: "receipt", item_positions: [], amount_effect: "separate_from_line_totals" }],
  };
}

test("exact fractional prices are displayed as server text, with no decimal approximation", () => {
  assert.equal(exactPrice({ numerator_yuan: 189, denominator: 2, display_yuan: "189 / 2" }), "NT$ 189 / 2");
  assert.equal(exactPrice({ numerator_yuan: 108, denominator: 2, display_yuan: "54" }), "NT$ 54");
  assert.equal(exactPrice(null), "待確認");
  assert.equal(exactPrice({ display_yuan: "94.5" }), "價格格式待確認");
  assert.equal(exactPrice({ display_yuan: "<img src=x>" }), "價格格式待確認");
});

test("product identity requires explicit specification and purchase unit, preserves unknown brand", () => {
  const source = { display_name: "合成無糖茶", brand: "", specification: "500mL／瓶", purchase_unit: "瓶", content_quantity: { value_decimal: "500", unit: "ml" } };
  assert.deepEqual(buildProduct(source), { display_name: "合成無糖茶", brand: null, specification: "500mL／瓶", purchase_unit: "瓶", content_quantity: { value_decimal: "500", unit: "ml" } });
  assert.throws(() => buildProduct({ ...source, specification: "" }), /明確規格/);
  assert.throws(() => buildProduct({ ...source, purchase_unit: " " }), /購買單位/);
  assert.throws(() => buildProduct({ ...source, content_quantity: { value_decimal: "5e2", unit: "ml" } }), /十進位/);
  assert.throws(() => buildProduct({ ...source, content_quantity: { value_decimal: "500", unit: "" } }), /同時填入/);
});

test("an initial price review uses independent revision zero and manually confirmed product IDs", () => {
  const edit = { mappings: [mapping(first), mapping(second)], adjustments: [{ method: "proportional_line_amount", amounts: {} }] };
  assert.deepEqual(buildPriceReview(edit, candidate(), 0), {
    expected_revision: 0,
    item_mappings: [{ item_position: 0, product_id: first, unit_match: "confirmed_same_product_and_unit" }, { item_position: 1, product_id: second, unit_match: "confirmed_same_product_and_unit" }],
    adjustment_decisions: [{ adjustment_position: 0, method: "proportional_line_amount", amounts_by_item: null }],
  });
});

test("choosing a product without confirming same product and unit cannot save silently", () => {
  const edit = { mappings: [{ product_id: first, unit_match: null }, null], adjustments: [] };
  assert.throws(() => buildPriceReview(edit, candidate(), 0), /仍需明確確認/);
  edit.mappings[0] = { product_id: null, unit_match: null };
  assert.deepEqual(buildPriceReview(edit, candidate(), 0).item_mappings, []);
});

test("explicit price discounts include every applicable line, including zero", () => {
  const edit = { mappings: [mapping(first), mapping(second)], adjustments: [{ method: "explicit_amounts", amounts: { 0: "20", 1: "0" } }] };
  const body = buildPriceReview(edit, candidate(), 3);
  assert.deepEqual(body.adjustment_decisions[0].amounts_by_item, [{ item_position: 0, amount_yuan: 20 }, { item_position: 1, amount_yuan: 0 }]);
  edit.adjustments[0].amounts[1] = "";
  assert.throws(() => buildPriceReview(edit, candidate(), 3), /明確填 0/);
  edit.adjustments[0].amounts[1] = "9007199254740992";
  assert.throws(() => buildPriceReview(edit, candidate(), 3), /安全處理/);
});

test("included adjustments and single-line scope never get a second allocation", () => {
  const receipt = candidate();
  const edit = { mappings: [mapping(first), mapping(second)], adjustments: [{ method: "explicit_amounts", amounts: { 0: "20", 1: "0" } }] };
  receipt.adjustments[0].amount_effect = "included_in_line_totals";
  assert.deepEqual(buildPriceReview(edit, receipt, 2).adjustment_decisions, []);
  receipt.adjustments[0].amount_effect = "separate_from_line_totals";
  receipt.adjustments[0].scope = "items"; receipt.adjustments[0].item_positions = [1];
  assert.deepEqual(adjustmentPositions(receipt, receipt.adjustments[0]), [1]);
  assert.deepEqual(buildPriceReview(edit, receipt, 2).adjustment_decisions, []);
});

test("unknown shared allocation stays omitted; it never reuses purpose allocation", () => {
  const edit = { mappings: [mapping(first), mapping(second)], adjustments: [{ method: "", amounts: {} }], line_decisions: [{ whole_purpose: "gift" }] };
  const result = buildPriceReview(edit, candidate(), 1);
  assert.deepEqual(result.adjustment_decisions, []);
  assert.equal("line_decisions" in result, false);
  assert.equal("candidate" in result, false);
});

test("mapping removal or replacement is detectable independently from ledger revision", () => {
  const previous = [{ item_position: 0, product_id: first }, { item_position: 1, product_id: second }];
  assert.deepEqual(revokedMappings(previous, [{ item_position: 0, product_id: first }]), [previous[1]]);
  assert.deepEqual(revokedMappings(previous, [{ item_position: 0, product_id: second }, { item_position: 1, product_id: second }]), [previous[0]]);
  assert.deepEqual(revokedMappings(previous, previous), []);
});

test("normalizing prices never changes original line totals or multiplies quantity", () => {
  const receipt = candidate();
  const original = structuredClone(receipt);
  buildPriceReview({ mappings: [mapping(first), null], adjustments: [{ method: "proportional_line_amount", amounts: {} }] }, receipt, 0);
  assert.deepEqual(receipt, original);
  assert.equal(receipt.items[0].printed_line_total_yuan, 120);
});
