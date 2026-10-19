# Comparing retained cost records

`RecordedCostComparator` supplies a read-only core primitive for Android
[#116](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/116).
It compares two immutable `InspectedCostEstimate` values, each already checked
against its explicitly supplied BuildSnapshot by
[`CostEstimateAdapter`](COST_ESTIMATE_INSPECTION.md). A caller can show the two
records and their differences without calculating an unsupported saving or
silently replacing either input.

This is partial implementation. It does not fetch prices, refresh a saved build,
store a new estimate, inspect a Pricebook, validate an approved usage model or
render an Android screen. None of #116's four whole-issue criteria is completed
by this primitive.

## Recorded values and identity

The result retains `before` and `after` as the actual immutable inspected inputs.
Each has its original recorded estimate ID, Build ID and arbitrary-precision
revision, pricebook ID, estimate timestamp, currency, period, coverage, amounts,
line records, assumptions and warnings. The inspector now also privately retains
the supplied BuildSnapshot's complete parsed values and exposes their serialized
`recordedBuildJson` beside `recordedJson`. These are retained JSON values;
whitespace, property order and original raw numeric token spellings are not an
archive-byte authentication mechanism. Decimal strings remain literal, including
their trailing zeroes and any schema-admitted quantity newline.

The comparator reports changed top-level fields and changed retained Build
content. Reusing an estimate ID for different retained estimate values produces
`SAME_ESTIMATE_ID_WITH_DIFFERENT_RECORDS`. Reusing a Build ID and the same
mathematical revision for different retained Build values produces
`SAME_BUILD_IDENTITY_WITH_DIFFERENT_RECORDS`. Neither conflict is interpreted as
a new historical revision. Object property order and numeric JSON spellings use
the existing exact structural equality from the Build comparator; array order
and strings remain significant for these integrity checks.

The complete Build is retained because matching ID and revision values alone
cannot prove that two callers supplied the same workload or deployment inputs.
`BUILD_RECORD` is deliberately a recorded-content difference, not a classification
that every changed Build field affects price. Equal retained Builds still do not
establish an approved usage-model identity.

## Line records and exact numbers

The result presents a deterministic union of the recorded line IDs. A common ID
is only a display join: it does not establish stable correspondence between two
estimates. Both full line records remain available. Changed selection, rate,
meter, method, confidence and assumptions are explicit. A renamed or removed
line remains a separate before-only record; a new ID remains a separate
after-only record. There is no positional pairing or match by display name.

Several distinct line IDs can share a selection, meter and rate. Every line is
retained, and `MULTIPLE_LINES_FOR_SAME_METER_AND_RATE` makes that condition
visible. The comparator never keys lines by that triple, combines their
quantities or silently drops a duplicate. The existing inspector continues to
reject duplicate line IDs within an individual estimate before comparison.

For a common line ID, quantity comparison is available only when the recorded
selection, meter and rate also match and both quantities satisfy the existing
whole-string `ContractDecimal` grammar. Exact `BigDecimal` comparison treats
`"1"` and `"1.000"` as numerically equal while preserving both strings. It
distinguishes adjacent integers above binary floating-point precision and all
supported fractional digits. A changed association returns `UNAVAILABLE`:
`720` GPU-hours and `720` storage-GB-months are not reported as equal quantities.
Even equal numbers under the same recorded association do not prove equal
workload, supported billing semantics or approved cross-estimate correspondence.

An inspector-admitted quantity such as `"720\n"` remains literal. It produces
`UNSUPPORTED_QUANTITY` and unavailable numeric comparison, without an escaping
exception, trimming or a fabricated zero. If a numeric comparison is unavailable,
`QUANTITY_LITERAL` can still show differing original strings. A before-only or
after-only line likewise has no numeric comparison or substituted missing value.

Already validated money values are compared by explicit currency and exact
decimal amount. Different scales are numerically equivalent, but retained
strings stay intact. Different currencies always differ even when their amount
strings match. No exchange rate, billing rounding, quantity-times-unit-price
calculation, total delta, annualization, amortization or period normalization is
performed. Same estimate IDs with changed decimal strings can therefore expose
an integrity conflict even when the displayed numeric comparison is equal.

## Evidence that remains unavailable

Every result preserves these gaps:

- `USAGE_MODEL_IDENTITY_NOT_ESTABLISHED`: the frozen estimate does not carry a
  governed usage-model revision. Matching declared quantities or Build values
  cannot supply it.
- `PRICEBOOK_REVISION_AND_HASH_NOT_ESTABLISHED`: the estimate carries only a
  pricebook ID. It does not identify the exact pricebook revision, content hash
  or original price records used by the producer.
- `RATE_ELIGIBILITY_NOT_ESTABLISHED`: a recorded rate ID/unit price supplies no
  independently accepted offering, region, plan, account, evidence or use-rights
  decision.
- `FRESHNESS_NOT_ESTABLISHED`: `estimated_at` remains a recorded timestamp, not a
  rate check date, validity interval or expiry verdict. A later or future-dated
  estimate is not automatically newer approved evidence.
- `COVERAGE_NOT_ACCEPTED`: producer-declared complete coverage, zero amounts or
  empty unpriced lists do not prove all applicable meters, free/included
  treatment, tax assumptions or billable operations have been accepted.

`comparability`, `savings` and `budgetPass` are consequently always
`NOT_ESTABLISHED`. Partial/unknown estimates remain inspectable. Newly unpriced
IDs, newly unclassified active selections, priced/unpriced overlap and changed
declared coverage remain visible with both original reported amounts. An absent
line is not zero cost, and a partial subtotal is not labelled a proven lower
bound. The comparator grants no disclosure permission over either private input.

## Bounds, ownership and use

Each input has passed the existing bounded JSON reader and unchanged frozen
schema through the inspector. Comparison introduces no stream, network, clock,
filesystem or draft mutation. Public collections are immutable copies and the
retained JSON nodes are private copies. Default diagnostic strings contain no
private identifier, amount, assumption or JSON content.

`maxLines` is an explicit local output bound from 1 through 10,000. When the union
of line IDs exceeds it, `completeness` is `LINE_LIMIT_EXCEEDED` and `lines` is
empty, with both complete input records still retained. There is no truncated
line comparison masquerading as a full one. `COMPLETE` describes traversal of
the supplied records, never accepted cost coverage. A later explicit comparison
with a sufficient bound starts cleanly; there is no retained comparator state.

Callers choose the two inspected records explicitly. They must bind asynchronous
UI results to current analysis/lifecycle tickets and independently apply any
display, storage or disclosure policy. This core API supplies no current-draft
or current-price authority.

## Research boundary and remaining delivery

The implementation consumes the delivered Research
[#39](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39) cost decision
at [9a4fa8af](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/9a4fa8afe71136611bdab50b824700777d39397d/research/T6-deployment-operations/cost-estimate.md):
retain source currencies, incompatible comparisons stay not computed, preserve
the historical cost packet, and treat a separately identified current estimate
as a candidate. The current note was rechecked on 2026-10-19 against Research
main [7d5b98ad](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/7d5b98ad53a3c92ac5b4e7c049d3f5197e1f28ec/research/T6-deployment-operations/cost-estimate.md).
Its sole change from the accepted Cost record updates the reference to the
accepted evidence policy; these cost-comparison boundaries are unchanged.
The [frozen pricing gates](../contracts/v1/docs/PRICING_AND_GATES.md) still require
applicable current rates and complete coverage for a matching budget quote.
This work changes no frozen schema, shared rule, usage model or source evidence.

| Literal #116 criterion | This primitive supplies | Remaining acceptance |
| --- | --- | --- |
| Compare like-for-like workloads/currencies, preserve dated price snapshots, show freshness and refresh changes. | Explicit recorded currency/period/Build differences and preservation of both supplied estimate records. | Governed comparable workload and deployment basis; exact approved Pricebook/usage-model packets; freshness policy; durable storage; actual refresh and Android UI. |
| A scenario comparison identifies differing conditions and withholds unsupported savings claims across currencies, periods or workload bases. | Recorded condition and line differences, same-identity conflicts, exact numeric checks and no savings-producing path. | Pricing-semantics and Android receiving acceptance; complete scenario-basis representation and integrated UI behavior. |
| Refreshing prices does not overwrite a previously pinned estimate; the old and new provenance remain inspectable. | Comparing supplied immutable inputs changes neither record. | An actual approved-price refresh workflow, atomic retention of complete old/new packets and failure/recovery integration. |
| Changed rates, expired offers and newly unpriced components are visible in the refresh comparison. | Changed recorded rate IDs/unit prices and explicit unpriced/unclassified changes. | Effective/expiry inputs and policy, actual refresh provenance, user-visible behavior and accepted real-data evidence. |

Native prerequisites #39, #67 and #103 retain their own outstanding acceptance.
The canonical #116 receiving record remains
`docs/delivery/android/work-packages/10.04.md` in `Pick-Your-AI/pickyour.ai`.
This document does not relocate that path, mark a criterion complete, assign an
absent reviewer or establish installed-device, publisher or release evidence.

## Reproduction

Use the pinned JDK21/Gradle environment in [CORE_FOUNDATION.md](CORE_FOUNDATION.md).
The focused suite exercises exact decimals, terminal-newline quantities,
different units/currencies/periods, duplicate meter lines, changed line IDs,
reused identities, coverage transitions, complete retained values, immutable
outputs and bounded traversal/recovery:

```sh
./gradlew --no-daemon --dependency-verification=strict :core:test \
  --tests ai.pickyour.core.pricing.RecordedCostComparisonTest \
  --tests ai.pickyour.core.pricing.CostEstimateAdapterTest
./gradlew --no-daemon --dependency-verification=strict :core:check :core:assemble
```

Run the required frozen-import and Python reference checks from the same core
instructions. Record actual executed counts and the reviewed exact source in the
PR evidence; these commands alone are not proof of a successful run.
