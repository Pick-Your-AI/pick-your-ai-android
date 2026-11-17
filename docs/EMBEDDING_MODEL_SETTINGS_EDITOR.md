# Typed embedding-model CONFIGURE

Fresh **Configure settings** for an existing `embedding_model` selection presents
the three optional fields from the frozen
[Selection schema](../contracts/v1/schemas/selection.schema.json).
This is a bounded contribution to [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
coordinated in [its existing editor lane](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#issuecomment-6285512948).
ADD retains the existing raw settings object. Role, replacement, activation,
other component editors and the saved-draft command keep their existing authority.

## Literal optional values

Each field has an explicit **Supply** control. Clear Supply to omit only that
field; no catalog-derived value or default is inserted.

| Field | Frozen rule and editing behavior |
| --- | --- |
| `credential_ref` | Optional opaque reference accepted by `^secret://[A-Za-z0-9/_:.-]+$`; the reference is never resolved |
| `output_dimensions` | Optional mathematical JSON integer of at least 1; the frozen field supplies no maximum |
| `region` | Optional literal string; supplied empty and whitespace strings remain distinct from omission |

Numeric input uses the existing bounded JSON reader and exact decimal/integer
admission. It is never narrowed through `Double`, `Float`, `Int` or `Long`.
Integral decimal/exponent spellings remain admissible. An unchanged admitted
numeric node retains its exact type and scale. Existing local JSON and recovery
budgets still apply; these do not introduce a new field bound into the schema.

Region text is not trimmed, normalized, interpreted as an answer state or checked
against an invented list of available regions. Read-only recovery distinguishes
**Supplied empty text** from **Not supplied**. Credential parsing preserves the
frozen validator's `Pattern.UNIX_LINES` and `find` semantics, including its
accepted single final LF. Multiline native controls retain those literal values.

Preparation calls the existing whole-Selection receiver before issuing a
canonical Configure command. Managed storage and the acknowledged baseline
remain the only save authority. Identity, catalog identity, role, enabled state,
notes, extensions, links, optional-tier activation and unrelated draft fields
are preserved. Inline errors identify the field without echoing its value.
No embedding execution, credential retrieval, service call or compatibility
decision is introduced.

## Bounded private recovery

The namespaced private checkpoint `typed-embedding-model/1` stores three nullable
strings, original draft/baseline/selection/catalog identity, exact kind/form and
a Boolean recording whether a save was in flight. Exact key and primitive-type
checks reject malformed or mixed protocol shapes. The checkpoint supplies no
write authority and does not replace the frozen Selection envelope.

The combined input budget is 20,000 UTF-16 code units. Larger input becomes a
fixed unavailable marker; no prefix is retained. Only an absent or bounded
frozen-pattern-valid credential reference can enter newly captured typed
recovery. An invalid supplied reference replaces the entire checkpoint with the
fixed unavailable marker, independently of any invalid numeric input. Live text
remains available for correction until the form closes. This safeguard does not
claim to identify arbitrary secrets in unrestricted region text or rewrite
previously stored raw input.

Restored input requires explicit review. A changed draft, baseline, selection,
catalog identity or saved in-flight marker cannot gain fresh save authority.
Otherwise valid stale input remains read-only until explicit discard/reopen.
Pause captures bounded input, retires the old controls and dismisses the form;
resume only restores availability. Saving freezes every control, and retired
Supply/Commit callbacks cannot revive an old form or replay its write.

Automatic view-state saving and autofill are disabled for the typed controls.
The IME flag requests that the keyboard avoid personalized learning; it is not a
guarantee about the keyboard. The bounded checkpoint owns local form recovery.
Legacy raw version-1/version-2 checkpoints and other typed namespaces retain
their own readers.

## Verification and limits

The causal baseline ran on 20 October 2026 at 07:20:05–07:22:40 UTC, source
`05304f216c0f2841c7e9595dd5098e34375e7711`, tree
`70ea79d83a6d529712b98c12da6c774614fcfe6a`. Only the new existing-API Activity
fixture differed from accepted `653f489e9ad276f0e7f97f335adf6d0256a563d9`.
Its one case reached CONFIGURE and failed because the typed **Credential
reference** caption was absent; there were no errors or skips. Earlier cache
directory and sandbox daemon-socket setup stops executed no tests and are
retained separately.

The implementation adds [14 Core cases](../core/src/test/kotlin/ai/pickyour/core/preview/PreviewEmbeddingModelSettingsTest.kt),
[16 Activity cases](../preview/src/test/kotlin/ai/pickyour/android/preview/EmbeddingModelSettingsActivityTest.kt)
and [8 checkpoint cases](../preview/src/test/kotlin/ai/pickyour/android/preview/EmbeddingModelFormCheckpointTest.kt).
They cover exact frozen admission, absence/empty distinction, wide integers,
managed no-op/CAS/deletion, retained history and a validated Build candidate,
real Activity/worker/pause/recreation paths in simulation, bounded Parcel
recovery and invalid-credential exclusion. The in-flight observer fences the
admitted worker request before its first durable read after pause.
The workflow requires these named cases without failure, error, duplication or
skip; actual execution receipts remain bound to their exact source and outputs.

JVM/Robolectric results and an unsigned local build do not establish installed
process death, physical accessibility, device qualification, release or
production acceptance. Broader #99 criteria and other owners' slices retain
their own acceptance. The intended `docs/delivery/android/work-packages/08.04.md`
coordinate in `Pick-Your-AI/pickyour.ai` remains governed by the canonical
[#99 delivery record](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#delivery-record-and-receiving-review);
this guide does not claim that the intended file is published.
