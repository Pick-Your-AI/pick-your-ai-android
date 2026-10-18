# Local incomplete draft persistence

Partial implementation for [Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26), extending the existing [draft editing model](DRAFT_HISTORY.md). The original adapter from PR #142 remains the canonical implementation; this extension adds local history without introducing a second storage engine. This is a local JVM persistence adapter, not a shared Draft contract, BuildSnapshot conversion, database/framework decision, or Android lifecycle qualification.

## Local format and write ordering

One existing, durably provisioned dedicated app-private directory stores one draft and its retained local history. New writes use private `local_format_version: 2`, containing the head storage revision number, optional legacy source hash and an ordered history of revision number, change code, restore target, content and metadata. Content serializes the existing model's nullable objective/context/primary model and all optional-tier activation, selection and setting values. Null remains unanswered; empty text and opaque incomplete references remain entered text. No catalog ID, price, lock, fact or canonical verdict is manufactured. Optional-tier defaults are those of the existing model, not a persistence migration.

A save requires the exact previously loaded storage token, or null for a genuinely missing draft. Storage tokens bind the directory, revision and content hash. A non-reentrant process guard and OS file lock cover the read/check/write transaction across cooperating store instances. Stale, foreign or missing tokens cannot replace an existing head. Omitting metadata preserves the current map; supplying an explicit empty map clears it. BUSY is retryable; it is not a successful save. Concurrent callers must retain their own pending edits after a conflict.

The caller must provision the store root and make its ancestor directory entries durable before construction; a missing root is rejected without creating it. Construction creates the `revisions` child and forces the store root directory before constructing the cache. This parent sync runs on every construction, including retries where a failed earlier sync left the child present. Directory creation or sync failures abort construction, so no usable store can acknowledge a save. Concurrent cooperating constructors may repeat this sync safely. This ordering is tested with injected initialization failures; it is not physical power-loss evidence.

