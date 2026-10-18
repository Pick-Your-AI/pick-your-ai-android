# Local incomplete draft persistence

Partial implementation for [Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26), extending the existing [draft editing model](DRAFT_HISTORY.md). The original adapter from PR #142 remains the canonical implementation; this extension adds local history without introducing a second storage engine. This is a local JVM persistence adapter, not a shared Draft contract, BuildSnapshot conversion, database/framework decision, or Android lifecycle qualification.

## Local format and write ordering

One existing, durably provisioned dedicated app-private directory stores one draft and its retained local history. New drafts use private `local_format_version: 4`, containing the head storage revision number, optional legacy source hash and an ordered history of revision number, change code, restore target, content, metadata and optional typed-selection change disclosure. A real explicit selection role change upgrades its envelope to format 5 as described below. Content serializes the model's nullable objective/context/primary model, workload inputs, the typed selection/link aggregate and all opaque optional-tier activation, selection and setting values. Null remains unanswered; empty text in existing free-text fields and opaque incomplete references remain entered text. No catalog ID, price, lock, fact or canonical verdict is manufactured. Optional-tier defaults are those of the existing model, not a persistence migration.

A save requires the exact previously loaded storage token, or null for a genuinely missing draft. Storage tokens bind the directory, revision and content hash. A non-reentrant process guard and OS file lock cover the read/check/write transaction across cooperating store instances. Stale, foreign or missing tokens cannot replace an existing head. Omitting metadata preserves the current map; supplying an explicit empty map clears it. BUSY is retryable; it is not a successful save. Concurrent callers must retain their own pending edits after a conflict.

The caller must provision the store root and make its ancestor directory entries durable before construction; a missing root is rejected without creating it. Construction creates the `revisions` child and forces the store root directory before constructing the cache. This parent sync runs on every construction, including retries where a failed earlier sync left the child present. Directory creation or sync failures abort construction, so no usable store can acknowledge a save. Concurrent cooperating constructors may repeat this sync safely. This ordering is tested with injected initialization failures; it is not physical power-loss evidence.

