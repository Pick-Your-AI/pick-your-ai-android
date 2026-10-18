# Initial Android product and native decisions

Decision candidate for independent receiving review of
[Android #20](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/20),
[#24](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/24) and
[#34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34).
Source inspection and desk walkthrough completed on 2026-10-18 against accepted
Android [`142bf20a9dda843c3ae57cfbc760a7bb97b71025`](https://github.com/Pick-Your-AI/pick-your-ai-android/tree/142bf20a9dda843c3ae57cfbc760a7bb97b71025).

The user has delegated project engineering and product choices. This record makes
those choices concrete for review; it does not invent another person's approval,
assignment, publisher identity or service commitment. The existing product decision
authority and Android technical review requirements still need an actual receiving
record. The three canonical website acceptance paths are preserved below.

## 1. Users, jobs and evidence decision — #20

Choose the individual comparing a workload's local and API options as the primary
design user, and a person reviewing that decision as the secondary design user.
These are hypotheses for a bounded planning client. Research has not established
Android demand, willingness to pay, usage frequency or an enterprise buyer.
The [original source assessment](01-product-scope.md#evidence-and-intended-users)
pins the customer, MVP and synthesis documents to research
`7acbefbf4e4e700231e88be272bc89e3a73dde15`; their desk-persona and provisional
recommendation labels are retained. Research #58 and #66 were open in this review.
Choosing this Android scope does not accept either research outcome.

The first-release jobs are to describe a workload, inspect evidence for candidate
components, assemble and compare a bounded plan, understand its missing inputs,
save it locally, and return to the same acknowledged revision. Explicit file
exchange is conditional on its format and disclosure work. Accounts, public
publishing and automatic execution are separate capabilities, not implicit parts
of saving or sharing a plan.

The Android device is the configuration client. Selected inference hardware may
be a separate computer or service. Do not read phone specifications as the target,
install a model to complete planning, or infer that a proposed stack can run on the
phone. Intermittent connectivity must preserve locally acknowledged work and the
last verified evidence snapshot. With no verified snapshot, browsing is unavailable
and the draft remains editable. Small screens require reachable labelled controls,
readable uncertainty, and recovery that preserves pending input.

Measure success through observable completion, rather than an invented adoption
target: whether the user can identify the acknowledged revision, distinguish a
missing input from a failed rule, identify which components are unpriced, return
to the same saved content, and understand what a file recipient will receive.
There is no conversion target, latency promise or demand measurement in this
decision. Product/performance qualification remains with
[#47](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/47).

### Completed desk walkthrough

Use one explicitly fictional but concrete workload: a developer plans a small
text assistant handling 200 requests per day, averaging 700 input and 400 output
tokens, concurrency 2, a 4,096-token requested context and 512 reserved output
tokens. The planning budget is USD `1000.00`; `us-east-fixture` is a synthetic
region identifier, never a real region recommendation. These values come from the
pinned [build fixture](../../contracts/v1/examples/build.json), not customer
observations. A separate machine with a weights model and inference engine is the
self-hosted candidate; API and managed alternatives require their own supported
catalog entries. Never reinterpret this weights-only fixture as an API product.

For the core-stack inspection, keep only the fixture selections whose canonical
registry tiers are T0, T1, T2 or T6, and keep only links with both endpoints still
selected. That leaves 15 selections and 8 links. All optional T3/T4/T5 selections
are absent. The reference check at the end of this record observes that even this
core stack remains indeterminate without resource and cost evidence.

| Primary journey and entry | Goal and observable completion | Desk observation and decision | Failure/recovery and unresolved assumption |
| --- | --- | --- | --- |
| Start: new local draft | Record the workload above; completion is a named draft with an acknowledged revision and explicit unanswered inputs | The accepted guest form has separate workload/context fields and durable save adapters. Choose explicit save for the first slice; a pending edit is not saved merely because it remains visible | Invalid raw input prevents save; interruption cannot produce a false acknowledgement. Whether this amount of mobile entry is useful remains a research hypothesis |
| Discover/assemble: catalog from the draft | Find the fictional model/runtime/hardware versions and inspect their provenance; completion is visible pinned choices and their differences | `cat.model.fixture` only advertises weights access. An API alternative therefore remains unanswered until a separate suitable catalog entry exists; it cannot inherit that identity or a fabricated price | Missing catalog/version is unavailable. Removing a selected item must preserve the original draft until an explicit supported edit; stale details cannot replace a newer selection. Real source rights and catalog coverage remain unaccepted |
| Review: summary of the current draft | Display selections, missing pieces, findings, estimate coverage, exact input versions and core T6 prerequisites, with navigation back to the affected input | The core reference calculation has three unknown resource/cost findings and blocked readiness. Decide that this is an honest summary, not a reason to fill in estimates. Current fixed-report inspectors do not yet summarize the edited guest draft; that remains implementation work under #113 | Changing context from 4,096 to 8,192 or daily requests from 200 to 400 invalidates related results. An older report must remain labelled stale and cannot become the new revision's summary. No physical capacity, quality or commercial price is established |
| Return: local library and retained revision | Reopen the acknowledged workload and its evidence pins; completion is the same saved content, with pending edits distinguishable | Source inspection confirms an explicit version token, immutable revisions and conflict handling. Choose a new revision for restore rather than rewriting old history | A newer disk head requires reconciliation; corrupt/unsupported data stays recoverable and cannot be silently reset. Actual process-death, migration and supported-device qualification remain separate tests |
| Exchange: explicit export/import | Review the disclosed fields and open a separately validated copy; completion is an understandable, versioned planning record | The first slice may ship without exchange while #30's safe format/disclosure and cross-client handoff are pending. The summary and local return journey must still work. Do not offer a nonfunctional share control | Reject unsupported, oversized or malformed imports before replacement; cancel preserves the original. A recipient's exported copy cannot be revoked. Cross-client interoperability and the usefulness of shared review remain unvalidated |

This is an executed design/source walkthrough, not an Android UI run. It resolves
the scope choices above and identifies missing implementation rather than claiming
that the five journeys have been installed and exercised end to end.

## 2. First slice and mode dispositions — #24

Consume section 1 as #20's named decision output. The selected first implementation
slice is a guest-local planning client: workload → pinned component choices →
honest summary → acknowledged local revision → return. Synthetic diagnostics can
exercise this path during development; a public real-data release additionally
needs its accepted source/catalog and applicable product, security, device and
distribution evidence. A fixture-only diagnostic is not that release.

| Capability | First-slice disposition | Unsupported/unavailable behavior | Evidence required for expansion |
| --- | --- | --- | --- |
| Workload, browse, selection, comparison and local revision history | Core | Empty values remain unanswered; missing versions remain visible; no invented default provider, region, rate or target hardware | Reviewed real catalog coverage and the corresponding Android behavior |
| Compatibility and summary | Core | Show pass/fail/unknown/skipped separately from missing-input readiness; bind findings to the current revision and all required evidence pins | Accepted shared semantics/conformance and the complete summary/remediation path in #113 |
| Cost and tradeoffs | Core presentation of units, assumptions, covered subtotal and uncovered components | Missing meters are unpriced, not zero; currency/period differences cannot be silently combined; partial coverage cannot become a complete bill | Research #39 and accepted matching pricebook, meter and scenario evidence |
| Capacity and quality | Conditional on accepted versioned evidence; uncertainty display is core | Missing calibration/measurement is unknown; no benchmark score, fit verdict or quality rank is synthesized | Applicable research and website sizing/quality handoffs for the exact selection |
| Local save, reopen, restore and delete | Core | Save acknowledgement follows durable commit; conflict/corruption is explicit; deleting local data cannot revoke external copies | Android durability, migration, recovery and device results for the selected storage implementation |
| File export/import | Conditional later slice; required before any exchange UI is enabled | Explain unavailable exchange; retain local summary/save; import never executes content or follows an embedded locator | Android #30, reviewed versioned envelope, disclosure, hostile-input checks and affected-client receiving review |
| Core T6 guardrails, evaluation, deployment prerequisites, monitoring and cost policy | Core planning inputs and missing-prerequisite display | Optional T3/T4/T5 omission never removes these requirements; configuration alone does not establish a running control or ready deployment | Accepted evidence and applicable implementation tests; execution remains separately excluded |
| T3 retrieval, T4 agents/tools, T5 training | Later, optional product capabilities | Preserve unsupported retained data for inspection; do not require optional tiers to complete a core plan or claim analysis for an unsupported tier | A separate scope decision, rights, contract support and tier-specific tests |
| Accounts, cloud sync, payments and subscriptions | Later; not enabled by this decision | No account, entitlement, remote-save or purchase promise | #25/#35 decisions and the applicable service, authorization, deletion and billing work |
| Analytics, remote crash upload, notifications and community publishing | Later; not enabled by this decision | No collection, permission prompt, background alert or publishing action is implied by local planning | Explicit product need and applicable policy/service/security acceptance |
| Provisioning, provider/model execution and purchasing | Excluded from the first planning slice | A saved or exported plan is not an instruction to run, provision or spend | Separate execution mandate, service/credential boundaries and corresponding acceptance |

### Representative mode review using the same workload

The four rows are distinct planning dispositions. They do not assert that the
current Android diagnostic supports all four modes, nor add a frozen-v1 enum or
relationship to represent a missing path.

| Planned mode | Disposition and representative journey | Honest-summary result and unresolved evidence |
| --- | --- | --- |
| API | Core planning path when an exact supported provider/model entry is available: enter 200 requests/day and token assumptions; choose that API entry; inspect request economics | The existing weights-only fixture supplies no API alternative. Keep provider/model unanswered and rate coverage unavailable until matching evidence exists. No provider call or credential is needed to plan |
| Self-hosted | Core planning path: choose the separate target, weights model, context policy, compatible engine, compute/accelerator/hosting and core T6 selections | The pinned fictional core example can be inspected, but without calibrated memory and full pricing its result is indeterminate/blocked. Neither the Android phone nor aggregate GPU memory substitutes for the requested target's evidence |
| Managed | Conditional later planning path: describe the same workload and select an exact managed model/service/region when supported | No capacity, availability or regional price is inferred from the self-hosted fixture. Until accepted service coverage exists, preserve the intent and explain that this mode cannot be fully assembled/analyzed |
| Hybrid | Core comparison of separate supported local and API plans; a combined multi-placement build is conditional later work | Compare explicit alternatives without implying routing, failover, data-transfer permission or a combined bill. A single combined plan needs accepted per-component placement/relationship semantics through research #40; unsupported relationships remain unknown |

The MVP/substitute recommendation motivates the bounded local/API comparison;
it does not validate demand or authorize all four modes at launch. Later expansion
requires both useful evidence from research #58/#66 and the capability-specific
handoff above. Their open research status does not prevent this reversible scope
decision and is not changed by it.

## 3. Native, package and shared-authority decisions — #34

Consume section 2 as #24's named scope output. Select native Kotlin in the existing
`Pick-Your-AI/pick-your-ai-android` repository. Continue the implemented platform
Views client for the initial guest planning slice, keeping domain behavior in the
SDK-independent `:core` module and Android lifecycle/UI/adapters in `:preview`.
The latter remains opt-in with `-PandroidPreview=true`. No website build begins
depending on an Android SDK or Gradle as a consequence.

Select `ai.pickyour.android.preview` as the local application ID/namespace of this
synthetic development client, exactly as already implemented. Its code and module
changes remain under the existing Pick-Your-AI repository's maintainer/review
authority; this workstream authors the decision under the user's project grant.
There is no claim that a new person has accepted an operational role. Keep
`ai.pickyour.android` as an unregistered future production-identity proposal, with
publisher/distribution verification under #53. Do not rename a preview installation
or create a release certificate from that proposal.

This choice supersedes the earlier automatic Compose/Room/three-module
recommendation for the first slice. Keep the existing local revision store behind
its adapter; do not introduce a second store or migration merely to match an
architecture diagram. `:data` and `:app` are future module proposals, not selected
or required modules for this bounded implementation. If a later production client,
database or service needs them, record its dependency/migration and identity
decision before activation. The first-slice choice itself is resolved.

| Accepted requirement | Selected approach and tradeoff | Alternative and reason not selected now |
| --- | --- | --- |
| Acknowledged offline edits and revision recovery | Kotlin core with the existing explicit version-token/revision store; native lifecycle carries identity/pending UI state and reloads durable content. This preserves reviewed behavior but still needs actual Android storage qualification | Compose plus Room can support the same requirement, but neither makes persistence automatic. Introducing it now adds migration/rewrite work without a demonstrated first-slice need |
| Accessible, adaptable component browsing | Native Views with visible labels, semantic headings, text outcomes, reachable controls and bounded pages. Existing source/tests cover restoration, empty results, measured large-text RTL controls and focus paths | Compose offers a declarative semantics model; migration stays conditional on a concrete maintainability/accessibility benefit with equivalent behavior and tests. Source inspection is not a claim that either framework passes TalkBack/device qualification |
| Reuse of trusted contracts and domain behavior | Reuse immutable contracts/assets/scenarios and parity-test Kotlin adapters; keep platform storage and UI native | Flutter would require a Dart port and a separate explicit Apple decision. A React wrapper reuses more UI but retains native storage, lifecycle, focus and document/share bridge obligations |
| Maintainability and independent platform delivery | Keep the two implemented modules and pinned toolchain; introduce additional boundaries only for an actual responsibility | A shared Dart client would couple two independent clients without current shared-client acceptance. A webview adds another integration surface to requirements already exercised natively |

The [existing architecture evidence and reuse map](02-native-architecture.md#inspected-platform-evidence)
remain supporting material: the pinned Android/Flutter sources were inspected,
not benchmarked or built as part of this decision. This review additionally
inspected the accepted Android source:

- [LocalDraftStore](../../core/src/main/kotlin/ai/pickyour/core/drafts/LocalDraftStore.kt)
  validates the expected revision token and uses the canonical verified-cache
  transaction. Corrupt/unsupported data blocks saving without rewriting original
  bytes. Directory/rename durability remains Android qualification work.
- [PreviewActivityTest](../../preview/src/test/kotlin/ai/pickyour/android/preview/PreviewActivityTest.kt)
  contains `acknowledgedFieldsAndExplicitEmptySurviveRecreation`,
  `newerDiskHeadCannotAdoptStalePendingFormAfterRecreation`,
  `catalogQuerySurvivesDetailsAndRecreationWithoutChangingPinnedSelection`,
  `emptyCatalogSearchCanRecoverAndReloadUsesTheCurrentQuery`,
  `editorsExposeLabelsAndSavedStatusUsesTextAndLiveRegion`,
  `largeTextRtlButtonsRetainMeasuredTargetsAndUntruncatedLabels`, and
  `forwardFocusOrderKeepsEditorsAndClearActionReachable`.
  The inspected assertions distinguish saved/pending content, retain exact pins,
  require visible labels and exercise focus/layout behavior. They are simulated
  tests, not physical-device evidence.
- [Settings](../../settings.gradle.kts), [root build](../../build.gradle.kts),
  [preview build](../../preview/build.gradle.kts) and
  [manifest](../../preview/src/main/AndroidManifest.xml) confirm the two-module
  dependency, pinned toolchain, preview identity, disabled release variant,
  no requested permissions, and independent JVM build.

Research `contracts/v1` remains the one shared contract authority, with extensions
through [research #40](https://github.com/Pick-Your-AI/pick-your-ai-research/issues/40).
Android's [import manifest](../../contracts/v1/IMPORT_MANIFEST.json) and
[adoption record](../CONTRACT_ADOPTION.md) identify the adopted bytes. Compatibility
uses the accepted versioned rules/graph and shared conformance evidence; Android
must not create a competing meaning for pass, unknown, placement or readiness.
Website services remain under their existing source/service authority. Apple may
consume the same contracts independently; this decision chooses no Apple stack,
backend, data store deployment or operations owner.

## 4. Evidence, remaining work and receiving record

### Reproduced core reference observation

The following command was executed from the accepted Android source checkout. It
reads the frozen inputs, filters a private in-memory copy, and does not alter any
contract or application file. This is a synthetic Python reference observation,
not an Android evaluation or a validated deployment.

```sh
python - <<'PY'
import importlib.util, json
from pathlib import Path
p = Path('contracts/v1')
load = lambda name: json.loads((p / 'examples' / f'{name}.json').read_text())
build, catalog, rules = load('build'), load('catalog'), load('ruleset')
registry = json.loads((p / 'schemas/component-registry.json').read_text())
core = {kind for kind, row in registry['kinds'].items()
        if row['tier'] in {'T0', 'T1', 'T2', 'T6'}}
build['selections'] = [s for s in build['selections'] if s['kind'] in core]
ids = {s['id'] for s in build['selections']}
build['links'] = [link for link in build['links']
                  if link['from_selection_id'] in ids and link['to_selection_id'] in ids]
spec = importlib.util.spec_from_file_location('reference', p / 'reference/evaluate.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
report = module.Evaluator(build, catalog, rules).evaluate()
print(len(build['selections']), len(build['links']))
print(report['result'], report['deployment_readiness'], report['summary'])
print([(f['rule_id'], f['outcome']) for f in report['findings']
       if f['outcome'] not in {'pass', 'skipped'}])
PY
```

Observed: 15 selections, 8 links; `indeterminate`, `blocked`; 21 passed, 0 failed,
3 unknown and 21 skipped rules. The unknowns are `rule.vram.estimate.fit`,
`rule.budget.monthly.cap` and `rule.vram.per.device.fit`. Optional-tier absence
does not prove core readiness, and a zero failed-rule count does not erase unknowns.
Input SHA-256 values are:

| Frozen input | SHA-256 |
| --- | --- |
| `examples/build.json` | `26762208769a8cf1012b4f33b1c1608556d275187ce21117d0b9632cdf951864` |
| `examples/catalog.json` | `2e9120db823faef3e1e330a7d2e8067b3ab064328e2faed699d15cf41880fbf8` |
| `examples/ruleset.json` | `05474568204fdbfd407cb4e4cfc9334e22fdf24e75111bed8990868926fab418` |

### Runtime work remains distinct

At the inspected source, the guest workload is editable and saved, but
[CostInspectionSection](../../preview/src/main/kotlin/ai/pickyour/android/preview/CostInspectionSection.kt)
and [FindingsInspectionSection](../../preview/src/main/kotlin/ai/pickyour/android/preview/FindingsInspectionSection.kt)
read fixed bundled build/report fixtures. They do not analyze the edited draft.
The required draft-bound summary, missing-piece display and remediation navigation
remain under [#113](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/113),
with workload/placement under [#83](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/83)
and safe exchange under [#30](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/30).
This decision resolves their intended direction, not their implementation criteria.

The installed harness remains with its existing #255/#237/#206 continuation.
[Run 39112194859](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39112194859),
artifact `12131331327`, tested source `159cf4c6a1bd6dcde1c4863c74e2c8c319bf8e13`
/ tree `eecf3179270b81726ed3c3395aa5d7e0777c6102`, rather than this accepted main.
Its retained receipt shows boot, guest readiness and Activity launch succeeded;
the first installed case failed its initial XML capture, and three lifecycle
cases were not run. ZIP SHA-256
`7b59904e3473df26a4bde3c74e3b2dc550b476d4c27721ed4edab0e23711d33f`
matches the hosted upload log. That basic launch is real partial evidence for
#48; it is not a passing installed suite or receiving-main qualification.

### Exact decision criteria and outstanding acceptance

The literal #20/#24/#34 validations are product/mode walkthrough and architecture
inspection/review. They do not require claiming an already complete app or store
release. The following decision evidence is ready for independent receiving review;
none of these rows is marked Done by this document.

| Issue and exact criterion | Decision evidence in this record |
| --- | --- |
| #20: Document target users, mobile context, first-release jobs and success measures; distinguish validated evidence from assumptions. The Android device is the configuration client, not automatically the selected inference target. | Section 1 selects the design users, jobs, observable completion and client/target distinction; research claims remain hypotheses |
| #20: Every primary journey names its goal, entry point, completion state, evidence and unresolved assumptions. | Section 1's five-row concrete walkthrough and source/implementation limitations |
| #20: A recorded decision distinguishes first-release jobs from hypotheses; Android-local inference is not implied by hardware comparison. | Section 1's explicit job choice and separate-target example |
| #24: Record core, later and conditional scope across API, managed, self-hosted and hybrid modes; consume Pick-Your-AI/pick-your-ai-research#58/Pick-Your-AI/pick-your-ai-research#66 without claiming their acceptance. | Section 2's mode dispositions consume section 1 and retain both research limitations |
| #24: Every capability has a release disposition and an explicit unsupported/unavailable behavior. | Section 2's capability matrix, disabled exchange and later expansion evidence |
| #24: The approved first slice runs from workload to an honest summary; planning/export and execution have separate scope gates. | Section 2's selected journey and independent planning, exchange and execution boundaries; actual summary delivery remains explicitly incomplete |
| #34: Compare Kotlin/Compose with a shared-client approach against offline, accessibility, reuse and maintenance; select package/repository/module boundaries with rationale. | Section 3's native decision, alternatives, implemented module/identity boundary and reuse map |
| #34: Each major technology choice maps to an accepted requirement and documented tradeoff. | Section 3 maps the section 1/2 requirements to lifecycle/storage, accessibility, reuse and maintenance choices |
| #34: The decision identifies repository/package ownership and one authority for shared contracts and compatibility semantics. | Section 3 identifies existing repository review authority, the actual local namespace, research #40 semantics authority and separate unregistered production identity |

Receiving review must accept the exact decision version and its named prerequisite
outputs in order: #20 → #24 → #34. Record the outcome, links, criterion results,
failed/recovery observations, reviewers and limitations at the existing paths in
`Pick-Your-AI/pickyour.ai`: `docs/delivery/android/work-packages/01.01.md`,
`01.02.md` and `01.03.md`. Their directory was absent at website main
`dc66a9e269917ab7241c7d08fd0e7f2a2f039872` in this read; this Android file is a
decision artifact, not a relocation or substitute for those records. Website
publication retains its own normal review/deployment boundary. Unassigned issue
metadata alone does not invalidate the user's delegated decision authority.

No frozen bytes, app behavior, runtime acceptance, issue checkboxes, ownership
commitments or production state change with this documentation. #48 keeps its
clean-build, actual install/launch, selected-identity and receiving-source gates;
the broader platform issues retain their device, security, service and store gates.