The cache also requires an existing durable root instead of implicitly creating one. The [Linux `fsync` manual](https://man7.org/linux/man-pages/man2/fsync.2.html) explains that syncing file data does not necessarily persist its containing directory entry; the containing directory must also be synced. [JDK 21 `FileChannel.force`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) documents local-device and operating-system limitations. References checked 2026-10-18. Directory-channel support and actual filesystem/device behavior still require Android qualification; these JVM tests do not establish universal crash or power-loss durability.

The underlying `VerifiedArtifactCache` stages bounded bytes, forces content and pointer data, atomically activates the pointer, then forces directory metadata. It has no unsafe fallback when atomic rename or directory sync is unsupported. Acknowledgment comes only after this sequence and outer transaction cleanup. Failures after activation was attempted, including outer lock/channel cleanup failure, return `Uncertain`; callers must reopen before retrying. Earlier successfully stored content-addressed bytes are retained and may be read by a previously retained hash. Hashes establish integrity, not authenticity or rights.

The store and cache directories are private implementation details: direct cache writes or other writers bypassing the outer transaction lock are unsupported. No hostile writer access is assumed inside app-private storage. The store does not perform network, encryption, account separation, backup exclusion or scheduling. Those belong to the eventual Android storage integration. The existing constructor may create the store/cache directories, but does not force every newly created ancestor entry. Callers must durably provision the private directory tree for any crash-durability guarantee; the cache flush sequence alone does not establish that parent-directory durability. Physical power-loss and Android filesystem behavior remain unverified.

## Bounds, recovery and migration

History is capped at 128 retained entries and envelope input is capped at 1 MiB and uses `BoundedJsonReader`'s depth, token, string, key and numeric limits. Writes pass the same reader and local format decoder before activation, so oversized or unsupported editing values cannot become an acknowledged unreadable record. Errors carry only typed codes, never user input or provider exception text.

Local formats 1 and 2 are supported. Unknown format versions, unknown fields, malformed/truncated input and invalid field types remain intact and block ordinary saves. The store never silently converts, overwrites, drops unsupported fields or resets a failed read to an empty draft. File/hash corruption is similarly explicit. Recovery requires a separate, deliberate export/migration or repair workflow; there is no destructive reset API. Unactivated staging left after interruption is ignored by reads and cleaned by the existing cache on a later valid save. Tests include real separate-JVM abrupt exit before/after pointer activation and subsequent reopen, in addition to fault injection. They do not prove Android process-death behavior or physical power-loss safety.

Reading format 1 never rewrites it. The first successful save or restore migrates it atomically to format 2. Its exact current content and metadata become a `LEGACY_ANCHOR` at the original storage revision number N, with the original artifact hash in `legacy_source_hash`; the new edit/restore is N+1. The original artifact remains readable through `revision(hash)`. No entries 0 through N-1 are fabricated. `earlierHistoryUnavailable` is true when N is positive; clients must show that limitation rather than infer a complete legacy timeline. Even N=0 remains explicitly a legacy anchor, not a reconstructed initial action. Failed migration preserves the old active envelope or returns `Uncertain` if activation may have happened. Counter overflow rejects migration without wrapping or resetting.

Format 2 validates contiguous retained numbering, head identity, change kinds, optional legacy anchor, and restore content/metadata consistency. Unknown future versions remain unchanged. The storage envelope does not enter `contracts/v1` or change canonical schema versions.

## Scope of retained history

A new format-2 draft starts with `INITIAL` at zero. A changed save appends `EDIT`.
An unchanged save with the exact current token returns the existing durable revision
without writing another artifact or consuming bounded history. This applies after
format-2 migration, including at the history limit. Metadata is part of the equality
check: omitted metadata preserves the current map, while an explicit empty map is
a change when metadata exists. Stale, foreign and missing tokens still conflict
before equality is considered; matching content cannot acknowledge another writer's
revision. The first format-1 save still performs its documented migration even
when its content is unchanged.

`restore(expectedVersion, retainedNumber)` always appends `RESTORE` with the selected
content and current metadata, including when restoring the current content. The
source entry and all prior revisions remain unchanged. Only retained numbers can
be restored; unknown targets return `UnknownRevision`, while stale/foreign tokens
still conflict. Restore is serialized by the same outer transaction and cache
activation as save, not a parallel persistence path.

Loaded history and metadata are defensively immutable and survive reopen. These storage tokens and revision numbers remain separate from `DraftHistory`'s in-memory identity tokens. No UI adapter or automatic synchronization between those models is supplied.

At the history or envelope limit, writes fail without pruning acknowledged revisions. The store does not evict cached artifacts, including the original v1 source. Total content-addressed disk usage remains unbounded and no garbage collection or production quota policy is implied. Library create/list/rename/duplicate/delete orchestration and current-build selection remain outside this one-draft adapter.

## Criterion coverage and remaining input

| Exact #26 criterion or check | This slice | Remaining gap |
| --- | --- | --- |
| “Allow empty and partially selected editing without fabricated fields; explicit transition to valid BuildSnapshot with versioned migration rules.” | Existing editing model, bounded v1/v2 round trips and explicit local v1 migration | BuildSnapshot conversion and reviewed migration rules remain incomplete |
| “Empty/partial drafts persist without fabricated catalog IDs, facts or zero substitutes.” | Empty, partial and populated inputs reopen unchanged; raw null/empty/settings values retained | Android storage/lifecycle/device and central receiving acceptance |
| “Snapshot creation checks semantic requirements beyond minimum array counts; later edits preserve the original snapshot.” | No canonical snapshot is created; saved local content is immutable and old hashes remain readable | Semantic cardinality, reference integrity, canonical pins and revision identity in the accepted conversion contract |
| “Round-trip empty, partial and complete drafts through persistence/conversion.” | Local empty/partial/populated editing-content persistence tested | Populated local input is not declared a complete canonical build; conversion unimplemented |
| “Test missing context/primary model, dangling references and old-draft migration without losing entered values.” | Missing fields and opaque unresolved references retained; unknown versions and malformed bytes preserved | Local v1 migration now preserves its anchor and old bytes; canonical snapshot migration and reference eligibility remain separate |

[Research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40) and [research #42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42) remain the conversion and saved-semantics authorities. Engineering discretion can implement this isolated local persistence; it cannot fabricate accepted shared cardinality/identity/migration semantics. Canonical receiving review remains `docs/delivery/android/work-packages/03.02.md` in `pickyour.ai`, including central contract owners and affected Android/web/iOS consumers. This partial evidence does not close #26 or #122.

Additional partial #67/#122 evidence: complete retained revision/head activation, reopening history after separate-JVM exit, restore as a new head with current metadata, explicit unavailable legacy history, migration overflow, corrupt-history preservation and bounded rejection without eviction. Still required: Android storage/lifecycle adoption, UI saved/saving/failed states and pending-edit recovery, accepted retention/backup policy, device evidence and receiving review.

Run the required checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). Tests cover empty/partial/populated reopen, retained earlier copies, stale/foreign tokens, concurrent writers, lock contention, unknown versions/fields, malformed/truncated bytes, hash corruption, oversized values, failures before and after activation, and uncertain outer cleanup.

Original PR #142 evidence on 2026-10-18: `:core:check :core:assemble --offline` passed **68 JVM tests**, including nine storage tests, and built the core JAR using the pinned toolchain. `scripts/check_contracts.py` verified all 43 frozen files and both manifests and passed 21 reference tests. `git diff --check` passed. No emulator, physical-device or Android process-death test ran for this slice.

Local history-extension evidence on 2026-10-18: `:core:check :core:assemble --offline`
passed **94 JVM tests**, including nine existing persistence tests and nine new
history/migration tests, and built the core JAR. The new cases include preserved
v1 anchors and unavailable history, failure during migration, malformed legacy
records, immutable edit/restore/edit, bounded history, overflow, corrupt restore
lineage and actual separate-JVM abrupt exit/reopen. No Android/device or power-loss
qualification is inferred from those results.
