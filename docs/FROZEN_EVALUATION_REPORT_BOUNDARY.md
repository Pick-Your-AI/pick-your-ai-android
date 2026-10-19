# Frozen EvaluationReport structural boundary

This is a bounded model and fixture contribution to
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and
[Android #32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32).
`FrozenEvaluationReportDecoder` retains the complete recorded frozen document
before a caller applies its own binding, evaluation and evidence requirements.
It is independent of the existing reference-report receiving API.

## Exact inputs

The imported frozen package remains pinned through
[contract adoption](CONTRACT_ADOPTION.md) to upstream
`b1c4eaa4037f2a1e02103849064a947e758a2b40`. The shared panel pins these unchanged
files, and the ordinary hash-first runner checks all 43 originals and both import
manifests before any reference execution:

| Input | SHA-256 |
| --- | --- |
| `evaluation-report.schema.json` | `78935ec78848de7655bd972851aa0357c4426f6870fc028ba81739c85040359b` |
| `common.schema.json` | `0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c` |
| `evaluation-report.json` synthetic example | `039626962ef0f2c4f706614d775ad47eda1d148c58f736c98ad169fc153221f3` |

The example contains 45 synthetic findings. Its passing error-severity findings
and recorded `valid` / `needs_review` pair are distinct fields, not a new
qualification performed by this decoder. Frozen fixtures, schemas and generators
are unchanged; the new mutation panel remains test-only.

## Retained fields and distinctions

| Record | Immutable projection |
| --- | --- |
| Report: all 14 required fields | Schema version; report/Build/catalog/ruleset/graph identities; exact Build and graph revisions; ruleset version and original evaluated timestamp; declared result/readiness; ordered findings; all summary counters |
| Finding: six required and two optional fields | Rule ID, closed outcome/severity, code, message, ordered unique-per-finding selection IDs; optional remediation and optional evidence array |
| Evidence: required path and three optional fields | Unrestricted path text, optional observed/expected arbitrary JSON, and optional source-version text |
| Summary: all four required fields | Exact nonnegative passed, failed, unknown and skipped integer values |

Report revisions are positive `BigInteger` values and all four summary counters
are nonnegative `BigInteger` values. Schema-valid integral numeric forms such as
`1`, `1.0` and `1e0` retain their mathematical value without narrowing through
`Long` or `Double`. Fractional and negative violations are rejected by the same
fixed validator before extraction. Existing consumer budgets still bound all
input and normalized representations; no new wire maximum is asserted.

A missing remediation remains absent, distinct from supplied empty or whitespace
text. A missing evidence field remains absent, distinct from a supplied empty
array. Each evidence row's `observed` and `expected` is independently optional:
absence has no wrapper, while present JSON null has an immutable
`FrozenReportValue` whose `json` is `"null"`. Boolean false, zero, empty text,
empty collections and nested objects remain supplied values. Neither wrapper
interprets strings such as `unknown` or objects that resemble rules, references,
URLs or schemas. No mutable parser node escapes the model.

Findings and evidence retain their original order and duplicates. Only the
schema's `selection_ids` constraint rejects duplicate IDs within one finding;
reusing a selection in another finding is valid. No ruleset lookup, unique-rule
requirement, code derivation or finding-to-rule severity coupling is invented.
The schema permits empty findings and independently declared summary, result and
readiness values, even where a receiving domain profile will reject their
combination.

The original ID, version, timestamp, code and free-text values remain strings.
The code's ASCII pattern and maximum length differ from the message's mere
nonempty-string requirement. Existing frozen regex search and terminal-LF
semantics are preserved; the reader does not trim values or introduce a stricter
SemVer library. Evidence path and source version are unrestricted text: a path
need not be a JSON Pointer, and source version need not be a semantic version.
No evidence reference is opened or resolved.

The report's `json` and opaque-value `json` preserve reparseable JSON values and
array order, not original whitespace, object spelling, numeric token spelling,
source bytes, content hashes or signatures. A caller requiring artifact identity
must retain and verify its own original bytes through the relevant existing
boundary.

## Admission, safety and caller responsibilities

The existing [bounded reader](../core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt)
parses exactly one UTF-8 object before the unchanged bundled EvaluationReport
schema validates the complete normalized document. All closed-object, required,
type, enum, reference-pattern and format constraints apply before typed fields
are constructed. Unsupported/missing/nontext schema versions return the explicit
`UnsupportedVersion` outcome. Other fixed-schema failures return `SchemaMismatch`;
parser limits, malformed JSON/UTF-8, trailing data and I/O failures retain their
finite `InvalidInput(JsonRejection)` result. The default object/result strings
expose no recorded identifiers, messages, evidence values or provider diagnostic.

Original and normalized data retain the shared 1 MiB byte, 32-level depth,
100,000-token, 16,384-code-unit string, 256-code-unit name and 128-character number
limits, including the existing exponent-scale bound. A short original numeric
token that grows beyond the normalized representation limit remains rejected.
Arbitrary evidence values use the same budgets rather than bypassing them.
Streams remain caller-owned on every result. Callers perform I/O off the UI
thread and retain transport timeout, cancellation, disclosure and storage policy.

## Existing stronger receiver stays authoritative

[ReferenceReportAdapter](../core/src/main/kotlin/ai/pickyour/core/evaluation/ReferenceReportAdapter.kt)
and [its inspection API](../core/src/main/kotlin/ai/pickyour/core/evaluation/ReferenceReportInspection.kt)
remain unchanged. That existing receiver requires an explicit Build and Ruleset,
exact lock/revision binding, one finding per rule in order, declared selection and
severity consistency, exact counts and derived result/readiness. Its frozen
reference profile never admits `ready`. A `FrozenEvaluationReportDecoding.Decoded`
value is a different type and supplies none of that receiving authority.

The new regressions pass the same structural documents to both APIs: the frozen
canonical example still receives normally, while foreign Build identity,
duplicate/reordered findings, inconsistent counters/results and recorded `ready`
remain rejected by the existing stronger receiver. No current caller, analysis
coordinator, evidence presentation, storage adapter or native UI switches to this
new reader. No rule executes and no evaluated report is generated.

## Fixture and JVM coverage

The committed [shared panel](../core/src/test/resources/contracts/frozen-evaluation-report-cases.json)
defines 348 explicit cases: 126 structural admissions, nine unsupported-version
outcomes and 213 fixed-schema rejections. The
[Python checker](../scripts/check_frozen_evaluation_report_fixtures.py) verifies
all three input hashes, the fixture profile, unique names, supported mutations,
required installed format checkers and each independently declared outcome. It
uses the unchanged canonical Python schema and runs additively beside the
original reference-report fixtures.

The [Kotlin suite](../core/src/test/kotlin/ai/pickyour/core/contracts/FrozenEvaluationReportTest.kt)
checks every typed field for every admitted shared case, followed by normalized
value re-decoding. Ten additional named methods cover the full canonical record,
opaque/absent/null evidence, raw precise numbers, string/array absence, strict
receiver separation, exact integer and normalized bounds, copied collections,
exact byte/string/depth boundaries, malformed whole input and caller-owned
streams. These counts describe committed cases, not an assertion that a later
source or device has executed them.

Follow [core reproduction](CORE_FOUNDATION.md): run
`python scripts/check_contracts.py`, integrity/ledger/wrapper checks, and the
required clean `:core:check :core:assemble runtimeInventory` command with the
pinned toolchain and strict dependency verification. Inspect actual method names,
zero failures/errors/skips and original source/output inventories. Preserve each
execution's exact source and failed/recovery history; independently reviewed
receiving and actual default-branch verification follow ordinary delivery.

Issues #21 and #32 remain open. Their canonical records, original criterion text,
ledger, Project fields and broader API/persistence/import, cross-client and
receiving requirements are unchanged. The unreleased #49 successor prerequisites
remain separate. Structural decoding establishes no evaluation correctness,
evidence authenticity, actual current Build, authorization, installed device,
provider behavior, deployment or release acceptance.
