# Loaded example versions in local Help

This is a partial contribution to
[Android #57](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/57).
The existing Help panel identifies the current preview application and the
exact loaded public example when its bytes match the bundled fixture. It does
not configure a remote API, staging or production variant.

## Actual source and consumer

The implementation begins at accepted Android
`44b4c7e3e882301a45a232b3ee18523023739bb1`, tree
`0d6e0e1f076a271a3e310cc6e91e7f7fe09565d5`. The bounded
[source claim](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/57#issuecomment-6258376382)
records the current issue and complete active-PR path inspection. Existing
catalog, autosave, installed-navigation, viewport, Graph and support controllers
retain their source and acceptance responsibilities.

[`PreviewActivity`](../preview/src/main/kotlin/ai/pickyour/android/preview/PreviewActivity.kt)
already reads bounded `assets/catalog.json` on its worker. The existing
[`CatalogFixtureData.read`](../preview/src/main/kotlin/ai/pickyour/android/preview/CatalogFixtureData.kt)
uses the fixed-schema
[`PreviewCatalogBrowser.read`](../core/src/main/kotlin/ai/pickyour/core/preview/PreviewCatalogBrowser.kt)
boundary, which hashes the exact original bytes. The private CatalogFixtureData
constructor prevents manually constructed public browse rows from supplying
this Help projection. A completed worker result replaces the current container
on the UI thread; catalog and pricebook admission behavior is unchanged.

The existing Help constructor receives a callback returning only the closed
`HelpCatalogIdentity` enum. It receives no catalog object, private draft,
arbitrary ID/version, hash, path, URL, file, log or exception. The Activity
withholds the loaded identity while the existing Retry control is disabled for
catalog loading. Otherwise it projects the current container. Missing data
defaults to unavailable. This adds no file read to the UI thread or second
loader, and grants no draft, catalog, source or service authority.

[`LocalHelp`](../preview/src/main/kotlin/ai/pickyour/android/preview/LocalHelp.kt)
captures the finite state once, after its current/resumed/alive guard, and renders
only source-owned resources. An open dialog retains its captured display while
the worker completes. Close and reopen Help to read the completed state. All
existing navigation consent, one-use dispatch, replacement, dismissal, pause and
destruction checks remain intact.

## Display states

| Actual captured state | Displayed catalog identity |
| --- | --- |
| Existing catalog load in progress, no container, or failed/malformed/unsupported catalog | Versions unavailable, with guidance to close Help and retry loading the bundled catalog. No known catalog ID, revision or schema label is borrowed. |
| Structurally admitted catalog whose exact byte hash differs from the source-owned bundled example | Unrecognized loaded bytes. No raw catalog ID, hash, revision, schema value or failure message is rendered. Even identical IDs and versions with different bytes do not receive the known label. |
| Structurally admitted catalog with the exact known original-byte hash | Loaded bundled catalog `catalog.fixture.1`; revision `1`; catalog schema `1.0.0`; explicitly synthetic example versions. |

Help also displays the compiled application ID, existing app version/code and
Android API level, the current **synthetic local preview** mode and **Remote API:
not configured**. Android API is the operating-system SDK level, not a remote
service version. No selected staging/production endpoint or remote capability
profile exists in this preview. These labels do not imply that the OpenAPI file
is packaged, an API is deployed, or source rights and current data are qualified.

The known label identifies the catalog only. A missing or rejected pricebook
does not change the identity of an otherwise correctly loaded catalog, and Help
does not claim a pricebook version, rate, capacity or quality result. A valid
different catalog retains its existing browsing behavior; this conservative
identity display adds no new admission or promotion route.

## Exact public fixture binding

The accepted original Research commit is
`9769fcd9ea380b1211669b2753181c7782444178`. Its `contracts/v1` tree is
`b1c4eaa4037f2a1e02103849064a947e758a2b40`; that second identifier is a tree,
not a commit. The Android imported subtree remains
`056f9896730547063d10e19a3c80d796622e05da`, including its import metadata.

| Source input | Exact value |
| --- | --- |
| Packaged source | `contracts/v1/examples/catalog.json` |
| Original bytes | 23,827 |
| Git blob | `062af97f42f32dc4358748f6c45ac35b602a3d2b` |
| SHA-256, used only for private comparison | `2e9120db823faef3e1e330a7d2e8067b3ab064328e2faed699d15cf41880fbf8` |
| Public fields in those exact bytes | `schema_version=1.0.0`, `id=catalog.fixture.1`, `revision=1` |

The existing [import manifest](../contracts/v1/IMPORT_MANIFEST.json) pins those
same bytes. The new known-label test reads the actual packaged asset, verifies
its hash and fields, and admits it through the existing loader before inspecting
the actual Help controls. A future legitimate fixture replacement needs a
reviewed identity-label update; a changed artifact must not inherit the old
label merely by copying an ID or version. Neither the known comparison hash nor
another catalog's hash is displayed or exported.

## Diagnostic and support boundary

The existing [ten-field diagnostic report](LOCAL_DIAGNOSTICS.md), its source,
resources, tests and preview/share consent remain unchanged. The Help action
still dismisses Help and invokes the original diagnostic preview. It does not
append these catalog labels to the report, chooser Intent, public GitHub URI,
clipboard, file or background request. `support_submission=unavailable` retains
its original direct-submission meaning.

The [support source note](SUPPORT_HELP.md) receives only a bounded constructor
input clarification. The separate #494 support receiving work retains its
original observations, proposed placement and ten-field diagnostic contract.
No issue form, reporting destination, submission receipt, support rota, private
channel, authority, permission or dependency changes here.

## Verification and original failures

The first new single-method attempt stopped at test compilation because the
test referred to Jackson directly even though it is not exported onto the
preview's test compile classpath. No new method ran in that attempt. The test
alone was corrected to use the Android JSON API already available in this
module; no dependency, product parser or fixture assertion was changed.

The corrected baseline method then executed against unchanged production
`44b4c7e3`. Its packaged hash, public field and actual loader assertions passed;
it failed because Help omitted the loaded public catalog label. The original
one-failure/zero-error/zero-skip XML and both attempt logs are retained separately.
No compilation failure is counted as the actual UI regression.

The dedicated Help tests cover the actual known fixture, a valid private-ID
catalog, unchanged identities with different bytes, malformed and unsupported
catalogs, a failed source read, a missing pricebook and successful recovery. They
also exercise real Retry/loading/completion/reopen behavior, stable captured
text, unchanged stored files, no outgoing Intent and exact original diagnostic
content. All original eight Help and eight diagnostic cases remain, including
private/pending data, missing handlers, consent/lifetime and 320 dp RTL at 200%
font. The existing CI attendance program adds all three new Help method names.

Use the existing pinned, already provisioned toolchain for bounded local checks:

```sh
./gradlew --no-daemon --offline --dependency-verification=strict \
  --max-workers=1 -PandroidPreview=true \
  :preview:assembleDebug :preview:lintDebug :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.LocalHelpActivityTest \
  --tests ai.pickyour.android.preview.LocalDiagnosticsActivityTest
```

Actual corrected command completion, named XML, original-byte APK/resources,
lint, source hashes and failures belong in the exact-source PR receipt. This
note describes coverage; it does not substitute expected methods for attendance.
No installed device, browser submission, remote API, provider, physical
accessibility, production variant or release execution is claimed by these
local simulated controls. Historical main receipts retain their original source.

## Remaining #57 acceptance

This supplies only version context for the current local preview. The literal
#57 endpoint/identity/capability sources for staging and production, selected
variant qualification, wrong-environment mutation prevention, missing remote
configuration failures and their service/operating inputs remain outstanding.
The accepted [service baseline](SERVICE_BASELINE.md) is a deployment decision
with explicit later operating gates, not an endpoint supplied by this change.

The intended canonical acceptance record remains
`Pick-Your-AI/pickyour.ai/docs/delivery/android/work-packages/02.02.md`.
This engineering note moves no record and changes no criterion, assignee, native
relationship, Project field, frozen contract or authority. #57 acceptance remains
open until its full literal outcome and receiving evidence are delivered.
