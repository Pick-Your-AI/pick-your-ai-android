# Recorded rule findings comparison

This Core API implements the historical rule-output portion of [#117](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/117). It compares explicitly supplied reports against their selected immutable BuildSnapshots and supplied rulesets. It does not change the local revision browser, access storage or evaluate current rules.

The existing [Build comparison](BUILD_COMPARISON.md) continues to return `NOT_LOADED` for historical findings and costs. No report is inferred from a Build identity, an empty finding list or a currently selected ruleset.

## Select and admit each side

First use `ComparisonSnapshot.read` with the exact requested Build ID and revision. Supply the corresponding historical ruleset and report streams, plus the exact expected report ID, to `RecordedRuleReport.read`:

```kotlin
val beforeReport = RecordedRuleReport.read(
    selectedBuild = beforeSnapshot,
    expectedReportId = selectedBeforeReportId,
    ruleset = beforeRulesetStream,
    report = beforeReportStream,
)
val afterReport = RecordedRuleReport.read(
    selectedBuild = afterSnapshot,
    expectedReportId = selectedAfterReportId,
    ruleset = afterRulesetStream,
    report = afterReportStream,
)
val comparison = RecordedFindingsComparator.compare(
    beforeSnapshot, afterSnapshot, beforeReport, afterReport,
)
```

Each attempted load uses the bounded UTF-8 reader and unchanged frozen schemas. The existing reference-report adapter checks Build identity, revision and recorded locks, the supplied ruleset identity/version, unique rules and corresponding findings, selection associations, declared counts and reference-profile result/readiness consistency. Admission uses the complete selected Build record. The comparator checks that complete record again, so a report cannot be reassigned to another Build with the same ID, revision and locks but different settings or roles.

Acceptance establishes structural and reference binding consistency. It does not authenticate a report, execute a rule, verify an evidence value or approve the reported outcome. The complete supplied ruleset is retained to disclose changes that reuse its recorded ID and version.

Streams remain caller-owned on every outcome. Perform this synchronous work off the UI thread; transport timeout, cancellation and access decisions remain the caller's responsibility. The API receives no URI, filesystem, current-state provider or clock.

## Missing, rejected and unsupported inputs

| Disposition | Meaning |
| --- | --- |
| `HistoricalReportInput.NotLoaded` | No attempted report admission was supplied to this comparison. This is the default. |
| `REPORT_NOT_SUPPLIED`, `RULESET_NOT_SUPPLIED`, or `REPORT_AND_RULESET_NOT_SUPPLIED` | The caller explicitly attempted admission without those inputs. The other stream is not consumed. This does not establish why an artifact is absent. |
| `INVALID_RULESET` or `INVALID_REPORT` | Strict parsing, a local safety budget, provider reading or the fixed schema rejected that input. Private input and provider-error text are not included in the diagnostic. |
| `UNSUPPORTED_*_SCHEMA_VERSION` | A complete parsed record has an unsupported textual schema version. Malformed or truncated input remains invalid. |
| `REPORT_IDENTITY_MISMATCH` | The supplied report is not the explicitly requested report ID. |
| `BINDING_MISMATCH` | A supplied record does not match the selected Build revision, recorded locks or ruleset. A stale recorded revision is rejected here; no latest revision is substituted. |
| `INCONSISTENT_FINDINGS` | The report does not satisfy the existing reference-profile associations, counts or declared-result rules. |
| `BUILD_RECORD_MISMATCH` | An admitted report was later supplied for a different complete Build record. |

If either side is not available, the comparison state is `NOT_COMPARED`; it returns no finding rows or metadata changes. The exact disposition of each side remains accessible. An empty row collection in this state never means that the historical evaluation found no problems.

## Read the recorded differences

With both inputs admitted, rows join on the literal rule ID and appear in stable sorted order. Added and removed rows retain their full available side. A shared ID retains both complete findings and all changed top-level fields: outcome, severity, code, message, remediation, selection IDs and evidence. An unchanged row has no change kind.

Evidence remains ordered, including duplicate paths. Absent evidence differs from a supplied empty array; absent values differ from JSON `null`, strings and typed values. Exact JSON numbers compare by their decimal value without narrowing to floating point. Text stays literal, including whitespace and Unicode controls. Full encoded values remain accessible; the Core API does not truncate or render them.

The result separately exposes complete report metadata changes, both selected Build lock maps and whether the recorded locks differ. Reused Build, ruleset or report identities with different complete records are explicit integrity conditions. A changed expression under the same ruleset version remains visible even if the recorded finding outcomes are unchanged.

The caller may set a finding budget from 1 through 10,000. If the union of admitted rule IDs exceeds it, the state is `FINDING_LIMIT_EXCEEDED` and no partial rows or metadata changes are returned. The full admitted inputs and identity conditions remain available.

## Limits and receiving scope

Rule-ID matching is a display join, not proof that rules from different versions have equivalent meaning. Matching recorded locks do not establish comparable evidence. The API always preserves the gaps for evaluation correctness, evidence authenticity, freshness, component causation and missing catalog/pricebook revision pins. Recorded timestamps do not establish freshness, and evidence paths are never dereferenced.

No current catalog, current evaluator, pricebook, network service or stored report is substituted. The API does not recompute missing historical outputs, calculate cost changes or promote a report to deployment readiness. It does not grant access to private Build or report contents.

This is a separate Core primitive. Wiring historical report selection and these dispositions into the accessible mobile comparison remains part of #117, together with cost evidence, installed accessibility and the complete receiving record. Existing local draft/revision comparison, storage authority and other work-package owners remain unchanged.

The companion `RecordedFindingsComparisonTest` covers default and missing inputs, strict admission, stale and reused identities, complete-record binding, recorded field and evidence differences, exact numeric values, bounded output, immutable collections and caller-owned streams. Ordinary repository CI supplies the execution evidence for each published revision.
