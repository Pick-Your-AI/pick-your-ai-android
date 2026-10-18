# External navigation boundary

## Incoming entry and local recovery (#76, partial)

The exported diagnostic Activity accepts an ordinary launcher `ACTION_MAIN` or
an explicit component launch with no action, provided there is no data URI, MIME
type, ClipData or selector. Other actions and those payload envelopes show a
generic unsupported-entry message. The preview does not interpret extras as a
route, draft identity or imported content, read a supplied content URI, echo the
payload or forward it to another app. It declares no incoming link/share filters.

A rejected cold entry does not load the active local draft or acquire its save
authority. The local library and new-draft actions remain available. Selecting
a library row still requires the existing switch confirmation and successful
read/active-pointer commit before the selected draft becomes editable. Creating
a draft still uses a new local identity and the same verified open path. Failed
or cancelled recovery leaves the entry unresolved. Merely editing the unbound
form or retrying the launcher cannot silently bind it to the previous draft.

An unsupported warm entry leaves the current editor, pending fields, draft ID,
saved baseline and any ongoing storage operation unchanged. Its visible error is
separate from saved/saving/failed status. A normal warm launcher event preserves
the editor too. The manifest uses `singleTop` to reuse the Activity when it is
already at the top of the target task. `onNewIntent` handles that incoming event
without launching a second editor. This does not promise a global singleton
across tasks, windows or caller-supplied flags.

Saved instance state carries the unresolved-entry and visible-error decisions,
alongside the existing bounded local editing checkpoint. Recreation of an
unresolved entry cannot acquire the active draft. After an explicit successful
local recovery, recreation restores that local selection rather than replaying
the consumed external request. The normal baseline check still determines save
authority; the route checkpoint never acknowledges a durable write. Raw incoming
URLs, content and extras are not copied into the app's saved-state Bundle.

Back retains Android's normal task behavior. In a cold rejected entry it returns
through the calling task or the platform's root-launcher behavior; there is no
synthetic editor back stack. In a warm rejected entry the current editor remains
the same Activity. Dialogs keep their existing cancellation and captured-authority
guards. Actual task routing and Back behavior require the installed cases below.

