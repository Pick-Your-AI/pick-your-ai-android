# Typed hosting-platform settings in the Android preview

The local preview has a typed **Configure** form for an existing
`hosting_platform` selection. It edits the three optional fields already declared
by the frozen Selection schema. This is a bounded contribution to
[#99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99), following the
existing [local selection controls](LOCAL_SELECTION_CONTROLS.md) and managed
revision path.

This guide is an implementation reference. The canonical #99 acceptance record
remains `docs/delivery/android/work-packages/08.04.md` in
`Pick-Your-AI/pickyour.ai`; the canonical prompt and coverage entry determine its
placement. The broader issue criteria, #27/#83 prerequisites and accountable
assignment still require their own completion. This editor does not establish
installed-device, physical-device, accessibility, provider, network or release
acceptance.

## Field authority and literal values

`PreviewHostingPlatformSettings.fields` reads the existing
`PreviewSettingsMetadata` definition. The editor does not add a schema, change a
frozen artifact, infer a default or reinterpret another field as this setting.

| Frozen field | Native input and absence | Accepted meaning |
| --- | --- | --- |
| `region` | A **Supply Region** checkbox and literal text input; clearing the checkbox omits the field | Any schema-admitted string, including supplied empty, whitespace, Unicode and literal `unknown` text; no trimming, case folding, region lookup or replacement |
| `account_ref` | A **Supply Account reference** checkbox and literal text input; clearing the checkbox omits the field | The existing opaque-reference pattern `^secret://[A-Za-z0-9/_:.-]+$`, with the frozen receiver's actual matching semantics; no credential lookup or account verification |
| `network_isolation` | A native single-choice dialog with **Not supplied**, `public`, `private` and `air_gapped` | Exactly one declared string or omission; no automatic selection and no change to the device or hosting network |

Omission is a missing property, not JSON `null` or an empty-string substitute. A
supplied empty `region` remains a JSON string and is distinct from omission.
Account reference and network isolation do not acquire an empty-string value
merely because the corresponding input is optional. The full Selection receiver
continues to reject wrong JSON types, unrecognized keys and undeclared enum
values.

The reference helper deliberately uses the frozen receiver's `Pattern.UNIX_LINES`
and `find()` behavior. That receiver admits a single final LF after an otherwise
matching opaque reference. The editor preserves that exact admitted spelling,
including the LF; it does not silently normalize it or substitute stricter
`matches()` semantics. A trailing CR, CRLF, repeated LF, invalid punctuation,
leading whitespace and a raw pasted credential remain inadmissible. The text
controls support literal newlines so a value already accepted by the receiver
does not lose its spelling when reopened.

The region field remains literal declared data. Secret-like words, URI-like
strings or placeholder words entered there do not become a different type or an
inferred unknown state. The special exclusion rule described below belongs to
the opaque account-reference field.

## Preparation and acknowledged persistence

`PreviewHostingPlatformSettings.configure` prepares a settings change from the
original immutable selection and the supplied input. It keeps selection ID,
catalog item/version, role, enabled state, notes and extensions. Unrelated
selections, links, objective/workload fields and private metadata remain under
the existing command and store implementation.

Per-field feedback is value-free and passes through the original complete
Selection receiver. After all supplied fields have been considered, the helper
passes the complete serialized settings object to `PreviewSelectionForms.configure`.
That existing path enforces the **16,384 UTF-16 code-unit settings-text limit**,
strict UTF-8 encoding, its 65,536-byte JSON input bound, complete Selection
validation and existing sensitive-input policy. JSON property names, quotes and
escapes consume the same total settings budget. Individually acceptable fields
cannot bypass that aggregate limit. A failed preparation exposes no partial
command; a whole-object budget failure uses the existing form-level invalid
status.

The Activity submits through the existing worker and managed `applySelection`
path. The current draft and baseline still determine save authority. A successful
unchanged commit is a no-op; it does not create a revision or supply defaults.
Cancellation changes no acknowledged content. A stale baseline, removed selection
or deleted draft cannot be restored by retaining an old form or prepared command.
Store acknowledgement remains separate from displaying input or creating a
checkpoint.

## Private pending input

The new private protocol uses the string version **`typed-hosting-platform/1`**,
form `hostingPlatform` and kind `hosting_platform`. It retains the original five
identities: draft ID, saved baseline, selection ID, catalog item ID and catalog
item version. It also retains the three nullable raw input strings and the
Boolean `wasSaving` state. The decoder requires the exact primitive types and key
set; it does not coerce missing keys, numbers, lists or objects into input.

Draft ID, baseline, selection ID and catalog item version must each contain
1–128 UTF-16 code units; catalog item ID has a 1–256 limit. All three input strings
together have a **20,000 UTF-16 code-unit private recovery budget**. This complete
raw bound and the identity bounds are checked before reference-pattern matching.
Network isolation must remain absent or one of the same three metadata choices.
No prefix is retained when the full input is over budget.

A supplied account reference must match the frozen opaque-reference pattern
before it can enter this typed checkpoint. Invalid account text leaves only the
existing fixed **Unavailable** marker, with no pasted value or new diagnostic
reason. The user can still correct the live field before the form is retired.
The 20,000-unit recovery allowance is not permission to save oversized settings;
the separate complete-settings admission path still applies on commit.

The protocol does not alias the integer-version raw readers, context-policy
version 3 or model version 4. Existing raw Configure checkpoints retain their
original raw editor and reader policy. Raw **Add**, including its role and JSON
settings controls, also retains its existing path. Other typed editors keep their
own protocols and authority.

## Pause, explicit review and retired controls

The form uses the accepted `SelectionEditSection` lifecycle. Actual Activity
pause captures bounded pending input, closes its windows and retires callbacks;
resume does not automatically reopen the form or submit a command. The user must
choose **Review pending input**. Previously retained Commit, supply and choice
controls cannot obtain fresh authority after cancellation, pause or destruction.

Editable recovery requires the same current draft, saved baseline, selection
kind and ID, and catalog item ID/version, with `wasSaving == false`. A mismatch,
removed selection or in-flight checkpoint is presented read-only until explicit
discard. The view distinguishes **Not supplied**, **Supplied empty string** and
**Supplied value**, even when a literal value itself spells one of those labels.
It never rebases retained input onto a different selection.

While a managed commit is in flight, text fields, supply controls, the choice
button and form actions are frozen. A checkpoint can record that a request was
in flight; it cannot acknowledge the request or replay it on resume. A released
worker may finish its already-owned request through the existing store. Recovery
of that in-flight input remains read-only, including when the write subsequently
succeeds.

Native input views disable automatic view-state saving and request no autofill,
suggestions or personalized IME learning. Those are Android control settings and
requests, not guarantees about an operating system, keyboard, accessibility
service, screenshot or physical device. The explicitly bounded checkpoint is the
editor's recovery mechanism. These boundaries follow the Android
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle),
[saved UI state](https://developer.android.com/topic/libraries/architecture/saving-states)
and [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)
documentation reviewed on 2026-10-20.

## Regression and CI scope

The authored regression inventory is source-specific:

| Suite | Methods | Required behavior |
| --- | ---: | --- |
| `PreviewHostingPlatformSettingsTest` | 18 | Frozen metadata; all eight presence combinations; exact literals and types; all choices and absence; reference policy; malformed Unicode; complete settings budget; value-free failures; identity/content preservation; no-op/stale/deleted/removed authority; managed file retention and explicit Build projection |
| `HostingPlatformFormCheckpointTest` | 10 | Actual Bundle/Parcel round trips; exact primitive keys and protocol; all five identity bounds; complete 20,000-unit budget; omitted/empty values; admitted LF references; invalid-account exclusion; separate legacy readers and in-flight state |
| `HostingPlatformSettingsActivityTest` | 22 | Labelled controls and input settings; raw Add; literal round trips; choices, cancellation and no-op; explicit/read-only recovery; all five mismatched identities; actual simulated pause/resume and retired callbacks; released in-flight worker; stale/removed selection and legacy raw Configure |

Two additive literal CI attendance checks cover the Core suite and the two Preview
suites. They require the exact class and method names, exact case count, no
missing/unexpected/duplicate cases, matching aggregate test totals, zero aggregate
failures/errors/skips and no per-case failure/error/skip node. An empty report,
skipped method or old source's report cannot substitute for current-source
execution. These checks do not use `continue-on-error` or convert a failing result
into a pass.

Authored methods and synthetic tests of the attendance parser are not evidence
that the Kotlin tests executed. A receiving review must retain the actual source
identity and original current-source results. Core execution establishes JVM
behavior under its fixtures. Robolectric establishes the specified simulated
Activity/Bundle/worker behavior. Both remain separate from the installed-preview
job, physical-device interaction, accessibility acceptance and release evidence
described in [CI_BASELINE.md](CI_BASELINE.md) and
[ANDROID_PREVIEW.md](ANDROID_PREVIEW.md).
