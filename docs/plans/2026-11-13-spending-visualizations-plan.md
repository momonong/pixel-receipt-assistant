# PixelReceipt native spending charts

Status: implemented and verified; part of the unpublished 0.7.2 candidate.

## Goal and scope

Answer “Where did my personal spending go, and how did it change?” in the actual
native `MobileInsightScreen` selected by `MobileContent`. Preserve existing filters,
13 analysis dimensions, matching ledger entries, metadata editing, update cleanup,
the `PixelReceipt` name, and every stored receipt/photo/ledger record.

The old Spending screen has a chart but is no longer the active route. The current
multi-dimensional screen renders only numeric progress cards. This is the cause of
the missing visualizations, rather than missing financial data.

## Design decisions

- Composition: a category donut with exact amounts and percentages in its touchable
  legend. At most five slices; always expose uncategorized spending, group small
  remaining categories explicitly, and allow the full breakdown to be expanded.
- Trend: one straight line on a calendar scale, exact point values and an accessible
  previous/next selection plus “view these entries.” One point is a point; missing
  dates are disclosed and never assigned a fabricated date or amount.
- Comparison: compact horizontal rankings for meals, merchants and cities. Keep the
  full dimension selector and drill into the same report scope.
- Normal phones stack cards; wide foldables pair composition and trend only when
  both fit. Large font sizes stack and wrap. Native Compose Canvas and semantics;
  no new dependency, webview or external browser.
- Use the existing teal brand and Chinese system typography. Quiet white panels,
  tabular money, neutral controls; color encodes data. Native screenshots, rather
  than the web-only inspector, are the acceptance evidence for this Android screen.

Design skill direction: `achromatic-ledger`, seed 1409012592, palette/type kept.
Its face is the existing receipt brand's teal on the primary spending line. Category
identity uses limited muted chart hues, with labels/amounts as equivalent evidence.
The signature interaction is moving from a receipt-derived category/date directly
to the real matching ledger rows, with advance purchases explicitly separate.

## Data inventory and boundaries

1. TWD whole-yuan integers from the existing deterministic active-ledger projection.
2. Personal spending = self + gift; advance purchases are a separate summary.
3. Voided entries and unconfirmed drafts stay excluded by the existing API.
4. Category/meal/merchant/city groups have exact reconciled totals and opaque keys.
5. Real accounts may have only 1–3 entries; do not invent a dense history.
6. Unknown categories, meal assignments and locations remain explicit and editable.
7. Signed unallocated adjustments are valid: use signed bars instead of a donut.
8. Percentages/coordinates are display ratios, never new ledger amounts.
9. Dates are actual report buckets; a query keeps its other filters during drilldown.
10. Synthetic screenshots come from deterministic backend projection fixtures;
    they are not the user's finances, forecasts, budgets or savings claims.

## Implementation and verification

1. Preserve current WIP hashes and prior immutable 0.7.2 APK; work on a focused branch.
2. Reproduce absent native chart with a failing Compose assertion.
3. Add bounded chart preparation/geometry and native donut, line and ranking controls.
4. Verify total/negative/zero/unknown/tiny share/overflow, sparse calendar placement,
   and category/date drilldown retaining combined query filters.
5. Capture real Compose at 320/390/600/840dp and 150% font; exercise interactions,
   sparse/empty data and signed adjustments. Inspect and correct actual pixels.
6. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` using existing offline
   tools and isolated output paths; request a fresh bounded code review.
7. Preserve a new immutable signed candidate and evidence. No merge, push, publish,
   deployment or phone installation without the current concrete release approval.

## Public references consulted

- [IBM Carbon simple charts](https://www.carbondesignsystem.com/building-blocks/data-visualization/simple-charts):
  line for time, horizontal bars for values, donut for composition.
- [Monarch Reports](https://help.monarch.com/hc/en-us/articles/21846787088916-Using-Reports):
  category shares, ranked breakdowns and chart-to-transaction drilldown.

No private receipt, credential or financial data was sent in these searches.

## Verification result

- Missing chart reproduced by a real Compose assertion, then resolved on the active route.
- 540 Android tests, no failures/errors/skips; `lintDebug` reports no issues;
  `assembleDebug` succeeds using existing offline tools.
- Native captures at 320dp/150% font, 390dp, 600dp and 840dp cover composition,
  trend, comparisons, sparse/zero/signed/undated data and actual query callbacks.
- Independent review identified an odd final ranking row using a different physical
  width on foldables. Its regression first reproduced 356px versus 764px; the final
  layout keeps the same bar width and the test passes.
- Daily/monthly/yearly bucket metadata is retained from the existing response;
  the monthly label and complete-month drilldown have a native regression.
- The 42 existing APK endpoint tests are reused after all 83 backend files in
  their source verification were confirmed byte-for-byte unchanged.
- Screenshots use synthetic calculated burdens, including a legacy-undated fixture
  following the existing backend projection test. No real phone, ledger, provider,
  service, Git publication or deployment operation was performed for this feature.
