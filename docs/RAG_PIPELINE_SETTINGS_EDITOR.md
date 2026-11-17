# Typed RAG-pipeline settings

This bounded [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
continuation adds labelled native controls to **Configure settings** for an
existing frozen `rag_pipeline` selection. Opening the form creates no defaults
and does not change the acknowledged draft. The raw Add form still asks for
explicit role, enabled state and settings. Legacy raw Configure recovery keeps
its existing correction path.

## Optional values and exact admission

| Frozen field | Supplied value | Absence |
| --- | --- | --- |
| `top_k` | Whole JSON number of at least 1 | Clear **Supply Top K** |
| `max_chunks` | Whole JSON number of at least 1 | Clear **Supply Maximum chunks** |
| `rerank_enabled` | Boolean: the value checkbox records either true or false | Clear **Supply Reranking enabled** |

Both counts use the existing
[settings metadata](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSettingsMetadata.kt)
and unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json).
There is no additional ceiling, relation between the counts, inferred reranking
choice, retrieval execution, resource estimate or compatibility result. Explicit
false remains different from an omitted Boolean.

The [Core adapter](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewRagPipelineSettings.kt)
reads exact numeric nodes with the existing bounded JSON reader. Wide integers and
mathematical integer forms such as `1.000` or `1e3` retain exact values rather
than passing through binary floating point. Untouched admitted nodes retain their
class and scale. The reader's ASCII JSON syntax, number-length, Unicode and
exponent limits remain in force. Locale grouping, decimal commas, strings, nulls,
fractions, duplicate or injected properties and trailing documents cannot supply
a numeric setting.

Checking Supply enables a numeric field without inventing a value. Supplied blank
input stays visible and requires correction or explicit removal. Clearing Supply
removes that property and its pending text. Each numeric field receives its own
fixed, value-free error. Both errors can be shown at once. Rejected preparation
does not dispatch a partial settings command.

The existing 16,384-character settings JSON limit and 65,536-byte parse budget
still apply. The complete Selection receiver also enforces its existing aggregate
budget. Admission uses the final proposed settings together: removing or shrinking
a sibling field is considered before final admission. A stale original sibling
does not create a false rejection, and independently valid fields do not bypass
the complete Selection limit.

## Save, lifecycle and private recovery

The [native editor](../preview/src/main/kotlin/ai/pickyour/android/preview/RagPipelineSettingsEditor.kt)
uses the existing Activity worker, captured draft identity and managed save path.
Cancel leaves the acknowledged draft intact; an unchanged commit is a no-op.
A successful save preserves selection and catalog identity, role, enabled state,
notes, extensions, links, unrelated selections and draft data. Stale or deleted
authority cannot overwrite a newer record or recreate a removed selection.
Saved values remain available to explicit, validated Build projection. Saving
these settings does not perform retrieval or reranking.

Text controls disable automatic View-state saving and autofill and request no
dictionary suggestions or personalized keyboard learning. The input-method flags
are platform requests, not installed-keyboard evidence. Each visible field label
refers to its native control. Supply and value controls freeze while a save is in
flight; retired controls cannot submit another write. Existing retrieval and
optional-intent guidance remains visible.

The [private checkpoint](../preview/src/main/kotlin/ai/pickyour/android/preview/RagPipelineFormCheckpoint.kt)
uses string version `typed-rag-pipeline/1`, form `configure_rag_pipeline` and
kind `rag_pipeline` under the existing component recovery key. Its 13-key
closed shape requires exact primitive types. It retains the two nullable numeric
strings, the supplied/false/true Boolean state, five original identities and an
in-flight flag. The combined numeric text budget is 20,000 UTF-16 code units;
capture and decoding both reject overflow without keeping a prefix. Malformed
or unfinished numeric text is private correction input, not a save instruction.
Existing raw, context, model, compute, provider, inference, accelerator and
embedding checkpoint readers remain separate.

Pause or recreation retires the old form. Explicit **Review pending input** is
required before another editable form can open. The draft, acknowledged baseline,
selection, catalog item/version and RAG kind must still match, and a capture made
during saving cannot obtain new save authority. Otherwise recovery displays
read-only values with no Commit button. Neither restoration nor the in-flight
flag confirms that an operation committed or replays it.

Read-only recovery identifies numeric absence, a supplied empty value and a
supplied value separately before displaying any literal raw text. Words such as
`Not supplied` in raw input cannot impersonate the state. Boolean absence,
supplied false and supplied true have distinct labels. Closing read-only review
keeps the pending checkpoint; explicit discard follows the existing recovery
flow.

## Required regression attendance and acceptance limits

The workflow adds exact class-qualified attendance checks for these suites,
requiring each declared method exactly once and no failed, errored or skipped case:

| Suite | Declared methods | Scope |
| --- | ---: | --- |
| `PreviewRagPipelineSettingsTest` | 17 | Frozen metadata, exact numbers, independent errors, final Selection budgets, managed state and explicit Build projection |
| `RagPipelineSettingsActivityTest` | 17 | Labelled controls, optional states, Activity/worker/storage saves, cancellation, pause, recovery and stale or retired controls |
| `RagPipelineFormCheckpointTest` | 9 | Actual Parcel round trips, 12 presence combinations, closed primitive shape, bounds, identities and separate legacy readers |

The first Activity regression keeps the exact typed-caption assertions used by
the historical test-only causal baseline. The normal workflow retains all other
Core, Preview and Installed attendance requirements. Source declarations and
review are not passing execution; a validation receipt must identify the actual
source and original runtime results.

These are Core and Robolectric/API-28 checks. They do not qualify an installed RAG
Configure journey, process death, TalkBack, physical accessibility, a keyboard,
real retrieval, whole-issue #99 completion or release. Existing generic installed
journeys retain their own narrower scope. Typed Add, objectives and governed
answer-state handoffs remain separate work.
