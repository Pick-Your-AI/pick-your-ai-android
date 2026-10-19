# Provisional finding guidance

Refs #101. The local `android-local-finding-guidance/1` presentation policy
separates a reported missing required choice from a reported component conflict.
It adds a fixed guidance line to the existing fixture Findings detail and saved
snapshot inspection. The original outcome, severity, report JSON, result and
readiness remain unchanged and visible. No failure becomes a pass.

The frozen [Ruleset schema](../contracts/v1/schemas/ruleset.schema.json) has no
presence/compatibility category. These labels are explicitly **provisional local
presentation**, as permitted by #101. They are not new shared metadata, a revised
evaluator, independent proof of correct evaluation, or a compatibility verdict
for a whole Build or alternative candidate.

## Exact source profiles

The entire admitted Ruleset must match one of these two immutable profiles:

| Identity, version and schema | Source | Whole canonical SHA-256 |
| --- | --- | --- |
| `rules.core.1`, `1.0.0`, `1.0.0` | [canonical baseline](../contracts/v1/baseline/core-ruleset.json), blob `d768d7121e1e29519da5ee25823e0e85ad3dcb07` | `1de9dc005b5012fdda5e0e5fc303a8ef2626a81098e314bfd8e68156ce86b0ff` |
| `rules.fixture.1`, `1.0.0`, `1.0.0` | [packaged example](../contracts/v1/examples/ruleset.json), blob `913f91513e02341b2757db67893387d2bcb61174` | `f01bab08fb7fc62eeafe5883180cabaf5695c52a8c835c7f1d0f80957bd51d80` |

All 45 complete rules and every other field are identical between these profiles;
only the top-level ID differs. Each identity has its own complete digest. Runtime
code does not rename arbitrary IDs or accept a matching subset of rules.
Canonical hashing uses UTF-8 JSON with sorted object keys and no extra whitespace.
Array order remains significant. Floating numeric nodes retain a floating marker,
so raw `1.0` or `1e0` cannot impersonate a pinned integer `1`.

An altered assertion, message, severity, order, publication date, version or
unrecognized identity leaves every finding unclassified. There is no rule-prefix,
message-text, severity-only or generalized expression classifier.

## Admission and presentation

The existing `ReferenceReportAdapter` first completes its schema, binding,
rule/finding order, affected-ID, severity, count, result and readiness checks.
Only its successful return captures the immutable facet, using the **same admitted
Build**. No current editor state, mutable JSON, private notes, settings or owner
payload enters the view. A receiver's acceptance still proves consistency, not
that the supplied report was evaluated correctly.

The retained-evaluation source descriptor binds this reviewed adapter's exact
SHA-256, `d39e266490ffb7814b0bb2cc05a108d60206f036a3be7beb6c340b29723fa525`.
Its existing request digest therefore changes with the receiver source. The
profile ID, reference time, evaluator/reference/schema pins, canonical report
bytes and golden cases are unchanged. Earlier source descriptors and focused
results retain their original identities; the updated descriptor requires its
own source-pin and build verification.

The fixed selection role is `primary`. Only enabled selections count as present.
Any repeated selection ID, including a disabled duplicate, withholds all guidance.
Multiple enabled selections in a referenced kind/role withhold that finding's
guidance. A fallback role never substitutes for a primary choice.

For an exact recognized profile and an actual canonical `fail`:

1. If a listed required primary choice is absent, show
   **Guidance: Incomplete (provisional)**.
2. Otherwise, if the row lists fit choices and all are present and unambiguous,
   show **Guidance: Reported incompatibility (provisional)**.
3. All other cases have no guidance line. In particular, pass, unknown and skipped
   findings never acquire either label.

A failed conditional optional-tier rule is considered only in its own reported
scope. This policy does not make T3–T5 choices globally required, invent an
activation, or reevaluate the rule's condition. It does not classify cost,
capacity, license, governance or regional evidence as component compatibility.

## Finite mapping

The following is the complete 45-row policy. There are 12 required-choice rows,
28 component-fit rows and 10 fully unclassified rows. The first two inventories
**overlap**; they must not be summed as distinct rules. Every listed role is
`primary`; an em dash means that facet is unregistered.

