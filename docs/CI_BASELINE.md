# Android CI checks and merge policy

This is the Android maintainer reference for [#59](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59).
It explains the existing checks, their failure paths and the intended main-branch
enforcement. Repository settings require their own observed readback; a workflow
file or this document does not configure branch protection.

The initial source and settings review on 2026-10-18 used
[`385001803c5084c371b30e6601b69da18b8d22a8`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/385001803c5084c371b30e6601b69da18b8d22a8).
This documentation candidate integrates accepted main
[`0051676e3f0a9e33585eb178015ace531c3cd0b4`](https://github.com/Pick-Your-AI/pick-your-ai-android/commit/0051676e3f0a9e33585eb178015ace531c3cd0b4),
including the stale-catalog callback regression from `174d0b1` and subsequent
accepted native changes. The initial observation remains historical. Later changes
must preserve the accepted native test set and refresh source, check and artifact
evidence.

## Checks that exist on main

| Exact check name | Source job | What its successful execution establishes | Retained artifacts |
| --- | --- | --- | --- |
| `Frozen contracts and JVM core` | [core.yml](../.github/workflows/core.yml), `core` | Frozen-byte/reference and tamper checks, delivery-ledger checks, wrapper integrity, JVM core check and assembly | Core JUnit results, test reports and library output |
| `Unsigned synthetic Android preview` | [core.yml](../.github/workflows/core.yml), `android-preview` | Wrapper integrity, strict dependency verification, unsigned APK assembly, Android lint and all named Activity simulations without failures or skips | Original unsigned APK, lint report and simulated Activity results |
| `Pinned secret scanning` | [secrets.yml](../.github/workflows/secrets.yml), `secrets` | Scanner guards, the actual pinned CLI's required negative fixtures and a clean scan of the exact checked-out source's reachable history | Sanitized `secret-scan.json` with source/tree, tool hashes, fixture results and finding metadata |

These unique names were observed on baseline `3850018` as GitHub Actions checks
(application slug `github-actions`, application ID `15368`). Both workflows run
for pull requests and pushes to `main`, with an explicit manual dispatch available.
They have no changed-path filter. Their concurrency groups cancel superseded runs;
a cancelled older run does not establish the current candidate's result.

Artifacts use the actual checkout SHA in their names and expire after seven days.
Read the run's checkout identity as well as the PR head: a pull-request run normally
tests GitHub's prepared merge commit. Record the artifact ID, downloaded ZIP SHA-256,
embedded source/tree where supplied and the observed test result before relying on
an artifact. An expired or unavailable artifact is an evidence gap, not a pass.

The preview remains an unsigned engineering artifact. Simulated Activity tests do
not establish installation, physical-device behavior, a release signature or store
acceptance. [The installed-emulator work in #206](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/206)
retains its actual four-case gate and failed diagnostic receipts. Its future check
name must not be configured as required until the reviewed job exists on main.

## Intended main policy and observed configuration

### Historical unsaved proposal (2026-10-18)

The following proposal and authentication attempt are historical. Do not replay
their zero-approval or empty-bypass settings over the later live review rules.
The [current additive correction](#current-required-status-gap-2026-10-19-observation)
preserves those rules after a fresh read; neither proposal is applied by this file.

The independent source review and coordinating-agent review accepted this
concrete, prepared ruleset configuration on 2026-10-18. It has not been saved:

| Setting | Intended value |
| --- | --- |
| Ruleset | `Android main review and CI`, intended enforcement `Active` |
| Target | Only `refs/heads/main` in this repository; no excluded branches |
| Delivery | Pull request required; preserve independent review of the actual source and current-main integration |
| Required checks | All three exact check names above, from GitHub Actions application `15368` |
| Current base | Require the candidate to include current `main` before merge; refresh review and execution when the combined source changes |
| Commit identity | Preserve the repository's verified-signature requirement |
| History protection | Disallow force pushes and branch deletion |
| Bypass | Empty bypass list; no administrator, application or creation-check exemption |
| Review settings | Required GitHub approvals `0`; dismiss stale approvals enabled; require review conversation resolution enabled |
| Other review restrictions | Require latest-push approval disabled; no Code Owners or team restrictions added |
| Merge methods | Preserve all three existing methods: merge, squash and rebase |
| Review evidence | Record the actual independent review and its exact source. Do not invent a GitHub approval, a named reviewer commitment or a paid review requirement |

The prepared form selects **GitHub Actions** separately for each required check,
not an unrestricted source. It adds no future installed-HVF, code-scanning or paid
coding-review requirement. A zero configured approval count does not waive the
repository's actual independent source and receiving review process.

GitHub's [protected-branch documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches),
checked 2026-10-18, distinguishes configured required checks from observed checks,
supports selecting their expected application, and explains strict up-to-date
requirements. It also permits skipped or neutral check conclusions under its own
rules. This project's review must still require the actual named tests to execute;
an empty or skipped job cannot substitute for their evidence.

The last **pre-change** readback on 2026-10-18 at main `3850018` reported
`protected: false`, `protection.enabled: false`, required-status enforcement `off`,
empty `contexts`/`checks`, and no repository or parent rulesets. The integration's
detailed branch-protection read was denied with HTTP 403; that result did not
establish an additional rule. [The earlier recorded review](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6246184712)
has the same bounded finding.

The coordinating agent then prepared the exact configuration above in the
repository settings UI and obtained independent review of those concrete values.
Selecting **Create** reached GitHub's **Confirm access** passkey/2FA requirement
before saving. The authentication screen and prepared form were preserved; no
alternate administration route or bypass was attempted. A subsequent ruleset API
read still returned an empty list. **No ruleset was created and enforcement is not
established.** Completion needs the account holder to satisfy GitHub's authentication
requirement, then save the reviewed form and verify the resulting rule identity,
effective branch, enforcement, source applications, strictness and bypass/review
settings. Recheck the form and live state before that final action in case another
authorized maintainer has changed the repository. A reviewed or merged document
does not substitute for this saved-settings readback.

The migrated issue's historical website `ci-required` rule is a separate setting.
Android work must preserve the website's existing checks and protections; it does
not rename, replace or modify them.

### Current required-status gap (2026-10-19 observation)

The [current settings receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/59#issuecomment-6257619397)
records four active Android rulesets but no required-status rule. Main is protected
by its other rules; its classic summary also has empty required contexts. The
existing review ruleset `25362700` requires one approving review, latest-push
approval and extra approval for unattributed changes, and retains its sole
OrganizationAdmin/always exception. These settings supersede the unsaved proposal
above, not its preserved historical observation.

The reviewed correction is to append the three exact check names above, each with
ruleset `integration_id: 15368`, while retaining the entire current rule,
default-branch condition and existing exception. Proposed status parameters are
`strict_required_status_checks_policy: true` and `do_not_enforce_on_create: false`.
Leave rulesets `25359638`, `25359637` and `25359641`, and the separate website rule,
unchanged. This is a preservation strategy, not authority to replay a stale body:
fresh-read all settings, reconcile concurrent edits, perform only the authorized
additive action, then verify the complete result and unchanged other rules.

Actual application remains blocked by the available administration surface. No
setting or authentication bypass is supplied by this documentation. The installed
correction remains with [#467](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/467);
append its fourth check only after corrected source is accepted on main and an
applicable actual check exists. The [02.05 receiving record](delivery/android/work-packages/02.05.md)
keeps settings, credential-context and installed evidence separately incomplete.

## Credential and review boundary

The inspected workflows use `pull_request`, `push` and `workflow_dispatch`, grant
only `contents: read` to `GITHUB_TOKEN`, disable persisted checkout credentials,
and reference no signing or production secret, deployment environment or release
upload. Actions are pinned to full commit SHAs. The preview's debug signing is
disabled. Do not add production keys to these build jobs or execute contributor
source in a privileged release job.

GitHub documents that [fork pull requests receive a read-only token and do not
receive other repository secrets](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflows-in-forked-repositories).
Its [secure-use reference](https://docs.github.com/en/actions/reference/security/secure-use)
describes the risks of privileged `pull_request_target`/`workflow_run` processing of
untrusted source. These references were checked 2026-10-18. Workflow-source review
does not establish the repository's actual secret, organization-secret, environment
or fork-approval settings; inspect those contexts separately without exposing values.
Any future signed release needs a separate reviewed authority and credential boundary.

Normal source review remains required even when all checks pass, including review
of workflow/test changes that could otherwise produce a misleading green result.
The existing signed preparation process uses an isolated feature/staging PR,
independent review and its normal gates. After a normal GitHub squash, verify the
signature, unchanged reviewed tree and expected parent. A separate main-target PR
then receives current-main review and fresh checks. This process must not weaken
main's settings or treat an unsigned source commit as already signed.

## Failure triage

| Failure | Maintainer response |
| --- | --- |
| Frozen contract/reference failure | Inspect the first failed command and exact changed path. Preserve canonical manifests, checker and frozen bytes. A substantive contract change belongs to the accepted versioning process, not a changed expected hash to make CI green |
| JVM domain failure | Read the actual JUnit failure and reproduce the affected behavior with the pinned wrapper/toolchain. Preserve unrelated passing cases and review the focused repair |
| Native compile, lint or simulation failure | Read the failed phase and retained report. A successful APK compile does not compensate for a lint error, missing named test, failure or skipped case |
| Secret finding or scanner error | Follow [SECRET_SCANNING.md](SECRET_SCANNING.md). Keep raw content and diagnostic streams private, inspect sanitized metadata, repair or review the underlying issue and rerun the real fixtures. Do not add an ignore or convert scanner/source-read failure into a clean result |
| Runner, SDK, network or emulator setup failure | Retain the exact setup output and mark unexecuted tests as unexecuted. Diagnose a concrete cause before a bounded retry. Do not change host permissions or claim an app crash from a setup/launch timeout |
| Missing, cancelled or unexpected-source required status | Compare exact context spelling, job source application, workflow event, source SHA and current base. Re-run or repair the actual intended workflow; do not manually manufacture a successful status or remove the requirement |
| Main or PR head changed | Recompute the combined tree, review any new delta and obtain current checks. Re-read refs immediately before the ordinary expected-head merge |
| Missing or mismatched artifact | Verify the original run, artifact identity and downloaded digest. Reject a receipt for a different source/tree; preserve the gap if the original bytes are no longer available |

## Actual success and controlled-failure evidence

[PR #223](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/223) was an isolated,
explicitly never-merge contract probe. Head
`1f521f31124f0db44612711ed5258d28d9e01311` added one indentation byte to
`contracts/v1/examples/build.json`; parsed JSON, manifests, checker, workflow and
all other source stayed unchanged. [Run 39100443483](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39100443483)
failed the ordinary core check with
`Contract integrity failure: frozen bytes changed: examples/build.json`.
The separate native job passed. The probe was closed without merging, and the
canonical fixture SHA-256 remained
`26762208769a8cf1012b4f33b1c1608556d275187ce21117d0b9632cdf951864`.
This proves the exercised byte-integrity failure path; it does not prove semantic
validation or enforcement by attempting to merge a known failing fixture.

[Reviewed main integration #252](https://github.com/Pick-Your-AI/pick-your-ai-android/pull/252)
produced signed main `a33e9cf7310fac94b46e00d616c026f4e7bd6d1b`, tree
`764de48a76f4418940d2357bc8bfe7b8961d749a`. Its post-merge
[core/native run 39105782564](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39105782564)
passed strict build/lint and all 43 then-current Activity simulations. The separate
[scanner run 39105782490](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39105782490)
passed all mandatory real scanner fixtures and found no pattern findings across
97 reachable commits. Its downloaded artifact `12128576635` matched ZIP SHA-256
`62ee7cbb221caf935e27284def28e5702094ad119d90a42a26ed6977b38de0f8` and embedded that
exact main source/tree. [The implementation receipt](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/69#issuecomment-6246851537)
retains the independent reviews and evidence limits. These are immutable recorded
results, not a promise that later source or expired artifacts have been verified.

## Remaining acceptance

The check implementation, inspectable artifacts and controlled failure now have
concrete evidence. Authenticated ruleset creation and effective readback,
credential-context review and the installed emulator outcome remain their own
gates. The named prerequisite from #48 supplies
the pinned build/module outputs; a CI result does not invent a production package
or supported-device release decision.

[ADR 0010](decisions/0010-android-59-acceptance-record.md) proposes placing only
the [02.05 receiving record](delivery/android/work-packages/02.05.md) in Android,
effective after independent receiving review and normal reviewed main delivery.
Until that event, its intended `docs/delivery/android/work-packages/02.05.md` path
in `Pick-Your-AI/pickyour.ai` remains authoritative; the original spelling/path
remain history afterward. This operations reference is not a second acceptance
record. Placement does not complete settings, installed or credential-context
work, whole-issue receiving or any destination/deployment gate.
