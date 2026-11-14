# Multi-receipt and storage release implementation plan

> **For agentic workers:** Use executing-plans for integration and dispatching-parallel-agents for the independent Android storage task. Preserve previous evidence and unrelated worktrees.

**Goal:** Ship complete multi-receipt recognition with scoped notes, manageable photo storage, and the existing in-app update path.

**Architecture:** Keep immutable originals and deterministic accounting. Add authenticated storage visibility and configurable server capacity. Treat completed phone copies as recoverable cache only after the exact central bytes are verified; never evict unsent or uncertain work. Preserve exact provider journals and use deduplicated backup snapshots instead of deleting historical originals.

**Tech Stack:** Existing Kotlin/Compose, Python/FastAPI/SQLite, content-addressed blobs, systemd release on the existing HP host.

**Spec:** `docs/MULTI_RECEIPT_AGENT.md`; latest user authorization to finish overnight, merge, push and deploy. Storage implementation choices below are engineering decisions, not new user requirements.

## Global constraints

- No original/ledger deletion, paid fallback, new provider, weight download, shared Broker policy changes, or unrelated service changes.
- Existing original worktrees and private evidence stay local; explicit staging only.
- Completed private photo evaluation is reused: left advance, right self, every printed row preserved, no ledger writes. Actual receipt data stays outside Git.
- No new provider calls needed for release verification.
- New clients opt into `receipt_groups_v1` in immutable intake capabilities. Missing capability keeps legacy behavior even when the server feature is enabled. Existing submitted request bytes do not change.

## Review focus

- Unknown upload or provider outcomes retain their original request/key and all local photos.
- A failed/hash-mismatched download cannot authorize cache eviction or replace a valid local copy.
- Two batches referencing the same photo cannot evict an unsent batch's source.
- A schema-6 deployment cannot silently roll back to a schema-5 binary.
- Old App clients remain usable while the new APK is available but not installed.

## Task 1: Complete backend storage and compatibility

Files: `store.py`, `api.py`, `cli.py`, `intake_models.py`, `intake_store.py`, `recognition.py`, focused backend tests.

- [x] Add authenticated `GET /v1/storage`: schema `pixelreceipt-storage-1`, `evidence_bytes`, `evidence_count`, `evidence_limit_bytes`, `database_bytes`, `filesystem_available_bytes`, `warning` (`ok`/`near_limit`/`full`/`low_disk`). No paths or private receipt content.
- [x] Configurable server evidence limit, default 1 GiB for compatibility; production explicitly 10 GiB, 512 MiB disk reserve. Existing hashes can still be reused at the limit.
- [x] Losslessly deduplicate persisted transport payload media against already immutable blobs, or retain exact journals when uncertain; do not purge originals or invent result certainty. Select the smallest reversible implementation supported by tests.
- [x] Add `capabilities: ["receipt_groups_v1"]` optional field; preserve legacy idempotency serialization for omitted values; test old/new clients and in-flight jobs.
- [x] Test quota boundary, authenticated usage/download, payload recovery, old retries and group opt-in.

## Task 2: Android storage and compatibility

Files: `ImageStore.kt`, mobile API/repository/ViewModel/settings/help plus focused tests. Isolated existing `/tmp/receipt-multi-mobile` worktree; preserve its multi-receipt patch.

- [x] Add `receipt_groups_v1` only to newly frozen submissions.
- [x] Show local photo usage and central storage with concise plain-language explanations in Settings.
- [x] Add bounded authenticated original-image recovery via existing `/v1/evidence/{sha256}/content`, verify hash/size/MIME, publish atomically; no redirects or token in URL.
- [x] Allow safe reclaim of completed central copies, excluding unsent/unknown/pending mutations/shared protected hashes. Verify exact remote bytes before first eviction; on-demand recovery in review/detail. Clear only app-owned photo cache, never gallery, notes, state or ledger.
- [x] Tests for corruption, offline recovery, shared references and storage failure; render narrow and large-font Settings.
- [x] Run required Android unit tests/lint/build once on integrated final source (same-byte build reuse; final scoped fixes tested).

## Task 3: Backups and release

Files: `deploy/receipt_backup.py`, backup tests, deployment docs, release notes/version metadata. Host-specific gate/scripts/evidence remain private.

- [x] Support verified prior-snapshot blob reuse by hardlink, never link to live originals; retain standalone restore validation and private permissions. Keep existing backups, no retroactive pruning.
- [x] Document capacity/retention guidance; bounded backup policy checks report pressure instead of deleting originals or old snapshots without a defined retention set.
- [ ] Prepare 0.8.0/code18 (verify predecessor) APK with existing signing identity; source/archive/metadata hashes fixed.
- [ ] Fresh scoped review, meaningful checks, explicit sensitive-file staging audit; publish branch/PR, merge main, push version tag.
- [ ] HP read-only preflight; unique reviewed operation; backup and additive migration; source/config/APK switch; verify protected services, authenticated download SHA, data identity and evidence inventory. Stop/reconcile unknown outcomes without replay.
- [ ] Separate Git, production, APK publication, phone installation and human acceptance in delivery.

## Execution record

- Starting state: `feat/multi-receipt-agent` at `aa30fef`; multi-receipt immutable candidate already passed backend 514 and Android 580 tests/lint/build. New storage source requires new affected checks.
- Ruling: use verified backup deduplication and visibility now; do not add a timer that retroactively deletes historical snapshots. This preserves the existing immutable/no-GC backup contract.

- Backend storage review found and fixed exact-limit payload reconstruction; the boundary now covers one and multiple media parts. WAL disappearance and externally live-linked backup inputs are covered. New transport journals use verified blob references; old journals and evidence bytes are unchanged.

- Final backend: 525 tests passed; Ruff clean. Backup/packaging/CLI suite: 35 passed, one explicit legacy integration opt-in skipped. Android full suite: 593 passed; subsequent timeout and accounting-completion corrections receive targeted regression, UI render, lint and build verification. Cache reclaim requires every related receipt to be confirmed in the ledger; reference, voided, deleted, incomplete and uncertain work remain protected.
