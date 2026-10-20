# Explicit CD1-0.2 answer API — Android #27, partial

The SDK-independent `ai.pickyour.core.evidence.answers` package consumes one
explicitly versioned answer profile and preserves unresolved causes through a
small, caller-supplied rule policy. It is an opt-in implementation increment for
[Android #27](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27).
The existing editor, frozen readers, diagnostic evaluator, evidence badges and
selection behavior do not call this package. Full #27 acceptance remains open.

## Canonical input and location

[Research #403](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/403)
delivered the reviewed CD1-0.2 representation at
[`cebbf59345e01d34b8ad4c20d0b57782f6e05369`](https://github.com/Pick-Your-AI/pick-your-ai-research/commit/cebbf59345e01d34b8ad4c20d0b57782f6e05369),
tree `6e9f196d091fb0fb89443c3fc0e6adcf05607c55`.
The selected executable references are
[attribution](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/cebbf59345e01d34b8ad4c20d0b57782f6e05369/tests/t0_answer_attribution_prototype.py),
[answer states](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/cebbf59345e01d34b8ad4c20d0b57782f6e05369/tests/t0_answer_state_prototype.py)
and the
[known-value schema profile](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/cebbf59345e01d34b8ad4c20d0b57782f6e05369/tests/t0_known_value_schema.py).
Their original imports, both encoding schemas, synthetic attribution input and
the complete containing research note are bound by the eight-path
[source lock](../core/src/test/resources/evidence/answers/cd1-reference-lock.json).
The lock records exact Git blobs, byte lengths and SHA-256 values.

This is a selected research answer representation, not an amendment to frozen
Build, Selection, Catalog, Ruleset or EvaluationReport bytes. A CD1-0.1 document
cannot be read as CD1-0.2, and an omitted assumption flag never becomes false.
Kotlin `:core` is the source location accepted by the
[Android native decision](proposals/02-native-architecture.md). The issue's
intended acceptance record remains
`docs/delivery/android/work-packages/03.05.md` in `Pick-Your-AI/pickyour.ai`;
this API guide does not relocate it or provide whole-issue receiving acceptance.

## Input and retained facts

Call `Cd1AnswerIndex.read(originalBytes)` with the original UTF-8 JSON document:

```json
{
  "x_t0_answer_states": {
    "proposal_version": "cd1-0.2",
    "answers": {
      "/synthetic/memory": {
        "state": "provided",
        "value": "persistent",
        "assumption": false,
        "source": "preset:synthetic-input"
      },
      "/synthetic/budget": { "state": "unknown" }
    }
  }
}
```

The envelope and records have closed shapes. A provided record requires an exact
Boolean `assumption`, an explicit JSON `value`, and optionally a source matching
`preset:[A-Za-z0-9][A-Za-z0-9._-]{0,63}`. Other states carry only `state`.
Source strings are bounded preset attribution; they confer no evidence grade,
publisher verification, independent review or permission to use a source.

| Encoded state | Native result | Value and metadata |
| --- | --- | --- |
| `provided` | `Cd1State.PROVIDED` | Field-admitted JSON value, explicit assumption and optional preset source |
| `unknown` | `Cd1State.UNKNOWN` | No provided value, truth or attribution |
| `missing` | `Cd1State.MISSING` | No provided value, truth or attribution |
| `withheld` | `Cd1State.WITHHELD` | No provided value, truth or attribution |
| `not_applicable` | `Cd1State.NOT_APPLICABLE` | Explicit inapplicability; no provided value, truth or attribution |

JSON null, false, zero, negative zero, empty strings, arrays and objects remain
distinct provided values where the caller's field schema admits them. An absent
answer or missing child of a provided object is `MISSING`. The API does not infer
that an unselected component is unknown, or add `not_selected` as a sixth wire
state. Presence, activation and optional-tier applicability require the separate
canonical selection interface.

The index copies and retains original bytes and their SHA-256. Returned bytes,
schema bytes and provided JSON trees are detached on every access. A caller's
mutation cannot rewrite subsequent reads. Numeric runtime rendering is never
represented as the original document. Rejections contain a typed problem and,
for bounded-input failures, a sanitized existing rejection category; raw values,
schema fragments and third-party exception causes are not exposed.

## Pointers and field admission

Answer paths use non-root RFC6901 string pointers. Root aliases, malformed
escapes, invalid surrogates and overlapping parent/child answer records reject.
Reads use the nearest recorded ancestor. A provided ancestor supports object
and array traversal; absent members and out-of-range canonical indices become
`MISSING`, while malformed indices and scalar traversal reject. An unrecorded
parent is never assembled from independently provided children. A withheld
descendant can explain its unresolved ancestor; the selected witness is the
lexicographically first original pointer by Unicode code point.

Create `KnownAnswerSchema` from the original JSON bytes of the caller's explicit
field schema, then use `index.read(pointer, schema)` or
`index.predicate(pointer, schema) { detachedValue -> ... }`. The callback returns
an explicit `Boolean?`; there is no implicit truthiness conversion. It executes
only after the requested value is provided and admitted. Missing, unresolved and
absent-child results do not compile or inspect an unused field vocabulary.

The supported schema profile follows the selected Draft 2020-12 reference:

- Literal text `unknown` remains ordinary free text when the field admits it.
  A reachable finite `enum` or `const` containing the marker, including nested
  literal values, is rejected as a known-value vocabulary. Existence of a JSON
  member cannot bypass this check. A property named `unknown` is still a name.
- Pure direct `not: {const: ...}` and `not: {enum: ...}` exclusions are permitted.
  Compound or nested negation cannot re-admit the unresolved marker.
- `$ref` is document-local with strict UTF-8 percent decoding, valid `~0`/`~1`
  tokens and supported schema locations. Root `$id` is isolated per field.
  Nested resource identifiers and `$dynamicRef` reject. Pointer jumps inspect
  resource-bearing ancestor schemas; a map entry literally named `$id` is data.
- Reachable schemas govern known-value policy. Unused `$defs` vocabularies and
  annotations do not become evidence. Schema shape validation remains separate.
  Instance `format` is an annotation, matching the pinned reference invocation.
- Eight private value-keyword implementations preserve the selected Python
  integer/binary64 comparison, typed structural equality and `multipleOf`
  behavior. They are installed only in this profile's private schema factory;
  the normal frozen-contract validator retains its own exact-decimal behavior.

### Explicit native budgets and unsupported syntax

These are local client admission limits, not new shared wire limits. Unsupported
input produces a rejection before a predicate runs; callers must not turn a
rejection into a pass, a known false fact or an automatic profile downgrade.

| Boundary | Native limit or behavior |
| --- | --- |
| Original answer document | Existing bounded JSON reader: 1 MiB, depth 32, 100,000 tokens, 16,384-character strings, 256-character member names, 128-character numeric tokens |
| Field schema | 65,536 original bytes; a private Boolean-schema wrapper uses the same bounded reader and is never exported as original input |
| Requested pointer | At most 16,384 UTF-16 units and 256 tokens; record paths also satisfy the reader's member-name limit |
| Schema count keywords | `min/maxLength`, `min/maxItems`, `min/maxProperties`, `min/maxContains` must fit nonnegative signed Int; larger valid reference schemas are unsupported locally, preventing the bundled validator's `intValue` narrowing |
| Regular expressions | At most 256 printable ASCII characters; literals, escaped punctuation, positive ASCII character classes, dot and start/end anchors; at most one quantified atom, requiring a start anchor; explicit finite repetition bounds at most 1,024 |
| Unsupported regex forms | Groups, alternation, backreferences, shorthand/Unicode classes, negated classes, inline flags, repeated quantifiers and non-ASCII pattern source reject; this is a portable subset, not general Python/Java regex equivalence |
| Reference resolution | Only the eight pinned bundled 2020-12 meta-schema resources and document-local references; arbitrary URL, file and classpath retrieval is denied |
| Reference applications | At most 4,096 document-local `$ref` applications per provided-value read, including repeated applications of a cached target; exceeding the limit produces `INVALID_FIELD_SCHEMA` before a predicate runs |

Portable regex/count syntax is checked at actual schema locations, including
unused definitions, but never inside arbitrary annotations. The marker-specific
policy remains lazy and reference-scoped. Progressing local recursive schemas
are supported; a recursive application that cannot progress rejects, preserving
the reference's failure instead of inventing a terminal finding.

The reference-application counter is private to each read's validation context;
independent and concurrent reads do not share it. It is checked before the
referenced validator expands its target, so a compact branching schema cannot
request unbounded repeated reference expansion during one read. Missing and
unresolved values still do not compile or validate an unused field schema.
This is a local work limit, not a bound on total heap use or a change to frozen
schemas, retained input bytes, or the selected research profile.

All original numeric tokens are checked before any read, including unread values.
Plain integers remain exact within the token budget. Float tokens must preserve
the canonical finite binary64/shortest-decimal admission and exact integer value
when integral. Thus `5e-324` is admitted while `4.9e-324` and underflowing nonzero
tokens reject; `9007199254740993` stays an exact integer while
`9007199254740993.0` rejects. Schema float bounds retain the reference's binary64
meaning: an integer above the actual binary value of schema `1e23` cannot pass by
being rounded to that bound. In pinned jsonschema 4.25.1, `0.3` with
`multipleOf: 0.1` rejects and `3` with the same bound passes. These observations
are compatibility facts from the selected reference, not a new numeric policy.

## Typed terminal policy

`Cd1Predicate` retains state, optional Boolean truth, causal pointer and provided
attribution. Negation changes only known Boolean truth; it never lowers an
unresolved state to true. `Cd1Rule` has an explicit compatibility/recommendation
policy, fixed severity and lazy applicability/assertion callbacks. Evaluation
invokes each needed callback once and reports metadata only from the terminal
cause that actually executed.

| Applicable predicate | Compatibility | Recommendation | Assertion invoked? |
| --- | --- | --- | --- |
| `not_applicable` | `skipped` | `skipped` | No |
| `unknown`, `missing`, `withheld` | `unknown` | `skipped` | No |
| Provided with unresolved predicate truth | `unknown` | `unknown` | No |
| Provided false | `skipped` | `skipped` | No |
| Provided true | Continue to assertion | Continue to assertion | Once |

| Assertion after provided-true applicability | Both policies |
| --- | --- |
| `not_applicable` | `skipped` |
| `unknown`, `missing`, `withheld`, or provided unresolved truth | `unknown` |
| Provided true / false | `pass` / `fail` |

Severity is copied independently of outcome. Candidate verdict and evidence
grade are not fields derived by this API. A caller-created `provided` predicate
is an explicit caller assertion, not proof that its source or evidence was
qualified. Application integration must bind its own immutable input snapshot,
adopted field definitions and actual evidence/selection authority before using
such a predicate.

## Fixed reference observations and verification

The [reproduction helper](../scripts/build_answer_profile_fixtures.py) verifies
all eight original byte/Git-blob pins, copies the unmodified executable inputs
into an isolated temporary directory and executes the selected Python 3.12 /
jsonschema 4.25.1 API. It performs no network request. Its check mode reproduces
the exact two committed fixture files; writing requires an explicit `--write`.

Both modes admit the fixed fixture and lock destinations before reference work,
then check the complete pair again before reading or publishing either file.
Existing checkout ancestors must be directories; existing final files must be regular,
singly linked files. Symlinks, hard links and nonregular destinations reject
without following them. Each binary write publishes a complete private sibling
by atomic replacement. A rejected destination or failed generation leaves both
previous files intact. If the second publication itself fails, the first may
already be replaced; the command fails without a success receipt, and the pair
must be regenerated and verified together. This is not a two-file transaction or
a crash-durability guarantee, and assumes no concurrent hostile replacement of
checkout directories. These output checks do not change source admission,
reference observations or the committed fixture bytes.

```sh
# Use an isolated environment with the accepted Research requirements installed.
python scripts/build_answer_profile_fixtures.py --research-root /path/to/pinned/research
./gradlew --no-daemon --dependency-verification=strict :core:check :core:assemble
```

The fixed corpus contains **623 synthetic observations**: 427 reads, of which
167 are admitted and 260 reject, plus all 196 applicability/assertion/policy/
negation combinations. No production T2/T4 evidence is manufactured. Its original
JSON/schema bytes and typed values remain bound per case. The JVM consumer checks
those 623 cases, one exact lock/corpus inventory test and 19 separate boundary
methods: **643 actual direct Kotlin/JVM cases passed**, with no failures, skips
or aborts, in the initial expanded run. The pinned fixture is 449,598 bytes with
SHA-256 `e41334d419c74d0a2d5c5e4dec3b4a3fc441430b2936f9cf99ed98754650ac97`.

The earlier 596-case run retained four actual failures: the default validator
falsely admitted two mixed numeric bounds, one `multipleOf` case and numeric
aliases under `uniqueItems`. The corrected private vocabulary then passed all
596 original cases before the 643-case expansion. Both histories are retained
separately.

The actual local Gradle 8.11.1 offline `:core:check :core:assemble` subsequently
passed in 2m 56s at staged source tree
`05ab83a2d19c29b0855610cb4e12d199b959e366` on accepted Android `30ead65b`.
All 77 original XML suites contain **4,265 unique passing cases**, with no
failures, errors or skipped tests, including every one of the 643 new cases.
All 398 execution input files were rehashed unchanged. The packaged core JAR is
1,019,285 bytes / SHA-256
`34e64fee0c1404d6bd6fbebf6fbb3017d673c546b9665a30d217f583cc743182`.
The existing 260-case conformance receipt also qualified against that actual XML.
The seven Python reference, tamper, ledger, conformance and new-fixture commands
passed with all ten exact Android requirements installed. The first attempts
retain their actual setup failures: a sandbox-denied local Gradle socket, an
incorrect read-only cache directory, and missing format checkers in a different
Python environment. No result from those attempts is counted as a passing gate.

This paragraph is a subsequent documentation-only receipt. Receiving hosted
results still require their own actual checkout and original artifact evidence;
the local JVM/Gradle results supply no Android installation, device, service or
release acceptance.

## Remaining literal #27 acceptance and receiving handoff

| Literal criterion scope | Evidence from this increment | Remaining work within that criterion |
| --- | --- | --- |
| Checked versus independently verified claims, incomplete capability arrays, unsupported/unknown, memory field and typed normalization | Explicit provided/unknown/missing/withheld/not-applicable and assumption/preset input; field-scoped marker rejection; original-byte and type preservation | Adopt and integrate checked/independent source policy, support-list completeness, versioned memory and selection/absence meanings with actual affected-owner review |
| Unknown/unanswered cannot pass merely by existence; unmarked-list omission remains unknown | Callbacks require a provided field-admitted value; missing/unresolved causes survive reads and negation; 196 terminal-policy observations | Adopt the independent requested-set/source-inventory completeness interfaces and their exhaustive/incomplete/optional-tier cases; this API performs no list quantification |
| Evidence grade, candidate verdict, rule outcome and severity independent; accepted rules reference defined fields | Outcome/severity remain separate and causal; no inferred grade or candidate verdict | Bind actual adopted rules to defined, versioned fields and the evidence/selection interfaces; no frozen diagnostic rule is activated here |

Representative T2/T4 provenance review and corrections against the adopted source
policy remain explicit work. The existing Apple presence-only renderer is an
affected-client presentation reference; its absent/null/literal categories do
not establish this answer profile, evidence eligibility or evaluator parity.
Central Research #40/#41 and affected Android/web/iOS receiving review remain
required for full semantic adoption. This document records a concrete partial
native implementation and its limits; it changes no issue criterion, acceptance
ledger, owner, delivery record placement or release gate.
