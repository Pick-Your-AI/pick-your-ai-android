# External navigation boundary

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
