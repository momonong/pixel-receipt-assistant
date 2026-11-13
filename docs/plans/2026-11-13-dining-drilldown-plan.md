# Dining merchant drilldown

Extend the unpublished 0.7.2 charts candidate, preserving the previous signed APK
and source verification. Use the existing native multi-dimensional analysis API;
no model call, backend write, financial migration or real-ledger mutation.

Status: implemented and independently reviewed. Full Android gate: 546 tests,
zero failures/errors/skips, no lint issues, APK assembled. Native captures cover
390dp, 320dp at 150% font, and 840dp unfolded. Publication/device acceptance pending.

The user wants to know which food shops they spend at, each shop's share, and where
the largest amounts go. Implement a clear total → food → merchant → matching ledger
path rather than asking them to assemble filters manually.

- A food entry on the overview opens category=food, retaining date and other filters.
- Within food, show merchant shares instead of a redundant 100%-food category ring.
- Default the comparison to merchants after entering a category. Keep meal, city,
  original merchant, brand and branch analysis available.
- Show the highest cumulative merchant, exact amount, share of currently filtered
  food personal spending, matched receipt count and existing per-receipt average.
- Personal spending remains self + gift, advances separate. Every denominator names
  the current filtered food scope; totals/ranks do not imply price inflation or an
  expensive shop. Counts mean ledger receipts, not inferred visits.
- Missing merchant identity remains “店家待補,” including in a long tail; never invent
  a brand or merge ambiguous store names. At most five ring slices, expandable rest.
- Generic category charts, calendar trend, signed/zero states, current APK cleanup,
  PixelReceipt casing and private data stay intact. No release operations in scope.

Verification: an actual deterministic category-filtered backend projection fixture;
native chart, percentage denominator and query callback checks; compact large text
and foldable screenshots; full Android test/lint/build gate; bounded independent
review; a new immutable signed candidate. Publication and phone acceptance separate.
