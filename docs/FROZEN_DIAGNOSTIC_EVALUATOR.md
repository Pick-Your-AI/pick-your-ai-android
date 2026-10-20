# Bounded frozen diagnostic evaluator (Android #84, partial)

The following is the historical diagnostic slice and its original receipts.
The later [retained-snapshot inspection profile](RETAINED_SNAPSHOT_EVALUATION.md)
adds an exact private input boundary, revocable native caller and explicit current
limits; its source, receiving decisions and executions remain separately attributed.

This bounded diagnostic slice was prepared through [PR #376](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/376)
at signed staging commit `82c6b49aa4cfc86fef98bbdc67d5a82c140b97f8` and received
on main through [PR #380](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/380)
at `e943a4dd959cc90a74acbc4a5a3d6b4736c5ca63`, tree
`c2acf3f97fdb9eb75632c465074593d97b3dacb0`. Its original implementation
baseline was `28fbcd88391d20b6c1e449e56725252a4f5d97cf`.
It consumes the existing frozen v1 import and current `ReferenceReportAdapter`.
It is an explicitly synthetic reference diagnostic, not an accepted production
interpreter, service, compatibility badge or deployment gate. No UI is wired to it.
No frozen file, governing decision or issue acceptance record is changed.

## Boundary

`FrozenDiagnosticEvaluator.evaluate(build, catalog, ruleset)` parses caller-owned
UTF-8 streams, validates the unchanged fixed schemas, checks schema/catalog/rules
identity locks, and rejects duplicate catalog, selection and rule identities.
Selected catalog items that exist must match selection versions and kinds, including
before derived metric use; missing items retain unknown. This is a stricter local
support boundary than the Python metric lookup loophole, not a frozen-v1 repair.
Every generated report passes the existing receiving adapter, preserving build
revision, catalog, ruleset, dependency graph locks, outcome and independent severity.
Graph identity is carried from the build; graph semantics are not executed here.
The report carries the frozen reference's fixed fixture timestamp. It must never
be interpreted as a real execution time or evidence-freshness receipt.

There are no fixture metric overrides and no accepted external cost/resource
estimates. Those metrics remain unknown, including under permissive policy.
The diagnostic computes only the existing reference's context sum, selected VRAM
and mutating-tool count. Missing or ambiguous references remain unknown. It retains
frozen behavior, including known upstream shortcomings; it does not repair them.
Typed failures separate invalid input, binding mismatch, work exhaustion,
unsupported operations and invalid generated reports. Python operations that
raise for unhashable set elements or non-string substring operands are explicitly
unsupported, never converted into a passing or failing compatibility finding.

## Bounds and numeric behavior

Each input has 256 KiB, 20,000 token, 4,096 UTF-16 string-unit and default 32-level
nesting limits. These are local support limits, not edits to frozen schemas.
A per-attempt work budget defaults to 250,000 and can be reduced for tests, with
an absolute maximum of 1,000,000. Expression traversal, selection matching,
recursive array/object comparisons and membership all consume work. String
substring scans reserve worst-case character work before calling the library.
Exhaustion rejects the entire attempt. Input parsing and schema validation retain
their separate finite input limits and are not counted as interpreter work.
The budget is deterministic operation accounting, not a measured time guarantee.

Decimal comparison uses `BigDecimal`, never a binary float. Recursive Python-style
container equality preserves bool/integer equality and does not coerce decimal
strings inside containers. This is a bounded profile, not a claim to support every
Python number spelling, arbitrary Unicode array pointer, or exceptional operand.
Build and graph revisions require integer tokens. Raw floating/exponent-number
operands are unsupported, including nested container
values and when the opposite operand is missing. Exact decimal strings remain
supported. Derived context sums require integer input tokens; VRAM operands require
integer tokens and products/sums within Python Decimal's 28-digit exact envelope.
Non-ASCII array-pointer segments reject explicitly. These conservative local
restrictions avoid inventing cross-language verdicts for unqualified inputs.

Callers must dispatch parsing/evaluation off the UI thread and use existing
`AnalysisCoordinator` attempt tickets before applying results. This synchronous
API does not cancel a blocking input provider or make a stale response current.

## Source-preparation checks (historical)

- `python scripts/check_diagnostic_fixtures.py`: 26 complete reports reproduced
  from the unchanged Python evaluator, including missing evidence, strict policy,
  exact decimal comparison, four outcomes, severity, recursive containment,
  boolean/unknown propagation and optional skips.
- `python scripts/check_contracts.py` in the workspace's isolated Python 3.12
  validation environment with `requirements-contracts.txt` installed: exit 0;
  all 43 frozen files and both manifests verified, 21 reference methods, four
  report fixtures, 57 quality cases, 160 pattern cases, 75 Event cases and the
  26 new diagnostic cases passed. The earlier system/research-environment
  attempts failed after the reference/report checks because quality-run format
  dependencies were absent; installing the repository-pinned requirements in the
  isolated validation environment resolved that setup failure.
- Four contract-integrity tamper tests, the local acceptance-ledger validator
  (129 issues, 423 incomplete criteria, 18 design dispositions), all 29 ledger
  tests, wrapper checksum and staged whitespace checks passed. The ledger is a
  repository snapshot check, not a live issue or acceptance-state verification.
- During source preparation, the local offline JVM command was not executed
  because the environment lacked an installed Gradle distribution, Kotlin compiler
  and dependency cache. The denied distribution download was not retried or
  bypassed. This historical local limitation is separate from the successful
  hosted verification below.
- Kotlin tests are authored for every golden plus identity/schema rejection,
  complete-attempt budget failure, long substring scans, nested containment,
  unsupported operands, independent repeated budgets, stream ownership, duplicate
  identities, kind/version mismatches, ambiguous selection, pointer boundaries
  and the 28/29-digit derived-arithmetic support boundary.

## Subsequent hosted verification

Hosted verification compiled and tested the evaluator. Core artifact `12142281749`,
SHA256 `9fad917f20b9eba9d5fa719a23a8376a5b67969c6b13a86e0a4639fc0aec49d2`,
records **44 evaluator tests and 2,475 total tests** across 57 suites, with no
failures, errors or skips, for hosted source
`31df3026fcafeae6e11ffa0d156b0deb57edcdee` and tree
`c2acf3f97fdb9eb75632c465074593d97b3dacb0`.
The [Core job](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39142085524/job/117718397880)
and [exact-source review](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/380#pullrequestreview-5563754383)
retain the execution and artifact inspection. These counts belong to that artifact
and are not inferred for another revision. Hosted artifacts have limited retention;
the hash identifies the inspected bytes but does not guarantee future availability.

After main integration at `e943a4dd959cc90a74acbc4a5a3d6b4736c5ca63`, all three
configured checks passed. Core job `117719970351` and Preview job `117719970094`
both recorded `BUILD SUCCESSFUL` on that exact main checkout, as recorded in the
[post-main receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/380#issuecomment-6252020363).
The signed preparation and main merge retained the reviewed trees and expected
parents through the [normal process](CI_BASELINE.md). These results establish
hosted build/test evidence for this bounded diagnostic slice; they do not establish
full Android #84 acceptance.

Python golden
reproduction does not establish Kotlin parity. No emulator, physical-device,
service, production-data or release result is claimed. Android #49's broader
cross-language/migration fixtures and #58's integrated asynchronous boundaries
remain prerequisites to full #84 acceptance. This record does not assign prerequisite owners or establish their acceptance.
