# Frozen support-rule mechanics

This opt-in Kotlin API reads the two explicitly selected frozen support assertions and applies caller-qualified membership mechanics. It is a partial implementation for [Android #27](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27). It does not enter the existing diagnostic evaluator, preview, retained-store flow or a provider runtime.

This is a fresh implementation made on 2026-10-20. The unpublished Oct 19 c8/4dc source was lost with its transient workspace; its historical hashes and execution are not evidence for these new files.

## Exact source and rule boundary

The canonical input is accepted Research [`05ec3efec6b29316eef9bc036c42678e4d225ce7`](https://github.com/Pick-Your-AI/pick-your-ai-research/commit/05ec3efec6b29316eef9bc036c42678e4d225ce7). The [12,067-byte prototype](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/05ec3efec6b29316eef9bc036c42678e4d225ce7/tests/frozen_support_rule_adapter_prototype.py) has SHA-256 `8c91e817767bcbe855b34acf9e5b5461bef250cda211b4840c0c71840b18b643` and profile `cd3-frozen-support-rules-0.1`. The generator fixes all 30 original Python, schema, registry, example, rule and requirement inputs by Git blob, byte count and SHA-256.

| Identity | Exact selected input |
| --- | --- |
| Baseline Ruleset | `e60e43c0abc3c5d927e10c578a39fdedfa76e00fce6242e2db3a46e4b1da907e` |
| Frozen operand reader | `092c8a99495f9b5ae960df40b27e33f15eefe6d1a7cd80119658fdc79ada1a5a` |
| Native membership dependency | Signed `dc9201e8d86fdbf264ada7e926e0ec39d70abc93`, tree `92ba52302422556adda547245c9b0d850a6cd3e3`; its normal current-main receiver is [#675](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/675) |
| Fixture container | `android-frozen-support-rules/0.1`; a test format, with no new product carrier |

The constructor takes original Build, CatalogSnapshot and exact baseline Ruleset byte arrays. It copies all three, checks the whole Build and CatalogSnapshot shapes, rejects duplicate selection IDs and duplicate kind/role identities across enabled and disabled selections, and rejects duplicate catalog item IDs even when versions differ. These checks run before every inactive or uncertain rule shortcut. It does not decide the whole Build's Ruleset lock or look up a latest catalog version.

| Selected rule | Requested value | Actual support operand |
| --- | --- | --- |
| `rule.engine.model.format` | Selected model item's `/spec/model_formats` array | Selected inference engine item's `/spec/model_formats` array |
| `rule.precision.engine` | Selected model's `/settings/precision` scalar | Selected inference engine item's `/spec/supported_precisions` array |

Both rules retain the original declaration, severity, applicability operands and reference pointers. The adapter reads the enabled exact selection and exact catalog ID/version/kind. Missing selections, items, versions or properties retain a typed unresolved observation and its reason; their absence does not supply a default.

## Caller interface

Production sources are in `ai.pickyour.core.evidence.rules`. `FrozenSupportRuleAdapter` exposes `supportScope(ruleId)`, `evaluate(ruleId, evidence, eligibleInputs, cohortCheckedOnly)` and detached `originalBytes`. Only the two IDs in `FrozenSupportRule` are admitted.

`RuleEvidence` contains `cohortId`, `cohortVersion`, optional exact `RuleSupportScope`, nullable `unsupported`, nullable `exhaustive` and nullable `requestedComplete`. It copies the supplied set. A null negative inventory is unresolved; an explicitly empty set records no negative claims. Neither is silently converted into the other.

`RuleSupportScope` binds all of the following:

- The exact selected engine identity.
- The SHA-256 of the original CatalogSnapshot bytes.
- The exact catalog item ID, version and kind.
- The actual support-property JSON pointer.
- The SHA-256 of the original Build bytes as the conditions identity.

The observed scope can contain text accepted by the older frozen schema. A proposed evidence scope must additionally satisfy the normalized evidence profile. In particular, a legacy frozen ID with terminal LF is retained as observed text, but cannot become normalized evidence merely by being read. Raw whitespace or member-order changes alter the original-byte hashes and therefore the scope.

`eligibleInputs` has four independent nullable Boolean entries. Omission is unresolved, an extra key is rejected, and erased Java/Kotlin callers cannot pass numbers or strings as Boolean qualifications.

| Qualification | Meaning supplied by the caller |
| --- | --- |
| `applies_when` | The actual applicability inputs have been admitted for this evaluation |
| `requested` | The requested scalar or inventory has been admitted |
| `requested_complete` | The caller has admitted the researched array's completeness claim |
| `cohort` | This exact support cohort is eligible for this evaluation |

`cohortCheckedOnly` is a separate nullable provenance qualification. A source name, matching digest, version label, literal string `verified` or a positive membership result establishes none of these qualifications. The API has no network access or automatic source/date/rank policy.

Example, after the caller has independently established every supplied fact:

```kotlin
val adapter = FrozenSupportRuleAdapter(buildBytes, catalogBytes, baselineRuleBytes)
val rule = FrozenSupportRule.MODEL_FORMAT.id
val evidence = RuleEvidence(
    cohortId = "cohort.reviewed.example",
    cohortVersion = "0.1.0",
    scope = adapter.supportScope(rule),
    unsupported = emptySet(),
    exhaustive = false,
    requestedComplete = false,
)
val result = adapter.evaluate(
    rule, evidence,
    eligibleInputs = mapOf(
        "applies_when" to true, "requested" to true,
        "requested_complete" to true, "cohort" to true,
    ),
    cohortCheckedOnly = true,
)
```

The example's Boolean facts are caller assertions for illustration, not a recommendation to infer them from the input files. Omitting those facts in a real call produces the appropriate unresolved boundary.

## Decision order and completeness

Evaluation follows the pinned prototype's causal order: catalog snapshot identity, applicability qualification, applicability value, support resolution, exact scope, requested qualification, requested presence, cohort qualification, explicit negative inventory, then the existing [support membership reducer](SUPPORT_MEMBERSHIP.md). A known false applicability operand dominates an unresolved one. The resulting `SKIPPED/not_applicable` is a rule outcome, not an independent evidence grade.

The selected precision is a scalar. When it is present and its request is qualified, its one-element requested set is complete. The model-format array is a researched inventory: completeness is effective only when its separate qualification is true and the caller supplies the nullable completeness value. The observation records whether completeness came from an admitted scalar, a caller-qualified inventory or remains unresolved.

The two assertions use `ANY`. An eligible positive witness can establish the selected result without treating every omitted capability as unsupported. Negative claims, exhaustive coverage and known-complete requested sets retain the reducer's independent meaning. Contradictions keep disputed findings; a checked-only cohort carries `checked_only_unverified` when the reducer actually uses it. No independent-verification grade is manufactured.

`FrozenSupportResult` exposes an immutable decision, the exact observed scope and a fresh detached `details()` tree. The comparison tree includes every original rule, raw input hash, operand observation, source path, scope, caller fact, completeness basis, per-value finding, dispute and label. It is the selected prototype's observation vocabulary, not a successor EvaluationReport or wire schema.

## Numeric, text and format admission

The private input reader uses strict UTF-8, rejects duplicate keys, trailing documents, nonfinite numbers and unpaired surrogate text, and preserves every original input byte. Floating tokens use the prototype's finite binary64 read view; integer tokens retain arbitrary-precision integer values within the local token limit. Thus rounding and underflow such as `4.9e-324`, `1e-999999` and `2.0000000000000001` follow the selected reference before schema bounds are checked. The raw top-level revision must remain a JSON integer: `1.0` and `1e0` are rejected even when mathematically integral.

This differs from the stricter CD1 answer-number admission. It does not reuse that stronger guard or relabel one profile as the other. The private schema view compares a floating value's exact binary value against integer bounds; the operand view and original tokens remain separate.

Native admission is an explicitly bounded subset of the minimal reference environment. The local limits are 1 MiB per input, nesting depth 32, 100,000 tokens, 16,384 UTF-16 code units per string, 256 per property name and 128 original numeric-token characters. Native schema format assertions also remain enabled.

As already documented in accepted [Research workflow guidance](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/05ec3efec6b29316eef9bc036c42678e4d225ce7/docs/RESEARCH_WORKFLOW.md#validation), the pinned requirements omit the optional `date-time` checker. The actual Python 3.12.14 environment used here satisfies all seven exact requirement versions and confirms that documented boundary. A separately retained four-call probe admitted an otherwise valid Build with `2026-02-30T00:00:00Z`, `not-a-date` and impossible date/time fields; it also admitted the valid control. The initial producer stopped because I had incorrectly expected that invalid-calendar input to be denied by the minimal reference environment. That failed expectation and its original log are preserved; it is not a Research regression. The common comparison corpus excludes this admission disagreement, and a native-only control proves Android still rejects the invalid calendar. No central schema, dependency policy or canonical semantics were silently changed to obtain parity.

## Reproducible evidence

`scripts/build_frozen_support_rule_fixtures.py --research-root <existing-research-clone>` reads only the exact pinned Git objects, verifies every original byte/hash and the exact seven installed requirement versions, checks the local static import closure, executes a private copy, and verifies it remains unchanged. It does not install dependencies, read a moving worktree as reference input or import unpinned local project modules. Default mode compares the complete retained fixture; only explicit `--write` replaces the deterministic gzip.

The comparison corpus retains original inputs in a deduplicated byte store and each invocation's canonical JSON/hash. Expected values come from the pinned Python adapter. The JVM test compares the complete observation, not only a Boolean verdict. Cases cover independent eligibility/completeness grids, positive/negative/disputed support, disabled identities, known-false applicability, exact item version/kind, every scope dimension, original-byte changes, malformed inputs, raw revision types, finite binary64 edge cases and legacy observed-versus-proposed identities.

The native boundary suite additionally checks detached bytes/collections/observations, the exact Ruleset and rule-ID boundary, representation limits before schema validation, finite-number behavior and the explicit stricter calendar admission. Existing production consumers are unchanged.

Execution receipts are recorded below only after their original logs, XML identities and exact source inputs have been qualified. Local JVM results do not establish installed Android behavior, shared field adoption or default evaluator activation.

## Actual local execution — 2026-10-20

The source executed by the final gate is local commit `387cfc1bf53803907d06dab94ba7978e5331ec3b`, tree `f7813ea6ccf95377648cfa2cdb55beaed1ea0762`. Its explicit baseline is the ordinary local union of accepted `13a387b1` and signed membership donor `dc9201e8`; the final original qualification matches all **766 source leaves** and their actual input hashes. This later receipt paragraph changes only documentation.

| Actual execution | Exact result and original receipt |
| --- | --- |
| Final canonical production and complete reproduction | **1,577** unique cases, **109** raw documents, **55** constructor rejections and **52** invocation/normalization rejections; all **30** source pins match. The default no-write reproduction ran 05:16:13.060197–05:16:19.403384 UTC and preserved every source byte. Original 310-byte log SHA-256 `41d8c7b0c051f62b48ced53711d8d6d63cea4e234e7a87bf93b349e287326afa`. |
| Canonical fixture bytes | 10,366,633 uncompressed bytes, SHA-256 `94cd79a1a0bb160e2cb151ea4b7fb8fad5517cb8ebd3ef7f786bdc0d4472fd47`; deterministic gzip 356,427 bytes, SHA-256 `865ba86d7ed78577972932aafb4e8e28205202c7e633bc739c49890601f160a0`. |
| Complete `:core:check :core:assemble` | **9,713 unique passing cases in 131 original XML suites**, zero failures/errors/skips/duplicate identities, including the exact **1,577** corpus IDs and **seven** source-declared native boundary methods. Actual 05:16:11.791823–05:17:31.646953 UTC, 79.882 seconds including build; Gradle reports three executed and three up-to-date actionable tasks. Original 1,876-byte log SHA-256 `154e0c18d4b9bf37148c4ba9afc6936d0db80f2996a105256039e49cca73fa71`. |
| Actual assembled Core JAR | 1,868,211 bytes, SHA-256 `2598ea87dab45fd887ead2477707e44144596681064d541c215b4f316fcdbf1e`; every member CRC passes and the new rule classes are present. |
| Retained original XML/JAR archive | 1,900,725 bytes, SHA-256 `78b2908c92262ba17914a6bcae44818e1da7c9f7a8ee7d38ea97541735add205`; all 132 members pass CRC and byte/hash qualification. |

The local run uses existing Temurin JDK 21.0.12.1, Gradle 8.11.1 and an owned cache. A transport-only local repository view exposes the already verified Maven/Gradle metadata bytes; project dependency locks and strict checksum verification remain enabled. No project repository configuration, lock, dependency or shared cache was changed.

Earlier receipts remain distinct. The initial 1,565-case corpus plus seven native methods passed before the added twelve exact item-kind observations. A sandbox attempt stopped before compilation on the required local daemon socket; the first isolated attempt stopped at dependency configuration because the local mirror view did not advertise its pinned Gradle module metadata. Correcting that owned transport view, without changing project source or dependencies, enabled the actual first passing run. The initial date-format expectation failure is described above. None is relabeled as a product test pass or omitted from the history.

These are actual local core JVM and reference results. This receipt makes no hosted, installed-device, independent source-verification, whole-build evaluator or issue-completion claim.

## Literal issue disposition

All three literal [#27 acceptance criteria](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27) remain **partial**:

| Criterion | Contribution of this slice | Remaining receiving boundary |
| --- | --- | --- |
| Reconcile evidence meanings, incomplete arrays, unsupported/unknown, memory field and typed answer states | Two named assertions consume explicit normalized membership facts and exact frozen inputs | This slice supplies no memory-field disposition, full typed-answer adoption, representative T2/T4 provenance review or whole evidence-policy acceptance |
| Unknown or unanswered cannot pass through existence; unmarked omission stays unknown | Full shape admission and explicit qualification/completeness gates retain unresolved operands and incomplete omission | It is an opt-in mechanics API; its accepted fields/rule bindings and actual consumer adoption remain separate |
| Grade, candidate verdict, outcome and severity stay independent; accepted rules use defined fields | Original rule/severity and per-rule decision/provenance are retained separately; only existing frozen operands are read | Central and affected-consumer acceptance of the scoped normalized profile remains required; no new candidate verdict or shared carrier is supplied |

Further field/version work stays with the canonical Research #40/#41/#42 owners and affected Android/web/Apple receiving roles. This document supplies neither a memory-rule replacement nor a global evaluator release.
