# Multi-receipt recognition

## Accepted scope

2026-11-15: the user approved a bounded recognition agent for several receipts in
one photograph, with original notes bound to each receipt, and a real test using
the supplied photograph. Original photos, drafts, ledger rows and notes must be
preserved. The later overnight instruction also authorizes finishing storage
management, merging main, pushing and deploying 0.8.0 to the existing HP service.
Changing shared Broker policy, new providers, paid fallback and downloading
embedding models remain outside this release.

Base: `aa30fef7f37a6391c6e371b1777d5e5740f1ca98` (0.7.3). Worktree:
`receipt-multi-agent`, branch `feat/multi-receipt-agent`. The original worktree's
20 pre-existing changes and all frozen release directories remain untouched.

## Design

The fixed workflow owns execution and money. A vision planning response identifies
receipt regions, groups continuation images, and binds verbatim note spans to a
receipt. The model may request bounded regional reading; it never posts a ledger
entry or invents amounts to reconcile a receipt. Original notes and image hashes
remain authoritative. Successful regional reads are persisted, not repeated.

Reuse the existing durable Broker queue adapter for every actual model request.
Represent regional extractions as internal child intakes linked to a root intake
by an additive table. Each request retains its own persisted key, payload and
result. Unknown dispatch is reconciled under the same key; it is never a repair
request. Child draft creation is atomic and idempotent. Root completion requires
all known receipt regions to have a result; incomplete coverage remains visible.

The root API adds `receipts`, an ordered array containing `receipt_id`, `label`,
`regions` (image_position and box in normalized 0..1000 coordinates), `intake_id`,
`status`, `error_code`, `draft_id`, `draft_status`, `ledger_id`, `ledger_status`,
and proposed `purpose`. Legacy singular draft fields remain compatible for old
records. Multi-receipt roots do not pretend that the first child is the whole
transaction. Child intakes are excluded from the top-level inbox query.

The Android batch detail shows each child and opens its existing native review
flow. Persist child review state independently; retain original batch/photo
ownership. Parent summaries count all child accounting states. No browser hop.

Model selection is capability-based through the existing Broker; configuration
may constrain authorized providers, but there is no hard-coded Gemini name in
the agent. Vision planning/extraction require image/document input. Text-only
reasoning cannot identify image regions. Preserve the existing legacy route
until deployment explicitly opts into the new workflow. Shared Broker project
API migration is independent of this feature.

Merchant embeddings are an evaluated follow-up. Region IDs/crops provide the
needed provenance now; no vector index, weights or merchant auto-fill in this
first stage.

## Tasks and evidence

- [x] Add bounded layout/region/note schemas, image derivation and persisted
  parent-child relations; test invalid regions, missing receipts, repeated
  completion, legacy migration and preservation of original rows.
- [x] Integrate planning, regional extraction and bounded repair with the
  existing queue journal; test unknown/no-replay, restart, partial failure,
  capability routing, and conflicting/unresolved note handling.
- [x] Add grouped native App review and status aggregation; test independent
  edits, completion/void/delete updates and old local-state compatibility.
- [x] Run backend suite and `./gradlew testDebugUnitTest lintDebug assembleDebug`.
- [x] Exercise the supplied photograph through the existing free Broker route
  into a private evaluation store. Both physical receipts, every printed row
  (including zero-price options), and different purposes are retained. Exact
  private evidence lives outside Git; no production ledger mutation.

## Review focus

- Similar or duplicate product names on different receipts cannot cross-bind.
- A missing child is not successful completion; a partial result is retained.
- Unknown network results never authorize another model dispatch.
- Historical pending jobs and old App state must remain readable unchanged.
- Child deletion or confirmation cannot mark the entire batch complete early.

## Execution and preservation boundaries

The new worker is opt-in: add `"receipt_agent": true` to the existing private
recognition configuration only during an authorized rollout. Default is `false`.
Only new requests with the immutable `capabilities: ["receipt_groups_v1"]`
opt-in use grouping; older App builds keep the existing single-receipt path.
Omitted capabilities serialize exactly as before for legacy idempotent retries.
Already submitted jobs retain their
stored payload and prompt version. Turning
the flag off does not discard an in-flight group or repeat a completed request.

This candidate adds SQLite schema 6: `receipt_groups`, `receipt_regions` and
`receipt_attempts`. Migration is additive; existing rows, photo blobs, credentials
and ledger identity are retained and checked by migration tests. The backup tool
accepts schema 6. Older binaries cannot read schema 6: rollback must follow the
existing database-preserving upgrade procedure, not simply start an older binary.

Bounds per batch:

- At most eight physical receipts, twenty total regions, four regions per receipt.
- One initial layout request and at most one correction of a completed invalid
  layout; no correction is allowed while dispatch/result status is unknown.
- One extraction per receipt, with at most one requested regional reread. At the
  maximum receipt count this permits at most eighteen generation requests.
