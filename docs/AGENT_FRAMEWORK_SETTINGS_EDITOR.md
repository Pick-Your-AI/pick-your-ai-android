# Typed agent-framework settings

This bounded [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99)
continuation adds labelled native controls to **Configure settings** for an
existing `agent_framework` selection. Opening the form creates no defaults and
does not change the acknowledged draft. Raw Add still asks for explicit role,
enabled state and settings. Legacy raw Configure recovery keeps its existing
correction path.

## Optional values and exact admission

| Frozen field | Supplied value | Absence |
| --- | --- | --- |
| `agent_count` | Whole JSON number of at least 1 | Clear **Supply Agent count** |
| `max_iterations` | Whole JSON number of at least 1 | Clear **Supply Maximum iterations** |
| `timeout_ms` | Whole JSON number of at least 1, in milliseconds | Clear **Supply Timeout (milliseconds)** |

All three fields use the existing
[settings metadata](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewSettingsMetadata.kt)
and unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json).
There is no additional ceiling or relation between the fields. A timeout stays
in milliseconds; the editor does not convert it to another unit or infer an
execution deadline. Leaving a setting absent does not invent an unknown value,
a default or a zero.

The [Core adapter](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewAgentFrameworkSettings.kt)
reads exact numeric nodes with the existing bounded JSON reader. Wide integers
and mathematical integer forms such as `1.000` and `1e3` retain exact values.
They do not pass through binary floating point or a machine-integer limit.
Untouched admitted nodes retain their class and scale. The reader's ASCII JSON
syntax, number-length, Unicode and exponent limits still apply. Locale grouping,
decimal commas, strings, nulls, fractions, duplicate or injected properties and
trailing documents cannot supply a numeric setting.

Checking Supply enables an empty field without inventing a value. Supplied blank
input stays visible and requires correction or explicit removal. Clearing Supply
removes that property and its pending text. Each field receives its own fixed,
value-free error; all three errors can appear together. Rejected preparation
does not dispatch a partial settings command.

The existing 16,384-character settings JSON limit and 65,536-byte parse budget
still apply. The complete Selection receiver also enforces its existing
aggregate budget. Admission uses the final proposed settings together, so
removing or shrinking a sibling is considered before final admission.
Independently valid fields cannot bypass the complete Selection limit.

## Save, lifecycle and private recovery

The [native editor](../preview/src/main/kotlin/ai/pickyour/android/preview/AgentFrameworkSettingsEditor.kt)
uses the existing Activity worker, captured draft identity and managed save path.
Cancel leaves the acknowledged draft intact; an unchanged commit is a no-op.
A successful save preserves selection and catalog identity, role, enabled state,
notes, extensions, links, unrelated selections and draft data. Stale or deleted
authority cannot overwrite a newer record or recreate a removed selection.
Saved values remain available to explicit, validated Build projection.

Text controls disable automatic View-state saving and autofill and request no
dictionary suggestions or personalized keyboard learning. These input-method
flags are requests, not evidence about an installed keyboard. Each visible field
label refers to its native control. Supply and value controls freeze while a save
is in flight. Retired controls cannot submit another write, and changing a
retired text widget cannot change captured recovery input. Existing agent
selection and optional-intent guidance remains visible.

The [private checkpoint](../preview/src/main/kotlin/ai/pickyour/android/preview/AgentFrameworkFormCheckpoint.kt)
uses string version `typed-agent-framework/1`, form
`configure_agent_framework` and kind `agent_framework` under the existing
component recovery key. Its 12-key closed shape requires exact primitive types.
It retains three nullable numeric strings, five original identities and an
in-flight flag. All three text keys remain present even when their value is null.
The combined raw text budget is 20,000 UTF-16 code units, counted with wide
integer arithmetic. Capture and decoding both reject overflow without keeping a
prefix. Malformed or unfinished numeric text remains private correction input,
never a save instruction. Existing raw and other typed checkpoint readers keep
their separate protocols.

Pause or recreation retires the old form. Explicit **Review pending input** is
required before another editable form can open. The draft, acknowledged baseline,
selection, catalog item/version and agent-framework kind must still match, and a
capture made during saving cannot obtain new save authority. Otherwise recovery
displays read-only values with no Commit button. Restoration does not confirm
that a save completed or replay it.

Read-only recovery labels absence, a supplied empty value and a supplied value
separately before displaying literal raw text. Input such as `Not supplied`
cannot impersonate the state label. Closing read-only review retains the pending
checkpoint; explicit discard follows the existing recovery flow.

## Required regression attendance and acceptance limits

The verification workflow requires exact class-qualified attendance for the
three suites, with each declared method exactly once and no failure, error or
skip:

| Suite | Declared methods | Scope |
| --- | ---: | --- |
| `PreviewAgentFrameworkSettingsTest` | 17 | Frozen metadata, all eight optional states, exact numbers, independent errors, final Selection budgets, managed state and explicit Build projection |
| `AgentFrameworkSettingsActivityTest` | 17 | Labelled controls, single-field saves, raw Add preservation, Activity/worker/storage saves, cancellation, pause, recovery and stale or retired controls |
| `AgentFrameworkFormCheckpointTest` | 9 | Actual Parcel round trips, eight presence combinations, closed primitive shape, writer/reader bounds, original identities and separate legacy readers |

The first Activity method preserves the complete existing-API test-only witness
against the earlier accepted source. The witness and implementation outcomes
must be attributed separately. The in-flight test fences the owned write before
its first durable observation. Declared methods and reviewed source are not
passing execution; validation receipts identify the actual source and original
results.

This change completes only the named existing-selection settings slice.
[The T4 selection behavior](AGENT_SELECTIONS.md) and
[Android #128](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/128)
remain broader acceptance contexts. Saving these structural settings does not
run agents, invoke tools, grant permissions, establish transport or prove
compatibility. Literal `unknown` or `not_applicable` is not a numeric value.
Governed answer-state handling, typed Add and other kinds remain separate work.

These are Core and Robolectric/API-28 checks. They do not qualify an installed
agent-framework Configure journey, process death, TalkBack, physical
accessibility, a keyboard, whole-issue #99 completion or release. Generic
installed journeys retain their separate, narrower scope.

The canonical receiving coordinate is recorded in
[Android #99's delivery record](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99#delivery-record-and-receiving-review):
`docs/delivery/android/work-packages/08.04.md` in
`Pick-Your-AI/pickyour.ai` is the intended acceptance path, not a claim that a
published file or completed acceptance exists.
