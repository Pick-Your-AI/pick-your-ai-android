# Normalized placement receiving profile

This is a bounded implementation contribution to
[Android #37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37).
It implements the deployment, relationship, physical-memory and sequence-demand
parts of the received `android-placement-receiving/0.1` test profile.
The [selected mapping](PLACEMENT_RELATIONSHIPS.md) remains the decision and
fixture index. This package does not add fields to frozen v1, select a product
placement, activate a saved ruleset, or dispatch from the existing evaluator.

## Accepted inputs and exact versions

The central source is signed Research
[449a29be1752a3c545709da466c7d53fc18fef73](https://github.com/Pick-Your-AI/pick-your-ai-research/commit/449a29be1752a3c545709da466c7d53fc18fef73).
Its accepted [component decision note](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/449a29be1752a3c545709da466c7d53fc18fef73/research/T0-configuration/component-schema-compatibility.md)
is Git blob `d3d53f3c135556a3bd227e8008dceb9f08676749`. The later CD-6,
CD-10 and CD-2 decisions supersede their dated pending summaries.
The actual bounded central/affected-consumer
[receiving design review](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/483#pullrequestreview-5565903014)
accepted the six interfaces and proposed vectors with explicit version,
qualification and migration conditions. It did not execute this implementation.

| Input | Consumer boundary |
| --- | --- |
| `cd2-physical-pools/0.1.0` | Explicit normalized pool, allocation, scenario and independent-ceiling records. ByteCount is a canonical decimal **string**, parsed directly to BigInteger. |
| CD1-0.2 | The existing typed-answer API supplies five states, original source/source-path and the exact provided assumption flag. This package does not duplicate its reader or change its schema policy. |
| CD-5 sequence demand | Explicit original R, E and same-runtime admission operands. Request concurrency is not resident sequence demand. |
| CD-6 / CD-10 | One directed selection graph, qualified semantic role and operation, exact catalog versions and adapter/base/engine tuple. |
| CD11-0.1 / CD1-0.1 | The separate preserving [context contribution](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/505) retains the old answer bytes and integer context counts. This package does not relabel or feed those originals to CD1-0.2. |

The exact CD1 dependency was initially qualified as signed isolated source
`01b1e7d44e8de24e09894a5f9a94eb83fea09324` through
[#475](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/475).
Its current-main continuation is
[#487](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/487).
Local implementation evidence below uses those six unchanged source files;
a staged dependency is not recorded as accepted default main.
The eventual receiving PR must name its actual accepted prerequisite and tree.

## Explicit API and retained originals

Production source is confined to
[`ai.pickyour.core.evidence.placement`](../core/src/main/kotlin/ai/pickyour/core/evidence/placement).
The caller constructs typed normalized records and calls these entry points;
there is no new wire document, second graph, network lookup or storage migration.

| Interface | Actual responsibility |
| --- | --- |
| `PlacementBasis.read` | Admit the full original frozen Build and any selected original catalog, retain named original inputs and normalization/source-policy identities, and compute an unambiguous framed identity. |
| `PlacementAnswers` constructor / `.read` | Read the actual unchanged CD1-0.2 envelope and one requested pointer against a fixed domain field schema. Retain state, detached provided value, source path, preset source and assumption. |
| `PlacementQualifications` | Apply explicit caller eligibility and conflict/checked-only assessments keyed to original input bytes, profile, field/fact, pointer and actual domain. These assessments are inputs, not authenticated evidence. |
| `PlacementGraph.resolve` / `lineage` | Resolve an operation-specific role in the one original graph and exact adapter/base lineage. |
| `PlacementDeployment.evaluate` | Derive each component's runtime-operator/capacity mode and applicable local predicates while preserving independent conditions. |
| `PlacementMemory.evaluate` / `PlacementWorkload.evaluate` | Evaluate already normalized physical accounting and scoped sequence demand, retaining qualified failures, unresolved operands and all used evidence. |

The basis includes complete Build bytes, its selected catalog input name
(including absence), every named input's kind and original bytes, operation,
normalization policy and source policy. Selecting a different catalog cannot
reuse the same basis even when both catalogs remain present in the input list.
Catalog matching uses the frozen snapshot lock and each selection's exact
item ID/version. An absent version stays unknown; a known catalog-kind mismatch
is a consistency failure.

Every supplied declared original hash is checked before evaluation.
Qualification records validate their original evidence input and hash against
their own retained basis, including unused records. A malformed ID, missing
declared original input, contradictory provided reference or failed original-byte
binding rejects admission. A valid old basis or unestablished scope instead makes
the affected evidence ineligible/unknown. Another independently qualified
predicate may still fail. A structural digest does not establish source rights,
freshness, provenance quality, measured capacity or truth.

Caller byte arrays are defensively copied. Exposed values and collections are
detached or immutable. The original five states stay distinct, including provided
false, zero, null and empty values. Causal ancestor/withheld paths use decoded
JSON Pointer ancestry; a raw textual prefix cannot create ancestry.

## Deployment and the one role graph

Operator and capacity are explicit component facts. Self-operated components are
self-hosted. Provider shared capacity is API mode; reserved throughput or dedicated
instances is managed mode. Multiple established component modes produce hybrid.
A protocol name or catalog access label supplies no operator fact.

Local applicability is derived per component. A self-hosted component receives
local predicates; provider components require qualified hardware exposure before
local hardware checks apply. An explicitly opaque API-only component skips those
local-only predicates and retains unresolved provider capacity separately.
Artifact and service conditions remain independent, including customer-supplied
runtimes. Unknown support completeness does not erase an otherwise established
deployment classification.

The normalized meaning of a link names an existing original edge, without
duplicating its endpoints. Host and serving-engine roles are singular; device
bindings may name several exact accelerators or compute profiles. Required
absence, known wrong-kind/disabled targets, missing inventory and missing exact
catalog versions retain different results. Multiple plausible candidates remain
unresolved without choosing the first array entry.

Adapter-only artifacts require an exact qualified base and its version. The
effective serving tuple includes the base's engine/host/device bindings while
retaining applicable conditions on both adapter and base. Full or merged artifacts
use their own deployment/capabilities and do not inherit a base's conditions.
Adapter lineage cycles remain unresolved; structural duplicate/dangling facts reject before
disabled, inapplicable or changed-basis evaluation shortcuts.

Condition completeness has an original Answer of the exact condition IDs plus
a separately qualified completeness claim. Its provided IDs must equal the
supplied records. Omitting a known failing condition cannot retain the same
complete inventory and silently produce a pass.

## Physical pools, copies and independent ceilings

Pool identity is an explicitly qualified physical address-space/partition/tenancy
tuple. Device membership and reciprocal pool references are separate facts.
Provided dangling references, contradictory memberships, repeated physical
identities and duplicated extents reject before applicability shortcuts.
A supplied target whose membership field is unknown remains unresolved; an absent
provided target is not such an unknown counterpart.

A physical allocation owns one qualified pool and extent, exact consumer IDs,
term and method, and independently qualified non-overlap. Each resident extent
is charged once per owning pool/scenario. Equal values, hashes or consumers never
prove sharing. Host offload and transfer copies remain separate allocations, and
a consumer may use a copy in another pool without adding another serving graph.
Advertised VRAM is not summed and device counts do not divide demand.

The two explicit coverage assumptions are `CONSERVATIVE_ALL_RESIDENT` and
`QUALIFIED_SCENARIOS`. They remain visible in every result.
The former cannot borrow an exclusion from an unrelated scenario. The latter
requires qualified scenario/lifetime scope; excluded rows need both scoped
residency and transition evidence. Required `always` components remain in the
scenario inventory. This does not fabricate an allocation lifetime.

For each pool and independent ceiling, C is a qualified capacity lower bound,
D a qualified demand upper bound, and R a qualified reserve upper bound:

| Original qualified basis | Comparison |
| --- | --- |
| `before_reserve` | D + R <= C |
| `after_reserve` | D <= C, retaining R and the actual deduction/reservation trace |

The basis is itself an original closed Answer with a normalization qualification,
not a mutable enum that can change arithmetic independently of the inputs.
Zero budget or reserve remains valid when actually supplied. No reserve is
invented, clamped or deducted twice. Source quantities, units, normalization method
and rounding remain explicit; this parser performs no unit conversion.

A qualified identity-disjoint failing subset can retain failure when other
nonnegative charges are missing. Uncertain alias, residency, physical identity,
source eligibility or scope cannot supply a failing witness. Pass requires all
required coverage; an empty ledger alone never proves fit or inapplicability.
An unused pool requires complete scoped inventory and qualified consumer placement.
It cannot manufacture a reserve-over-capacity failure for an unneeded pool.

Independent ceilings retain their own actual instance/replica/rank/admission/stage
and scenario, with separate demand, accounting completeness and capacity basis.
They are not additional physical pools. A pool pass cannot erase a rank-ceiling
failure, and an omitted required component cannot leave the ceiling's scenario
silently eligible. Access/support failures remain separate from arithmetic.
All numeric sums and comparisons use BigInteger, including one-byte boundaries
beyond binary64 and signed-Long precision.

## Sequence demand and admission

`PlacementWorkload` admits original runtime-source references and all supplied
artifact facts before evaluation shortcuts. A required binding failure stays a
failure while its dependent sequence quantity remains unknown.

For each qualified co-resident class, D includes R times E. Combining classes
requires their explicit same-scope allocation and complete inventory.
For one request with four expanded sequences and a qualified same-scope sequence
admission cap of two, D is four and S is two. Request-unit, per-step, wrong-build,
wrong-replica or wrong-stage caps cannot reduce resident demand.

An unknown expansion leaves D and S unknown even when an admission cap is known.
An unavailable cap permits S = D only when a separate qualified expanded-demand
bound covers retained and queued KV state; the result still records unknown
admission and the conservative fallback assumption. No token geometry, KV-byte
quantity, sum of independent stage maxima or runtime support is inferred here.

## Results and evidence attribution

Every check retains its domain, original observations, actual used qualifications,
outcome, reason, fixed error severity and `on_unknown=review`.
The result retains basis and qualification identities and explicit assumptions.
Failure takes precedence over unknown in aggregation; incomplete coverage does
not turn a qualified failure into a pass or erase it.

Checked-only attribution follows every used prerequisite: deployment facts,
role bindings, device maps, scenario membership, qualified exclusions, zero-demand
comparisons and unused-pool decisions. Unused budget evidence does not become
support for an unused-pool skip. Conditional capacity and `mayNotFit` describe
the scoped upper-bound result; they do not assert installed operation, latency,
quality, engine compatibility, a recommended configuration or measured fit.

## Bounded input limits

These are local admission limits for this test profile, not new central wire
limits: 256 KiB per retained input/Build, 2 MiB in total, 32 named inputs,
512 selections, 1,024 graph links, 1,024 records per typed inventory, 64 scenarios,
4,096 scenario rows, 8,192 access rows/qualifications and 4,096 UTF-16 code units per
nonempty logical ID. Malformed surrogate sequences reject. CD-2 logical IDs retain
nonempty Unicode text; they do not inherit the separate CD11 restricted-ID grammar.
The existing bounded JSON reader and CD1 reader/schema limits apply independently.
ByteCount is exactly `0|[1-9][0-9]*`; JSON numbers, float/exponent aliases,
leading zeros, signs, whitespace and booleans are not ByteCount strings.

## Actual focused verification and original failures

The corrected production source and all three actual test classes were compiled
with the pinned Kotlin 2.1.20 compiler, OpenJDK 17.0.20 and JVM target 17.
The original qualified #505 Core JAR supplies unchanged frozen infrastructure;
the six exact #475 CD1 files were compiled as separately identified dependencies.
Ten runtime JARs and eighteen compiler/test artifacts were checked against the
existing verification pins. The project's actual `processTestResources` already
supplies the two unchanged canonical examples used by the synthetic fixture builder.

| Actual class | Unique successful cases |
| --- | ---: |
| `PlacementBindingDeploymentTest` | 41 |
| `PlacementMemoryTest` | 49 |
| `PlacementWorkloadTest` | 21 |
| Total | 111 |

All 111 started and finished successfully across fifteen containers, with zero
failure, error, skip, abort or duplicate test identity. This is the focused local
source receipt, not configured Gradle/JDK21, full Core/reference, Android Preview
or default-main execution. The receiving PR must run and qualify those normal
configured gates on its actual composed source.

The preceding 103-case corrected run remains a separate actual receipt. A final
independent full-source review found two additional evidence-attribution gaps:
adapter-kind prerequisites were missing from dependent base/version exits, and
runtime-access checks omitted the residency prerequisite when byte demand was
unknown. The correction retains those actual prerequisites and stops attributing
an unconsulted base-version answer to a base-ID mismatch. Seven adapter-lineage
cases and one independent runtime-access case now cover those paths; all 103
previous test identities remain present and successful in the 111-case run.

The retained first test attempt could not load the canonical examples from the
standalone classpath. The next attempt exposed invalid synthetic selection IDs;
only the test builder was corrected to obey the unchanged frozen grammar.
The following real 34-case run had 29 passes and five failures: four hit an
unsupported grouped/alternating ByteCount field pattern in the pinned portable
schema dialect, and one showed a missing original runtime-source preflight.
The correction uses the supported digit pattern plus an exact canonical
postcheck and validates all declared runtime references. None of those failed
runs is counted as corrected execution.

Independent draft review also found the active catalog selector missing from
basis identity, unused original qualification bindings unchecked, known catalog
kind failures lost, deployment classification coupled to unrelated support
coverage, and condition inventories omitted. The later memory/workload review
found dangling references hidden by unknown inventories, structural facts hidden
by skips, known binding failures collapsed to unknown, and missing prerequisite
evidence. Their focused negative cases, qualified reserve-basis and scenario cases
are included in the preceding 103-case corrected run. Original logs and source packets remain
separate from later source and hosted evidence.

## #37 receiving and migration boundary

D1–D3, B1–B3, M1–M4, W1 and G1 from the selected mapping are exercised by these
three classes. C1–C5 retain the separate exact CD11/CD1-0.1 context implementation
and its own source, original fixtures, independent review and receiving execution.
The already imported 98 placement wire cases within the 223-case neutral bundle
remain untouched; these normalized behavioral tests do not replace their original
structural outcomes or add a second producer.

All three literal #37 criteria remain open until the complete actual-version
behavior, required central/affected-consumer receiving decisions, prerequisite
outputs and canonical record are reconciled. This source note supplies no absent
assignee, installed-device, provider, operator, release or product-default approval.
The historical intended record remains
`Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/03.04.md` until an
explicit placement decision. #21's full boundary criteria, #27/#49 source ownership,
the context controller and central successor/version/migration authority remain
separate. No historical Build/Answer bytes or frozen result is silently migrated.
