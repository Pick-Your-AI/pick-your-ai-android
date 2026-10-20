# Explicit local selection controls

Partial [#112](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/112) and
[#98](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/98), using the
accepted native Views preview and canonical managed-library command adapter.
Canonical receiving records remain the existing website paths `08.02.md` and
`07.05.md`; this file does not relocate acceptance or close either issue.
The [foundation selection continuation](FOUNDATION_SELECTIONS.md) separately
references [#83](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/83).
It makes frozen `workload` and `deployment_mode` choices editable through these
same controls, with kind-specific settings guidance and explicit activation/roles.
The [retrieval selection continuation](RETRIEVAL_SELECTIONS.md) separately adds
the five frozen T3 kinds for partial
[#127](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/127), with explicit
settings and component state kept separate from optional-tier intent.
The [agent selection continuation](AGENT_SELECTIONS.md) adds the five frozen T4
kinds for partial [#128](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/128).
Declared scopes, endpoints and memory inputs remain local planning values; saving
does not connect an account, invoke a tool or grant external permissions.
The [training selection continuation](TRAINING_SELECTIONS.md) adds the four frozen
T5 kinds for partial [#129](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/129).
Its fourteen optional settings retain explicit training assumptions and artifact
references without adopting proposed fields, approving rights or starting a job.
The [guardrail and evaluation continuation](GUARDRAIL_EVALUATION_SELECTIONS.md)
adds those two frozen T6 kinds for partial
[#41](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/41). The shared T6
planning section keeps guidance visible before saving and with T3–T5 disabled;
these forms also explain evidence conditions. Their five optional settings
remain requested inputs; applicable requirements and measured coverage stay
unavailable, with no safety, quality or readiness verdict.
The [operational planning continuation](OPERATIONAL_SELECTIONS.md) adds the three
remaining frozen T6 kinds for partial
[#52](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/52).
Its twelve optional deployment, monitoring and cost settings remain declared
inputs. Approval, capacity, observation, price coverage and runtime actions still
require separate evidence; one shared planning panel retains that distinction.

The [typed hosting-platform settings editor](HOSTING_PLATFORM_SETTINGS_EDITOR.md)
adds a bounded existing-`hosting_platform` CONFIGURE form for partial
[#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99). It preserves
literal region text, opaque account references and the optional frozen network
isolation choice through the same complete Selection and managed revision path.
These are declared settings; no account, region or network is verified. Raw ADD,
legacy raw Configure recovery and the other typed editors retain their routes.

The saved-component section exposes a separate exact-fixture component chooser.
It does not reinterpret the old guest sample-selection metadata as a typed domain
selection. Every listed item keeps its exact kind, catalog ID, version and captured
snapshot hash. The current frozen catalog is synthetic. There is no network
lookup, source-rights assertion, latest-version fallback, incoming component-link
route or automatic draft edit when a catalog detail opens.

Add requires an explicit role, visible enabled checkbox (initially unchecked) and
an exact settings JSON object. The form starts with `{}` as visible empty input;
required fields are never invented. Required accelerator/compute/context/vector values
must be entered according to the frozen schema. The small technical settings
editor accepts at most 16,384 source characters and parses a single bounded JSON
object with duplicate-key rejection. Raw invalid or over-limit text is not prefix
truncated, is not saved and remains in the open form for correction. Existing
settings exceeding this small editor limit remain saved and cannot be truncated
through this editor. Raw credentials must not be entered; applicable settings
accept only opaque secret references under the frozen schema.

For partial [#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99),
new add/settings input also rejects URI authority userinfo in the frozen
`model_provider.api_base_url`, `model.weights_uri`, `mcp_server.endpoint`,
`integration.endpoint` and `fine_tuned_model.artifact_uri` fields when that kind
is admitted by the editor. The check follows the existing JSON budgets and
schema validation, produces no command on rejection and never includes entered
values in its failure result. Custom schemes, exact legitimate URI spelling,
opaque secret references and `@` outside the authority remain unchanged.
This is a specific userinfo boundary, not secret-pattern detection in arbitrary
text or query values. Frozen/history decoding, retained records, role-only edits
and replacement remain unchanged; this does not complete #99 or qualify exports,
diagnostics, installed-device behavior or release.

Fresh **Edit settings** for `model_provider` now offers
[three typed optional controls](MODEL_PROVIDER_SETTINGS_EDITOR.md). They preserve
literal Region text, including a supplied empty string, exact opaque credential
references and admitted API base URIs. Each field remains absent until explicitly
supplied. Raw Add and legacy raw Configure recovery retain their existing paths.

New Activity captures of open add/settings forms also exclude positively
recognized URI user information under the same five-field policy. They retain
only a fixed exclusion reason, without clearing the live correction text or
changing save authority. Ordinary malformed text, legitimate URI spelling and
legacy raw recovery remain distinct. See the exact bounds, compatibility and
remaining privacy scope in [component form recovery](COMPONENT_FORM_RECOVERY.md#uri-user-information-in-newly-captured-settings).

Role and settings editors keep multiline input literal and request
[no dictionary suggestions](https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_FLAG_NO_SUGGESTIONS)
and [no personalized keyboard learning](https://developer.android.com/reference/android/view/inputmethod/EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING).
These are requests to the input method; they do not guarantee that an installed
keyboard honors them or authorize raw credential entry. The same field factory
applies them to add, settings and role forms, including explicitly reviewed
checkpoint recovery. The existing bounded checkpoint remains the only View-state
path for pending raw input; opening, typing or restoring a form supplies no save
authority. Installed keyboard and accessibility qualification remain separate.

Replace chooses an existing selection of the same kind and retains its identity,
role, enabled state, settings, notes, extensions and links. **Edit settings** changes
only settings. **Edit role** changes the explicitly entered role while retaining
the complete saved settings, including values beyond the small settings editor's
limit. It keeps the selection identity and all other content. Duplicate roles for
the same kind reject the whole edit, including a collision with a disabled
selection. See [explicit selection roles](LOCAL_SELECTION_ROLES.md) for the local
history format and recovery boundaries. Disable/re-enable retains settings and links. Removal confirms the exact
selection and lists its incident link IDs; only that selection and those links are
removed. No secondary selection is promoted. All frozen T0–T6 kinds are now
admitted through these same controls; unregistered future kinds remain rejected.
Cost policy retains the frozen one-enabled-selection limit, while deployment and
monitoring retain multiple explicit roles. No new runtime or export route follows.

Controls require a clean acknowledged guest form. A dialog captures the exact
record object, draft context and applicable catalog snapshot. Dispatch checks
these again, disables editing, prepares bounded input on the existing worker and
uses the library-bound adapter with original revision/hash. Repeated taps cannot
create another revision. External concurrent edits are detected by canonical CAS;
conflict and uncertainty revoke edit authority until explicit reopen. Invalid
forms and ordinary command rejections stay visible without acknowledging a save.
Every mutation dialog has its own polite inline feedback, cleared on retry. It
shows the exact outcome wording: correctable invalid/rejected input remains
distinct from conflict or uncertain acknowledgement requiring explicit reopen.
The latter never claims that no revision was committed.
Dismissing or canceling a dialog revokes its callbacks. While a command is in
flight, form fields, checkbox and cancellation are disabled so later user edits
cannot be silently discarded by completion. Invalid/rejected outcomes restore
the same form controls and raw input. Destroying the Activity terminally closes
this section and its dialogs; retained buttons cannot dispatch to a closed worker.
The [bounded component checkpoint](COMPONENT_FORM_RECOVERY.md) retains unfinished
add/settings/role input for explicit review after recreation. It supplies no command
or save authority. Completed cancellation and explicit discard clear that pending
input; destructive confirmations are never restored.

Successful status means only local persistence. UI copy explicitly withholds
compatibility, pricing, deployment requirements and readiness. Canonical v1,
role/cardinality rules and accepted storage history remain unchanged. Dependent
requirements after deployment changes, incoming trusted routes, full product
forms, installed process-death recovery, named domain/UX/data receiving review,
TalkBack/device qualification and complete issue acceptance remain outstanding.

## Validation scope

Three core form regressions exercise exact numbers/role/enabled state, missing
required settings, malformed/duplicate/oversized input, schema rejection and
replacement preservation. Seven mandatory paused-looper Activity simulations cover
add/configure/toggle/remove, invalid input retention, concurrent-head conflict,
repeated/retired and canceled callbacks, in-flight field freezing and exact
replacement no-op with dirty-form blocking.
Three additional actual input-connection simulations verify the literal-input and
learning requests on add, settings and recreated role fields. They preserve exact
opaque reference text and pending edits, require explicit commits, and retain
acknowledged data when forms are canceled. The role case also checks that ordinary
View-state saving stays disabled before and after explicit checkpoint review.
The original source checkpoint's 43 existing Activity simulations and 50-case
Activity total are historical counts. Current-main receiving retains every named
Activity, cost-presentation, catalog-snapshot and complete recorded-price-browser
regression in the current workflow, alongside these seven component-control
additions. Hosted strict unsigned build/lint, all current named report guards and
artifact-linked inventory/source-input checks must pass on the reviewed receiving
source before this UI slice is accepted for merge. Local core tests do not
establish Android build, installed/emulated runtime, physical-device or release
evidence.
