# Privacy and data in the synthetic preview

**Privacy & data** is available beside Help in the admitted guest-local preview.
It explains the current storage and external handoffs without reading a draft or
creating a diagnostic report. This is a bounded implementation contribution to
[Android #43](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/43), whose
native prerequisites #23 and #25 are accepted. It does not complete the issue's
release-wide SDK inventory, privacy-policy, Google Play declaration, actual
network-observation or release-review criteria.

## Source-backed disclosure map

The text in [`privacy_strings.xml`](../preview/src/main/res/values/privacy_strings.xml)
describes the enabled preview, including its separate local data stores:

| User-facing topic | Current behavior and source |
| --- | --- |
| Local drafts | `PreviewActivity` opens `PreviewDraftLibrary` and `PreviewActiveDraftStore` under `noBackupFilesDir`. `PreviewDraftPersistence` saves title/selection metadata, objective/context, workload, component settings and optional-tier state through the canonical durable store. Eligible edits use the existing autosave controller; only acknowledged saves are confirmed. The draft ID, active selection pointer and retained revisions stay local. |
| Catalog and saved snapshots | The bundled loader retains catalog bytes under `noBackupFilesDir` through `CatalogRetainedSnapshots`. `SnapshotEvaluationSection` can read an existing private `filesDir/retained-snapshots` archive; it does not create one. Draft deletion does not delete that archive or the separate catalog cache. Catalog and price examples remain synthetic, and inspecting them makes no provider request. |
| Reviewed diagnostics | `LocalDiagnosticReport` accepts only finite storage/catalog states. Its ten fixed fields contain the compiled app identity/version, Android API level and fixed preview declarations, with no draft, component identity, path, file, log, credential, account identifier or content-derived digest. `LocalDiagnostics` displays the exact report before a separate one-use `ACTION_SEND` chooser. The receiving app can transmit or retain the resulting copy; this preview does not observe delivery. |
| External browser | `LocalHelp` displays one fixed public GitHub destination before its separate one-use `ACTION_VIEW` handoff. The URI has no draft-derived data, report, attachment or URI grant. The external browser/site owns subsequent networking, sign-in and submitted content. `CatalogSourceNavigation` retains an empty default host allowlist in this preview, so catalog sources cannot dispatch. |
| Draft deletion | `PreviewDraftLibrary.delete` removes the selected draft's local retained artifacts through its existing confirmation and interrupted-cleanup flow. It does not remove external copies or promise secure erasure. Separate catalog and saved-snapshot stores remain until separately removed or app data is cleared. |
| Backup and recovery | The manifest declares `allowBackup=false` and `fullBackupContent=false`; `backup_rules.xml` excludes all supported app-data domains from both cloud backup and device transfer. Drafts/catalog copies additionally use `noBackupFilesDir`. The disclosure states the configuration, without promising behavior on every device. There is no connected draft-file export or cloud restore in the preview. |
| Enabled service scope | The preview is the sole admitted build environment; real-data modes fail closed and release variants are disabled. The application manifest has no requested permissions or declared service, receiver or provider. The app adds no account, synchronization, advertising, analytics or crash-reporting SDK. Runtime parser/validator dependencies remain governed by the existing locked and verified dependency inventory; this feature adds none. |

The fixed disclosure is not a formal legal policy or a declaration that no
information can leave the device. The diagnostic chooser and public browser
handoffs are explicitly described. Device operating-system services, a keyboard,
the receiving app and GitHub are not controlled by this read-only view. Future
changes to collection, runtime dependencies, admitted environments, backup,
export, support or navigation must revisit this map and the displayed text.

## View and lifecycle

[`LocalPrivacy`](../preview/src/main/kotlin/ai/pickyour/android/preview/LocalPrivacy.kt)
accepts its Activity, a lifecycle predicate and an admitted before-display
callback that retires older catalog parent, detail, price and source windows,
along with comparison and complete cost inspections. It receives no draft,
report, URI, storage handle or external dispatcher. Its only dialog action is
Close. Opening a fresh dialog retires the prior one; delayed dismissal of a
prior dialog cannot clear the current reference. Pause dismisses it, and
destruction permanently closes its owner. A retained entry from a destroyed
Activity cannot reopen it. No preference, consent state or save authority is
created by reading the page.

The native ScrollView contains wrapping resource text and six distinct section
headings. API 28 and later expose heading and pane-title semantics. The entry
button has a minimum 48dp target. The dialog follows the current layout
direction and keeps its standard Close action outside the scrolling text.
This follows Android's documented
[View accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/views/principles-views).
Android's [Auto Backup documentation](https://developer.android.com/guide/topics/data/autobackup)
also documents the `noBackupFilesDir` exclusion. Both primary references were
read on 2026-10-19; device behavior remains a separate evidence boundary.

## Regression boundary

`LocalPrivacyActivityTest` uses the actual Activity, controls and private files
in Robolectric SDK 28. Its nine methods cover fixed nonidentifying disclosures,
the enabled manifest capability boundary, cancel/replace behavior, pause/resume,
terminal owners, recreation, and each rendered line at 320dp with RTL and 2x
font scaling. The preservation cases seed acknowledged private draft text,
then keep distinct pending edits; they compare the saved-state inputs and
hashes of files in both private storage roots. `AutosaveUiClock` pumps requested
UI frames while remaining strictly before the real 750ms autosave deadline.
Opening this view is not allowed to write just because a test advanced time.

Two catalog interaction methods replay retained parent and detail/price/source
controls while Privacy is open, after it closes and after fresh inspection.
They require the older owners to retire, preserve pending and stored data, and
keep fresh selection and inspection usable. The source-recovery sample has no
URI and exercises missing-source Back recovery; it does not add a browser
destination or establish an external-navigation result.

The workflow requires exactly these nine named cases, the matching suite and
class identities, no duplicates/failures/errors/skips, and consistent totals.
These are native-control JVM simulations, not installed-device network traces,
manual TalkBack traversal, physical-device deletion/backup observations, a
release SDK audit or Google Play Data safety acceptance. Existing diagnostic and
Help tests continue to own actual chooser/browser intent-contract verification.
