# Frozen pricebook candidate screening

Partial implementation for [Android #66](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/66).
`RateScreen` accepts one bounded frozen-v1 Pricebook and an explicit component ID,
version, meter, region, currency and as-of instant. It validates the unmodified
schema, rejects duplicate rate IDs and reversed intervals, and returns exact
candidate IDs with the pricebook ID/revision and received-byte SHA-256. No implicit system clock, exchange
rate, region default, alternative summation or fixture fallback is used.

This is discovery, not accepted rate resolution. Every result contains
`EVIDENCE_REVIEW_REQUIRED`; even a singleton requires policy and provenance review.
No amount, free/included classification, estimate, subtotal or budget verdict is
produced. A zero-length candidate list means no match, never free. Results and
queries redact their values in diagnostic `toString` output.

## Version admission

The stream entry points `RecordedPricebook.read(input)` and
`RateScreen.screen(input, query)` return their respective
`UnsupportedSchemaVersion` result for a present textual `schema_version` that
is not exactly `1.0.0`. Classification follows complete strict bounded parsing
and end-of-input verification. Invalid UTF-8, duplicate keys, malformed or trailing
input, read failure and exceeded parser budgets retain `InvalidDocument`, even
when an unsupported version appeared earlier. Missing, null and nontext versions
also remain invalid documents. No input value or exception is copied into either
result's diagnostic string.

The version distinction does not admit a future schema or interpret its fields.
Supported-version documents still pass the full frozen-schema, exact money,
interval, duplicate-ID and local revision checks. The parsed-object screening
overload is unchanged. Exhaustive consumers of either sealed result must handle
the added rejection case; the Android fixture loader continues to use only a
successful parsed record. `PricebookVersionAdmissionTest` covers both stream
entry points, all six parser budgets, malformed and failed-read precedence,
caller-owned streams and supported-version metadata/candidate behavior.
This completes one bounded part of [Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21);
other API/storage boundaries and that issue's receiving acceptance remain open.

## Conservative boundaries

- Exact component version, meter, currency and region matching; missing/blank
  rate regions remain unresolved rather than being interpreted as global.
- Start time is included and the end time is conservatively excluded. This local
  screening convention does not decide the shared billing interval policy.
- Free-text conditions remain unresolved. Contract and estimated prices remain
  unresolved. Their conditions cannot establish eligibility, discounts or meters.
- Several matching list rates remain ambiguous; no rate is selected automatically.
- Unsupported timestamp precision/range fails closed. Input streams are caller-owned.
- `asOf` filters rate applicability, not historical knowledge: later capture or
  provenance timestamps still require evidence review and do not certify freshness.
- Candidate IDs refer back to the original pinned artifact. The result retains
  its received-byte hash; this checksum and ID/revision do not authenticate a publisher.
  The caller must retain the original artifact and provenance.

`RecordedPricebook` now provides the immutable, validated snapshot shared by
screening and [recorded catalog price context](CATALOG_PRICE_CONTEXT.md). Calling
`screen(pricebook, query)` and `screen(input, query)` preserves the same candidate
boundaries and unresolved gaps. Exact item/version metadata lookup on the book
retains every alternative and never supplies eligibility by itself. The Android
detail view displays fictional recorded metadata without choosing an as-of query,
rate, current quote or total. No amount is added to `RateCandidates`.

On 2026-10-18, [research #39](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/39)
was open, and [research PR #113](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/113)
was an unmerged partial proposal. Its rounding, quantities, GB/GiB, default currency
and meter extensions are not adopted here. Frozen schemas remain unchanged. The
pricebook fixture used in tests is synthetic, with test-local list-price changes;
it is not a market quote or source-use receipt.

## Criterion evidence and remaining work

| #66 criterion | Executable evidence | Remaining acceptance |
| --- | --- | --- |
| Exact versions, meter, region, currency, effective dates and eligibility determine rates | Exact-field and boundary tests; unresolved free-text eligibility is excluded | Governed structured eligibility, approved real pricebook and provenance policy |
| Expired, future, wrong-region/currency and ineligible rates cannot be accepted | Candidate filtering and unresolved-condition tests | Actual estimate integration; no accepted estimate is produced here |
| Reproducible pinned inputs/as-of, no accidental addition of alternatives | Query has explicit time, output retains revision and exact byte hash; alternatives marked ambiguous | Authenticated publication and complete estimate pinning |
| Missing rate means unknown rather than zero | Empty candidates carry `NO_MATCH`; no amount exists in this API | Integrated cost presentation and receiving review |

Tests also cover malformed JSON, schema/version rejection, duplicate identities,
reversed intervals, defensive output immutability and redacted diagnostics.
Run `./gradlew --no-daemon :core:check :core:assemble`, the frozen reference runner
and tamper tests from [CORE_FOUNDATION.md](CORE_FOUNDATION.md).

The canonical acceptance path remains
`docs/delivery/android/work-packages/10.01.md` in `Pick-Your-AI/pickyour.ai`.
This Android-local evidence does not move it or close #66, #85 or #103.
Recorded fixture metadata has a bounded Android presentation; accepted-rate UI,
real-data, installed-device and receiving-policy acceptance remain unexecuted.
