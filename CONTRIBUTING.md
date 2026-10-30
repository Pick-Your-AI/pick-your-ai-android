# Contributing

Current team and Project names: [organization naming register](https://github.com/Pick-Your-AI/.github/blob/ce8c876f6331b4f427d7fc8c2deff4776d003cf5/docs/NAMING.md).

Start from the existing fully qualified planning issue linked in the README. Read its acceptance criteria and decision-record path, reconcile prerequisites, confirm the live assignee and receiving reviewer, and inspect overlapping PRs. Do not infer implementation assignment from repository creation or research ownership.

Use a scoped branch, signed commits and a pull request. Obtain independent receiving review and satisfy repository rules before merge. Include the canonical issue, changed behavior or guidance, evidence, limitations and required handoffs. Do not close a work package solely because a PR merged; reconcile every acceptance criterion with receiving review and evidence.

## Access, branches and reviews

Early contributors push branches named `contrib/<your-login>/<topic>` to this repository; other contributors work from a fork. Every pull request into `main` needs one approval from someone other than its author, and only maintainers merge. The [organization contributing rules](https://github.com/Pick-Your-AI/.github/blob/main/CONTRIBUTING.md) define roles, credentials and enforcement.

The organization's team model assigns Triage to the `Mobile Apps` parent team and Write to the `Android` child team. Triage permits issue and pull-request triage but does not itself grant push access. `Lead Maintainers - Mobile Apps` receives team-granted Admin access; organization owners retain their repository administration rights, and a person's effective access can also include other grants. Early Contributors use their Write access only for their `contrib/<your-login>/<topic>` branches. Team membership and settings are managed by organization owners and designated GitHub team maintainers under the [accepted delegation record](https://github.com/Pick-Your-AI/.github/issues/18); repository Admin access and operational ownership are separate.

For the exact Android check names, source/artifact verification, intended main-branch policy and failure triage, read [CI_BASELINE.md](docs/CI_BASELINE.md). Its observed-settings section distinguishes proposed enforcement from actual repository readback; green checks alone do not configure protection.

Run `git diff --check`, check relative Markdown links, and manually review scope and canonical references. For core/contract changes, follow [CORE_FOUNDATION.md](docs/CORE_FOUNDATION.md): verify the frozen import, run the Python reference suite and `./gradlew --no-daemon :core:check :core:assemble`. Never claim installed Android or production validation from JVM, reference or documentation checks.

The [pinned secret-scanning check](docs/SECRET_SCANNING.md) runs against the exact
checkout and its reachable history. Review sanitized findings without copying
matched values into public comments; successful pattern detection is one security
check and does not establish dependency, credential-rotation or release acceptance.

Use the [security response runbook](docs/SECURITY_RESPONSE.md) for vulnerability
intake, containment, urgent fixes and credential/key recovery. Its reproducible
local rehearsal preserves the difference between current-file removal and an
unresolved historical finding; it establishes no live response or release.

The [runtime inventory](docs/RUNTIME_INVENTORY.md) links the resolved core and
optional unsigned-preview runtime inputs to their build artifact hashes. Review
POM license declarations and missing/inherited metadata before using it for any
distribution decision; the diagnostic inventory does not supply release notices.

Keep local build, simulator/emulator, physical device, store and production results distinct. Only perform release, provisioning, spending or outreach when the authority actually granted permits it. Read the organization [code of conduct](https://github.com/Pick-Your-AI/.github/blob/main/CODE_OF_CONDUCT.md).

For preview/toolchain changes, also run the opt-in unsigned Android build and lint
commands in [ANDROID_PREVIEW.md](docs/ANDROID_PREVIEW.md). Keep SDK/signing material
out of source. The ordinary preview build remains unsigned. The separately bounded
[installed-emulator CI lane](docs/EMULATOR_SMOKE.md) creates a disposable test key
outside source and installs only in its own fresh AVD; its observed receipt is
distinct from simulated tests, physical-device acceptance and release signing.

## Routing decisions

For an unresolved decision, first read the existing issue, its assignees, relevant
decision record and related pull requests. Preserve accepted ownership and local
review, evidence, security and release requirements. Routing identifies a contact;
it does not grant access, appoint an owner, authorize publication or waive a gate.

Use the canonical [escalation guide](https://github.com/Pick-Your-AI/.github/blob/main/docs/governance/ESCALATION.md) and
[versioned routing policy](https://github.com/Pick-Your-AI/.github/blob/main/governance/escalation-policy.json). These links depend on the organization
governance change merging; until then, use its reviewed checkout and describe the
route as provisional. Do not claim the central tooling is installed or active.

The order is repository team → repository maintainer → repository lead maintainer
→ applicable parent teams → CompleteDotTech. Record a disposition at each level,
including unresolved, vacant, pending-acceptance or inaccessible roles; membership,
team-management status and repository permissions do not establish accepted
operational ownership. Keep owner placeholders for the final fallback. Do not
silently skip an unresolved role or treat a proposed mapping as accepted.

An operator with read access must refresh the private snapshot before resolving:

```sh
python /path/to/org-checkout/governance/router.py refresh --policy /path/to/org-checkout/governance/escalation-policy.json --snapshot /private/path/routing-snapshot.json --status /private/path/routing-status.json
python /path/to/org-checkout/governance/router.py resolve --policy /path/to/org-checkout/governance/escalation-policy.json --snapshot /private/path/routing-snapshot.json --repo Pick-Your-AI/pick-your-ai-android --max-age-seconds 86400 --status /private/path/routing-status.json
```

Here `86400` is an **operator-selected example**, not an approved default. Select
an appropriate explicit maximum age; a proposed policy value does not supply one.
Check that refresh succeeded. If refresh fails, stop recipient selection and
report the failure; the last successful snapshot is retained for diagnosis, not
as a current route. Report stale or incomplete coverage explicitly. Keep snapshots,
raw API responses, rosters and private
identities outside public Git and public issues. Share only the necessary public
team destination and a sanitized decision summary.

When escalation is needed, include the decision needed, current responsible role,
prior disposition and proposed next recipient. Reuse information already in the
issue or PR; routine contributions need no full escalation history. Send nothing
and change no assignment automatically. Sensitive reports follow the existing
private security/support process.
