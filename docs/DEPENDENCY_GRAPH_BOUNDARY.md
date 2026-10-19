# Frozen dependency graph decoding

`DependencyGraphDecoder` consumes one bounded, caller-owned UTF-8 stream and
returns an immutable structural view of the unchanged frozen graph. It preserves
the graph identity, exact positive `BigInteger` revision and every ordered edge.
The whole document must pass before any graph is returned; a malformed later
edge cannot produce a partially accepted graph.

This is partial implementation for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21),
[#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49) and
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32), with an
explicit adapter for the existing
[#100](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/100)
invalidation primitive. It does not supply a compatibility evaluator, placement
decision, current-build binding or UI reevaluation scheduler.

## Retained fields and outcomes

Each `DependencyEdge` retains `id`, `dependentKind`, `dependsOnKind`,
`relationship`, `activation` and `rationale`. The direction remains explicit:
the dependent kind depends on the prerequisite kind. All 29 endpoint kinds are
checked by the pinned schema; the four relationship and eight activation labels
have closed enums with their exact wire spellings.

Repeated edge identifiers, repeated entries, self edges and cycles remain in
their original order. Frozen v1 does not prohibit them. The reader neither
deduplicates nor sorts the artifact, infers transitive relationships, or treats
`informs` as a compatibility requirement. Empty edges, unknown kinds/labels and
new placement, component-role or memory-pool fields are rejected according to
the existing schema. Such new semantics require the canonical versioned
[research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40)
process.

The `json` property retains reparseable JSON values and all supplied strings,
including whitespace and the frozen identifier pattern's accepted terminal LF.
Identifiers are not approved paths, navigation destinations or object authority.
Rationale remains inert text. `json` is not the original byte layout, number
spelling, artifact hash or source signature.

The exact retained representation is checked against the same bounded reader
before success. Numeric normalization can expand a permitted input token; an
expanded representation that exceeds the limit remains
`InvalidInput(LIMIT_EXCEEDED)`. Revisions are never narrowed through `Long` or
`Double`, and fractional values are never truncated to an integer.

| Outcome | Meaning |
| --- | --- |
| `Decoded` | All supported structural fields were copied; graph policy and source authority remain separate |
| `InvalidInput` | The bounded parser rejects bytes, syntax, root, trailing content, input/retained-representation limits or provider IO |
| `UnsupportedVersion` | `schema_version` is absent, nontextual or different from `1.0.0`; no migration is attempted |
| `SchemaMismatch` | A frozen field, type, enum, integer constraint or required presence fails |

Only sanitized enums escape on rejection. Default `toString()` implementations
omit graph contents; reading fields or `json` explicitly still requires the
caller's disclosure policy. Collections cannot be mutated through casts, and no
mutable parser tree escapes. Streams remain caller-owned on success and failure.
Use the existing off-UI execution boundary and provide transport timeouts and
cancellation; this synchronous reader creates no network or filesystem action.

## Explicit invalidation adapter

The new `DependencyInvalidation.affectedByChange(graph, changedKinds, before,
after)` overload projects the decoded endpoint/activation fields into the
existing algorithm. The original `List<InvalidationEdge>` entry point and its
before/after truth table remain unchanged. Decoding by itself never activates an
edge; this overload explicitly calls the already implemented policy.

That policy examines direct neighbours in both directions, includes edges active
before or after a change, and treats unknown mode/production context
conservatively. It retains removed/disabled selection identities for rechecking.
Each supplied edge is visited finitely; cycles do not schedule recursive work.
Output identities are deterministic and deduplicated, while the original graph
and historical input contexts remain unchanged. The policy already treats all
relationship labels as recheck adjacency; this contribution adds no new
relationship interpretation or transitive propagation.

The caller must still establish exact graph/build pins, identify all changed
kinds and bind asynchronous work to the current acknowledged revision. Full
evaluation remains the existing conservative fallback when changed kinds are
unknown. This adapter neither stores results nor schedules UI, cost, resource or
evaluation invalidation.

## Pinned reference cases and reproduction

The unchanged [import manifest](../contracts/v1/IMPORT_MANIFEST.json) and
hash-first reference runner remain authoritative. The graph fixture pins its
complete direct schema dependency and canonical example:

| Input | SHA-256 |
| --- | --- |
| `schemas/common.schema.json` | `0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c` |
| `schemas/dependency-graph.schema.json` | `fb6a8bb42a9424d4ff2b520301f816c247789a3d70d1124afea1a8dc5ed5bd75` |
| `examples/dependency-graph.json` | `e9b3bca19a9cf8241fd74f6618824882a3413649822add88be1ac93cb3f1bacc` |

The [159 shared cases](../core/src/test/resources/contracts/dependency-graph-cases.json)
cover all 29 kinds at both endpoints, all 32 relationship/activation combinations,
required/null/wrong-type fields, exact revisions, future versions, unsupported
placement fields, duplicate/cyclic/self edges and whole-document rejection. The
[checker](../scripts/check_dependency_graph_fixtures.py) verifies deterministic
fixture bytes and actual Python Draft202012Validator outcomes against the pinned
schema; its explicit `--write` mode regenerates only the synthetic test fixture.
The Kotlin test executes those same expected outcomes through the public decoder,
checks every typed field and reopens every accepted JSON representation.

Separate JVM cases exercise exact numeric retention, representation growth,
duplicate/escaped keys, byte/depth/token/string/number limits, malformed UTF-8 and
surrogates, caller-owned streams, sanitized IO failure and immutable collections.
Actual decoded graphs also exercise the existing invalidation consumer, including
each activation, before/after changes, cycles, inverse neighbours and disabled
identities. These synthetic inputs establish no real dependency or capacity fact.

Use the pinned environment in [CORE_FOUNDATION.md](CORE_FOUNDATION.md):

```sh
python scripts/check_contracts.py
./gradlew --no-daemon :core:check :core:assemble
```

The Python command preserves all prior report, quality and pattern checks. Native
preview and exact artifact checks retain their existing CI gates. The separate
Apple graph boundary [#262](https://github.com/Pick-Your-AI/pick-your-ai-apple/pull/262)
uses the same graph/schema inputs but retains its documented stricter numeric
and terminal-LF identifier subset. This finite Android matrix does not assert
universal cross-client numeric or wire parity.

## Remaining acceptance

| Issue | This contribution | Still separate |
| --- | --- | --- |
| #21 | Frozen graph decoding, exact revision and immutable fields | Actual API/storage consumers, every other model and affected-owner acceptance |
| #49 | Shared Python/Kotlin source pins, expected outcomes and retained-value round trips | Full cross-client migration/rollback matrix and successor contract acceptance |
| #32 | Graph parser/field/numeric/immutability and consumer regressions | Complete domain, Android device and release regression suites |
| #100 | Explicit decoded graph input to the existing finite direct-adjacency algorithm | Governed graph/build applicability, durable dirty-state propagation, evaluation/resource/cost scheduling and current UI integration |

Canonical acceptance-record paths remain unchanged. Source review, JVM/reference
tests, simulated Android checks, installed-device evidence and release acceptance
remain separate stages; this slice alone closes none of these broader issues.
