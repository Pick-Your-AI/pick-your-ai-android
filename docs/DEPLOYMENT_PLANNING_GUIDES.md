# Deployment planning reference guides

This native view supplies API, managed, self-hosted and hybrid reference guidance
inside the existing core T6 planning section. A user explicitly opens a guide to
read external prerequisites, monitoring considerations and remaining limits.
Only one guide is expanded at a time. Opening a guide does not classify the
saved build, choose a catalog item, change optional activation, or run a check.

This is a bounded continuation of
[Android #52](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/52).
The separately reviewed operational selection editor and its receiving PR #504
remain a distinct source and execution checkpoint. This guide does not extend
that receiver's frozen scope.

## Reference edition and source authority

The UI identifies **Android planning guide 1**, interpreted against the existing
frozen **configuration contract 1.0.0**. It does not describe a new contract
version. Its reference text is checked against these exact accepted Research
files at commit 17924a4143ac6ca7db0725a04f5f41c4ce1f35ac:

| Source | Exact Git blob | Sections consumed |
| --- | --- | --- |
| [Deployment](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/17924a4143ac6ca7db0725a04f5f41c4ce1f35ac/research/T6-deployment-operations/deployment.md) | 08c3dacf4ce574071a4ce88a13509be42ace5f38 | Mode definitions; deployment steps S1–S51 with their applicability and source limits; checklist per mode; single-host runtime and rollback limits; retention and human-review readiness reconciliation |
| [Monitoring](https://github.com/Pick-Your-AI/pick-your-ai-research/blob/17924a4143ac6ca7db0725a04f5f41c4ce1f35ac/research/T6-deployment-operations/monitoring.md) | c56f0ca430629a01620d3988933e4b1c5891e910 | Signal sources by mode and the following coverage/unknown limits |

These are source-bound reference interpretations. They do not accept every
Research hypothesis, qualify a live provider offering, or adopt a successor
schema, rule, telemetry contract or readiness verdict. Research #37 keeps
deployment mechanics, #36 keeps measured quality and promotion gates, and #38
keeps actual signals, thresholds, routing and telemetry-failure behavior.

| Reference | Meaning and external preparation | Monitoring and limits retained |
| --- | --- | --- |
| API | Provider-operated shared serving. Identify the exact offering, model/version, region, access and effective data controls. Shared serving can still require model-access setup, a deployment object, invocation identity and readback. | Bind client observations and eligible provider signals to their actual scope. Provider-internal capacity or queues may be unavailable. Billing delay and recorded budget preferences do not establish an enforced spend stop. |
| managed | Provider-operated dedicated or reserved serving. Qualify the actual endpoint, version, capacity, access, rollout and recovery path. Reservation steps require an actual reservation. | Keep client and exposed endpoint/platform signals distinct. Neither the provider label nor the mode supplies internal hardware evidence, a universal rollback capability or a universal cost. |
| self-hosted | User-operated serving. Identify the actual CPU/device, host-memory and storage arrangement, image/weights/runtime pins, access boundaries, startup behavior and measured workload. | Qualify supported engine, host and client observations. An exporter, health endpoint or catalog capacity fact is insufficient evidence. Single-host and clustered recovery need their own mechanics and compatible retained artifacts. |
| hybrid reference | Compose the applicable plans for the actual serving components and the connections between them. Multiple API providers alone do not establish hybrid serving. | Preserve each component's requirements, known failures, unknowns, signal scope, data boundaries and cost coverage. A whole-build label cannot suppress an applicable local check. |

The fourth reference is not a fourth frozen mode or a saved configuration
field. The existing deployment-mode selection still refers to its exact catalog
identity and version; this view receives neither. No mode is selected by
default or inferred from a provider name, a legacy sample selection or a
partially completed draft.

Applicable data controls must be reviewed before the first protected upload,
storage or request, including effective retention, content capture and sink
region. A privacy review is not deferred until traffic cutover. The reference
keeps unknown evidence unknown and known unsupported required operations
negative. It supplies no default thresholds, secret values, execution commands
or assurance that effective settings have been read back.

## Native behavior and authority boundaries

DeploymentPlanningGuides is a dedicated Kotlin/Views component. The existing
T6PlanningSection contains it through one additive construction call. The
guide receives an Activity, a lifecycle predicate and an explicit navigation
callback that cancels pending catalog viewport restoration. It receives no
draft, catalog data, profile, report, storage adapter, worker or execution callback.

The accepted viewport continuation adds one lazy callback at the Activity's
T6 construction site. Only an explicit live reference click consumes pending
catalog restoration, including an accessibility click without a touch/key
event. Restoring the reference's display state does not invoke that callback.
The callback is lazy because the catalog is initialized later in onCreate;
no early read of its uninitialized field occurs. The entire accepted viewport
implementation remains unchanged.

The existing T6 source caption and five selection summaries remain authoritative
for their existing scope. Pending edits refer to the acknowledged saved
revision. Loading and unreconciled recovery continue to withhold counts, even
if another disk read could find a newer head. Reference navigation remains
available in those states and does not replace the unavailable result.

Each card uses native buttons and text. The button labels distinguish reading
and hiding a reference; the selected state and accessible headings describe the
expanded card. Controls have minimum 48 dp dimensions, paragraphs wrap, and
the existing page scroll container makes individual text lines reachable.
There are no nested scrolling regions, web views, link spans, generated
commands, clipboard actions, intents, download actions or background tasks.

The view has one stable resource ID. Its own saved state is a Bundle containing
only the parent view state, reference-state format 1, and an optional fixed
reference key. Child text is excluded from hierarchy-state dispatch. Unknown
keys or future formats restore a collapsed reference. This is display state;
it carries no private draft inputs, selected catalog identity, consent or save
token. Normal Activity recreation separately restores existing draft controls
under their original authority checks.

An action requires the guide to remain attached, its Activity to be live, and
the lifecycle predicate to permit it. Detached and destroyed controls cannot
change the retired view. No dialog, asynchronous completion or external
operation is introduced.

## Declared native regression coverage

DeploymentPlanningGuidesActivityTest declares ten native interaction cases:

1. Open all four references before the first save and with every optional tier
   disabled, preserving the disk, existing source caption and saved revision.
2. Preserve exact deployment/monitoring selections, private title/objective/
   context and opaque credential-reference fixtures, catalog browsing context,
   optional choices and retained history while navigating all references.
3. Keep pending editor inputs and acknowledged selection summaries unchanged.
4. Open a reference while a queued draft read withholds every selection count;
   retain the selected reference when the actual read completes.
5. Parcel-round-trip the display state and recreate the Activity while keeping
   pending inputs separate; retired controls cannot affect the new view.
6. Preserve unavailable planning after a newer external disk write, even when
   the reference display state is restored.
7. Reject unknown reference keys and future display-state formats by restoring
   collapsed, without changing any saved selection.
8. Reject detached controls while allowing the reattached current view to
   navigate normally, with unchanged storage.
9. Direct and accessibility reference clicks cancel a delayed exact-catalog
   restoration while preserving its search, filters and page, using real native
   planning/catalog views in a secondary Activity.
10. At 320 dp, right-to-left layout and double font scale, reach every button and
   every line of the expanded reference text with no ellipsis or ancestor
   clipping.

The workflow requires the exact ten methods in their actual JUnit class and
rejects missing or duplicate methods, a wrong suite, report-total mismatches,
failures, errors and skips. Existing guards remain unchanged.

At this authoring checkpoint these are source-level tests, not a claimed native
execution. A completed original report must be tied to its exact executed
source before receiving acceptance. The local shared filesystem was full;
no original test failure or pass is inferred from the decision not to start a
new Gradle process. Installed-process, physical-device, TalkBack and release
evidence remain separate.

## Issue #52 acceptance remains scoped

The literal criteria are preserved:

| Criterion | Contribution and remaining acceptance |
| --- | --- |
| Core planning summary preserves readiness, limitations and relevant deployment/monitoring guidance. Detailed configuration exports and operational catalog breadth follow scope decisions; actual runtime provisioning remains separate. | Adds manually selected source-bound reference guidance and preserves existing acknowledged/unavailable summaries. Exact current-source execution and the broader readiness/receiving decisions remain necessary. No export or provisioning is enabled. |
| Every supported core planning summary identifies remaining deployment prerequisites and relevant monitoring limitations without claiming execution. | The four reference views expose relevant external steps and limits. They do not compute selected-build applicability or establish that every required build-specific prerequisite has been identified. |
| Any enabled detailed export reproduces the supported configuration and omits secrets; unsupported operations are labelled rather than emitted as working commands. | Detailed export remains disabled in this scope. The private-input navigation controls do not substitute for an export-redaction test. |

The first implementation subtask, “Define the core planning summary's deployment
prerequisites, monitoring considerations and unresolved operational decisions
for each supported mode,” receives the reference mapping above. The third,
“Implement planning output that identifies required external steps, source
versions, credentials to obtain separately and actions requiring later
authorization,” receives an explicit reference edition and these view
boundaries. Neither is a claim of complete selected-build applicability.

The second subtask, “Map detailed deployment/monitoring selections and export
fields only where accepted contracts and reviewed evidence support them,”
retains its own operational-selection and conditional-export scope. The fourth,
“Validate export redaction and retain readiness findings, cost coverage and
limitations when a plan is saved or shared,” remains open. No export is
introduced by this guide.

The existing website receiving-record path
docs/delivery/android/work-packages/13.05.md in
Pick-Your-AI/pickyour.ai remains the intended acceptance location pending its
explicit placement decision. This repository note does not relocate that
record, appoint a reviewer or close #52.
