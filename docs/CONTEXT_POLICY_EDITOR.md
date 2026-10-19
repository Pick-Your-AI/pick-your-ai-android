# Typed context policy configuration

This contribution gives an existing `context_policy` selection four explicit
CONFIGURE controls. It uses the bundled Selection schema and the existing
selection preparation and managed-save path. ADD and other component kinds keep
their established raw settings editor.

## Field and presence semantics

| Frozen settings key | Control | Accepted value |
| --- | --- | --- |
| `requested_context_tokens` | Required integer input | At least 1 token |
| `reserved_output_tokens` | Required integer input | At least 0 tokens; explicit zero is retained |
| `truncation_strategy` | Optional choice | `reject`, `truncate_oldest`, `summarize`, `sliding_window` |
| `kv_cache_precision` | Optional choice | `fp32`, `bf16`, `fp16`, `fp8`, `int8`, `int4` |

**Not supplied** removes an optional key. Opening a control does not choose a
default. Empty required input is invalid; it does not become zero. Display text,
`unknown`, `not_applicable` and JSON null are not new accepted setting values.

`PreviewSettingsMetadata` reads the exact bundled
`contracts/v1/schemas/selection.schema.json` through the existing bounded reader.
It maps the schema's 29 closed kind definitions and 100 declared properties into
immutable constraint descriptors. Only these four fields receive controls.
There is no second registry, external schema resolution, inferred unit registry,
or adoption of a Shared Answers/CD1 field or answer-state carrier. Unfamiliar
bundled metadata fails initialization instead of supplying unrestricted fields.

Both integer fields retain raw text until explicit submission. Preparation uses
the existing exact bounded JSON-number reader; it rejects fractional values,
locale grouping/decimal separators, quoted numerals, duplicate or extra wrapper
members, malformed UTF-8 and numbers outside the reader envelope. Integral
decimal/exponent spellings remain exact. It does not narrow values to `Long` or
`Double`, round them, or rewrite an untouched admitted numeric node. A whole
prospective selection still passes through `PreviewSelectionForms.configure`,
the frozen schema and the existing sensitive-input check.

Field errors identify the constraint without copying rejected input. Correcting
one number clears that field's error. Original selection identity, role, enabled
state, catalog pin, literal notes/extensions and other draft content are retained.

## Save, cancellation and recovery

The form captures its original draft, selection and baseline. Its immutable
input is prepared on the existing worker and submitted through the existing
managed compare-and-swap path. No new executor, storage transaction or replacement
write authority is introduced. No-op, conflict, rejected, failed and uncertain
outcomes retain their established meanings. Cancel writes no revision.

Numbers, optional-choice buttons and confirmation controls freeze while a save
is pending. Dismissed choice callbacks and forms whose baseline is no longer
current cannot restore editing or issue a write. Field feedback is shown only
for the still-open current form and the submitted input.

New typed recovery uses a private version-3 primitive checkpoint with a fixed
`configure_context_policy` form identity, the original draft/selection/baseline,
the saving flag, two raw strings and two explicit optional values. Its combined
raw budget is 20,000 UTF-16 code units; some symbols use two units. Malformed
types, extra/missing keys, unsupported versions or choices, and excessive input
become the existing unavailable outcome. No valid prefix is retained.

Invalid numeric text remains transient raw input, never a string-valued durable
Selection setting. Exact current identity and a checkpoint captured before a
save permit editable recovery. A changed draft/head/selection or a captured
in-flight save permits read-only review and discard, without replay or new save
authority. The existing v1/v2 raw checkpoints continue through their original
reader and raw CONFIGURE editor, including legacy invalid context-policy JSON.
The existing URI-userinfo exclusion and unavailable markers stay distinct.

This is bounded new-form recovery, not a general history redactor or a claim
about credentials in unrelated fields. The implementation does not change
durable Selection/Build schemas, draft/cache formats or external export routes.

## Verification and remaining scope

The causal test-only baseline ran on unchanged HEAD
`2a80f0c4002a995e17f651d34407532f96e39e52`, staged tree
`f787c54851a76d3a678b7c5fe2e7dbd365900984`, on 2026-10-19 from
13:22:03 to 13:25:22 UTC. All seven Activity methods executed: four missing-typed-
caption failures and three passing raw ADD, legacy recovery and cancel controls,
with no errors or skips. The staged tree is not a commit. The original source,
command, logs and XML are preserved separately from the implementation.

The focused implementation runs also used unchanged HEAD `2a80f0c4`, with these
distinct staged trees and actual results on 2026-10-19:

| Staged source tree | Actual local run | Result |
| --- | --- | --- |
| `6842eac47b83a5bf8aff89bab0ce54ade5da48fc` | 13:49:30–13:51:06 UTC; 12 Core cases | 11 passed, one new assertion incorrectly compared opaque `DraftVersion` objects by reference. Preview did not execute. |
| `2b38cb880d1d01c4270ed84af8b5f1de6d448130` | 13:53:58–13:56:40 UTC; 12 Core and 66 Preview cases | 76 passed, two appended Activity assertions failed. All original seven causal methods now passed. |
| `066988e38379aa929b780afbe1c0c4917b896e4e` | 14:00:10–14:01:29 UTC; the full 15-case typed Activity suite | All 15 passed after two test-only corrections; no errors or skips. |

The Core assertion now checks the actual revision number, artifact hash and
unchanged active bytes after validated Build-candidate creation. The two Activity
corrections preserve the existing whole-draft Reopen requirement after another
writer changes the saved head, and distinguish the still-open form from
Robolectric's global reference to a dismissed choice dialog. The original failed
sources, logs and XML remain preserved; no production code was changed to bypass
those assertions.

The other 63 cases passed in the `2b38cb88` run and their source stayed unchanged
in `066988e3`: 12 Core, eight new primitive-checkpoint cases and 43 existing
raw/URI/role recovery controls. Together with the final 15 Activity passes, 78
distinct focused identities have passing execution, with per-run attribution.
The initial source inventory counted 77 because it missed an existing role test
with extra annotations; the command selected its whole class and actual XML
confirmed that additional case. These are staged trees, not test commits or
hosted receiving results.

The suites cover exact preparation and managed storage/validated Build-candidate
round trips, Activity save/cancel/currentness, and primitive recovery. Both new
workflow attendance programs passed against the exact retained Core/Checkpoint
reports from `2b38cb88` and Activity report from `066988e3` in a separate review
view. That checker execution did not rerun product tests. The final guide-only
evidence update leaves executable inputs unchanged. Eventual receiving workflow
results retain their own source identity and complete configured scope.

Robolectric lifecycle and worker tests are simulated Activity evidence. They do
not establish installed process death, physical-device testing, signed release
or production behavior. The local validated Build-candidate test is not a
public export dispatch.

This remains a partial contribution to [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
with [#27](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/27) and
[#83](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/83) retained as
their own criteria. Typed ADD, the other component fields, broader answer-state
UI/storage/export, missing prerequisites and installed acceptance remain open.
The preserved `08.04` acceptance coordinate is in
[Pick-Your-AI/pick-your-ai-website](https://github.com/Pick-Your-AI/pick-your-ai-website/blob/main/docs/delivery/android/work-packages/08.04.md);
this guide does not relocate or mark that record accepted.
