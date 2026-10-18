# Frozen Ruleset decoding

`FrozenRulesetDecoder` validates one bounded UTF-8 Ruleset document and copies
every frozen field into immutable Kotlin values. The existing generic validator
already supports this document; the new reader exposes typed rules, expressions
and operands without returning a mutable JSON tree.

This is partial implementation for
[Android #21](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/21) and
[#32](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/32). It consumes
unchanged frozen v1 inputs. It does not establish the successor decisions and
affected-consumer adoption required by
[#49](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/49).

## Retained fields and shapes

| Input | Immutable representation |
| --- | --- |
| Ruleset envelope | Exact schema version, ID, ruleset version, publication timestamp string and ordered rules. Only `schema_version: "1.0.0"` is supported. |
| Rule | ID, severity, applicability expression, assertion expression, unknown policy, message, remediation, ordered affected kinds and optional owner. |
| Binary comparison | All ten operators retain distinct left and right operands: `eq`, `ne`, `lt`, `lte`, `gt`, `gte`, `in`, `contains`, `subset_of`, `intersects`. |
| Existence | `exists` retains its single operand. |
| Junction | `all` and `any` retain nonempty ordered argument lists, including duplicate expressions and single-argument lists. |
| Negation and implication | `not` retains its child; `implies` retains the distinct `if` condition and `then` consequence. No flattening or rewriting occurs. |
| Literal operand | An immutable JSON string retains any allowed value: null, booleans, strings, exact numbers, arrays and objects. Expression-shaped and reference-shaped objects remain literal data. |
| Metric operand | All seven frozen metric names are represented by a closed enum. No metric is computed. |
| Reference operand | Closed scope plus original pointer, optional kind, role and selection ID. Optional fields retain absence. No reference is resolved. |

Severity, unknown policy, reference scope and both occurrences of the 29 component
kinds match the frozen schema exactly. The arrays have distinct constraints:
`rules` is nonempty but permits duplicate objects and IDs; `affected_kinds` is
nonempty and unique; junction arguments are nonempty but may repeat. The decoder
never keys rules by ID or silently deduplicates them.

The three operand wrappers and five expression shapes are closed schema variants.
A missing literal field is invalid, while present `literal: null` is valid.
Comparison operators have no schema-defined operand-type constraint, and `exists`
on a literal null is structurally valid. The reader invents no comparison result,
availability verdict or normalization of a literal `"unknown"`.

Reference scope does not conditionally require or forbid kind, role or selection
ID; they may coexist or be absent independently. The pointer pattern checks only
the leading `/`, retaining `/`, `//` and `/~2`. This is not an RFC 6901 validity
assertion or a pointer resolver. Frozen role, selection-ID and general-ID patterns
keep their distinct length/character rules and existing
[terminal-LF behavior](FROZEN_PATTERN_PARITY.md).

Ruleset `version` uses the unchanged frozen regex: leading zeros and its defined
prerelease spelling are permitted, while a `+` build suffix is rejected. A SemVer
library is not substituted. The asserted date-time format is checked while exact
timestamp and offset spelling remain intact. Nonempty message, remediation and
optional owner strings are not trimmed; whitespace remains a supplied value.
Decoding does not assign an owner or authenticate publication.

## Input, immutability and privacy

The decoder reuses the existing
[bounded reader](../core/src/main/kotlin/ai/pickyour/core/importing/BoundedJsonReader.kt)
and [fixed validator](../core/src/main/kotlin/ai/pickyour/core/contracts/FrozenSchemaValidator.kt).
Schema validation precedes typed extraction. The existing depth budget bounds
recursive expressions and literal JSON. Original and normalized representations
pass the same byte, token, string, name, number and depth limits. Dependencies,
frozen bytes and consumer budgets are unchanged.

The full document's `json` and each literal's `json` retain JSON values, including
exact arbitrary-precision numbers, without binary floating-point conversion.
These immutable strings do not retain original number spelling, whitespace,
content hashes or signatures. Numeric normalization beyond the existing
representation limits is rejected. Rules, affected kinds and junction arguments
are copied into unmodifiable lists; collection casts or source mutation cannot
change a decoded value.

Default model and result strings omit supplied IDs, messages, owners, pointers
and literal data. Explicit field or `json` access still reveals supplied content
and requires the receiving caller's privacy/disclosure review.

| Result | Meaning |
| --- | --- |
| `Decoded` | Supported frozen fields were validated and copied; no rule was executed or accepted as domain evidence. |
| `InvalidInput` | An existing sanitized reason reports malformed JSON/UTF-8, unsupported root, trailing content, original/normalized limits or provider IO failure. |
| `UnsupportedVersion` | Schema version is absent, nontextual or different from `1.0.0`; no migration is attempted. |
| `SchemaMismatch` | Required fields, closed variants, types, enums, patterns, formats or array constraints fail; input fragments and validator diagnostics are withheld. |

The caller owns the stream on every outcome and supplies off-UI execution,
timeouts and cancellation. Decoding has no network, storage, UI, publication or
telemetry action. It performs no rule execution, reference lookup, metric
calculation, unknown-state normalization, baseline activation or compatibility
decision. Repeated decoding returns independent immutable representations.

## Shared structural cases

The [explicit synthetic fixture](../core/src/test/resources/contracts/ruleset-cases.json)
contains a small base document and 145 named mutations/source cases with declared
outcomes: 56 structural successes, five unsupported versions and 84 schema
rejections. The Kotlin factory and [Python checker](../scripts/check_ruleset_fixtures.py)
consume the same records. Expectations are explicit rather than generated by the
implementation under test. Four exact frozen inputs are pinned:

| Input | SHA-256 |
| --- | --- |
| `schemas/ruleset.schema.json` | `b251e49774d7e6a34522555adefd40f56f5e647a8a2b4a69a0be0a4618b1a8d3` |
| `schemas/common.schema.json` | `0342ce2f1114e7276fc7f6a08ff5b86e8727caadd83ae4f443de9a7e1431c43c` |
| `examples/ruleset.json` | `05474568204fdbfd407cb4e4cfc9334e22fdf24e75111bed8990868926fab418` |
| `baseline/core-ruleset.json` | `e60e43c0abc3c5d927e10c578a39fdedfa76e00fce6242e2db3a46e4b1da907e` |

The original example and baseline each contain 45 rules; their complete typed
field trees are compared against the unchanged inputs. The baseline is copied
only into test resources, without adding it to the runtime JAR or preview assets.
Every successful case compares all typed fields and recursive shapes, not only
the retained full-document JSON. Seven additional Kotlin methods check exact enum
inventories/all kinds, wide/fractional/exponent literals, order and immutability,
opaque expression-shaped literals, parser limits, caller-owned streams and the
exact accepted/rejected expression-depth boundary.

The Python checker requires the pinned date-time checker to be available. The
hash-first runner still verifies all 43 frozen files and both manifests before
the original reference suite and all added fixture checkers. A finite structural
set does not establish universal language equivalence or executable rule meaning.

Use the existing toolchain in [CORE_FOUNDATION.md](CORE_FOUNDATION.md):

```sh
python scripts/check_contracts.py
./gradlew --no-daemon :core:check :core:assemble
```

Actual execution counts, initial failures/recovery and independent review belong
in the PR receipt at their exact source/composition. Test definitions alone are
not execution evidence. Existing resource, graph, draft, deployment and UI owners
retain their separate routes. Canonical acceptance paths and the ledger are
unchanged: this document closes no work package, approves no ruleset and supplies
no installed-device, physical-device, service or release acceptance.
