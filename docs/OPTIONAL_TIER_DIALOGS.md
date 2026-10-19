# Optional-tier confirmation lifetime

The synthetic preview requires a current, visible confirmation before changing
an acknowledged T3, T4 or T5 activation. Choosing **Keep edits**, canceling a
picker, dismissing its confirmation, opening another tier picker, changing the
saved baseline, making the form uneditable or closing the section revokes the
previous dialog. A retained callback cannot regain authority when editing becomes
available again.

The section tracks one current picker or confirmation. Each callback verifies
the section is open, the form is editable, the captured saved record is current,
and its own dialog is both visible and the active dialog. A confirmed transition
consumes that authority before dispatch. The existing Activity and store still
perform their clean-form, draft-identity and compare-and-swap checks. No tier,
selection, setting, evaluation, price or readiness semantics change.

## Reproduced defect and verification

On accepted main `385001803c5084c371b30e6601b69da18b8d22a8`, the actual Activity
regression saved a draft, captured the tier confirmation's positive button,
pressed **Keep edits**, waited until the dialog closed, and then delivered the
retained positive callback. The acknowledged draft's baseline changed. The single
targeted case failed at its unchanged-baseline assertion with zero errors or
skips. A matching saved-record CAS did not protect a withdrawn confirmation.

`OptionalTierDialogTest` exercises actual preview controls and the managed draft
adapter. Its cases verify canceled confirmation and picker callbacks, loss and
restoration of form editability, superseded pickers, a successful current T4
confirmation with T3 preserved, and an unchanged revision after replaying a
consumed confirmation. The workflow requires all four named cases and rejects
missing, failed, errored or skipped reports. Existing Activity cases remain
required separately.

With the pinned JDK/SDK from [the preview instructions](ANDROID_PREVIEW.md):

```sh
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:testDebugUnitTest --tests ai.pickyour.android.preview.OptionalTierDialogTest
./gradlew --no-daemon --dependency-verification=strict -PandroidPreview=true \
  :preview:assembleDebug :preview:lintDebug :preview:testDebugUnitTest :core:check :core:assemble
```

These are simulated Activity results, not installed-device, assistive-technology,
process-death or power-loss evidence. This correction is partial
[#121](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/121) and
[#106](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/106) work. The
canonical acceptance records remain `docs/delivery/android/work-packages/08.03.md`
and `15.02.md` in `Pick-Your-AI/pickyour.ai`; no issue status, owner, acceptance
criterion or release commitment changes here.
