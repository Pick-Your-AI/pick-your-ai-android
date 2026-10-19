# Android CD11-0.1 context profile

This opt-in JVM package consumes the original synthetic `cd11-0.1` context binding
and its hash-bound `cd1-0.1` answer bytes. It implements the context portion of the
[Android #37 selected profile](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37#issuecomment-6257300075),
with the corresponding
[central receiving decision](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40#issuecomment-6257299200).
The scope is C1–C5 and the context-specific G1 admission boundary in the
[reviewed mapping](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/521ee3770bc5e0553f5874ae6c1e7bb0ff3ec910/docs/PLACEMENT_RELATIONSHIPS.md).
The mapping's other deployment, role-graph, physical-memory and historical-dispatch
interfaces retain their separate implementation and receiving work.

## Exact inputs and source basis

The research basis is accepted commit
[`449a29be1752a3c545709da466c7d53fc18fef73`](https://github.com/Pick-Your-AI/pick-your-ai-research/commit/449a29be1752a3c545709da466c7d53fc18fef73).
Its later CD-2 documentary delivery does not change the original CD-11 answer
version. The original reference sources remain research prototypes; this package
is their bounded Android consumer.

| Reference at that research commit | Exact Git blob | Role here |
| --- | --- | --- |
| [Context binding reader](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/449a29be1752a3c545709da466c7d53fc18fef73/tests/context_budget_binding_prototype.py) | `93807c06c217565e4fc5debf4639b9fdea3557c4` | Closed binding, typed scope, seven fields, answer-state and preflight behavior |
| [Context arithmetic](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/449a29be1752a3c545709da466c7d53fc18fef73/tests/context_budget_prototype.py) | `37a24ff1c822906f19fd885b2987dc5b4bb6ae43` | CTX-01/02 normalized arithmetic, outcome aggregation and effective-limit helper |
| [Original CD1-0.1 reader](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/449a29be1752a3c545709da466c7d53fc18fef73/tests/t0_answer_state_prototype.py) | `01a713cc7021a6f42e9b7b3850b334527904f652` | Original envelope, all-record admission, exact/ancestor lookup, retained source and numeric admission |
| [Binding fixture](../core/src/test/resources/placement/context/reference/context-budget-binding.json) | `659821843ecd9db3c9d85469cfa89877329da684` | Exact 647-byte synthetic input |
| [Answer fixture](../core/src/test/resources/placement/context/reference/context-budget-answers.json) | `3006ada1fe0945649296b3e3f9a5fec0aa240f0b` | Exact 734-byte original answers |
| [Binding schema](../core/src/test/resources/placement/context/reference/context-budget-binding.schema.json) | `5480495d59bf9c41c9108c7d13934359420a088e` | Exact original CD11-0.1 shape |
| [Answer schema](../core/src/test/resources/placement/context/reference/t0-answer-states.schema.json) | `91aab5e6bae86f3b7dfb8e504a65c02502c6defb` | Exact original CD1-0.1 envelope, including its optional provided source |

The four resource files are byte-preserved inputs, not a new neutral corpus.
Their [manifest](../core/src/test/resources/placement/context/reference/reference-pins.json)
records source paths, Git blobs and byte lengths. Tests verify those identities.
The fixture's declared answer SHA-256 is
`be4fc13496144cb559fa2a8116da5f81fd5e1395f50fa6c6b1b8999bf6c9b2f1`.

The native source basis is Android
[`a6db68625bb3dc5003e163468e991cebb6635923`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/a6db68625bb3dc5003e163468e991cebb6635923).
The package uses its existing
[bounded JSON reader](../core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt),
whose basis blob is `bad5750b5b8dec5eea23d9298a9fcf4c683a3406`.
The private shortest-decimal admission technique follows the separately reviewed
[Android CD1 numeric implementation](https://github.com/Pick-Your-AI/pick-your-ai-android/blob/01b1e7d44e8de24e09894a5f9a94eb83fea09324/core/src/main/kotlin/ai/pickyour/core/evidence/answers/Cd1Numbers.kt),
blob `6b372b42feef77217fb6078559d7d698fcf2c721`, to retain the pinned old
reference's numeric behavior. No code or API in that independently owned answer
package is changed or called.

## Entry points and retained information

All new production types live in
[`ai.pickyour.core.evidence.placement.context`](../core/src/main/kotlin/ai/pickyour/core/evidence/placement/context/Cd11ContextRecord.kt).
Nothing registers this profile with the frozen Build reader, general answer
reader, report adapter, source resolver or application dispatch.

| API | Required caller facts and result |
| --- | --- |
| `Cd11ContextRecord.read(bindingBytes, answerBytes)` | Copies both original byte arrays, validates the closed CD11-0.1 binding, checks the declared SHA-256 against original answer bytes, and admits the original CD1-0.1 envelope. Returns typed `Accepted` or a sanitized `Rejected` reason |
| `Cd11Scope` and `Cd11Reference` | Immutable, constructor-validated selection, model/version, route/version, operation, tokenizer/version and settings digest. Equality retains exact labels; it does not authenticate them |
| `record.evaluate(selectedScope, enabled, evidenceEligible)` | Requires the caller's separately established active scope, nullable activation and nullable source eligibility. Returns an immutable outcome, reason, lower/upper checks, `mayNotFit` and all seven answer observations |
| `ContextBudgetMath.evaluate(budget)` | Pure arithmetic on already normalized `BigInteger` counts and nullable operands. This lower-level entry point does not perform wire or evidence admission |
| `ContextBudgetMath.effectiveLimit(...)` | Uses an explicitly evidenced extension when supplied, otherwise the supplied native limit, then applies a supplied runtime ceiling. A missing native operand stays unresolved |

The record retains both original byte arrays and their SHA-256 digests. Byte
getters return copies. Scope, values, observations and returned collections are
immutable; automatic text output and rejection reasons omit original values,
source text and scope labels. Explicit getters intentionally expose retained
facts to the caller.

Every observation distinguishes its fixed requested path from the original
`sourcePath`. A provided parent can supply a typed child and retain the parent's
literal optional `source`. An unresolved parent supplies its original state and
cause path. An absent member stays missing with that parent path; it does not
inherit a provided source. Provided children cannot manufacture a parent value.
A withheld descendant supplies the original reference's first witness in Unicode
scalar order. The additional `origin` enum describes these lookup causes without
rewriting the old envelope metadata.

## Type, version and input boundaries

Only `cd11-0.1` with `cd1-0.1` is admitted. The separate
[CD1-0.2 work](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/487)
retains its own API and required assumption metadata. This adapter performs no
0.1-to-0.2 conversion and has no public generic CD1 index or answer-rule API.
The CD-2 physical-pool profile's canonical ByteCount strings are a different
representation.

All answer records are validated, including unused ones. A provided record must
retain its value, which can be null, false, zero or an empty value when unused by
these seven typed context fields. Its source is optional and must contain a
non-whitespace character when present; no source label implies an assumption,
preset or evidence authority. Unresolved records contain only their state.
Overlapping parent/child answer paths reject. RFC 6901 escaping and empty path
tokens are retained.

Context counts require original JSON integer tokens. Booleans, strings,
decimal/exponent spellings, null and implicit defaults cannot supply a count.
Inputs and input/shared capacities are positive; output reservation, output
capacity and total-policy capacity can be zero. Input intervals must be ordered.
Counts use `BigInteger`, including the pinned `2**80` boundary without rounding
or overflow. Every floating token elsewhere in the old envelope still undergoes
the original finite/shortest-decimal and exact-integral admission rules. Retained
bytes preserve numeric spelling, including negative zero; this fixed-context
adapter does not expose a reconstructed generic JSON document.

These are **native consumer budgets**, not new research or frozen-wire maxima:

| Bound per original input | Limit |
| --- | --- |
| UTF-8 bytes | 65,536 for the binding and separately 65,536 for answers |
| Container nesting | 32, using the existing reader's depth convention |
| JSON tokens | 100,000, also subject to the smaller byte budget |
| String value / property name | 16,384 / 256 UTF-16 code units |
| Numeric token | 128 characters |
| Decimal scale guard | Existing bounded-reader range of -10,000 through 10,000 |

Strict UTF-8, duplicate decoded keys, lone surrogates, unsupported root values and
trailing documents reject through the existing reader. Source and version strings
are never trimmed or normalized. Version length follows the schema's Unicode
code-point count of 1–200, with its nonblank and control-character restrictions.

## Conservative context behavior

Typed admission happens before evaluation. Malformed identities, unsupported
versions or a failed declared answer-byte binding reject. After admission, an
explicitly disabled selection skips. Unknown activation, absent or mismatched
selected scope, and false or unresolved source eligibility produce `unknown`
with no arithmetic checks from that ineligible record. Passing matching labels
does not establish source eligibility; the caller must supply that fact.

For an eligible record, the shared-window check compares input plus reserved
output against the shared capacity. Separate vendor budgets compare input and
output independently and skip a combined vendor-window check. The wire reader
rejects a declared provided shared vendor cap under separate semantics. The pure
normalized arithmetic retains the reference's lower-level behavior of ignoring
an unused shared cap; callers needing wire admission use the record reader.

An explicitly provided total-policy cap is an additional sum constraint under
either semantic mode. Missing, unknown or withheld optional input/policy caps
remain unknown unless explicitly not applicable. A separate input cap remains
required even if its answer says not applicable. Required unresolved values
never become zero or a skipped constraint.

Applicable checks combine as `fail` before `unknown` before `pass`; an empty or
entirely skipped collection is `skipped`. Upper endpoints determine the
conservative outcome. A failure has `mayNotFit = true` only when no lower-endpoint
check already establishes failure. Thus a straddling interval remains visible
beside another unknown operand, while a definite independent failure removes an
uncertainty-only label. A valid record for another scope contributes no failure
from its own ineligible numbers; independently eligible findings can still be
combined by their caller.

| Selected context group | Focused native behavior |
| --- | --- |
| C1 | 7,000 input plus 2,000 output fails an 8,000 shared window; separate 8,000-input / 4,000-output caps pass without inventing a 12,000 shared limit |
| C2 | A separate explicit 8,500 total-policy cap fails the same 9,000 sum, while both vendor caps still pass |
| C3 | Eligible 5,000–7,000 input plus 2,000 output straddles 8,000; missing operands remain unknown and known independent failures survive |
| C4 | Every scope field is exact, invalid scope rejects before shortcuts, and missing/mismatched scope or unresolved eligibility cannot supply another route's cap |
| C5 | The arithmetic helper can use a separately eligible normalized native limit when extension evidence is absent; the binding reader cannot invent normalization or fallback evidence |
| G1, context portion | Original hashes and metadata, closed versions, all-record validation, exact integer tokens, bounded parsing and defensive outputs are exercised without editing frozen history |

## Validation and remaining receiving scope

The [binding tests](../core/src/test/kotlin/ai/pickyour/core/evidence/placement/context/Cd11ContextRecordTest.kt)
retain exact original fixture identities and exercise typed admission, all five
states, ancestor causes, unused payloads, route/evidence preflight, parser limits
and defensive outputs. The
[arithmetic tests](../core/src/test/kotlin/ai/pickyour/core/evidence/placement/context/ContextBudgetMathTest.kt)
cover the normalized reference, four-outcome composition, uncertainty, independent
failures, native fallback and arbitrary-precision boundaries.

A focused reproduction uses the repository's pinned wrapper and toolchain:

```sh
./gradlew --no-daemon :core:test --tests 'ai.pickyour.core.evidence.placement.context.*'
```

The normal full core, native-preview, contract/reference, ledger, dependency and
scanner gates in [CI_BASELINE.md](CI_BASELINE.md) still apply to the actual
receiving source. Source tests and this document are not execution receipts:
record the actual source/tree, toolchain, original logs and test identities before
claiming a result.

This package supplies an explicit synthetic context consumer, without a tokenizer,
vendor/source resolver, live serving route, physical-memory allocator, production
dispatch or device measurement. It returns a separate value and preserves all
existing frozen history and conformance inputs. The existing 98 placement IDs,
223 neutral observations, remaining selected-profile groups and actual current
main composition keep their own evidence and acceptance boundaries.
The canonical #37 receiving record remains
`docs/delivery/android/work-packages/03.04.md` in `Pick-Your-AI/pickyour.ai`;
this source guide neither relocates it nor closes #37 or its prerequisite #21.
