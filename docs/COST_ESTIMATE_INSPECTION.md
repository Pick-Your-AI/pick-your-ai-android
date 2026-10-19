# Frozen cost-estimate receiving guard

Partial Android [#103](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/103) groundwork. `CostEstimateAdapter` inspects an explicitly supplied frozen-v1 BuildSnapshot and CostEstimate, without recomputing prices, performing network requests, or changing either document. The issue and its empty comment thread were refreshed on 2026-10-18. Research [#39](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39) still identifies rounding, unit and receiving-handoff decisions; this implementation does not decide them.

## Receiving checks

Both inputs pass the shared bounded UTF-8 parser and unchanged bundled schemas. Build ID, arbitrary-precision revision and the build's explicit pricebook ID must match. A missing pricebook pin is a failure, never a lookup of latest data. Duplicate selection or line IDs fail before indexing. Referenced priced/unpriced selections must exist and be enabled. A selection may have both a priced meter and an uncovered meter, so membership in both lists is preserved. A declared complete estimate with explicit unpriced selections is rejected as contradictory.

The public `inspect(build: InputStream, estimate: InputStream)` boundary admits
the Build first. Each input must finish the shared strict parse, including EOF,
before version inspection. A present textual `schema_version` other than exact
`1.0.0` returns `UNSUPPORTED_BUILD_SCHEMA_VERSION` or
`UNSUPPORTED_ESTIMATE_SCHEMA_VERSION` for that input. These value-free diagnostics
identify an unsupported version; they do not certify its remaining fields or
perform migration. The two codes follow the nine original enum entries, whose
positions remain unchanged.

Missing, null or nontext version fields still return `INVALID_BUILD` or
`INVALID_ESTIMATE`, as do malformed JSON, duplicate keys, invalid UTF-8, trailing
content, provider read failures and exceeded parser budgets. A Build parse,
version or schema rejection leaves the estimate stream unread. Both record
admissions still precede the existing pricebook-pin, identity, coverage, currency
and exact-money checks; those checks retain their original order. Neither input
is modified or closed. The generic frozen validator and other adapters retain
their existing APIs.

All line unit-price/amount and summary money currencies must match the estimate's declared currency. No conversion or default currency is supplied. The already-recorded line amounts must sum exactly to the recorded subtotal, and subtotal plus recorded tax must equal recorded total. These are decimal consistency checks, not new quantity, billing or rounding semantics. Different decimal scales are numerically equivalent; original decimal strings remain available. No amount is recalculated from quantity times unit price: applicability, discounts, quote/manual-override semantics and rounding remain upstream responsibilities.

Every money node must also satisfy the existing whole-string `ContractMoney` and
`ContractDecimal` boundary before arithmetic or immutable result construction.
A terminal LF can satisfy a frozen schema pattern while remaining outside that
typed-money subset. Such money now returns `INVALID_ESTIMATE` without an escaping
exception, trimming or coercion. Exact currency consistency retains its earlier
`MIXED_CURRENCY` precedence. Quantity remains a recorded string and is not subject
to this money preflight. The shared [frozen-pattern cases](FROZEN_PATTERN_PARITY.md)
document that distinction and preserve the reference schema outcomes.

An inspected result owns immutable values and a serialized snapshot containing every original line, quantity basis, rate ID, assumption, warning and decimal string. This private material is not a public export; callers must apply disclosure policy. Default diagnostic strings do not print its content. Caller streams remain open.

## Safe interpretation

`declaredCoverage` records what the producer said; it is not accepted coverage. `reportedSubtotal`, `reportedTax` and `reportedTotal` deliberately remain reported values. For partial/unknown coverage, the reported total is not a complete stack price or a proven lower bound. No omitted selection is assigned a zero amount. Explicit reported zero is preserved, but does not prove free/included billing.

`unclassifiedActiveSelectionIds` exposes enabled selections absent from both priced lines and the producer's unpriced list. This is not an assertion that all of them need a separate charge: an accepted model may explain inclusion. Until that explanation exists, absence is not proof of inclusion. The frozen synthetic complete fixture deliberately leaves such selections unclassified and retains its original warnings.

Freshness, free/included treatment and budget pass are always `NOT_ESTABLISHED`, even for declared complete coverage. There is no pass-producing API. The adapter does not load a Pricebook or validate rate/component versions, effective dates, regions, eligibility, total meter coverage, source-use rights or real vendor evidence. It does not synthesize pricebook revision pins missing from frozen v1 or accept the fixture as production evidence. A timestamp is retained without inventing a freshness threshold. The summary does not interpret unsupported ranges or infer uncertainty bounds.

This synchronous check binds the supplied records only. Async/UI consumers must use current `AnalysisCoordinator` tickets and invalidate results on any input change; calling the inspector does not establish that the supplied build is still the user's current draft.

The fixed synthetic Android inspector now uses the bounded, locale-aware
[cost presentation adapter](COST_PRESENTATION.md). It keeps canonical decimals,
explicit currencies, declared coverage and missing classifications while escaping
literal text and disclosing display counts. Its simulated checks remain separate
from current-draft pricing, accepted real rates and installed-device evidence.

## Criterion coverage and remaining acceptance

| Exact #103 criterion | Evidence | Remaining gap |
| --- | --- | --- |
| Known subtotal, missing items, ranges and assumptions visible; unknown never becomes zero or a complete total. Partial, stale or mixed-currency evidence cannot falsely pass a budget gate; free, included and unknown are distinct. | Recorded amounts/assumptions retained, missing classifications explicit, mixed currency rejected, no budget pass emitted | Android presentation, accepted ranges and free/included evidence, freshness and actual gate integration |
| A partially priced stack displays a known subtotal under stated assumptions plus unpriced components, without presenting a complete total or assigning zero to missing items. Call it a lower bound only when the approved pricing model proves that interpretation. | Partial/unknown coverage and unpriced IDs retained; overlap with priced meters preserved; no lower-bound inference | Actual UI wording/render tests and accepted usage model |
| Free/included treatment follows accepted evidence policy and cannot silently mask an unknown billing classification. | Explicit zero remains reported; free/included treatment always unestablished | Reviewed evidence-backed billing classification |
| The summary and detailed breakdown agree on currency, period, coverage and current revision, including stale/error states. | Same immutable recorded input, exact record binding, currency and declared-sum checks | Current UI ticket integration, rate freshness and rendering |

Tests cover complete/partial/unknown fixtures, explicit zero and absent classifications, mixed currencies, exact decimals, inconsistent totals, duplicate/disabled/unknown IDs, huge revisions, missing/mismatched pins, malformed/oversized schemas, immutable outputs and caller-stream ownership. No Android UI/emulator/device result or complete cost estimate is claimed.

The focused cost suite also covers old and future textual versions for both
inputs, missing/nontext fields, complete-parse and EOF precedence, each bounded
input failure, Build-first stream consumption, semantic-check ordering and
supported records exactly at the existing byte budget. Its original 20 case
identities remain, with the former generic future-version expectation updated
to the explicit estimate code. This supplies one bounded portion of Android
[#21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21); the broader
API/storage/import, consumer-receiving and pricing criteria remain separate.

Run the checks in [CORE_FOUNDATION.md](CORE_FOUNDATION.md). Frozen inputs are [CostEstimate](../contracts/v1/schemas/cost-estimate.schema.json) and [BuildSnapshot](../contracts/v1/schemas/build.schema.json). Existing [rate screening](RATE_SCREENING.md) remains a separate conservative discovery primitive; neither API promotes the other's outputs to accepted rates. Canonical receiving record remains `docs/delivery/android/work-packages/10.03.md` in `Pick-Your-AI/pickyour.ai`. No criterion or issue is closed by this patch.
