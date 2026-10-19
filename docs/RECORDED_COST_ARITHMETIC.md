# Recorded cost-line arithmetic

The existing cost inspector now adds a separate observation of each displayed
line's recorded quantity multiplied by its recorded unit price. It keeps the
declared line amount, subtotal, tax, total, method and assumptions unchanged.
This is a bounded contribution to Android [#85](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/85),
used by the actual read-only synthetic cost screen rather than a standalone unused
calculation helper. It does not produce a quote or price the editable draft.

## Selected local profile

`RecordedCostArithmetic` identifies this engineering choice as
`android-recorded-exact-product/1`. The frozen
[pricing guide](../contracts/v1/docs/PRICING_AND_GATES.md) calls for decimal
quantity × unit-price checking under a documented rounding rule. This local
profile performs no rounding at all. It does not adopt the alternatives in
Research [#113](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/113),
which closed without merging, or treat the illustrative rounding of individual
research scenarios as a universal invoice policy.

The boundary takes an existing immutable `InspectedCostLine`. The original
`CostEstimateAdapter` still owns schema/version, Build/Pricebook-ID binding,
currency and declared subtotal/tax consistency. Its rejection ordering and all
frozen inputs remain unchanged. This new observation does not become an adapter
admission requirement and cannot turn a declared record into an accepted quote.

| Observation | Meaning |
| --- | --- |
| `MATCHES_EXACT_PRODUCT` | The declared amount is numerically equal to the unrounded product under this profile. |
| `DIFFERS_FROM_EXACT_PRODUCT` | The declared amount differs from that unrounded product. Billing assumptions, a quote, manual override or rounding can require separate review; this is not a finding that the declaration is invalid. |
| `EXACT_PRODUCT_OUTSIDE_PROFILE` | The exact finite product needs more than 12 fractional digits after computed trailing zeroes are removed. It cannot be emitted as frozen Money without a separate policy; the exact observation remains available. |
| `UNSUPPORTED_QUANTITY` | The recorded quantity is outside the existing strict whole-string decimal subset. The original literal, including a schema-admitted terminal LF, remains unchanged. |
| `DECIMAL_LIMIT_EXCEEDED` | A decimal input or computed output exceeds the local work limit. No partial, rounded or truncated calculation is returned. |

The two operands each permit up to 256 characters and the declared amount permits
up to 512 characters. These limits are checked before new decimal conversion or
comparison. The computed plain-string output is separately limited to 512
characters. They are local work limits, not changes to frozen schema admission.
Every admitted conversion uses exact `BigDecimal`; multiplication uses no
`MathContext`, binary floating-point value or rounding operation. Only the computed
product has trailing zeroes removed. All original operand and declared-amount
lexemes, including their zeroes, remain intact. Comparison uses numeric value,
so `0.02` and `0.0200` agree without making their recorded strings identical.

Recorded quantity is already in the recorded meter's units. For example, a
quantity of `1.5` for `input_tokens_1m` multiplied by a unit price of `2` produces
an unrounded observation of `3`; the inspector does not divide by a million
again. It derives no token count, activity/idle hours, storage conversion, period
normalization, training/egress quantity, billable increment, discount, minimum
commitment or free-tier subtraction. Method and assumption strings remain
recorded evidence and are not decoded as an alternative formula.

An exact zero remains only an arithmetic result. Equality, a declared complete
coverage label or the absence of displayed failures establishes no free/included
treatment, rate applicability, source rights, freshness, tax correctness, meter
coverage, lower bound or budget pass. No Pricebook is loaded and no live vendor
or service is called. Partial and unknown estimates retain their original
meaning, and a quote/manual override is neither rejected nor normalized when
its declared amount differs.

## Actual display boundary

`CostInspectionText` invokes the observation for each of its at most eight
displayed lines. Each line retains its original quantity, unit price, declared
amount, rate/selection identity, meter, method, confidence and assumptions, then
shows its own arithmetic observation. There is no aggregate successful-estimate
badge: matching, differing and uninspected lines can coexist. Existing declared
totals still cover every retained line, including any hidden by the display bound.

Computed products use the existing quoted, direction-isolated, bounded literal
renderer and an explicit currency code. A long product may be visibly truncated
for display, while the typed observation retains its complete bounded result.
This is distinct from truncating a computation or overwriting a declared amount.
Existing declared amounts continue to use the selected screen locale without
rounding. No new navigation, editor mutation, storage operation or asynchronous
ownership path is introduced; `CostInspectionSection` retains its current lifetime
guard and reads the same fixed synthetic assets.

## Verification and remaining receiving work

The ten dedicated Core methods cover exact decimal products, differing scales,
computed trailing-zero normalization, products beyond the frozen money precision,
zero quantities/prices, million-token and idle-GPU-hour units, pre-adjusted
quantities, all four method labels, literal unsupported quantities, both maximal
operands and the full 512-character product, explicit input limits, repeatability,
immutable records and value-free diagnostic strings.

The eight dedicated simulated Android cases use the actual formatter and include
the actual inspector button/section path. They cover locale-preserved declared
values, quote/override differences, unrounded excess precision, unsupported
literal quantities, bounded display, recorded units/zero, and mixed observations
across a truncated line list. Mandatory CI attendance checks require these exact
case identities, the right suite/class, no duplicates and no failures, errors or
skips. Existing CostEstimateAdapter and cost-presentation tests remain unchanged.
Actual execution belongs to the exact source/test receipt on the delivery PR;
declared test inventories alone do not claim an executed result.

The real-price publication and applicable-rate input to
[#66](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/66) remains separate.
Research #39/#64 completion does not supply an accepted rate, usage/invoice policy
or production Pricebook. Full #85 still needs the approved scenario model,
reproducible estimates with its specified bindings, exact upstream receiving and
every literal acceptance criterion. Its canonical acceptance-record placement,
installed-device, assistive-technology and release boundaries are unchanged.
