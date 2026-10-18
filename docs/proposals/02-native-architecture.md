# Native architecture recommendation

Proposal for [Android #34](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/34).
Receiving acceptance is pending. The existing repository remains documentation
only; this recommendation does not silently approve scaffolding or change the
canonical acceptance-record path. Read the [scope proposal](01-product-scope.md).

## Decision and alternatives

Recommend Kotlin with Jetpack Compose for the Android client, a small pure Kotlin
domain module, and Room-backed transactional local storage. Use the existing
`Pick-Your-AI/pick-your-ai-android` repository. Propose `ai.pickyour.android` as the
application ID and namespace; its Play availability and publisher authorization
have not been checked. Do not register it or issue a release identity from this
proposal alone.

| Requirement | Kotlin/Compose recommendation | Flutter alternative | React/web wrapper alternative |
| --- | --- | --- | --- |
| Lifecycle and durable edits | Native lifecycle APIs, saved navigation identity and a transactional repository; process-death tests remain necessary | State restoration and durable storage still need deliberate implementation; widgets alone do not persist drafts | Browser lifecycle/storage behavior does not satisfy Android durability by itself; native bridge work remains |
| Offline storage/migration | Room/SQLite transaction boundary owned by Android data layer | SQLite integration and migrations through chosen packages/platform integration | Existing `localStorage` repository is browser-specific and unsuitable as Android persistence authority |
| Accessibility/adaptive UI | Compose semantics plus TalkBack, keyboard, scaling and window testing | Flutter has semantics and accessibility testing; device verification is still needed | Existing responsive React UI is reusable in a wrapper, but mixed web/native focus and platform behavior need separate validation |
| Reuse | Reuse frozen schemas, data, fixtures and semantics; port and parity-test domain implementation | Same language boundary for current TypeScript domain; Dart port/parity work remains | Most direct UI/TypeScript reuse, at the cost of maintaining a browser/native integration boundary |
| Maintenance | One Android platform and its native tooling initially; Apple remains separately scoped | Shared Dart client can help if both clients explicitly adopt it; Apple has not made that decision | Additional webview/bridge surface; still requires Android release/security work |

This is an engineering tradeoff, not a benchmark claiming one framework is faster
or more accessible. Flutter is a credible alternative; reject it for this bounded
Android-first proposal because adopting a shared Dart UI does not reuse the current
React/TypeScript implementation and would couple a still-open Apple decision.
Reject a web wrapper because app-private durability, lifecycle recovery and native
document/share behavior are central requirements rather than optional integrations.

## Inspected platform evidence

The official [Android architecture sample at `ee66e1526b84c026615df032c705842b7d2a521f`](https://github.com/android/architecture-samples/tree/ee66e1526b84c026615df032c705842b7d2a521f)
was inspected, not built. Its `README.md` documents Compose, navigation, Flow,
local Room storage, a fake remote and tests. In the same revision:

- `app/src/main/java/com/example/android/architecture/blueprints/todoapp/tasks/TasksViewModel.kt` uses `SavedStateHandle` for filter state.
- `.../tasks/TasksScreen.kt` uses lifecycle-aware state collection and content descriptions; `app/src/androidTest/.../tasks/TasksScreenTest.kt` contains UI assertions.
- `.../addedittask/AddEditTaskViewModel.kt` keeps unsaved edits in memory and explicitly saves through its repository. It is not evidence of durable autosave after process death.
- `.../data/source/local/ToDoDatabase.kt` demonstrates Room, but has `exportSchema = false`. Do not copy that sample setting into production migration policy.

The official Flutter documentation source at
[`1791ca9a14dc66d4a340b1c991e3aa13346eb350`](https://github.com/flutter/website/tree/1791ca9a14dc66d4a340b1c991e3aa13346eb350)
contains Android restoration, offline-first and accessibility guidance. The
following documents were inspected at that revision:

- [Android restoration](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/platform-integration/android/restore-state-android.md) distinguishes short-lived restoration state from long-lived storage and requires restorable navigation.
- [Offline-first architecture](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/app-architecture/design-patterns/offline-first.md) combines local and remote sources behind a repository; offline reconciliation remains application work.
- [Accessibility testing](https://github.com/flutter/website/blob/1791ca9a14dc66d4a340b1c991e3aa13346eb350/sites/docs/src/content/ui/accessibility/accessibility-testing.md) describes semantics/contrast/target checks and platform accessibility inspection.

Both frameworks require real application and device tests. These are source
inspections, not executed sample tests or a measured framework comparison.

## Module and reuse map

Propose three modules initially, with new modules only when a demonstrated boundary
needs them. Feature packages need not become Gradle modules immediately.

| Location | Responsibility | Dependency direction |
| --- | --- | --- |
| `:core` | Platform-independent model adapters, pure evaluation/cost/revision logic and parity fixtures | No Android UI or database dependency |
| `:data` | Room schema/migrations, repositories, atomic catalog activation and future network adapters | Depends on `:core`; exposes repository interfaces to UI |
| `:app` | Compose navigation, screens, Android lifecycle, document/share adapters and accessibility | Depends on `:core` and `:data`; does not embed a competing compatibility policy |

The authoritative contract remains research `contracts/v1`, adopted verbatim at a
pinned revision with checksums; #40 owns extensions. The existing website
[`dc66a9e269917ab7241c7d08fd0e7f2a2f039872`](https://github.com/Pick-Your-AI/pickyour.ai/tree/dc66a9e269917ab7241c7d08fd0e7f2a2f039872)
pins contract source `d4bdca479f78439b3b98e7418b37acbaccbaa63c` in
`contracts/PROVENANCE.json`. Android must explicitly record its own adoption pin;
do not assume the website's pin is the newest approved contract.

| Existing website artifact | Android reuse decision |
| --- | --- |
| `contracts/v1/**`, provenance, baseline graph/ruleset, examples | Reuse as immutable language-neutral inputs with their rights/provenance; verify hashes and preserve synthetic labels |
| `src/domain/{build,evaluator,invalidation,cost,decimal}.ts` | Behavioral reference; port only with shared fixture/parity checks, decimal semantics and revision locking |
| `tests/unit/{evaluator-parity,scenarios,contracts}.test.ts` | Reuse scenarios and expected semantics, not a claim that TypeScript tests validate Kotlin |
| `src/data/local-builds.ts` | Do not reuse storage implementation: `localStorage`, memory fallback and browser limits are not Room transactions or Android process-death proof |
| `src/domain/share.ts` | Inspect envelope/version compatibility; byte/depth limits and safe projection require Android-specific review, not direct copying |
| React components, router, TanStack hooks, DOM validators | Platform-specific; no direct import into Compose |
| Brand assets and display labels | Reuse only with original provenance and canonical label handoff (#18); do not treat synthetic images/data as real product evidence |

Website checks continue unchanged. No Gradle dependency, generated Kotlin type or
new contract field is added to the website or research repositories. Apple may
consume shared fixtures/contracts independently; this does not select its UI stack.

## Required implementation behavior

- Durable edits: transactionally write the next revision and head before showing
  saved state. Undo creates a revision. Never depend on `onStop`/`onDestroy` to save.
- Restoration: saved UI state carries draft/revision/navigation identifiers; reload
  durable content from storage. Do not serialize an entire private catalog or build
  into a saved-state bundle. No acknowledgement may refer only to in-memory state.
- Migration: export schema history; test every supported upgrade path and failed
  migration. Preserve recoverable data; prohibit destructive fallback for user data.
- Async analysis: bind results to draft revision, catalog, graph/ruleset and
  pricebook pins. Cancellation alone is not protection against late results.
- Catalog activation: validate a complete candidate and hashes before changing the
  active pointer; interruption retains the previous usable snapshot.
- Accessibility: use semantic names/states, logical focus, non-color-only outcomes,
  scalable text and adaptive layouts; run manual TalkBack/keyboard/device checks.

## Toolchain and remaining acceptance

Pin JDK, Gradle wrapper/checksum, Android Gradle Plugin, Kotlin/Compose and SDK versions
together only after checking their current compatibility documentation. Min/target
SDK and device support belong to #47/#54; no timeless target-SDK number is asserted.
The review machine has Java 21 runtime but no Android SDK, Gradle wrapper, emulator
or application source. No build, instrumented test or process-death test ran.

Before #34 closes, obtain product/Android receiving review of the scope and reuse
map, record package identity disposition, and reconcile the #24 handoff. Before a
release, require a verified publisher identity and actual distribution certificate;
an engineering namespace recommendation is not store ownership evidence.

## Acceptance disposition

### Issue #34

Canonical record: `docs/delivery/android/work-packages/01.03.md` in `Pick-Your-AI/pickyour.ai`.

| Exact live criterion | Evidence in this proposal | Remaining acceptance |
| --- | --- | --- |
| Compare Kotlin/Compose with a shared-client approach against offline, accessibility, reuse and maintenance; select package/repository/module boundaries with rationale. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| Each major technology choice maps to an accepted requirement and documented tradeoff. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
| The decision identifies repository/package ownership and one authority for shared contracts and compatibility semantics. | Proposed reasoning and requirements above; not executed application behavior | Receiving review, applicable upstream handoffs and observed implementation evidence remain pending |
