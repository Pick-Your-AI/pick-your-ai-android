# Bounded frozen diagnostic evaluator (Android #84, partial)

This proposed, staging-bound slice starts at accepted Android `28fbcd88391d20b6c1e449e56725252a4f5d97cf`.
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

## Reproduction and current verification

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
- `./gradlew --offline --no-daemon :core:check :core:assemble`: required JVM gate,
  **not executed** in this environment. JDK 21 exists, but no installed Gradle
  distribution, Kotlin compiler or dependency cache was found. A previously denied
  distribution download was not retried or bypassed.
- Kotlin tests are authored for every golden plus identity/schema rejection,
  complete-attempt budget failure, long substring scans, nested containment,
  unsupported operands, independent repeated budgets, stream ownership, duplicate
  identities, kind/version mismatches, ambiguous selection, pointer boundaries
  and the 28/29-digit derived-arithmetic support boundary.

The source-level independent review is conditional on compilation and actual JVM
results. This proposal remains uncompiled pending its exact-source hosted gate.
Before acceptance, bind this record to the published source commit, reviewed tree,
independent review receipt and actual hosted run/artifacts; those identities are
not yet asserted here. Follow [the signed preparation process](CI_BASELINE.md):
source-to-owned-stage review/checks, normal verified-signature squash, then a
separate current-main receiver with composition review and fresh checks.
Python golden
reproduction does not establish Kotlin parity. No emulator, physical-device,
service, production-data or release result is claimed. Android #49's broader
cross-language/migration fixtures and #58's integrated asynchronous boundaries
remain prerequisites to full #84 acceptance. Their live assignees remain unfilled;
no owner or receiving approval is inferred from this preparation.
