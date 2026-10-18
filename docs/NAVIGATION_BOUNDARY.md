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
| #31 untrusted external navigation / malformed input | `ExternalLinkPolicyTest`: hostile schemes, spoofed hosts, userinfo, encoded authority, whitespace/control, lengths; valid adjacent URLs | Actual Android intent/document flows and release-candidate security review |
| #97 safe source-link opening | Exact allowlist and immutable user-visible candidate; no network or state mutation | Reviewed source host configuration, explicit UI consent, actual browser fallback |
| #124 hostile URL handling | Early syntax/authority rejection | Verified Android App Links, domain/certificate association, share authorization, cold/warm navigation, revoke behavior and install-free fallback |

A candidate is not a safety, evidence, source-rights or authorization verdict.
Opening it still requires a user action. An external browser controls its later
redirects; this policy makes no redirect-confinement promise. Future controlled
HTTP or WebView adapters must separately validate each redirect and never treat
this browser policy as an SSRF filter. No DNS resolution, network, browser launch,
WebView, Intent, fetch or draft mutation is performed here.

Run `./gradlew --no-daemon :core:check :core:assemble` and the checks in
[CORE_FOUNDATION.md](CORE_FOUNDATION.md). JVM evidence does not establish device or
App Links completion. Canonical acceptance records remain in `pickyour.ai` at
`docs/delivery/android/work-packages/14.05.md`, `07.03.md` and `11.06.md`;
parent coordination should transfer this partial evidence without closing issues.