- A valid one-photo/two-receipt plan normally needs three generation requests.
  Explain/admission checks and result polling do not generate model output.
- Each dispatch uses the existing persistent Broker request key and a single
  provider attempt. One failed child retains all successful siblings.

Only named `left/top/right/bottom` coordinates cross the layout-model boundary.
Decimal integer strings can be normalized without changing their values; the raw
response and number of conversions are retained. Fractions, booleans, exponents,
out-of-range/empty/nested regions and reversed left/right bindings are rejected.
Cropping uses EXIF-oriented originals before resizing and never rewrites originals.

## Notes and final review

Notes keep their original batch/image scope and verbatim source. A planner cannot
copy a note from another image, shorten a negative/partial instruction, or apply
one item-specific quote to multiple receipts. Only an explicit all-batch phrase
can authorize the same whole-receipt purpose across multiple receipts.

Once item extraction finishes, bindings are checked again against all receipts
sharing that note source. Until those rows are available, an item-specific note
stays unresolved. A repeated item name across receipts does not silently choose
one occurrence. Unresolved group notes remain visible in each review and do not
become blanket item instructions.

For an unambiguous whole-receipt statement such as left/advance and right/self,
the validated original-image binding resolves a crop reader's uncertainty about
spatial words. Conflicting purposes, other quotes and invalid item mappings are
still rejected by the existing deterministic reviewer. Both the original model
response and `receipt_note_binding` basis are retained. Missing printed unit-price
column evidence leaves `printed_unit_price_yuan` null; quantity one is not evidence
of a printed unit price. Monetary calculation and final confirmation remain in
the existing deterministic domain and human review flow.

The App shows separate receipt cards in the original batch and uses the existing
native review screen for each. Each draft has independent edits, delete/void
state, and ledger status. The parent is fully accounted for only after all child
receipts are confirmed; recognition success alone does not mean ledger completion.
Group deletion directs the user to the appropriate per-receipt action and retains
the original photo/notes. Existing single-receipt local records remain compatible.

## Broker resources and merchant similarity

The agent sends capability requirements through the existing adapter rather than
hard-coding a provider. Deployment configuration may pin an authorized provider
and model. The inspected receipt-specific deployment currently has one available
free vision/document route; this does not imply access to every shared Broker
provider. The legacy PDF adapter also needs its configured model identity for
token preflight. Removing that pin is not a shared-pool migration.

The reviewed Broker implementation filters eligibility/capabilities first, then
prefers the earliest known quota-reset or credit-expiry opportunity before the
configured priority. A daily reset interval is not by itself proof of best route.
Keep policy/admission in Broker; text-only planning cannot locate image regions.
This feature does not add a separate text-model call for notes that the validated
binding and deterministic reviewer can resolve.

A later project-API migration can expose approved shared routes. Its credentials,
trust profile and image/context/output limits need separate integration validation.
The verified local vision target's current limits do not fit these 1600-pixel,
4096-output-token extraction requests unchanged. Do not silently route to it.

For merchant suggestions, retain `(original hash, image position, region, crop
hash)` and index only merchant labels the user has confirmed. Compare cropped
receipt images plus printed text, return ranked candidates, and leave low-margin
matches unselected. Similar POS templates across different shops must be tested
as hard negatives; visual similarity alone is not a merchant identity.

Google documents [EmbeddingGemma 2](https://ai.google.dev/gemma/docs/embeddinggemma)
as a 740M multimodal embedding model with image input; EmbeddingGemma 1 is text
only. This is distinct from the text-generation [Gemma 4 family](https://ai.google.dev/gemma/docs/core/model_card_4).
The inspected Broker embedding API currently accepts text inputs, so image vectors
would require a verified local/adapter path. No weights, vector index, provider,
merchant autofill or paid fallback were installed in this stage.

## Verification and delivery

The 514-test backend suite passes and covers migration preservation, independent draft/accounting
state, scope conflicts, coordinate validation, bounded repair, unknown-result
reconciliation, deletion during processing and idempotent restart. Android has
580 passing unit/rendering tests and clean lint/build evidence; updated rendering
fixtures were additionally checked at 390 dp and 320 dp with 150% font scale.

The actual photograph required four generation requests: the initial invalid
layout, one corrected layout and two receipt reads. Saved live outputs were then
replayed through final scope/price postprocessing in a fresh private store, with
zero additional model calls. This separates live extraction evidence from final
postprocessing verification. All original evaluation state and the source photo
were preserved. Original photographs, identifying data and full model responses
are excluded from source-controlled fixtures/reports.

These counts describe the original isolated multi-receipt candidate. The 0.8.0
storage/release integration has its own verification evidence. Git publication,
production deployment, authenticated APK download and phone installation are
separate milestones; build evidence alone is not an OTA release or human acceptance.

## Progress

Design follows the user's accepted direction; details above are implementation
choices. No additional approval loop is needed for the authorized scope.
