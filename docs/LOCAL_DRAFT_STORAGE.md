# Local incomplete draft persistence

Partial implementation for [Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26), extending the existing [draft editing model](DRAFT_HISTORY.md). Live issue and comments were refreshed on 2026-10-18; no comments or overlapping persistence PR were present. This is a local JVM persistence adapter, not a shared Draft contract, BuildSnapshot conversion, database/framework decision, or Android lifecycle qualification.

## Local format and write ordering

One dedicated app-private directory stores one draft's latest content and editing metadata. The private envelope contains `local_format_version: 1`, a nonnegative local storage revision, content and metadata. Content serializes the existing model's nullable objective/context/primary model and all optional-tier activation, selection and setting values. Null remains unanswered; empty text and opaque incomplete references remain entered text. No catalog ID, price, lock, fact or canonical verdict is manufactured. Optional-tier defaults are those of the existing model, not a persistence migration.

A save requires the exact previously loaded storage token, or null for a genuinely missing draft. Storage tokens bind the directory, revision and content hash. A non-reentrant process guard and OS file lock cover the read/check/write transaction across cooperating store instances. Stale, foreign or missing tokens cannot replace an existing head. BUSY is retryable; it is not a successful save. Concurrent callers must retain their own pending edits after a conflict.

The underlying `VerifiedArtifactCache` stages bounded bytes, forces content and pointer data, atomically activates the pointer, then forces directory metadata. It has no unsafe fallback when atomic rename or directory sync is unsupported. Acknowledgment comes only after this sequence and outer transaction cleanup. Failures after activation was attempted, including outer lock/channel cleanup failure, return `Uncertain`; callers must reopen before retrying. Earlier successfully stored content-addressed bytes are retained and may be read by a previously retained hash. Hashes establish integrity, not authenticity or rights.

The store and cache directories are private implementation details: direct cache writes or other writers bypassing the outer transaction lock are unsupported. No hostile writer access is assumed inside app-private storage. The store does not perform network, encryption, account separation, backup exclusion or scheduling. Those belong to the eventual Android storage integration.

## Bounds, recovery and migration

Envelope input is capped at 1 MiB and uses `BoundedJsonReader`'s depth, token, string, key and numeric limits. Writes pass the same reader and local format decoder before activation, so oversized or unsupported editing values cannot become an acknowledged unreadable record. Errors carry only typed codes, never user input or provider exception text.

Only local format 1 is supported. Unknown format versions, unknown fields, malformed/truncated input and invalid field types remain intact and block ordinary saves. The store never silently converts, overwrites, drops unsupported fields or resets a failed read to an empty draft. File/hash corruption is similarly explicit. Recovery requires a separate, deliberate export/migration or repair workflow; there is no destructive reset API. Unactivated staging left after interruption is ignored by reads and cleaned by the existing cache on a later valid save. This is replay/fault-injection evidence, not proof of real Android process death or physical power-loss behavior.

No historical format exists to migrate yet. A future local version needs an explicit reviewed migration that preserves original bytes and entered values. The storage envelope does not enter `contracts/v1` or change canonical schema versions.

## Scope of retained history

This adapter persists **latest draft content and metadata**, not the `DraftHistory` revision graph. It does not serialize `RevisionChange`, `restoredFrom`, parent hashes or a navigable history index. Prior bytes remain readable only when the caller has retained their hash; after restart this adapter does not discover an undo timeline. Reconstructing `DraftHistory` from loaded content starts a new in-memory sequence. Its numbering and tokens must never substitute for the separately loaded storage token. Durable undo/restore lineage and full #122 acceptance require a separate extension. Total retained storage is unbounded; no production retention or quota policy is implied.

## Criterion coverage and remaining input

| Exact #26 criterion or check | This slice | Remaining gap |
| --- | --- | --- |
| “Allow empty and partially selected editing without fabricated fields; explicit transition to valid BuildSnapshot with versioned migration rules.” | Existing editing model plus bounded local version 1 round trip | BuildSnapshot conversion and reviewed migration rules remain incomplete |
| “Empty/partial drafts persist without fabricated catalog IDs, facts or zero substitutes.” | Empty, partial and populated inputs reopen unchanged; raw null/empty/settings values retained | Android storage/lifecycle/device and central receiving acceptance |
| “Snapshot creation checks semantic requirements beyond minimum array counts; later edits preserve the original snapshot.” | No canonical snapshot is created; saved local content is immutable and old hashes remain readable | Semantic cardinality, reference integrity, canonical pins and revision identity in the accepted conversion contract |
| “Round-trip empty, partial and complete drafts through persistence/conversion.” | Local empty/partial/populated editing-content persistence tested | Populated local input is not declared a complete canonical build; conversion unimplemented |
| “Test missing context/primary model, dangling references and old-draft migration without losing entered values.” | Missing fields and opaque unresolved references retained; unknown versions and malformed bytes preserved | Reference eligibility and old-version conversion require an accepted migration; no guessed migration or canonical validity claim |

[Research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40) and [research #42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42) remain the conversion and saved-semantics authorities. Engineering discretion can implement this isolated local persistence; it cannot fabricate accepted shared cardinality/identity/migration semantics. Canonical receiving review remains `docs/delivery/android/work-packages/03.02.md` in `pickyour.ai`, including central contract owners and affected Android/web/iOS consumers. This partial evidence does not close #26 or #122.

Run the required checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). New tests cover empty/partial/populated reopen, retained earlier copies, stale/foreign tokens, concurrent writers, lock contention, unknown versions/fields, malformed/truncated bytes, hash corruption, oversized values, failures before and after activation, and uncertain outer cleanup.

Local evidence on 2026-10-18: `:core:check :core:assemble --offline` passed **68 JVM tests**, including nine storage tests, and built the core JAR using the pinned toolchain. `scripts/check_contracts.py` verified all 43 frozen files and both manifests and passed 21 reference tests. `git diff --check` passed. No emulator, physical-device or Android process-death test ran for this slice.
