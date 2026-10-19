# System bars around the scrolling preview

This correction is a bounded continuation of [Android #80](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/80)
and [Android #64](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/64).
It fixes the preview's fixed viewport insets and light-theme system-bar contrast.
The existing owners retain the separate scroll/focus/caret checkpoint and checked-choice work.

## Observed defect

The original installed [run 39166306506](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39166306506),
actual checkout `b6f7fa915c2e6460b3420e359110eade1deca813`, successfully exercised all four
lifecycle cases while retaining a separate display defect. Original artifact `12151541742`
is 1,028,453 bytes with SHA-256
`a7c2d060b00662756c023ddb35e93979b3fefcbcca655f95ae26d10ee6edec52`.
Its eight original PNG/XML pairs show scrolled form content behind the system bars;
the status icons are light against the preview's light background. This visual
observation does not change the passing lifecycle results or qualify later source.

The same setup remains in accepted main `e8241ef4ea75071339e6a945e48fc6d433819ffa`.
The inset listener pads the inner `LinearLayout`, so that protection moves with the
content when its `ScrollView` scrolls. The platform light theme does not explicitly
select dark status-bar icons. Android 15 enforces edge-to-edge for target SDK 35,
which is the preview's current target.

## Fixed viewport and system-bar contrast

The existing 24 dp form padding stays on the inner content. The same delivered
system-window inset values now pad the fixed `ScrollView` viewport, with clipping
to that padding. Scrolling moves the form within this protected viewport. Repeated
and changed inset dispatch replaces the four physical edge values, so orientation,
RTL and subsequent empty insets neither accumulate nor leave old padding. The
listener keeps dispatching the original insets to descendants and requests
insets after installing the viewport.

The preview theme still inherits `Theme.Material.Light.NoActionBar`. On API 26,
it selects a light status bar with dark icons and keeps the platform's inherited
navigation-bar background and icon appearance together. API 27 and later select
the qualified theme, which also uses a light navigation bar with dark icons. Both
light bar colors follow the theme background. The navigation-bar XML appearance
attribute is confined to `values-v27`; the minimum SDK remains 26. Android 15
retains its enforced transparent/translucent bar behavior.
No new UI framework, dependency or system-bar hiding is added.
The existing inset types and keyboard contract are retained. This change does not
implement focus/caret restoration, keyboard animation or a complete adaptive-window
experience; those remain in their existing work packages.

Primary platform references checked on 2026-10-18:

- [Android 15 edge-to-edge behavior](https://developer.android.com/about/versions/15/behavior-changes-15#edge-to-edge)
- [Insets for Views](https://developer.android.com/develop/ui/views/layout/edge-to-edge#system-bars-insets)
- [System-bar theme appearance](https://developer.android.com/develop/ui/views/layout/edge-to-edge-manually)

## Floating-dialog navigation icons

The combined installed [run 39174016224](https://github.com/Pick-Your-AI/pick-your-ai-android/actions/runs/39174016224)
on actual checkout `97824529d15e6a2d6c8e9189eca74aa622008396`, tree
`75447a245d63483fa1b063f876d980bf5dfd5e83`, passed the four lifecycle cases and
showed the corrected viewport. Its original artifact `12154555297` is 982,219 bytes,
SHA-256 `66b07b12f23df5b518e15fda08b9abec786bdf573d2b3dc91787d1f87592aa71`.
A separate remaining defect appears in the two modal checkpoints: dark navigation
icons (solid RGB 69,70,79) remain over the dimmed background (RGB 100,100,100), a
measured pixel-pair contrast of 1.583:1. The six nonmodal screenshots use the same
icons over RGB 250,250,250, measuring 8.974:1. These bounded measurements do not
establish full accessibility compliance or invalidate the passing lifecycle cases.

The API 27 theme now selects an alert-dialog overlay that explicitly requests
light navigation icons for the floating window. Its parent remains the platform
`ThemeOverlay.Material.Dialog.Alert`; normal modal dimming and all existing dialog
behavior remain. The underlying Activity retains its own light-background/dark-icon
appearance throughout showing, dismissal and repeated dialogs. The API 26 theme
and its inherited navigation appearance are unchanged. No per-dialog callback,
Activity focus hook or global system-UI state mutation is introduced.

This follows the pinned Android 15 framework's
[dialog theme overlay](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/res/res/values/themes_material.xml),
[window appearance initialization](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/core/java/com/android/internal/policy/PhoneWindow.java)
and [dimming-window navigation appearance selection](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r1/services/core/java/com/android/server/wm/DisplayPolicy.java),
inspected on 2026-10-18. Corrected installed pixels require a new run of the selected
source; the original 978 screenshots remain the before-correction evidence.

## Validation scope

Four mandatory `PreviewActivityTest` methods exercise the actual Activity's viewport
while scrolling, repeated/orientation/RTL inset updates, removal of prior insets,
and the actual manifest-selected light-bar theme attributes. The existing native
receipt gate requires all four names and retains every preceding required method;
missing, failed or skipped results remain failures.

Two mandatory `PreviewWindowInsetsActivityTest` methods additionally measure the
real form's visible clipping rectangle at the start, middle and end of scrolling,
and verify exact pending fields, focus/caret and acknowledged saved identity through
repeated inset dispatch and synthetic portrait, landscape and keyboard-height sizes.
Their separate receipt gate checks exact suite/class identity, required unique names,
matching counts and no failed, skipped or errored case. Fixture-only reflection
constructs API 28 `WindowInsets(Rect)` values because compileSdk 35 hides that
constructor; the actual listener receives those values through public dispatch.

Two additional mandatory Activity methods open the real empty-library and deletion
AlertDialogs. They verify light navigation-icon requests on each dimmed floating
window, unchanged 0.6 dim amount and unchanged underlying Activity appearance.
Cancellation, reopening, confirmation and dismissal retain pending text/caret,
acknowledged identity or the explicit deleted/save-disabled state as applicable.
The strict existing Activity receipt gate requires both unique method names.

These are API 28 Robolectric simulations using the existing pinned runtime.
Current-source build, lint, original reports and receiving review are required for
delivery. The canonical installed-emulator lane supplies separate API 35 screenshots
and lifecycle evidence when it consumes this source. The old screenshots above are
failure evidence only for the display behavior they show; they are not a new-source
visual pass. Physical devices, TalkBack, gesture-navigation/cutout matrices, keyboard
transitions and full #80/#64 acceptance remain separate.

## Activity focus and effective navigation appearance

The later installed receiving commit `44423a63aeaeff35f051053dbf0c37283dff3d01`
(tree `10b709d5c2c66e98c394dc6b3e1ca0f19dd0c343`) includes the dialog overlay
above, but its original modal06/08 images still show the same dark navigation
glyphs over the dimmed bar. Its four functional cases, native results and viewport
clipping remain separate from the uncorrected navigation appearance. Reading a
dialog's resource/legacy bit alone did not establish effective WindowManager
appearance on that API35 guest.

The Activity now responds to its own window-focus lifecycle. On the first focus
loss it remembers only the navigation appearance bits, then requests light
navigation icons through the legacy flag and, on API30+, the public
`WindowInsetsController` appearance API. When focus returns it restores the
original navigation bits while preserving unrelated current flags. Repeated
focus-loss callbacks do not replace the saved value, and a destroyed Activity
cannot restore a retired window. API26 retains its existing resource behavior.

Focus loss can also occur for system overlays or backgrounding; this callback is
not proof that a particular dialog owns focus. Existing dialog instances,
listeners, dim amount, pending form and persistence behavior are retained. Three
API28 native simulations explicitly drive the real Activity callback through a
shown confirmation, repeated focus changes and recreation. They cover the
legacy branch and local state, not API30+ WindowManager propagation. The effective
API35 appearance, both modal frames and restored ordinary bars still require
fresh original installed evidence from the chosen receiving source.
