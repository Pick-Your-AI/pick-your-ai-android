# Read-only frozen snapshot comparison

Partial implementation for [Android #117](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/117). Live issue/comments and open PRs were refreshed before implementation on 2026-10-18. This slice compares two explicit frozen-v1 BuildSnapshot documents without loading a latest revision, changing either source, recomputing reports or modifying frozen contracts.

## Inputs and stable differences

`ComparisonSnapshot.read` requires the expected build ID and revision. It uses `BoundedJsonReader` and the unchanged bundled Build schema, rejects identity mismatches, and keeps a private parsed copy. Duplicate selection IDs or link IDs are rejected before indexing so none can be silently dropped. This extra integrity check is not a claim of complete cross-document semantic validation: catalog existence, reference eligibility, cardinality beyond the schema and shared receiving acceptance remain outside this reader.

The snapshot exposes its identity and an immutable copy of every recorded lock. Revision uses `BigInteger`, preserving the contract's integer range rather than narrowing it to a JVM Long. No mutable source nodes escape. Diagnostic `toString` methods omit build contents; consumers must still apply privacy and disclosure rules to explicit diff values.

Selections and relationships are matched by their actual IDs, independently of array position. Added/removed members get one marker at their ID path. Existing members are compared recursively by sorted object keys. Role changes have their own category even when the catalog item is unchanged; selection settings, relationships, objectives, locks and policy remain separate categories. Other top-level changes, including identity/version and extension fields, are metadata. Nested arrays are compared atomically: their order/element identities are not inferred.

JSON numbers compare by exact mathematical value, including integer and decimal
representations such as `1` and `1.0`. This follows the adopted
[JSON Schema 2020-12 instance data model and equality](https://json-schema.org/draft/2020-12/json-schema-core#section-4.2.2)
(checked 2026-10-18). The same comparison applies recursively within objects and
ordered arrays and to the recorded lock set. A representation-only difference
cannot invent an input change, different evidence locks or an integrity
discrepancy. Comparisons use exact decimals without conversion to binary floating
point. Strings remain literal: frozen decimal strings such as `"100"` and
`"100.00"` still produce a structural marker. Retained inputs, lock display values
and reported changed values are not rewritten or used as an artifact hash.

Paths use JSON-Pointer-style escaping (`~0`, `~1`), but selection/link segments are stable IDs instead of array offsets. They are comparison paths, **not executable JSON Pointers or patches**. Changes are sorted by path. The default and maximum budget is 10,000 markers; callers may lower it. Exceeding the budget returns explicit `LIMIT_EXCEEDED` with no partial markers, never an empty successful comparison. Consumers must check completeness before presenting a diff. Values are JSON encodings; absent values use Kotlin null, distinct from an explicit JSON null encoded as `"null"`. No semantic cost, compatibility or sensitivity conclusion follows from a structural marker.

An unchanged identity with substantive differences is flagged `SAME_IDENTITY_WITH_DIFFERENCES`, an integrity discrepancy rather than an ordinary new revision. Reordering selections or links alone produces no markers and does not trigger that discrepancy. Different explicit identities are disclosed even when other fields match.

## Retained local revision comparison

The [guest preview's retained-history comparison](ANDROID_PREVIEW.md#read-only-local-revision-comparison)
uses the same exact JSON value comparison for its bounded, schema-valid selection
and link payloads. Settings and namespaced extensions are compared before their
stable display strings are rendered. Numerically equal spellings therefore do
not create false setting changes when another edit creates a saved revision.
The original retained numeric encodings, source history, metadata and durable
head remain untouched. Workload decimal strings and other text still compare
literally; booleans, missing values, nulls and array order remain distinct.

The durable-history regression first failed on reviewed tree `640bd341`:
an objective-only change also reported settings and extensions as changed for
equivalent numeric values. With this change applied to merged main `404b4944`, the corrected consumer
passed **413 JVM cases** in 45 reports, including three new durable-history
regressions, with zero failures, errors or skipped cases under strict offline
verification. Those cases also retain exact changes beyond binary floating-point
precision and preserve source hashes after comparison. This local core evidence
does not establish historical cost/finding comparison, installed Android
accessibility or full #117 acceptance.

## Evidence and historical outputs

Every recorded lock is retained on each side and every changed, added or removed lock appears in the diff. Frozen v1 includes catalog snapshot ID, ruleset ID/version, dependency-graph ID/revision and optional pricebook ID. It does **not** provide catalog/pricebook revision pins or cost/resource evidence identities in this lock set; the comparator does not fabricate them. Extensions are structurally compared as metadata without assigning an unreviewed evidence meaning.

Different locks yield `RECORDED_LOCKS_DIFFER`; matching locks yield `SAME_RECORDED_LOCKS_NOT_PROOF_OF_COMPARABILITY`. Neither status attributes a cost/finding difference to a component or establishes reproducibility. The API accepts no historical reports or artifacts, so cost and finding availability are explicitly `NOT_LOADED` on every result. It does not claim those outputs are absent from the server, and never substitutes a freshly computed report under an old label. Report binding, unavailable retained-artifact classification, numeric cost comparison and compatibility-outcome comparison need a separate reviewed integration.

## Exact #117 criteria

| Exact live acceptance criterion | Evidence in this slice | Remaining work |
| --- | --- | --- |
| Compare selections, settings, assumptions, costs and rule outcomes while explaining differing evidence versions. | Stable structural selection/setting/objective/relationship diffs and recorded-lock disclosures | Cost and outcome comparison, assumptions absent from frozen v1, artifact availability and reviewed output binding |
| Comparing revisions is read-only and identifies setting/role changes even when the selected catalog item is unchanged. | Tests change settings and role independently; original inputs and retained snapshots remain unchanged | Android comparison UI and receiving integration acceptance |
| Differing catalog/rules/pricebook/evidence versions are disclosed and do not appear as unexplained component-caused cost or compatibility changes. | Explicit differences for all representable locks; same-lock result never asserts comparability | Cost/resource evidence identities and missing revision pins cannot be invented; bind retained outputs under accepted shared semantics |
| Unavailable historical evidence is identified explicitly rather than recomputed against latest data under the old report label. | Historical cost/findings explicitly not loaded; no evaluation/network API | Loader must distinguish available, unavailable, stale and non-comparable retained historical artifacts; this comparator does not inspect an artifact repository |

The tests cover setting/role/objective/graph changes, selection/link additions/removals, reordered arrays, missing pricebook pins, rules/catalog changes, same-identity discrepancies, source immutability, malformed/oversized input, explicit identity mismatch and duplicate IDs. No cost or compatibility result is computed.

Prerequisites #84 (accepted evaluator/report binding), #86 (selected build/revision storage integration) and #103 (cost coverage semantics) still gate their named integrated outcomes. Accessible mobile navigation/context, privacy-owner review, server history support under `pickyour.ai#17`, and affected receiving acceptance remain pending. Canonical acceptance remains `docs/delivery/android/work-packages/11.02.md` in `pickyour.ai`; parent coordination should link this partial evidence. This change does not close #117 or change the criterion ledger's incomplete disposition.

Run the checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md) and `python scripts/check_delivery_ledger.py`.

Local validation on 2026-10-18 passed `:core:check :core:assemble --offline`: **111 JVM tests**, including ten new comparison tests, plus core JAR assembly. Frozen verification passed all 43 files and both manifests, 21 reference tests and four report-fixture replays. The delivery-ledger structural check passed 129 issue URLs and 441 incomplete criteria (no live source comparison in that command). `git diff --check` passed. No Android UI, emulator or device validation was performed for this core slice.

The numeric-equality repair on 2026-10-18 reproduced four false markers against
accepted main `952eb015`: equal numeric values in an extension array, the graph
revision lock, the build revision and a selection setting. The initial corrected
source on that base passed all **407 JVM cases**, including **14 comparison cases**, with zero
failures, errors or skipped tests under strict offline dependency verification.
The added cases also preserve exact changes beyond binary floating-point
precision, literal string and boolean distinctions, atomic array-order changes
and the original recorded lock display. Frozen verification, all 21 reference
tests, four report fixtures, 57 quality-run cases, four import-integrity tests,
ledger validation, wrapper integrity and whitespace checks passed. These are
core and synthetic contract results; the integrated comparison, device and
receiving-acceptance gaps above remain open.

After the accepted modal/storage repair in `8162a557`, the unchanged numeric
implementation was composed without conflicts and the entire core suite ran
again. All **410 JVM cases** in 44 reports passed with zero failures, errors or
skips under strict offline verification; core assembly also passed. The earlier
407-case result remains scoped to its original `952eb015` base.
