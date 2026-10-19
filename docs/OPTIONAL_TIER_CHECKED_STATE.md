# Optional-tier checked state

The local preview's T3, T4 and T5 activation pickers mark the value from their
captured acknowledged draft: **Enabled**, **Disabled** or **Unknown**. Each is a
native single-choice list with the existing translated labels. Unknown remains
an explicit value; it is not presented as disabled or as a compatibility result.

Opening a picker does not edit the draft. Choosing a different row still opens
the separate confirmation, and only its current positive action can dispatch
the existing guarded command. Canceling either dialog and reopening the picker
shows the saved value. A proposed or canceled row does not become the saved value.
The existing [dialog lifetime rules](OPTIONAL_TIER_DIALOGS.md) continue to revoke
superseded, dismissed, uneditable and destroyed controls before dispatch. Activity
and store identity, clean-form and compare-and-swap checks remain unchanged.

This uses Android's documented
[`AlertDialog.Builder.setSingleChoiceItems`](https://developer.android.com/reference/android/app/AlertDialog.Builder#setSingleChoiceItems(java.lang.CharSequence[],%20int,%20android.content.DialogInterface.OnClickListener)),
reviewed on 2026-10-19. The platform marks the supplied checked index and leaves
item-click dismissal to the caller. The existing section explicitly dismisses
the picker when it presents the confirmation; it does not leave two active
dialogs or make a row click sufficient to save.

## Regression coverage

`OptionalTierChoiceAccessibilityTest` uses actual preview controls and the local
draft adapter in the existing SDK 28 JVM simulation. Its four methods inspect
the native single-choice mode, every checked and unchecked row, accessibility
node text/checkability, and saved Disabled/Enabled/Unknown values across T3–T5.
They also cover unchanged baseline/history on opening and cancellation,
reopening after a proposal, superseded callbacks, one confirmed transition and
recreation with an obsolete picker. The four existing `OptionalTierDialogTest`
methods retain their separate editability, cancellation and one-dispatch checks.
The workflow requires each new class-qualified method exactly once with no
failed, errored, missing or skipped result.

With the pinned toolchain in [the preview instructions](ANDROID_PREVIEW.md):

```sh
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:testDebugUnitTest \
  --tests ai.pickyour.android.preview.OptionalTierChoiceAccessibilityTest \
  --tests ai.pickyour.android.preview.OptionalTierDialogTest
```

On the unchanged picker at `bc664e3bb9f085bc1950f05a80cb3c43c7e7f925`, the four new
methods fail because the native list reports no single-choice state. The four
existing methods pass. Before that baseline, an initial test helper incorrectly
compared newly decoded history wrapper objects; its original failure is retained,
and the helper now compares revision values alongside the unchanged baseline hash.
That correction changes no production or existing test behavior.

## Partial acceptance

This is a bounded contribution to
[#64](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/64), extending the
previous catalog/workload checked-choice presentation to optional-tier controls.
The simulated native state and authority checks are distinct from installed
TalkBack/keyboard traversal, physical-device behavior, contrast/font-expansion
qualification, reduced-motion coverage and a complete reusable component library.
All three literal #64 criteria remain open for their full outcome. The canonical
`docs/delivery/android/work-packages/06.02.md` acceptance-record path remains in
`Pick-Your-AI/pickyour.ai`; this behavior note does not move it or create a second
receiving record. No activation rule, saved format, evaluation, price, owner,
Project field or release gate changes.
