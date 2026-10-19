# Explicit local training and fine-tuned model selections

This partial [Android #129](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/129)
continuation admits the four frozen optional T5 kinds to the existing native
component editor: `training_framework`, `training_compute`, `training_dataset`
and `fine_tuned_model`. It uses the same explicit Views form, bounded parser,
typed commands and canonical managed-library save adapter as the
[agent editor](AGENT_SELECTIONS.md). It introduces no successor-contract field,
storage format or training service.

The canonical acceptance record remains
`docs/delivery/android/work-packages/13.03.md` in `Pick-Your-AI/pickyour.ai`.
This implementation note preserves that boundary and every criterion; it is not
a replacement acceptance record or whole-issue closure.

## Exact frozen inputs

All four kinds have optional activation and cardinality `many` in the unchanged
[registry](../contracts/v1/schemas/component-registry.json). Their fourteen
[Selection settings](../contracts/v1/schemas/selection.schema.json) are all optional.
An explicit `{}` records unanswered settings without filling defaults.

| Kind | Optional settings and exact bounds |
| --- | --- |
| `training_framework` | `method`: `sft`, `lora`, `qlora`, `dpo`, `full_finetune` or `distillation`; `epochs` and `learning_rate`: numbers greater than 0; `batch_size`: integer of at least 1 |
| `training_compute` | `gpu_hours_budget`: number of at least 0; `spot_allowed`: boolean; `checkpoint_interval_minutes`: integer of at least 1 |
| `training_dataset` | `train_split`, `eval_split`, `test_split`: numbers from 0 through 1; `redaction_enabled`: boolean |
| `fine_tuned_model` | `use_adapter`: boolean; `artifact_uri`: URI string; `revision`: string, including an explicit empty string |

Objects stay closed to other fields. Exact decimal values and large integers,
omitted values, permitted zero, explicit false, empty revision, Unicode,
whitespace and URI spelling retain the existing parser/schema semantics.
The split fields have individual bounds in v1; this editor introduces no
normalization or sum-to-one rule. A saved set of fractions is not evidence of a
correct dataset split. A permitted method value is an entered assumption, not
proof that its exact framework, base model, configuration or account supports it.

The forms show all applicable names, types and bounds. Invalid or duplicate-key
JSON, wrong types, fractional integer fields, out-of-range values, malformed URIs,
unknown fields and over-limit text remain visible without a partial save. The
existing 16,384-character settings editor limit, strict UTF-8 parser and larger
retained-value boundaries are unchanged. A separate role edit keeps all existing
settings, including values too large for the small settings editor.

## Catalog lineage and evidence

Catalog declarations remain in the exact selected
[CatalogItem](../contracts/v1/schemas/catalog-item.schema.json), separately from
editable settings:

| Kind | Catalog declarations retained by exact item/version |
| --- | --- |
| `training_framework` | Declared methods, backends, optional artifact formats and distributed-support flag |
| `training_compute` | Required compute-profile reference, optional storage reference, maximum GPU-hours and ephemeral flag |
| `training_dataset` | Required data-source references and classification; optional example count, license/privacy declarations and held-out-test flag |
| `fine_tuned_model` | Required base-model, training-framework and training-dataset references and artifact format; optional adapter-only and access-mode declarations |

Add and replacement require the captured catalog hash, kind, exact item ID and
version against the supplied snapshot. The stored Selection retains its exact
item ID/version. Settings edits never rewrite those catalog declarations or
substitute a different base, dataset or training path. Catalog snapshots and
their original bytes remain separate immutable inputs to snapshot preparation.
Missing or ambiguous reference targets withhold that structural projection.

The frozen ID-only lineage does not supply an immutable upstream checkpoint
revision. An entered fine-tuned-model `revision` is not a substitute for base
revision evidence. Proposed `base_model_revision`, adapter shape, evaluation
references, richer dataset governance and new training price/resource fields
remain outside these settings. Attempts to insert catalog fields such as
`base_model_ref`, `compute_profile_ref`, `license_verified` or `privacy_approved`
into settings reject the complete edit.

The intake read the accepted Research T5 records at
[`c9ed2d15523c66b57202639ec03b5f9fe886f1ef`](https://github.com/Pick-Your-AI/pick-your-ai-research/tree/c9ed2d15523c66b57202639ec03b5f9fe886f1ef/research/T5-training-fine-tuning),
including the framework participant/evidence envelope, exact dataset/artifact/
compute receiving interfaces and bounded quantity/unit handoffs. Those interfaces
do not adopt a new Android field or turn a source-checked claim into a reproduced
training, conversion, price or serving result. Research
[PR #388](https://github.com/Pick-Your-AI/pick-your-ai-research/pull/388) was an
unmerged draft at intake, head `cf1047ad57fbf14b27a5e5b5e838bdab920b8e77`;
its revised summaries were read as proposed context. Their existence supplies
neither an accepted extension nor actual dataset/artifact records.

Recorded rights, privacy and held-out-test booleans do not establish a selected
user's legal basis, processing approval, leakage test or evaluation. A method or
format label does not qualify every combination. Missing measurements and rates
remain unknown and unpriced; a GPU-hour budget is not a quote, allocation or
training-memory estimate. Detailed governed eligibility, runtime/export findings
and pricing keep their existing research/contract/consumer owners.

## Explicit intent, persistence and external effects

Add starts with an empty role, unchecked component-enabled checkbox and visible
`{}` settings. Several enabled selections of each kind can coexist under distinct
roles. Duplicate `(kind, role)` values reject even when a selection is disabled.
Replacement retains selection identity, role, enabled state, settings, notes,
extensions and links. Removal affects only that identity and its incident links.
No other component is created or promoted.

Each component's enabled state and the separate T5 `ENABLED`/`DISABLED`/`UNKNOWN`
intent remain independent. Editing settings, including `use_adapter`,
`redaction_enabled` or `spot_allowed`, never activates T5. Disabling T5 preserves
the component settings and states for explicit correction or re-enable. Existing
snapshot preparation withholds unknown intent, disabled T5 with an enabled T5
selection and enabled T5 without an enabled T5 selection. Satisfying the structural
presence condition is not proof of a complete or eligible training plan.

The store preserves original revision/hash CAS, draft and library liveness,
guest fields, workload inputs, opaque optional-tier inputs, metadata and history.
Format 4 already represents the settings/commands; a real role change uses the
existing format-5 transition. Unchanged settings, role, enabled state and exact
catalog replacement reuse the durable revision. Reopen and restore preserve
original content and old pinned bytes. Stale, foreign or deleted authority cannot
save against another record.

Canceled or catalog-stale controls cannot dispatch later writes. Parcel recovery
retains bounded raw form input for explicit review. Changed saved heads and
in-flight checkpoints restore read-only input without rebasing consent or replaying
an acknowledged operation. The separate choice, technical-input/IME and optional-tier
controllers retain their existing ownership and lifecycle behavior during integration.

An artifact URI is literal text without automatic links or URI resolution. The
guidance asks users to keep credentials out of that field; no credential field or
credential transport is added. Saving or preparing a structural in-memory Build
candidate downloads no weights or datasets, starts no job, reads no artifact,
allocates no compute and grants no training or distribution rights. Candidate
preparation retains exact inputs and does not write an archive, publish a plan,
create an evaluation grade or convert unavailable prices into zero cost.

## Validation and remaining criteria

Ten dedicated core cases cover all four kinds and fourteen settings; all six
methods; exact decimals, large integers, optional absence and literal text;
negative schema and proposed-field inputs; roles; exact catalog pins;
unchanged revisions and defensive copies; format/history/reopen/restore;
independent intent and unpriced structural projection; missing lineage targets;
and managed-library preservation with stale, foreign and deleted authority.

The before-admission probe at local commit
`76d6403374f22230435eb544178e9d4307f4cfd1` executed all ten new cases: ten failures,
zero errors or skips, with the complete source manifest unchanged. Each failure
reached the existing T5 admission boundary. This original failed execution remains
separate from subsequent fixed-source results; its failure is not relabeled as a
passing suite.

Ten paused-looper Activity cases cover all-kind creation/reopen; complete retained
invalid input; every frozen setting with literal URI handling; independent T5
disable/unknown/re-enable; multiple roles and selective removal; unchanged
settings/role/exact replacement; all-kind parcel recovery; canceled/catalog-stale
controls; concurrent-head read-only recovery; acknowledged in-flight recovery;
and narrow RTL layout with twice the normal font scale. The workflow requires
all ten actual method names, including the separately annotated RTL case, and
rejects missing, wrong, duplicate, failed, errored or skipped reports without
freezing the total against future additions.

At this T5 source checkpoint, existing unsupported-kind tests kept their complete
rejection assertions on the five remaining T6 kinds. The foundation method was
retitled from
`retained optional and core tier six kinds stay outside this command subset` to
`retained core tier six kinds stay outside this command subset`, reflecting the
now-supported four T5 kinds. No existing Activity case or report requirement is
removed. Source, hosted/locally executed counts, artifact hashes and any failed
attempts belong to their exact delivery receipts; authored tests alone are not
passing execution or installed-device evidence.

The subsequent [guardrail/evaluation continuation](GUARDRAIL_EVALUATION_SELECTIONS.md)
admits those two T6 kinds. It retains every command rejection assertion for
`deployment_target`, `monitoring_stack` and `cost_policy`, and retitles the
foundation method to `retained deployment monitoring and cost policy kinds stay
outside this command subset`. That later admission and its new dedicated tests
do not change the historical T5 source or execution attribution above.
The further [operational continuation](OPERATIONAL_SELECTIONS.md) admits those
three remaining frozen kinds and explicitly retitles the foundation case again.
Their new positive/negative coverage preserves unknown future-kind rejection;
the historical T5 and two-kind T6 results retain their original source boundaries.

| Original #129 criterion | Delivered local scope and remaining acceptance |
| --- | --- |
| T5 datasets, lineage, training methods/compute/export/runtime checks remain conditional on accepted contract extensions. | Uses only frozen v1 settings and retains the conditional extension boundary. Executable eligibility, new fields, measured findings and consuming acceptance remain separate. |
| A supported training plan preserves base-model and dataset identities, method, compute assumptions and artifact lineage through a record round trip. | Exact selected catalog ID/version, settings, links and local history survive the exercised local round trips and structural preparation. Actual user dataset/artifact lineage, upstream checkpoint evidence, governed training eligibility and complete planning export acceptance remain unresolved. |
| Unaccepted contract extensions, unknown dataset rights and unsupported training/export combinations remain explicit and cannot become deployable claims. | Extra settings reject; native guidance keeps rights, prices, measurements and runtime/export support unestablished. No deployable verdict is created. Full evidence-based findings, share-safe outputs and end-to-end product qualification still need their accepted contracts and receiving evidence. |

All three criteria remain incomplete. The original allowed/disallowed-method,
missing-lineage, unavailable-price and unsupported-runtime fixture requirements
remain visible through this scope and the issue. A saved permitted method value
does not replace an applicable compatibility fixture result. The native
prerequisites [#36](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/36)
and [#121](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/121), their
transitive [#37](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/37)
boundary and central Research contract owners continue to govern broader
acceptance. Installed lifecycle, TalkBack, physical-device durability, public
sharing and release qualification require their own evidence.
