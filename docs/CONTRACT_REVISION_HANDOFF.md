# Coordinated contract revision handoff

This is the procedure for the future-update subtask in
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21).
It consumes the [frozen import baseline](CONTRACT_ADOPTION.md) and preserves its
[exact provenance and reproduction record](CONTRACT_IMPORT_EVIDENCE.md).
It does not approve a new version, assign another person's ownership, relocate an
acceptance record or extend the currently selected guest-local implementation.

## Establish the accepted change before updating consumers

A successor starts with an explicit accepted revision from the canonical
[research #40 contract process](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40).
The handoff must identify the producer commit and content tree, contract/API
version, affected fields and semantics, exact schema/generator/fixture hashes,
and the actual affected-owner decisions. A merged research proposal or synthetic
prototype alone is not that release. Saved-build changes also retain
[research #42](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/42)'s
authority; evidence, pricing and rights keep the authorities in
[repository scope](REPOSITORY_SCOPE.md).

Keep original frozen v1 bytes and expectations intact. Add an explicitly versioned
successor alongside the retained baseline only when the accepted decision calls
for one. Do not change old hashes, widen old enums, inject defaults or regenerate
the old directory to make a successor test pass. The package's Git subtree is a
content identity; an accepted producer commit separately establishes its history.

## One reviewable update packet

| Packet area | Concrete information required for receiving review |
| --- | --- |
| Producer and generators | Exact accepted source; changed registry/schema inputs; deterministic generator commands, tool/dependency pins and output comparison. The frozen `generate_schemas.py`, `create_examples.py` and `generate_component_docs.py` remain archived v1 inputs; execute any reproduction in an isolated copy. |
| Validators | Version dispatch and closed shape/kind/enum rules; exact string and numeric handling; allowed schema/format resolution; strict input and resource limits; separately documented consumer policies. A decoder restriction must not be presented as a new wire rule. |
| Fixtures and expectations | Versioned positive, malformed, unsupported-version and migration payloads; unchanged original bytes and IDs; source hashes; explicit expected typed outcomes and value-preservation comparisons. A declared representation exclusion requires actual receiving review. |
| Generated or handwritten models | Complete supported-field/enum mapping, optional absence versus null/empty, ordered duplicates where permitted, immutable values, exact decimal strings and arbitrary-precision quantities. Record intentional projections separately from complete wire models. |
| Actual boundaries | Every affected API response/request, durable read/write, import/export and queued callback; its owner, source path, supported version and outcome before the value gains local authority. Structural decoding alone does not activate a caller or qualify report, price or evidence binding. |
| Migration and recovery | Supported old reads, explicit transformation trigger, source/target version and identity, unchanged original snapshots, conditional writes, interruption/failure outcome and rollback. Frozen wire versions and private store format numbers remain distinct. |
| Consumers and delivery | Actual central contract and affected Android/web/iOS receiving decisions at fixed source hashes, coordinated deployment/adoption order where applicable, accepted limitations and the canonical criterion records. An unfilled role remains unfilled. |

The packet identifies existing implementation lanes before editing their files.
One writer owns each adopted source; affected readers receive an immutable
candidate and report concrete compatibility findings. This procedure creates no
second service, evaluator, fixture corpus or migration owner.

## Verify original values at the boundary that receives them

Reference comparison must exercise each participating implementation's actual
interface. Passing a rounded decoded object to a validator cannot establish that
the original JSON number was preserved. Compare original wire text and decoded
values separately where that distinction matters, including large integers,
integral decimal/exponent forms, exact money strings, optional states and unknown
versus unsupported outcomes. Do not narrow to a binary float and then attribute
agreement to the original payload.

The [recorded Manifest reference diagnostic](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49#issuecomment-6254033978)
is an actual example of that distinction: the exercised standard decoder had
already changed some numbers before the existing TypeScript object validator
ran. Its admission agreement is separate from exact-value parity. A new adapter
preflight must be identified as new behavior, with its own tests and affected
consumer review, rather than credited to the unchanged reference.

For each adopted version, retain the original golden outcomes and run the
appropriate reference/JVM/runtime cases at the actual candidate. Include wrong
version, extra field, unknown kind/enum, malformed decimal, missing versus explicit
empty/null, stale identity and failed conditional-write controls relevant to the
changed boundary. Verify old snapshot hashes after supported migrations and
failed/rolled-back attempts. Scope controls to actual supported behavior; do not
invent a migration merely to label every version pair supported.

## Receiving and failure disposition

Record actual source and prepared-tree identities, commands, environments,
case/observation inventories, raw evidence hashes and independent review. Keep
source-only composition, execution, default-branch verification and installed
device evidence distinct. Failed or stopped attempts retain their original
outcomes; later corrections do not relabel them. Normal repository review,
configured gates and guarded delivery still apply.

The receiving decision must say which original criterion is satisfied and why,
which interface/versions were actually exercised, and which limits remain.
Missing upstream semantics or consumer agreement remain a dependency; they are
not a passing fallback. A recorded failure is a failure of that attempt, not a
reason to rewrite the frozen input.

The current [#49 fixture receiver #432](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/432)
owns its declared neutral profile and private-migration scenarios. Its original
representation exclusions, pending shared semantics and separate runtime-parity
scope remain intact. This procedure neither duplicates that corpus nor supplies
the missing #27/#37 outputs. Broad #21, #32 and #49 completion still requires their
literal criteria, actual accepted handoffs and canonical receiving records.