| Rule | Required choices | Fit choices |
| --- | --- | --- |
| `rule.required.foundation` | workload, deployment_mode, model, context_policy | — |
| `rule.model.provider.match` | — | model, model_provider |
| `rule.model.context.fit` | — | — |
| `rule.model.output.limit` | — | model, context_policy |
| `rule.selfhost.requires.infrastructure` | hosting_platform, accelerator, compute_profile, inference_engine | — |
| `rule.selfhost.requires.weights` | — | deployment_mode, model |
| `rule.engine.model.format` | — | model, inference_engine |
| `rule.engine.accelerator.backend` | — | accelerator, inference_engine |
| `rule.precision.engine` | — | inference_engine, model |
| `rule.precision.accelerator` | — | accelerator, model |
| `rule.vram.estimate.fit` | — | — |
| `rule.api.model.access` | — | deployment_mode, model |
| `rule.host.region` | — | — |
| `rule.embedding.dimension` | — | embedding_model, vector_database |
| `rule.vector.dimension.support` | — | vector_database |
| `rule.vector.distance.support` | — | vector_database |
| `rule.embedding.distance.support` | — | embedding_model, vector_database |
| `rule.rag.knowledge` | knowledge_base | — |
| `rule.rag.dense.dependencies` | embedding_model, vector_database | — |
| `rule.agent.tools.framework` | agent_framework | tool, agent_framework |
| `rule.agent.mcp.framework` | agent_framework | mcp_server, agent_framework |
| `rule.memory.vector.storage` | vector_database | — |
| `rule.tool.mutation.guardrails` | guardrail | tool, guardrail |
| `rule.training.model.capable` | training_framework, training_compute, training_dataset | model, training_framework, training_compute, training_dataset |
| `rule.training.dataset.governance` | — | — |
| `rule.training.base.lineage` | — | fine_tuned_model, model |
| `rule.training.method.support` | — | training_framework |
| `rule.training.backend.support` | — | training_framework, accelerator |
| `rule.restricted.monitoring.redaction` | monitoring_stack | — |
| `rule.production.gates` | evaluation_suite, monitoring_stack, guardrail | — |
| `rule.budget.monthly.cap` | — | — |
| `rule.compute.sku.accelerator` | — | compute_profile, accelerator |
| `rule.compute.sku.host` | — | compute_profile, hosting_platform |
| `rule.compute.accelerator.count` | — | compute_profile, accelerator |
| `rule.host.accelerator.catalog` | — | hosting_platform, accelerator |
| `rule.tensor.parallel.devices` | — | accelerator, inference_engine |
| `rule.context.policy.limit` | — | — |
| `rule.model.serving.mode.selfhost` | — | model, deployment_mode |
| `rule.model.selfhost.license` | — | — |
| `rule.deployment.target.region` | — | — |
| `rule.memory.framework.compatibility` | agent_framework | memory_store, agent_framework |
| `rule.training.artifact.engine` | — | fine_tuned_model, inference_engine |
| `rule.knowledge.restricted.acl` | — | — |
| `rule.vram.per.device.fit` | — | — |
| `rule.tensor.parallel.engine.support` | — | inference_engine |

## Consumer and ownership limits

Both existing consumers add the same line without altering literal source
formatting, severity, affected selection IDs, role/version context, pagination,
request identity, callback retirement, Activity lifetime, save/CAS or editor
navigation. The saved-snapshot renderer continues its existing read-only
acquisition and selected-snapshot authority. Canonical reports and exported wire
values gain no field. `CompatibleOptions` continues to rank and filter using its
original canonical outcomes; this facet grants no new verdict or candidate
selection authority.

This slice does not replace the separately owned retained-source text correction
(#549/#554), unsupported-schema receiver correction (#545), compatible-choice
UI, or broader #101 remediation and accessibility work. The original acceptance
path remains `docs/delivery/android/work-packages/09.03.md` in `pickyour.ai`.
Issue-level receiving remains separate from local compilation and simulation.

## Verification scope

The four original native TextView controls use the existing bounded evaluator
and receiver on controlled copies of packaged Build/Catalog/Ruleset inputs:
a disabled required workload, a second valid selected provider, their mixed case,
and unchanged pass/unknown/skipped controls. On unchanged `56cc178e`, one control
passed and three new guidance assertions failed; all four executed with no
errors or skips. Canonical failure/severity and immutable-source assertions ran
before each missing-label failure. The mixed case stopped at its first missing
label, so its later second-label assertion was not reached in that original run.

Those original four methods are retained unchanged. New core controls exercise
both exact profiles, the complete finite inventories, object/array order,
changed metadata, raw floating types, ambiguity, primary-role presence and
immutable source binding through the existing receiver. Consistent supplied
reports in those admission tests are not evidence of evaluator execution.
Two actual saved-archive-to-evaluator-to-Activity controls cover a missing
knowledge base for an active RAG pipeline and an output reservation above the
selected model limit, then an altered Ruleset with those same canonical failures.
They retain the archive's foundation/provider-link prerequisites and unchanged
pending editor and archive bytes. Their first setup attempt was withheld before
evaluation. Source inspection showed those inputs lacked a required foundation
choice and a linked provider; those
two failed attempts remain in the original evidence, without weakening admission.
Existing formatter, recorded-context and saved-snapshot lifecycle regressions
remain part of focused receiving validation. A workflow attendance guard requires
all six new native methods, exact identities, no duplicates, and no failure,
error or skip. Actual result receipts identify their executed source separately.

These are JVM and Robolectric results, not an installed device, real TalkBack
journey, production evaluator, empirical compatibility, deployment or release.