Primary references checked 2026-10-18: Android's [tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack)
defines `singleTop`, its per-task limit, caller flags and version-dependent root
Back behavior; the [Activity `onNewIntent` API](https://developer.android.com/reference/android/app/Activity#onNewIntent(android.content.Intent))
defines delivery to a reused Activity. The existing JDK21/Gradle8.11.1/AGP8.9.2,
compile/target35 and min26 preview pins are unchanged.

`IncomingEntryTest` has seven mandatory paused-looper cases. The original case
failed on main `8162a5571147874635d3139e73a171a1077a6d2b`: after saving and destroying
the prior Activity, an unsupported `ACTION_VIEW` silently displayed `previous
local draft` instead of an unbound editor. The unchanged case passes with this
correction. Additional cases cover seven unsupported action/envelope variants,
warm pending title/objective/exact decimal restoration and subsequent save,
ordinary cold/warm launches, blocked recreation/launcher retry, selected-library
recovery and new-identity recovery. They use the actual serial worker and local
storage, asserting source baselines, active identities and absence of forwarding.
These are Android simulations, not installed launch, process-kill or task-stack
acceptance. Run the opt-in native checks in [ANDROID_PREVIEW.md](ANDROID_PREVIEW.md).

Local verification on the stated base passed unsigned assembly, lint and all
128 native cases in six actual XML reports with no failures, errors or skips.
All five required native workflow report programs ran against those reports.
Lint reported fourteen warnings and no errors. The unsigned APK SHA-256 was
`3773d7195d30a2e3ed382fda834ff75cfa1402a75ec5db1575298a4ef3053386`;
its 113 packaged entries match the retained main artifact's entry set, with only
the two affected DEX files, manifest and string resource table changed. The
candidate's hosted checks and reviewed main delivery remain separate receipts.

The [library/lifecycle criterion audit](LIBRARY_LIFECYCLE_AUDIT.md) preserves all
#76/#86 criteria and missing evidence. Stable supported catalog, builder, library,
comparison and shared-entry routes are still incomplete. This correction supplies
no shared-build route format, verified Android App Link, remote object access,
revocation behavior or production import. Those require their existing contracts
and receiving tests; a fail-closed unsupported state does not complete #76.

## Outgoing source policy

Partial implementation for Android #31, #97 and #124. `ExternalLinkPolicy` is a
pure JVM policy that accepts a candidate only for an exact configured ASCII HTTPS
host, with no userinfo and only the default TLS port. It checks length before
parsing and rejects ambiguous authorities, privileged schemes, backslashes,
non-ASCII display controls and malformed escapes. Result diagnostics do not echo
the destination or its potentially private query parameters. No host is trusted
by default; the application must supply its reviewed source host set.

The policy follows the parsed scheme and complete host validation described in
[Android's unsafe URI loading guidance](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading),
consulted 2026-10-18. It is deliberately stricter than generic URI syntax. Unicode
paths must be percent-encoded; arbitrary external sites and non-default ports are
not supported by this bounded policy.

| Criterion | Executable evidence | Remaining gap |
| --- | --- | --- |
| #31 untrusted external navigation / malformed input | `ExternalLinkPolicyTest`: hostile schemes, spoofed hosts, userinfo, encoded authority, whitespace/control, lengths; valid adjacent URLs. Simulated Activity cases reject hostile source URIs before any Intent dispatch. | Installed external-handler/document flows and release-candidate security review |
| #97 safe source-link opening | Exact allowlist; immutable source selection; complete URL confirmation; current-owner and single-use dispatch; recoverable missing/restricted handler. | Reviewed real source host configuration and installed browser/handler behavior |
| #124 hostile URL handling | Early syntax/authority rejection | Verified Android App Links, domain/certificate association, share authorization, cold/warm navigation, revoke behavior and install-free fallback |

A candidate is not a safety, evidence, source-rights or authorization verdict.
Opening it still requires a user action. An external browser controls its later
redirects; this policy makes no redirect-confinement promise. Future controlled
HTTP or WebView adapters must separately validate each redirect and never treat
this browser policy as an SSRF filter. The core policy performs no DNS resolution,
network, browser launch, WebView, Intent, fetch or draft mutation.

## Android source-navigation adapter

`CatalogSourceNavigation` connects the policy to the Android detail dialog through
**Review sources**. It keeps the immutable selected item/version/snapshot and
provenance ordering, shows at most eight rows per page, and exposes all remaining
records through explicit previous/next actions. Row text uses the existing bounded
literal escaping; a truncated row cannot serve as navigation consent.

Only a permitted source can open a confirmation. That confirmation displays the
full outgoing URL (up to the policy's 4,096-character limit) and its parsed host,
and explains that Android chooses an external handler which can use its own
session and follow redirects. The HTTPS scheme is normalized to lowercase for
Android matching; authority, encoded path, query and fragment are unchanged. The
recorded source string remains unchanged in the detail projection.

The confirmation checks the captured Activity, draft baseline/identity and catalog
snapshot again, then revalidates the exact source immediately before dispatch.
Cancelled, dismissed, replaced or retired dialogs have no authority; a retained
confirmation can dispatch at most once. No source URI is auto-opened on catalog
load, detail viewing, source selection, cancellation or Activity recreation.

Dispatch constructs a fresh `ACTION_VIEW` Intent with `CATEGORY_BROWSABLE` and only
the confirmed HTTPS URI. It does not parse an Intent from source text, attach
draft fields, extras, ClipData, a selector/component, or URI-grant flags. The
adapter performs no in-app network request or WebView load. `ActivityNotFoundException`
and `SecurityException` produce a generic recoverable message without logging
platform exception text; another attempt requires a new source choice and confirmation.

The application supplies **no trusted hosts** in this synthetic preview. Tests
inject `docs.example.com` explicitly; that test configuration does not become an
application trust decision or alter frozen catalog examples. Source-host
configuration must be reviewed with the actual published catalog before enablement.

The implementation follows Android's [URL handling and package visibility
guidance](https://developer.android.com/training/package-visibility/use-cases#open-urls),
checked 2026-10-18: start the Intent and handle an unavailable receiver without
adding package-visibility queries. Android can choose a browser, another matching
app, or a disambiguation dialog; this adapter does not promise a particular browser.
The [Uri API](https://developer.android.com/reference/android/net/Uri#normalizeScheme())
defines the scheme-only normalization. Manifest permissions, exported components,
package visibility and signing configuration remain unchanged.

Eight required paused-looper Activity cases exercise the actual detail entry point
for absent sources and the configured adapter for full consent, exact Intent
content, cancellation/replay, hostile/unconfigured sources, paging, unavailable
and restricted handlers, changed authority, recreation and long URL presentation.
These are JVM Android simulations; they establish neither a real browser launch,
installed-app recovery, redirect confinement, source rights nor full issue acceptance.

Run `./gradlew --no-daemon :core:check :core:assemble` and the checks in
[CORE_FOUNDATION.md](CORE_FOUNDATION.md). JVM evidence does not establish device or
App Links completion. Canonical acceptance records remain in `pickyour.ai` at
`docs/delivery/android/work-packages/14.05.md`, `07.03.md` and `11.06.md`;
parent coordination should transfer this partial evidence without closing issues.
