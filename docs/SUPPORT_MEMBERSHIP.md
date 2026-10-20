# Normalized support membership

This preparation contributes to [Android #27](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27).
The pure Kotlin API in `ai.pickyour.core.evidence.membership` evaluates one
already qualified support-evidence cohort. Research
[#40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40) retains
compatibility and versioned rule ownership; Research
[#41](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/41) retains
source qualification and evidence ordering.

## Scope and inputs

Call `SupportMembership.evaluate(requested, evidence, requirement, applicable,
requestedComplete)`. `MembershipRequirement.ANY` is the default. Applicability
defaults to true; requested completeness remains unknown by default.

`SupportEvidence` records an exact `sourceId`, nullable `eligible`, nullable
`supported` set, nullable `exhaustive`, explicit `unsupported` set and separately
qualified nullable `checkedOnly`. Source identity and list presence cannot
establish any of those facts. Unknown positive inventory is null; a known empty
inventory is an empty set. Unknown requested values and a known empty requested
set also remain distinct. Every Boolean fact is a Kotlin `Boolean?`; strings and
numeric markers are not accepted by this typed API.

The caller must first resolve exact source, version, configuration, conditions,
scope, rights and authority. Opposite statements supplied here represent claims
still equally ranked after that review. This reducer does not perform source
admission, combine independent support dimensions, authenticate provenance or
resolve a catalog query. A literal value already qualified by the caller remains
literal; this API is not a raw answer reader or an unknown-marker classifier.

## Member findings and aggregate outcomes

| Qualified member evidence | Finding |
| --- | --- |
| Both positive and explicit negative | Unknown, `opposite_equal_rank_claims`, disputed |
| Positive without an opposite claim | Pass, `documented_member` |
| Explicit negative without an opposite claim | Fail, `explicit_unsupported` |
| Missing positive list, with no relevant negative | Unknown, `support_list_missing` |
| Absent from a known exhaustive list | Fail, `exhaustive_absence` |
| Absent from a known list without qualified exhaustiveness | Unknown, `unmarked_absence` |

ANY passes with one positive witness; ALL fails with one negative counterexample.
An all-negative ANY result and an all-positive ALL result require separately
qualified `requestedComplete == true`. Otherwise their aggregate outcome is
unknown, with `requested_set_incomplete` for false or
`requested_completeness_unknown` for null. Member findings and disputes remain
visible even when aggregate completeness is unresolved. A dispute on one
alternative remains recorded when a different undisputed alternative passes ANY.

Known irrelevant applicability yields skipped. Unknown applicability, unknown
requested values, an empty requested set and unqualified evidence yield their
distinct unknown reasons, with no member findings or evidence label. A complete
empty set remains unknown. Malformed requested text is rejected before these
shortcuts.

The only evidence label this reducer can emit is
`MembershipEvidenceLabel.CHECKED_ONLY_UNVERIFIED`, encoded as `unverified`.
It requires separately established `checkedOnly == true` and actual use of
cohort evidence. A missing list with no relevant negative claim supplies no
evidence to label. A missing label does not establish verification. Eligibility,
a passing outcome, source names such as `verified`, and exhaustive lists do
not grant a stronger evidence grade.

## Native value ownership and text

All input sets are copied. Exposed evidence sets and decision findings reject
mutation, including through JVM mutable-collection casts. Findings retain exact
values and are ordered by Unicode scalar value to match Python, including
supplementary characters whose order differs under JVM UTF-16 comparison.
Case, spacing and canonically equivalent Unicode spellings are not rewritten.
Automatic model text omits source and member values.

Native inputs require well-formed Unicode scalar sequences. Unpaired UTF-16
surrogates and foreign raw-collection non-string values are rejected with fixed
messages. This is explicit JVM input admission; the parity claim does not
include Python strings containing lone surrogates or arbitrary non-string
runtime values outside the normalized cohort.

Blank requested members follow the exact Python 3.12 whitespace predicate.
Kotlin `isBlank` is not equivalent, so the implementation keeps the explicit
29-code-point inventory. Zero-width space and the byte-order-mark character are
retained as literal values when an upstream qualified caller supplies them;
they are not silently trimmed or converted to unknown.

## Pinned reference and reproduction

The fixture references accepted Research
[`ec29b802ff1a3c8a2f1e89384a0c4dc9b89c8a60`](https://github.com/Pick-Your-AI/pick-your-ai-research/commit/ec29b802ff1a3c8a2f1e89384a0c4dc9b89c8a60).
Its manifest binds five exact Git blobs, byte counts and SHA-256 digests: the
membership prototype, its tests, evidence-label tests, component compatibility
note and catalog evidence policy. The 6,146-byte executable prototype has blob
`4406ccc9de150aa2955abce6173025c350e17da2` and SHA-256
`a2120d738889253b5bf558827abc22c4de0be99dc6caa3ebee89551e4817a185`.

The generator reads those immutable objects from an existing local Research
checkout and executes only the exact reviewed standard-library reducer. It does
not fetch current sources or derive expected outcomes from the Kotlin code.
The checked-in fixture uses gzip to retain the complete input without redundant
storage. Verification compares the entire decompressed canonical JSON, so a
compression-library difference cannot alter the reference semantics.
Each of 1,530 synthetic cases contains exact ASCII JSON input bytes, an input
digest and the Python outcome. The cases include 1,080 evidence combinations,
324 applicability/eligibility/request shortcuts, 90 invalid blank requests,
18 Unicode/literal ordering cases and 18 source/provenance identity controls.
Kotlin checks every outcome, reason, exact source, ordered member finding,
dispute and label against that reference.

```sh
python3.12 scripts/build_support_membership_fixtures.py --research-root /path/to/research
./gradlew --no-daemon --offline --dependency-verification=strict :core:check :core:assemble
```

Only the explicit `--write` option regenerates the fixture. Ordinary reference
verification compares the complete canonical bytes. JVM tests additionally
cover defensive ownership, default arguments, raw-collection rejection, Unicode
admission, literal order and absence of automatic text disclosure. Test source
and reproduction commands are not execution receipts; actual source, environment,
results and original failures belong in the reviewed PR's validation record.

## Remaining acceptance

This preparation introduces no CD-1 answer carrier, shared wire migration, query
binding, source adapter, evaluator activation, UI behavior, memory rule, severity
policy or native-device qualification. The frozen v1 contract and existing
diagnostic interpreter retain their own semantics. Support membership does not
prove joint architecture/backend/quantization support, runtime fit, residency,
execution or independent verification.

Android #27's three complete acceptance criteria remain partial until their
integrated adapter, evidence fixtures and affected-consumer reviews are supplied.
This file and its synthetic tests do not close that issue or establish named
owner acceptance.
