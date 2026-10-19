# Local draft to frozen Build candidate

Current issue-level reconciliation is in the proposed
[03.02 receiving record](delivery/android/work-packages/03.02.md), under
[ADR 0007](decisions/0007-android-26-acceptance-record.md). The slice coverage and
remaining-input statements below retain their original implementation scope and
dated evidence; the receiving record separately maps the complete current model
and its remaining delivery/review gates. This crosslink does not relabel an
earlier run or close another work package.

This is bounded implementation for
[Android #26](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26).
It supplies a callable local conversion boundary in the existing Kotlin core and
managed preview persistence adapter. It does not create a second revision store,
change the frozen contract, publish a Build, or provide a complete creation UI.

## Decision and intake

The 2026-10-18 intake read the complete live #26 and #21 issues, comments and open
Android PRs at main `a037d2b94ed55c0946cd04383b68a3f1dc25875a`. No active converter
or #26 assignee/comment existed. The issue's intended website acceptance record,
`Pick-Your-AI/pickyour.ai:docs/delivery/android/work-packages/03.02.md`, returned
404. This implementation note does not relocate it. The Android engineer in
workspace `e2303f1e7427` recorded bounded ownership in
[the issue discussion](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/26#issuecomment-6249440401).

The accepted [native decision](proposals/02-native-architecture.md) permits this
Kotlin/core and canonical-store implementation. The exact frozen import and its
receiving limits remain in [CONTRACT_ADOPTION.md](CONTRACT_ADOPTION.md).
Existing #21 conformance work, including the independently owned #287 parity
correction, retains its scope. Neither its green fixtures nor this converter
establish production approval of the baseline rules.

The current in-session research engineer inspected research #40/#42 and their
accepted artifacts. The existing migration experiments start with an already
valid Build and cannot map local free text. Frozen
[Build.objectives](../contracts/v1/schemas/build.schema.json) has no free-text
objective/context/model field and rejects arbitrary additional properties.
The engineering decision is therefore a **retained local draft plus a separately
identified frozen candidate**, constructed only from explicit typed facts.
Technical receiving review of the exact implementation remains required; the
research discussion does not invent another maintainer's acceptance or change
shared saved-build semantics.

## Input and output boundary

`LocalDraftStore.prepareSnapshotCandidate` reads the current durable record while
holding its existing transaction lock. It checks the captured directory identity,
revision number and artifact hash before preparation. The managed
`PreviewDraftPersistence` adapter also holds the existing library liveness guard.
Neither method writes, migrates, allocates an identity, changes the active draft,
or invokes a remote service. Both methods belong on a worker thread.

`DraftSnapshotInputs.profileVersion = 1` selects this local admission profile.
It is separate from the unchanged frozen `schema_version = "1.0.0"` and the local
storage envelope's version. Unsupported profiles are withheld without choosing a
newer profile. Required missing inputs remain missing.

| Input | Source and rule |
| --- | --- |
| Build ID, revision, name, owner reference | Explicit `DraftSnapshotIdentity` caller inputs; no local directory/counter/title or guest identity substitution |
| Created and updated time | Explicit caller `Instant` values, serialized as UTC instants; updated time cannot precede created time; no clock lookup |
| Objectives | Only entered `DraftWorkload` fields; region, currency and classification are required; unanswered optional fields are omitted; exact decimal strings and integers are preserved |
| Selections and links | Complete immutable `DraftSelections`, including disabled entries, original IDs, roles, typed settings, notes, extensions and optional link flags |
| Catalog, ruleset, dependency graph | Each requires caller-selected exact ID, revision/version, SHA-256 and original bytes; no lookup, newest/first fallback or mixed artifact assembly |
| Pricebook | The same exact input binding if supplied; absence omits its lock and establishes no zero price or complete coverage |
| Lifecycle | Always `draft` in this local profile; this is an output-state policy, not evaluation, deployment readiness or permission |

Every artifact receives the bounded strict JSON reader and its fixed frozen
schema. Exact hash mismatch is checked first; a matching hash is byte identity
only. After complete bounded parsing, a present textual `schema_version` other
than exactly `"1.0.0"` is withheld as `ARTIFACT_UNSUPPORTED_SCHEMA_VERSION` at its
fixed artifact path, without including the rejected text. This diagnostic reaches
both public preparation facades. Missing, null or nontext version fields remain
`ARTIFACT_SCHEMA_MISMATCH`, as do malformed, duplicate-key, trailing, invalid-UTF-8
or over-limit inputs. The generic frozen validator and the archive's existing
version checks and public outcomes remain unchanged. Rejection creates no
candidate and changes no durable draft, token, active selection or prior candidate.

Expected artifact IDs and revision/version pins must
match the actual artifact. Ruleset versions remain exact strings. Integer artifact
revisions compare by exact mathematical integer value, including integral decimal
or exponent forms; their original raw bytes remain unchanged. This local profile
supports at most 128 decimal digits for these counters, consistent with the local
integer serialization budget. Larger schema-valid counters are explicitly
unsupported, not silently truncated to a native machine integer.

The counter-budget regression exposed the pre-existing exact numeric minimum
defect addressed separately in [#320](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/320).
Projection validation relies on that reviewed correction; it does not bypass a
failed frozen schema or rewrite the original artifact to hide the failure. The
schema-valid `1e129` control reaches this profile's explicit unsupported-counter
result after that correction. The separate catalog/pricebook display readers
keep their existing local 128-digit admission policy.

The resulting `DraftSnapshotCandidate` owns independent copies of the frozen
Build bytes, the exact reviewed current local envelope bytes and all supplied
artifact bytes. Its source record retains parsed local history, metadata, format,
legacy-source hash and local revision identity. Local descriptors include the
catalog and pricebook revisions/hashes omitted by the frozen Build locks. These
descriptors are an in-memory client type, not an invented shared sidecar or an
assertion of research CD-7 publication/qualification acceptance.

`Created` means that independent candidate bytes exist in memory. The separate
[private archive](LOCAL_SNAPSHOT_ARCHIVE.md) now supplies a durable transaction,
immutable Build ID/revision bindings, its own CAS, complete-bundle retention and
read-only recovery. Candidate creation alone still provides no saved feedback.
The archive reuses this pure projection admission without granting live draft
write authority to a reopened snapshot. This candidate and archive retain the
exact current envelope and represented typed history, not every historical cache
file. A legacy-source hash remains a historical provenance reference whose older
raw serialization is not a declared archive member.

## Semantic and local admission checks

The reference sources are the unchanged frozen
[registry](../contracts/v1/schemas/component-registry.json),
[reference integrity tests](../contracts/v1/tests/test_contract.py),
[validation layers](../contracts/v1/docs/AGENT_PROTOCOL.md),
[component relationships](../contracts/v1/docs/COMPONENTS.md) and
[dependency semantics](../contracts/v1/docs/DEPENDENCIES.md).
The minimum-array-count control in the regression suite is schema-valid with two
selections and is still withheld for absent semantic foundation selections.

Preparation requires enabled `primary` workload, deployment mode, model and
context-policy selections, matching the frozen foundation rule's explicit roles.
The existing immutable aggregate already rejects duplicate selection IDs, all
duplicate `(kind, role)` pairs including disabled entries, active cardinality-one
conflicts, duplicate link IDs and missing selection endpoints. No second reducer
or alternate draft history is introduced.

All selected catalog ID/version/kind triples must resolve, including disabled
selections. The 18 exact catalog reference fields from the frozen reference test
must point to existing entries of the declared kind. Versionless references with
multiple versions for the target ID are withheld as ambiguous; an exact selection
pair cannot disambiguate another item's versionless reference. Unambiguous
reference diagnostics include the referenced ID and sorted available versions.
Duplicate catalog ID/version pairs, rule IDs, graph-edge IDs and price-rate IDs
are rejected. Price-rate item/version references must exist in the selected
catalog. Active models must have an explicit `provided_by` link to an active
selection for their declared catalog provider. No links are manufactured from
catalog references or dependency graph edges; graph cycles are not rejected.

The following are additional **local projection admission choices**, not changes
to the frozen schema or compatibility evaluator:

- An optional T3–T5 activation of `UNKNOWN` remains unresolved and is withheld.
- An enabled typed optional component under a locally disabled tier is
  contradictory intent and is withheld.
- An enabled optional tier without an enabled typed component is an incomplete
  representation and is withheld. Disabled or empty tiers create no global
  requirement for optional components; disabled typed payloads are retained.
- Entered workload count/concurrency/latency values that conflict with the same
  explicit workload-selection settings are withheld. Neither side is rewritten.
- Review diagnostics are bounded to 256 findings and 128 related values per
  finding. Any exceeded review bound explicitly adds `REVIEW_LIMIT_EXCEEDED` and
  withholds the candidate. A truncated review cannot be mistaken for successful
  or complete validation. Ambiguous-reference descriptor lists are shared during
  inspection to avoid quadratic retained diagnostics.

Structural/reference admission does not run compatibility, capacity, pricing,
evidence, source-use or deployment policies. In particular it does not repair the
frozen ruleset's hybrid/self-hosted applicability gap or claim provider membership,
region equivalence, current prices, qualified artifacts or readiness from labels.
The existing T6 content is retained and its independent gates remain required.

## Retained-only review and stale authority

Every review presents the immutable typed content being projected, the explicit
Build identity and chosen descriptors, and the source data that only the local
record retains. Its logical field-path inventory includes:

- Free-text `objective`, `context` and `primaryModel`, even if null, empty or equal
  to a catalog label or ID.
- Each T3–T5 activation, opaque selection set and opaque setting map.
- Local format version, local revision, legacy-source hash, the metadata object
  and every metadata key, and the complete retained local history.

These paths describe the logical current draft plus local envelope fields, not
the frozen Build and not a pointer into every historical envelope version.
Metadata keys use JSON Pointer escaping. The source itself retains the exact
values; diagnostic `toString` methods do not emit payloads, IDs, paths or amounts.
Nothing is stuffed into invented settings, notes or extensions to conceal a
representation gap. Existing typed selection notes/extensions remain intact.

`Review.acknowledgeRetainedOnly` accepts exactly the complete field-path set and
returns an opaque acknowledgment bound to that review object. Missing or extra
fields fail. A different review cannot reuse it, even when source fields and the
frozen Build bytes match but chosen artifact descriptors differ.
`createSnapshotCandidate` checks that same review acknowledgment and rechecks the
current stored source under the canonical transaction and library liveness guard.
Later content, optional-state, metadata or restore changes invalidate the review;
returning to earlier visible values does not restore its old authority. Foreign
directories, missing/corrupt records, pending/completed deletion, contention and
cancellation cannot materialize it. A fresh correctly acknowledged review works.

The acknowledgment API does not claim a human has used a UI. Product adoption
must present this disclosure and collect the explicit choice. This slice adds no
button, document export, sharing grant or automatic acknowledgment.

## Existing migrations and remaining acceptance

At accepted selection-role main `ed816a4c`, preparation uses the existing local
format 1–5 decoder without rewriting the source. Formats 1–3 have no typed
selection aggregate inferred from free text, so incomplete projections stay
withheld. Explicit existing save/restore migration can write format 4 while
retaining entered values, earlier entries and legacy identity. A real role edit
introduces format 5, which later writes and restores retain; unchanged format-4
`CONFIGURE` history keeps its settings-only meaning. This converter does not add
or silently invoke a migration. It projects the acknowledged durable draft;
unsaved or pending role-form input remains outside its input, and the future
creation UI must explicitly handle that distinction. Unknown/corrupt input stays
preserved and blocks preparation. A future shared schema or local profile requires
an explicit version, migration decision and affected-consumer review.

| Literal #26 criterion | Bounded coverage here | Remaining acceptance |
| --- | --- | --- |
| Allow empty and partially selected editing without fabricated fields; explicit transition to valid BuildSnapshot with versioned migration rules. | Canonical persistence plus explicit retained-draft/frozen-candidate transition and supported predecessor checks | The linked private archive supplies the core creation/identity transaction; product disclosure/removal UI, accepted shared handoffs and the exact receiving record remain |
| Empty/partial drafts persist without fabricated catalog IDs, facts or zero substitutes. | Empty/partial persistence→preparation keeps source bytes, nulls and exact entered zero/decimal distinctions | Full #21 prerequisite reconciliation and issue-level receiving acceptance remain required |
| Snapshot creation checks semantic requirements beyond minimum array counts; later edits preserve the original snapshot. | Foundation/reference/pin preflight, immutable independent candidate bytes and stale-review checks; later edits leave the candidate and original local revision intact | See the linked private archive for durable concurrency/recovery evidence; a memory candidate remains distinct from an acknowledged archive transaction |

The regression suite covers the four implementation subtasks through the existing
incomplete draft, edit/restore history and migration boundaries plus this explicit
candidate conversion. Empty/partial/full round trips, missing context/primary
model, dangling references and old-format preservation remain named checks.
Actual shared receiving acceptance is pending and no literal criterion, native
prerequisite, canonical acceptance path or broader work package is closed here.

## Verification

Run the required frozen-import/reference checks and core/native gates from
[CORE_FOUNDATION.md](CORE_FOUNDATION.md) and [CI_BASELINE.md](CI_BASELINE.md).
Focused local command: `:core:test --tests ai.pickyour.core.drafts.DraftSnapshotProjectionTest
--tests ai.pickyour.core.preview.PreviewDraftProjectionTest
--tests ai.pickyour.core.drafts.DraftProjectionVersionTest
--tests ai.pickyour.core.drafts.SnapshotBundleIntegrityTest` with the pinned offline
toolchain.

The historical focused run at local
`803c7a247e8e32d2a348441b77ef995a0b86face`, before
`DraftProjectionVersionTest` existed, composed the independently reviewed numeric
prerequisite and executed 15 projection and three managed-library cases with zero
failures, errors or skips. The earlier counter failure is retained as the
discovery evidence for #320, rather than relabeled as a successful run. The
in-session research engineer has read the complete projection, canonical wrapper
changes, tests and this boundary note; its source review is distinct from the
author's test execution.

The version-diagnostic regression baseline at local
`02f7ce0fd1d401cee25e05f44d8e622c864e447c` executed all 82 new cases: 69 passed and
13 failed because unsupported textual versions received the generic schema code.
At corrected source `b41dda6d249d81e069b387cfc6720be40f55f929`, the focused command
above executed 108 cases in four suites with zero failures, errors or skips:
82 version, 15 existing projection, three managed-library projection and eight
archive-integrity cases. All 82 baseline identities were retained, including the
13 formerly failing cases. The original failed and corrected logs/XML remain
separate evidence; neither run is an installed Android result.

Final exact-tree full gates and published receiving review belong in the delivery
receipt. No installed-device or complete work-package acceptance is implied.
