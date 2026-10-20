# Historical costs for selected Builds

This Core API connects the recorded-cost comparison to the two explicit Build selections in [#117](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/117). It reuses [cost inspection](COST_ESTIMATE_INSPECTION.md) and the existing [recorded-cost comparator](RECORDED_COST_COMPARISON.md).

## Admit each selected input

Read each `ComparisonSnapshot` using its requested Build ID and revision. Supply its historical estimate stream and the expected estimate ID:

```kotlin
val beforeCost = SelectedCostEstimate.read(
    beforeBuild, selectedBeforeEstimateId, beforeEstimateStream,
)
val afterCost = SelectedCostEstimate.read(
    afterBuild, selectedAfterEstimateId, afterEstimateStream,
)
val result = SelectedCostComparator.compare(
    beforeBuild, afterBuild, beforeCost, afterCost,
)
```

Admission applies the existing bounded parser, unchanged frozen schemas, Build/revision/pricebook binding, line identity, coverage and exact declared-amount consistency checks. It also requires the requested estimate ID. The complete selected Build is retained, and comparison checks that record again. An estimate admitted for one Build cannot be reassigned to another record with the same ID and revision but different settings, roles, objectives, locks or metadata.

Exact JSON numeric values and property ordering follow the existing Build comparison equality. The retained estimate keeps its full recorded Build and estimate values, decimal strings, line evidence, assumptions and warnings. Streams stay caller-owned, including after rejection. Run this synchronous admission off the UI thread.

## Preserve each side's disposition

| Input or result | Meaning |
| --- | --- |
| `HistoricalCostInput.NotLoaded` | No attempted admission was supplied. This remains the default. |
| `ESTIMATE_NOT_SUPPLIED` | The caller explicitly attempted admission with no estimate stream. It does not establish why the input is absent. |
| `ESTIMATE_IDENTITY_MISMATCH` | The valid supplied estimate has a different ID from the requested estimate. |
| `BUILD_RECORD_MISMATCH` | A previously admitted input was supplied for a different complete selected Build. |
| Other unavailable reasons | The existing inspector rejected the input, including stale binding, unsupported schema, missing pricebook pin, malformed/provider input or inconsistent recorded amounts. |
| `NOT_COMPARED` | At least one side is unavailable or not loaded. `recorded` is null; no cost difference or missing zero amount is synthesized. |
| `COMPLETE` | Both selected inputs were admitted and the existing comparator traversed all supplied line IDs. This does not establish complete pricing coverage. |
| `LINE_LIMIT_EXCEEDED` | The line-ID union exceeded the explicit bound. Both admitted records remain available and no partial line comparison is presented. |

The line bound remains 1 through 10,000. A later explicit request starts independently. Reversing the comparison requires reversing both Build selections and their admitted inputs.

## Read recorded differences conservatively

When `recorded` is available, it is the existing `RecordedCostComparison`. Its literal line-ID joins, currency/period/pricebook differences, unpriced and unclassified selections, unknown coverage, reused-identity conditions and immutable full inputs remain intact. The comparator's permanent gaps for usage-model identity, pricebook revision/hash, rate eligibility, freshness and accepted coverage are preserved. `comparability`, `savings` and `budgetPass` remain `NOT_ESTABLISHED`.

Admission establishes consistency of the supplied records. It does not authenticate historical evidence, fetch current rates, normalize currencies or periods, interpret an absent line as free/included, or recompute a missing estimate. Recorded timestamps remain recorded timestamps.

The existing `BuildComparator` continues to return `NOT_LOADED` for historical costs and findings. The separate [historical findings API](RECORDED_FINDINGS_COMPARISON.md) retains its own input admission. Selecting and presenting these inputs in the accessible mobile comparison, durable historical retention, installed accessibility and the complete #117 receiving record remain separate work. This API adds no Activity, storage, refresh, evaluator or publication authority.

## Validation

`SelectedCostComparisonTest` covers explicit selection, full-record reassignment, stale and unsupported inputs, independent missing sides, exact retained evidence, unchanged pricing limits, bounded traversal and caller ownership. Run it in the pinned project environment:

```sh
./gradlew --no-daemon --dependency-verification=strict :core:test \
  --tests ai.pickyour.core.comparison.SelectedCostComparisonTest
```

The ordinary repository gates in [CORE_FOUNDATION.md](CORE_FOUNDATION.md) remain required. Their actual results belong to the source on which they execute.
