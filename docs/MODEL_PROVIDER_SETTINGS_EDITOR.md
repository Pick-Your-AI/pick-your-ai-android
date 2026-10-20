# Typed model-provider settings

Partial [Android #99](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/99).
Fresh **Edit settings** for an existing frozen `model_provider` selection opens
three labelled native text controls. Each field has an explicit **Supply**
checkbox. Opening the editor does not add defaults or change a draft. The existing
raw Add form still requires explicit role, enabled state and settings; legacy raw
Configure recovery retains its original editor.

| Frozen field | Supplied value | Omission |
| --- | --- | --- |
| `region` | Exact Unicode string, including empty, whitespace, mixed case or `unknown`; a declared, unverified region | Clear **Supply Region** |
| `credential_ref` | Exact reference admitted by the existing frozen pattern, including its permitted terminal LF | Clear **Supply Credential reference** |
| `api_base_url` | Exact URI admitted by the existing frozen format and new-input authority-userinfo policy | Clear **Supply API base URL** |

All three fields are optional frozen strings. The controls do not reinterpret
JSON-looking input, trim whitespace, normalize case, add an endpoint, choose a
region or invent a credential. A supplied empty Region remains different from an
absent Region. The editor shows an absent hint only when **Supply** is clear.
Read-only recovery labels absence, a supplied empty string, and a supplied value
separately; literal text such as `Not supplied` cannot stand in for the state.
No new answer-state vocabulary or verification result follows from this input.

## Admission and persistence

Field guidance comes from the accepted frozen settings metadata. Preparation uses
the existing complete Selection receiver for field feedback, followed by the
existing settings command adapter for aggregate admission. Correctable errors
identify a field and a fixed reason without echoing entered values. There is no
partially configured command after a rejection.

An individual field is bounded by the existing 16,384-character settings-editor
limit. The complete serialized settings object, including JSON escaping and any
retained settings, must also fit that same limit. Input is never prefix-truncated.
The complete receiver still enforces Unicode, schema and JSON budgets. A field
that fits independently can therefore remain unsavable when the complete object
exceeds the existing budget.

Commit changes only the three explicitly configured settings on the original
selection. Selection identity, catalog identity/version, role, enabled state,
notes, extensions, other selections and links remain intact. The managed adapter
uses the captured draft revision and hash: unchanged input is a no-op, a stale
writer cannot overwrite another revision, and a removed selection cannot be
recreated by this editor. A successful acknowledgement means local persistence.
Existing history remains immutable and explicitly validated Build projection uses
the saved values. The form neither contacts a provider nor resolves a reference,
opens a URI, verifies region availability, quotes a price or establishes fit.

The URI policy remains the existing narrow new-input policy: recognized user
information in the authority is rejected. Legitimate custom schemes, exact
spelling and `@` outside the authority remain subject to the frozen URI receiver.
This does not detect arbitrary secrets in Region, query text or other strings.
Historical saved URI values remain readable; a rejected new edit leaves the
acknowledged saved value intact for explicit correction.

## Private pending-input recovery

All text controls disable automatic View-state saving and autofill, and request
multiline literal input, no dictionary suggestions and no personalized keyboard
learning. These input-method flags are requests, not evidence that an installed
keyboard honors them. Labels refer to generated native control IDs. During a
submitted operation the editor freezes its text, Supply controls and cancellation;
closed controls cannot submit another command.

The bounded private checkpoint is the sole pending-input capture for these
controls. It keeps three nullable strings, the original draft/baseline,
selection/catalog identity and an in-flight marker. The three strings together
may contain at most 20,000 UTF-16 code units; identity fields have their existing
separate bounds. Oversized input produces unavailable recovery, without a prefix
or automatic View-state fallback. Capture and decoding independently exclude an
invalid credential reference. For bounded input, positively recognized URI user
information retains only the fixed exclusion marker, even when another field is
invalid. The live form remains available for correction; these exclusions do not
clear its text.

The inner bundle uses the namespaced string version `typed-model-provider/1`,
form `modelProvider`, kind `model_provider`, exact primitive types and exact keys.
It remains under the existing `componentFormRecovery` outer key. A distinct string
version avoids collisions with independently added integer versions. Existing raw
v1/v2, context v3 and model v4 readers retain their original protocols.

Recreation does not reopen a writable form or replay a save. Explicit **Review
pending input** is required. A matching acknowledged draft, baseline, selection
and catalog identity can offer the typed editor again. An identity mismatch or an
in-flight capture offers read-only values and explicit discard, with no fresh
save authority. Canceling a writable editor or explicitly discarding recovery
removes pending input. Closing read-only review retains the checkpoint for later
review or discard. Recovery never proves whether an interrupted operation committed.

## Required regression attendance and remaining acceptance

The additive CI attendance guards require each of the following declared methods
exactly once with zero failures, errors or skips:

| Suite | Required methods | Scope |
| --- | ---: | --- |
| `PreviewModelProviderSettingsTest` | 19 | Frozen strings, literal and aggregate limits, privacy, managed persistence and history |
| `ModelProviderSettingsActivityTest` | 19 | Native controls, managed Activity/worker integration, recovery and stale/retired input |
| `ModelProviderFormCheckpointTest` | 12 | Exact primitive protocol, bounds, privacy, legacy compatibility and safe diagnostics |

The existing generic Configure input-method test also locates the typed provider
credential control and checks exact pending reference text, cancellation and
unchanged durable values. Existing attendance guards are retained. Declared test
inventory is not an execution result; qualification must identify the actual
source tree and its original runtime output. Robolectric evidence does not qualify
an installed provider Configure journey, process death, TalkBack, a physical
device, a keyboard implementation or release.

This continuation preserves the accepted Kotlin/Views/store architecture and the
existing receiving-record locations. It does not complete all kind-specific forms,
typed Add, objectives or answer-state behavior, or establish whole-issue #99
acceptance or its remaining prerequisites. Installed/native, accessibility and
release acceptance remain separate.
