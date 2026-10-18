# Private immutable snapshot archive

This extends [Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26)
with durable creation and read-only recovery of the complete
[reviewed local projection](DRAFT_SNAPSHOT_PROJECTION.md). The implementation uses
the existing Kotlin core, canonical draft store and managed-library guard. It
introduces no shared schema, service, export format, evaluator or new draft engine.

The source baseline is signed main
`343667ebf0066be6b386e4df722364637a4badde`, delivered through
[#360](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/360).
Its [fresh main receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/360#issuecomment-6251841287)
retains its own execution identity; it is not an execution of this archive.
[The continuing issue record](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6251844016)
keeps this work in the existing lane. The intended website acceptance path
`docs/delivery/android/work-packages/03.02.md` is unchanged.

## Local decision and declared retention

Research [#40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40)
and [#42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42) retain
shared contract and saved-build authority. The in-session research specialist
reviewed their accepted identity, reproducibility and retention boundaries for
this implementation. The adopted local choice is an independently retained,
private complete bundle of the already reviewed projection, identified by the
exact Build ID and its mathematical positive revision. It does not settle shared
product deletion, expiry, forks, publication, access or migration rules.

Editing, restoring or deleting the source draft does not change or delete an
indexed bundle. There is no automatic expiry or eviction. The archive currently
supplies creation, inspection and read-only recovery APIs. It has no public
creation/removal UI, snapshot removal operation, import/export flow or background
synchronization. Product integration must make this independent retention explicit,
provide deliberate snapshot removal, and handle pending form inputs and review
before presenting a complete user-facing save flow. It must not silently archive
unsaved form text or acknowledge a different durable baseline.

| Retained member | Exact scope |
| --- | --- |
| Build | Original frozen-v1 candidate bytes, explicit caller identity, typed settings/selections/links/objectives and `draft` lifecycle |
| Source | Exact **current** local envelope bytes, including all represented typed history, local metadata, format and legacy-source reference |
| Catalog, ruleset, dependency graph | Complete original bytes and each acknowledged exact ID, revision/version and SHA-256 |
| Pricebook, when supplied | Complete original bytes and acknowledged exact pin; absence remains absence |
| Private manifest | Bundle/profile versions, mathematical Build identity, member order/length/hash/descriptors, current source format/revision and the complete retained-only checklist |

The manifest is a private local storage format, not a CD-7 shared sidecar or an
export envelope. It contains no filesystem path, live draft token or credentials
invented by this implementation. Source values may nevertheless be private and
were deliberately omitted from the frozen Build. Neither the full bundle nor its
source bytes may be treated as a redacted share payload. The implementation never
logs the payload, field values or IDs in result/exception descriptions.

A `legacy_source_hash` inside the current envelope is retained **as historical
provenance only**. Its older raw serialization is not an additional bundle member.
The represented historical values survive; retrieval or verification of every
historical serialization is not promised. A later original-format restore,
migration replay or every-history-byte feature must obtain those exact referenced
bytes or report unavailable/unsupported. Reopening this archive does not fetch,
reconstruct or claim to have verified that external predecessor.

## Authority and API sequence

`LocalSnapshotArchive` requires a dedicated, existing, durably provisioned private
directory. Its root must be disjoint from the source draft: it cannot be that
draft directory, a descendant, or an ancestor. The caller must durably provision
its ancestor directory entries. Construction synchronizes newly created archive
children and fails if that cannot be completed. The managed-library adapter also
requires the archive to be disjoint from its **entire library root**, including
nonreserved sibling names: another draft's legitimate recursive deletion must
never reach an acknowledged snapshot. It checks that policy while library
liveness is locked, before source/archive publication. Standalone adapters retain
the source-disjoint rule; callers must keep their dedicated archive outside other
independently managed draft/deletion namespaces as well. Construction/loading of
a caller-provided directory is separate from publication admission. These
synchronous APIs belong on a worker thread.

1. Load the archive. `SnapshotArchiveLoad.Available` provides a verified current
   index and an opaque `SnapshotArchiveVersion`. A genuinely empty new namespace
   is initialized with a durable empty index and a separate durable namespace
   seal. The seal's random nonce identifies only this local storage namespace;
   it does not allocate a Build ID, owner identity or shared revision.
2. Prepare the candidate from a captured durable source using
   `prepareSnapshotCandidate`. All existing explicit caller-input, semantic,
   exact-pin and retained-only checks still apply. The archive cannot turn a
   withheld incomplete draft into a valid candidate.
3. Review the exact durable source, projected facts, identity, artifact
   descriptors and complete retained-only checklist. Obtain the opaque
   acknowledgment for this exact review only after that review. A path-set
   equality check is an API safeguard, not evidence that a UI or person has
   actually displayed/read the disclosure.
4. Call `archiveSnapshotCandidate(review, acknowledgment, archive, expectedArchive)`
   through `LocalDraftStore` or its managed `PreviewDraftPersistence` adapter.
   Library liveness, source path/hash/revision and exact acknowledgment identity
   remain locked across archive publication. The archive independently checks
   its current path, namespace nonce, generation and index hash against the
   captured version. No implicit current-head retry occurs.
5. Consume the explicit result. Only `Archived` acknowledges completed persistence
   after the archive, source and managed-library transactions return. Reopening
   a result must still verify its exact bundle before reading it as stored data.

`DraftSnapshotCandidate` remains an independent in-memory result of the original
conversion API. The durable API adds its own transaction rather than changing
what `Created` means. Archive reopening uses the same extracted pure projection
admission on the decoded source content; it never constructs a `DraftVersion` or
silently makes a retained snapshot the current editable draft.

| Result | Caller behavior |
| --- | --- |
| `Archived` | The complete bundle is indexed; `alreadyPresent` distinguishes an exact-byte retry from a new index entry |
| `SourceConflict` / `ReviewAcknowledgmentMismatch` | Preserve pending input; obtain the appropriate current durable-source review and complete acknowledgment |
| `ArchiveConflict` | Reopen the archive explicitly; a stale or foreign version is never replaced automatically |
| `IdentityConflict` | The same Build ID/mathematical revision already reserves different complete bytes; preserve both caller input and existing data; choose a new explicit identity/revision only through the caller's workflow |
| `NotPublished` | No index activation was attempted; the previous index is unchanged. The supplied exact bundle hash may identify a complete unreferenced object; inspect/reconcile before retrying |
| `Uncertain` | Activation or a later transaction completion may have happened. Reopen the current index and verify the exact identity **and bundle hash** before reporting success or attempting another write |
| Typed source/archive unavailable result | Preserve data and the specific busy, cancelled, I/O, corruption, unsupported-version or limit result; do not invent a replacement |

## Exact identity, bytes and reopening

The private identity key stores a canonical decimal representation of the positive
mathematical revision. For example, frozen Build revisions `1`, `1.0` and `1e0`
refer to one identity. Their raw Build bytes and complete bundle hashes remain
distinct. Equivalent JSON whitespace, field order or numeric spellings are never
rewritten into the retained raw members. A different complete bundle for an
already reserved key returns a conflict, even if only retained-only source data
or a numeric spelling changed. Exact complete-byte retry is idempotent only with
current source/review authority and the current archive version.

Before encoding or writing, the archive checks that the retained current source
bytes match the source hash authorized by the review and that each original
artifact matches its acknowledged hash. It retains those exact pins; computing a
new hash from different bytes cannot silently replace an acknowledged descriptor.

The bounded binary format uses a fixed magic/version, a bounded JSON manifest
and fixed-order, length-declared members. It contains no file paths, compression
or unpacking instructions. Reopen checks the framing, exact member set/order,
length and SHA-256, manifest identities and complete retained-only inventory.
It applies the strict reader and frozen schemas, decodes the exact source
history, repeats the established projection admission/reference checks, and
compares the resulting Build values with the retained Build. Mathematical
numeric equality is used where JSON numbers denote the same value; decimal
**strings** keep their exact spelling. Catalog/pricebook revision limits remain
the declared local profile rather than changes to frozen schema validity.

`load` verifies index metadata; an entry is not a claim that its referenced bundle
has already been checked in that call. `read(key)` verifies the current indexed
bundle. `readAt(capturedVersion, key)` verifies the exact captured immutable
historical index and requested bundle, without activating either. A historical
version remains unusable as a current write token after any head change.
`inspectRetained(exactHash)` verifies a retained complete object even when the
current index is unavailable. It provides no index/publication or write authority;
the object can be unreferenced after an interrupted attempt. Missing indexed data
is corruption; a key absent from a valid index is a distinct `Missing` result.

Unsupported bundle, projection, source or shared schema versions remain unavailable
without migration or rewriting. Corrupt current index metadata never causes a
fallback to an older index or a newly guessed Build. Earlier captured index
receipts and exact retained-object receipts remain read-only recovery inputs.

## Atomic publication, recovery and budgets

All cooperating writers use a non-reentrant canonical-root JVM guard followed by
a nonblocking OS file lock. Lock order is managed library, source draft, archive,
then one cache at a time. Contention reports busy; it never waits indefinitely or
opens a competing lock channel that could release another POSIX process lock.
The archive reuses `VerifiedArtifactCache` for two bounded stores:

- Immutable complete bundle blobs are fully written, hashed, forced and published
  before index activation. Their cache's incidental active pointer is not the
  archive's publication authority.
- Immutable index versions retain all previously indexed entries. Their active
  pointer is the publication boundary. The new pointer and directory metadata
  must finish the existing cache's force/atomic-rename sequence before success.

Every retained complete bundle is verified before append, including complete
objects left before index activation. Those objects consume quota and reserve
their mathematical identity. An identical retry may reuse them; different bytes
cannot take their identity. Missing/corrupt indexed bundles or conflicting retained
identities block append. Earlier healthy receipts can still be inspected.

A durable empty baseline precedes the namespace seal. Before sealing, only the
exact never-published empty initialization layout can resume. Once sealed, a
missing current index is corruption even if only the empty baseline file remains.
A missing seal beside retained data also fails closed. Recreating a genuinely
empty namespace changes the nonce and rejects old head tokens. A failure after
writing the seal but before its force is completed must finish that durability
boundary on reopen before allowing writes.

| Local profile budget | Limit and consequence |
| --- | --- |
| Indexed snapshots | At most 32; no implicit replacement or eviction |
| Complete bundle | At most 8 MiB, with each original JSON member at most 1 MiB |
| Manifest and each index | Existing bounded JSON reader/encoder, at most 1 MiB, depth 32 and 100,000 tokens |
| Retained complete bundle files | At most 64 files and 64 MiB, including unreferenced completed attempts |
| Retained index files | At most 64 files and 4 MiB, including the empty baseline and failed-publication objects |

These are explicit private admission/retention limits, not a filesystem free-space
guarantee or a shared contract amendment. Byte limits can be reached before entry
counts. Filesystem metadata and fixed staging files are outside the retained-payload
budgets. Limit failures preserve existing data; index-limit failures can leave a
complete unreferenced bundle for exact inspection. There is no unsafe rename or
fsync fallback, automatic cleanup of complete objects, silent quota expansion,
source edit cascade or implied deletion policy.

The guarantees rely on the same cooperating-writer and filesystem assumptions as
[the canonical cache](../core/src/main/kotlin/ai/pickyour/core/cache/VerifiedArtifactCache.kt). These local JVM fault injections do
not qualify real Android power-loss behavior, device filesystems, hardware-backed
storage, installed lifecycle, encryption or production readiness. Hashes establish
byte identity; they do not authenticate a publisher or authorize source use.

## Verification and remaining receiving work

Use the pinned commands in [CORE_FOUNDATION.md](CORE_FOUNDATION.md),
[ANDROID_PREVIEW.md](ANDROID_PREVIEW.md) and [CI_BASELINE.md](CI_BASELINE.md).
Focused classes are `LocalSnapshotArchiveTest`, `SnapshotBundleIntegrityTest` and
`PreviewDraftArchiveTest`, alongside the unchanged projection/library suites.
The tests exercise the actual store and managed-library adapters with synthetic
frozen fixtures: complete bytes/history, optional absence, mathematical identity,
source/archive CAS, concurrent writers, all eight bundle/index write checkpoints,
initialization interruptions, transaction completion failures, exact unreferenced
retry, quotas, stale historical authority, corrupt/missing/unsupported inputs,
recomputed-hash reference tampering, actual managed deletion and legacy provenance.
No fixture is promoted to real model, pricing, publication or compatibility evidence.

On 2026-10-18, the author ran the complete focused proposal with the pinned JDK 21,
Gradle 8.11.1 and strictly verified offline dependencies: **48 actual JUnit
methods** passed with zero failures, errors or skips. This comprises 17 archive,
8 bundle-integrity, 5 managed-archive and 18 existing projection methods. The
eight write checkpoints are separate injected situations inside one method,
not eight extra JUnit cases. Exact XML, logs and source hashes are retained with
the local delivery evidence; this initial run preceded the immutable source
commit and is not a hosted or installed result.

During self-review, two added internal corruption controls first reproduced
incorrect `Archived` results for substituted current-source bytes and substituted
schema-valid catalog bytes with unchanged acknowledged hashes. The encoder now
requires agreement with the reviewed source hash and each acknowledged artifact
hash before any write. The same two controls then passed, and are included in the
48-method run. These controls exercise internal test seams of this new proposal;
they do not claim a defect in accepted main or a demonstrated public mutation
path. Earlier fixture setup and compilation mistakes are retained as failed
attempts, separately from those executed red/green regressions.

A subsequent independent receiving discussion identified a concrete managed
placement risk. Two additional controls ran against unchanged `606f5f29`
production and failed: an archive under a different live draft had already been
acknowledged and then removed by that other draft's legitimate deletion; a
nonreserved library sibling was also admitted. The outside-library archive and
standalone sibling positive paths remained intact. The bound-library predicate
now enforces the explicit placement policy above. The two unchanged controls
and all five focused archive/projection classes then passed together: 50 actual
methods, zero failures, errors or skips. The final composed source/gates retain
separate delivery evidence; the earlier 48-method and `606f5f29` observations are
not relabeled.

The complete current-tree core, frozen/reference, ledger, unsigned Android build,
Robolectric, lint and configured hosted gates remain required. Source review,
author executions and post-main verification retain their separate literal
commit/tree/run/artifact identities in the delivery receipt. This archive supplies
concrete durable identity/recovery evidence for #26; it does not close the issue
without reconciling all literal criteria, #21 receiving prerequisites, affected
consumer reviews and the intended acceptance record. Product creation/removal
flows and any original-serialization replay retain their explicit scope above.
