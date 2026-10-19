# Explicit deployment, monitoring and cost planning inputs

This is a bounded continuation for [Android #52](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/52)
in the accepted Kotlin core and native Views preview. It admits the three remaining
frozen T6 kinds, `deployment_target`, `monitoring_stack` and `cost_policy`, through
the existing explicit selection controls. It preserves the one shared five-area
planning panel and its acknowledged saved-revision boundary. A recorded setting
does not establish operational readiness, capacity, authorization or execution.

The intended acceptance record remains
`Pick-Your-AI/pickyour.ai:docs/delivery/android/work-packages/13.05.md`.
This engineering record does not relocate that record, assert that it exists or
close #52. The relevant T3–T6 research owners and Android domain reviewer retain
the issue's receiving roles. Its three criteria and four subtasks remain visible
below; input editing alone does not complete them.

## Exact frozen settings

The unchanged [Selection schema](../contracts/v1/schemas/selection.schema.json)
and [component registry](../contracts/v1/schemas/component-registry.json) define
all twelve fields. Every field is optional. The visible empty object `{}` records
unanswered settings; it supplies no default environment, instance count, sampling
rate, retention period, budget, approval reference or activation.

| Kind | Setting | Frozen accepted value |
| --- | --- | --- |
| `deployment_target` | `environment` | `development`, `staging` or `production` |
| `deployment_target` | `replicas_min` | Integer at least 0 |
| `deployment_target` | `replicas_max` | Integer at least 1 |
| `deployment_target` | `region` | Text, including an explicitly empty string |
| `deployment_target` | `approval_ticket` | Text, including an explicitly empty string |
| `monitoring_stack` | `sample_rate` | Number from 0 through 1 |
| `monitoring_stack` | `retention_days` | Integer at least 0 |
| `monitoring_stack` | `credential_ref` | Opaque reference satisfying the frozen `^secret://[A-Za-z0-9/_:.-]+$` pattern |
| `cost_policy` | `monthly_budget_amount` | Decimal string matching `^(0\|[1-9][0-9]*)(\.[0-9]{1,12})?$` |
| `cost_policy` | `per_request_budget_amount` | The same decimal-string shape |
| `cost_policy` | `enforce_hard_cap` | Boolean |
| `cost_policy` | `notify_at_pct` | Number from 0 through 100 |

Add and settings forms show these rules before entry. Money is entered as quoted
decimal text, such as `"0.10"`; it is not converted through a floating-point type.
Integer and numeric fields retain the existing exact bounded representation,
including large integers and decimal spelling. Missing, zero, false and empty
text remain distinct. The schema defines individual replica bounds but no
cross-field ordering constraint; the editor retains explicit independent values
and explains that their consistency and capacity need later review.

Every add requires an explicit role, enabled checkbox and exact catalog item,
version and captured snapshot hash. The checkbox starts unchecked. Deployment
and monitoring use the existing many-active cardinality; distinct enabled roles
can coexist. Cost policy has cardinality one for enabled selections. A second
cost policy can be stored disabled under another role, then enabled only after
the first is explicitly disabled or removed. No remaining choice is promoted.
Duplicate `(kind, role)` pairs reject even when one selection is disabled.

The small settings editor retains its existing 16,384-character input limit,
bounded JSON parsing and duplicate-key rejection. Invalid types, extra members,
invalid ranges, malformed JSON and proposed runtime fields reject the whole form.
Exact invalid text remains available for correction; no valid prefix is saved.
Stored settings beyond the small editor limit cannot be truncated by opening or
submitting that editor. Role-only editing preserves the complete stored settings.

## Declared inputs and remaining operational work

The forms distinguish these inputs from external outcomes:

- A deployment environment, region, replica count or approval-ticket string does
  not deploy a workload, confirm capacity, approve access or change the selected
  deployment mode. Source identity/version, target access, capacity, actual
  approvals, rollout and rollback still require their own evidence and action.
- A sample rate, retention period or opaque credential reference does not collect
  observations, create alarms, retrieve a credential or activate retention.
  Signal coverage, actual monitoring access and operating limits remain unknown.
- A budget, hard-cap preference or notification percentage does not calculate a
  price, enforce spending, send an alert or authorize a charge. Currency, rate
  coverage, measured workload and the applicable pricing evidence remain separate.

T6 stays core planning work when T3–T5 are disabled. The existing shared section
continues to show all five areas, the next configuration step and the evidence
that is unavailable. Counts come from one acknowledged saved revision; pending
edits never become saved counts. Loading or reconciliation withholds all counts,
even when another reader can see a disk head. No new panel or T6 tier switch is
introduced. These forms supplement that common disclosure with exact field rules;
they do not implement per-mode prerequisite evaluation or a readiness verdict.

The consumed scope is the accepted [#24 guest-local decision](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24),
the pinned [epic-13 execution guidance](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/da3c6045f84260506bd326bb24ddfa8a7e6a23e8/prompts/mobile/android/epic-13.md)
and the accepted [T6 research summary](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/0659dac2a439893b7765048c1ef7c8442ba6cb25/research/T6-deployment-operations/README.md).
Source claims, reviewed evidence and reproduced outcomes remain distinct.
Proposed successor fields or policy recommendations do not become frozen settings
through this UI. Research #40 and the native mode/evidence owners retain their
versioning and receiving boundaries; the Android device is not assumed to be the
inference target.

## Privacy, storage and recovery

`credential_ref` accepts the existing frozen opaque reference shape only. Raw
passwords, other schemes and malformed reference values fail before a command is
created. The reader preserves exact accepted spelling, including its existing
final-line-feed pattern behavior; this editor adds no trimming or normalization.
No reference is resolved, followed or substituted. Rejections contain no entered
payload in their diagnostic object. This specific typed-field boundary does not
claim secret-pattern detection in arbitrary text, notes or unrelated fields.

Saving uses the existing managed-library liveness guard and canonical revision/
hash CAS. There is no new history store, storage version, journal or write path.
Add, replace, settings edit, role edit, enable/disable and remove retain their
existing meaning. Replacement preserves identity, role, enabled state, settings,
notes, extensions and relationships. Removal deletes only the selected identity
and its incident links. Changed commands append one acknowledged revision; exact
no-ops reuse the previous revision and bytes. Format-4 settings history, format-5
explicit role changes, old exact history and restoration remain valid.

Forms capture the acknowledged record, local draft identity and relevant catalog
snapshot. Changed catalog pins, stale or foreign save authority and a deleted
library entry cannot gain a current token. Cancellation, replacement and Activity
destruction retire old callbacks. The existing pending-input checkpoint preserves
literal role/settings, enabled state and identity for explicit review after
recreation; it confers no write authority. A newer saved head or an acknowledged
in-flight write keeps the recovered input read-only. In those states the shared
planning panel reports unavailable authority and withholds counts.

The existing core-only snapshot preparation can retain these exact selected
inputs when its foundation and explicit relationship prerequisites are satisfied.
It still withholds a candidate when a required catalog relationship is missing.
No new native export, deployment command or credential-resolution route is enabled.
Private guest notes stay outside that structural candidate; this is a bounded
existing projection check, not qualification of a detailed saved/shared export.

## Verification and acceptance boundary

Ten dedicated core cases cover all twelve field boundaries and exact types,
optional intent, duplicate roles and active cardinality, malformed/raw credential
references, exact pins and replacement, format-4/5 history and restoration, no-op
and defensive-copy behavior, structural projection and managed liveness/CAS.
The unchanged baseline compiled those ten tests and failed all ten at missing
admission. Fixed core `e05eba152b26c9185a46c99994fc356d7adab15f` executed
3,655 tests in 79 original suites with no failures, errors or skips. The original
failing source and reports remain separate from that fixed result.

Eight existing test files keep their other assertions while updating the
now-supported boundary. The foundation case formerly named
`retained deployment monitoring and cost policy kinds stay outside this command subset`
is explicitly retitled
`retained operational kinds allow explicit edits while unregistered kinds cannot enter`.
It exercises the three admitted kinds and keeps unknown future kinds rejected.
This is an intentional identity change, not a missing or silently skipped test.

Eleven dedicated paused-looper Activity cases exercise explicit/default-free
input, invalid retry with saved-byte preservation, all optional-tier states,
multiple deployment/monitoring roles, cost-policy cardinality, unchanged edits,
parcel recovery, canceled/stale catalog controls, newer-head reconciliation and
acknowledged in-flight writes. The native graphics case checks complete text and
reachable controls at 320dp RTL with twice the font scale. The literal CI guard
requires every named method and rejects missing, wrong, duplicate, failed or
skipped cases while allowing future additions. Actual native, assembly and lint
results remain bound to their own original source manifests and PR receipts.
The first native checkpoint `c47661f6bf153b07ff155d3b70d2dde7d92c3714`
compiled and executed all 311 cases: 310 passed and the new RTL case failed its
full input-field reachability assertion. There were no errors or skips. Its
original log and 25 reports remain a failed source result, with no debug assembly
or lint qualification attributed to that attempt.
The unchanged-behavior diagnostic checkpoint `22a054edb85360a9ff20fe61c2e3483355b752b3`
reproduced the failure in that one case and recorded a 444px-tall settings field
inside a 378px scroll viewport. The correction requires each actual settings
line to be scroll-reachable, retaining exact full-text/no-ellipsis checks and
whole-button/role reachability. It changes no product layout, field value or
save assertion; requiring that entire multiline field to fit simultaneously
would not test the scrollable editor's actual interaction. Both original failure
receipts remain distinct from the corrected native gate.

| #52 criterion | This bounded contribution | Remaining acceptance |
| --- | --- | --- |
| Core planning preserves readiness, limitations and deployment/monitoring guidance; detailed export breadth follows scope; runtime remains separate | One existing core panel retains its limitations; all twelve frozen inputs become explicit and durable, with field-specific external-work disclosures | Supported-mode prerequisites, readiness/evidence decisions and any selected detailed-export scope still need their accepted outputs and receiving review |
| Every supported core summary identifies remaining deployment prerequisites and relevant monitoring limitations without claiming execution | Common source/access/capacity/approval/rollout and signal/retention limitations accompany the relevant forms; no execution is claimed | Per-mode templates and condition-specific findings have not been implemented or qualified by these input controls |
| Any enabled detailed export reproduces supported configuration and omits secrets; unsupported operations are labelled | This slice enables no detailed export or working operational command; typed credentials remain opaque and existing structural preparation is tested | A later enabled export needs its approved redaction boundary, private/credential-like fixtures, exact saved/shared findings and explicit unsupported-operation output |

The four issue subtasks therefore remain partial or outstanding: mode-specific
prerequisites and unresolved decisions; detailed fields only under accepted
contracts/evidence; required external steps/source versions/credentials and later
authorization; and export redaction with saved/shared readiness and cost limits.
This slice supplies frozen input mapping and common limitations, without inventing
the remaining domain decisions. The canonical record and relevant research/Android
receiving review, actual installed-device/process-death/TalkBack qualification and
release evidence remain separate. Local simulation and an unsigned APK do not
establish those outcomes.