The cache also requires an existing durable root instead of implicitly creating one. The [Linux `fsync` manual](https://man7.org/linux/man-pages/man2/fsync.2.html) explains that syncing file data does not necessarily persist its containing directory entry; the containing directory must also be synced. [JDK 21 `FileChannel.force`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) documents local-device and operating-system limitations. References checked 2026-10-18. Directory-channel support and actual filesystem/device behavior still require Android qualification; these JVM tests do not establish universal crash or power-loss durability.

The underlying `VerifiedArtifactCache` stages bounded bytes, forces content and pointer data, atomically activates the pointer, then forces directory metadata. It has no unsafe fallback when atomic rename or directory sync is unsupported. Acknowledgment comes only after this sequence and outer transaction cleanup. Failures after activation was attempted, including outer lock/channel cleanup failure, return `Uncertain`; callers must reopen before retrying. Earlier successfully stored content-addressed bytes are retained and may be read by a previously retained hash. Hashes establish integrity, not authenticity or rights.

The store and cache directories are private implementation details: direct cache writes or other writers bypassing the outer transaction lock are unsupported. No hostile writer access is assumed inside app-private storage. The store does not perform network, encryption, account separation, backup exclusion or scheduling. Those belong to the eventual Android storage integration. The existing constructor may create the store/cache directories, but does not force every newly created ancestor entry. Callers must durably provision the private directory tree for any crash-durability guarantee; the cache flush sequence alone does not establish that parent-directory durability. Physical power-loss and Android filesystem behavior remain unverified.

## Bounds, recovery and migration

History is capped at 128 retained entries and envelope input is capped at 1 MiB and uses `BoundedJsonReader`'s depth, token, string, key and numeric limits. Writes pass the same reader and local format decoder before activation, so oversized or unsupported editing values cannot become an acknowledged unreadable record. Errors carry only typed codes, never user input or provider exception text.

Local formats 1, 2, 3, 4 and 5 are supported. Unknown format versions, unknown fields, malformed/truncated input and invalid field types remain intact and block ordinary saves. The store never silently converts, overwrites, drops unsupported fields or resets a failed read to an empty draft. File/hash corruption is similarly explicit. Recovery requires a separate, deliberate export/migration or repair workflow; there is no destructive reset API. Unactivated staging left after interruption is ignored by reads and cleaned by the existing cache on a later valid save. Tests include real separate-JVM abrupt exit before/after pointer activation and subsequent reopen, in addition to fault injection. They do not prove Android process-death behavior or physical power-loss safety.

Reading any supported older format never rewrites it. The first successful format-1 save or restore migrates it atomically to format 4. Its exact current content and metadata become a `LEGACY_ANCHOR` at the original storage revision number N, with the original artifact hash in `legacy_source_hash`; the new edit/restore is N+1. The original artifact remains readable through `revision(hash)`. No entries 0 through N-1 are fabricated. `earlierHistoryUnavailable` is true when N is positive; clients must show that limitation rather than infer a complete legacy timeline. Even N=0 remains explicitly a legacy anchor, not a reconstructed initial action. Failed migration preserves the old active envelope or returns `Uncertain` if activation may have happened. Counter overflow rejects migration without wrapping or resetting.

Formats 2, 3, 4 and 5 validate contiguous retained numbering, head identity, change kinds,
optional legacy anchor, and restore content/metadata consistency. Format 3 adds a
required `workload` object to every retained content entry, with exactly nine nullable
fields. Reading formats 1/2 supplies an all-unanswered workload in memory only;
unknown fields in those older formats still fail closed. Missing fields, a null
workload aggregate, unknown fields or invalid values in formats 3/4/5 are corrupt rather
than silently defaulted. Format 4 adds explicit `components` and per-revision
`selection_change` fields; see [typed local selections](TYPED_LOCAL_SELECTIONS.md).
Formats 1/2/3 receive empty typed components in memory without inferring them from
legacy fields. Future versions remain unchanged and block writes.
The storage envelope does not enter `contracts/v1` or change canonical schema versions.

Format 5 adds the private `CONFIGURE_ROLE` selection action while keeping format-4
`CONFIGURE` settings-only. The decoder requires an actual role change and permits
only that role and explicitly supplied settings to differ; identity, catalog,
activation, notes, extensions, metadata and links must match the action's existing
guards. A role-only native edit supplies the complete original settings unchanged.
Reading, rejecting or no-oping a format-4 draft does not upgrade it. The first real
role edit appends one revision through the same CAS transaction and retains every
old history entry and original artifact. Later saves and restores retain format 5,
even when restoring a pre-role revision. Size/history limits never prune entries
to make migration fit; unsupported format 6 still blocks writes and preserves bytes.
See [explicit selection roles](LOCAL_SELECTION_ROLES.md) for the input and recovery
scope. No frozen contract is changed by this private version boundary.

An unchanged format-2 or format-3 save remains a byte-preserving no-op. Its first changed save
or explicit restore atomically writes format 4, preserving every prior entry's
number, change, restore target, content and metadata, plus any original
`legacy_source_hash`. The new entry uses the next revision number; no history is
reset, synthesized or pruned. All format-2 entries gain explicit unanswered workload fields; format-3 workload
values remain exact. Every old entry gains empty typed components and a null
selection-change disclosure in the new envelope. The original predecessor artifact remains readable by its
original hash, and its loaded token becomes stale after activation. The same
pre-activation failure, uncertainty, CAS, overflow and bounded-history rules apply.
The additional explicit objects can push a valid near-limit predecessor history over
the 1 MiB budget, even when a short append in the old format would fit. Such a
migration returns `LIMIT_EXCEEDED` and preserves the exact old bytes, token and
history; unchanged saves still no-op. No history is dropped to make an upgrade fit.

## Incomplete workload inputs (#83, partial)

`DraftContent.workload` is one immutable `DraftWorkload` with every field initially
null. There is no second absent-aggregate state. Fields are user-entered assumptions,
not catalog facts or measured capabilities. This private model mirrors existing
frozen `Build.objectives` field bounds without making its required fields mandatory
in an incomplete draft:

- `region`: nonempty string when answered; no invented region or trimming
- `currency`: three uppercase ASCII letters when answered
- `monthly_budget_amount`: `ContractDecimal`, preserving the exact nonnegative decimal
  string (including trailing zeroes, at most 12 fractional digits). Budget may be
  entered while currency remains unanswered; this is not a complete money value
- `requests_per_day`, `avg_input_tokens`, `avg_output_tokens`: nonnegative `BigInteger`
- `max_concurrency`, `max_latency_ms`: positive `BigInteger`
- `data_classification`: public, internal, confidential or restricted when answered

Integer counts have no 32/64-bit narrowing. Existing durable parser budgets still
apply, including 128 numeric characters and 16,384 string code units, the 1 MiB
envelope and 128 history entries. A value beyond a local budget cannot become an
acknowledged unreadable revision. Null remains distinct from entered zero, and
`0.00` remains distinct from `0.0`; neither is an unknown-price substitute.

Workload is domain content, never metadata. Restore therefore restores the selected
workload while retaining current lock/version and guest-form metadata. Tier and
context edits retain all workload fields; revision comparison exposes each changed
field and preserves decimal spelling. Diagnostic `toString` values remain redacted.
No workload editor, completion/readiness verdict, placement selection, quote,
compatibility evaluation, BuildSnapshot conversion or Android lifecycle evidence
is added by this storage slice. Those and receiving review remain open for #83.

## Scope of retained history

A new format-4 draft starts with `INITIAL` at zero. A changed save appends `EDIT`.
An unchanged save with the exact current token returns the existing durable revision
without writing another artifact or consuming bounded history. This applies to formats 2/3/4/5, including at the history limit. Metadata is part of the equality
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
| “Allow empty and partially selected editing without fabricated fields; explicit transition to valid BuildSnapshot with versioned migration rules.” | Existing editing model, bounded v1/v2/v3/v4/v5 reads and explicit local migrations | BuildSnapshot conversion and reviewed migration rules remain incomplete |
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

Local workload-storage evidence on 2026-10-18: the original checkpoint on base
`a4e7df28` passed **233 JVM tests** in the offline `:core:check :core:assemble` run,
with zero failures, errors or skipped tests. Integration onto findings-inspection
main `0a870ef8` then passed **237 JVM tests** with the same zero-failure result.
After adding the near-limit format-2 migration and oversized first-write regressions,
the final run on that base passed **239 JVM tests**, with zero failures, errors or
skipped tests. These cases verify `LIMIT_EXCEEDED` rather than a generic IO error
when Jackson wraps the bounded writer's explicit size signal; other IO errors keep
their existing outcomes. The migration case also verifies byte-for-byte preservation
of the old envelope and immutable artifact, token/history preservation, and unchanged-save no-ops.
The initial 15 added tests cover workload model bounds, exact decimals and large
integers, fail-closed decoding, unchanged legacy reads/saves, format-2 migration
and activation failures, restore/CAS, context/tier preservation and comparison.
All 43 frozen files and both manifests verified; 21 reference tests, four reference
report fixtures, four integrity regressions, six delivery-ledger regressions and
the wrapper checksum passed. No Android SDK build, emulator, physical-device or
power-loss qualification ran for this storage-only slice.
