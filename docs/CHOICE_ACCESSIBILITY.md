# Current choices in native pickers

This bounded contribution to [Android #64](https://github.com/Pick-Your-AI/pick-your-ai-android/issues/64)
uses the accepted [Kotlin/platform Views foundation](proposals/02-native-architecture.md).
It improves four existing synthetic-preview controls: catalog tier, catalog kind,
catalog sort order and workload data classification.

Each picker now uses a native single-choice list. The currently applied value is
checked when the dialog opens, including All tiers, All kinds and an unanswered
classification. Native checked rows retain their normal text, role and checked
state; no replacement content description hides the option label. The option
values, ordering and existing parent-button wording remain unchanged.

Opening or closing a picker does not apply an edit. One explicit item selection
uses the existing local action and closes the dialog, preserving the previous
action-list interaction. The shared helper rejects repeated or dismissed-dialog
callbacks. Each caller still supplies its lifecycle guard; classification also
requires the captured workload-input identity and an editable form. A stale
classification dialog closes without applying the row the native list just checked.
It cannot leave an unapplied choice displayed as current.

The catalog controls change only transient browse state and reset paging as before.
Classification remains a user-entered assumption, with unanswered distinct from
every classification value. It uses the existing explicit-save path; this control
does not create a measured fact, choose a service or write a draft by itself.
Existing saved-state adapters restore the values, and reopening derives the checked
row from that restored state. A modal window itself is not persisted.

## Verification and limits

The dedicated [simulated-UI suite](../preview/src/test/kotlin/ai/pickyour/android/preview/PreviewChoiceAccessibilityTest.kt)
exercises the actual four pickers. It checks the native single-choice list, a
laid-out checked row and its accessibility node, explicit All/unanswered values,
reopening, cancellation, retained repeated callbacks, changed input authority,
recreation and preservation of pending text without an implicit durable write.
All five named cases must execute successfully in the existing unsigned preview
workflow; missing, failed, errored or skipped cases cannot supply that receipt.

These are Android API 28 JVM simulations. They do not establish an installed
TalkBack announcement, keyboard navigation, full reading order, contrast, target
size, large-text/RTL behavior or the supported device matrix. #64's broader
component-library and receiving acceptance remains open. Its canonical receiving
record remains `docs/delivery/android/work-packages/06.02.md` in `pickyour.ai`;
this behavior note does not relocate it or change any criterion status.

Platform references: Android's [dialog guide](https://developer.android.com/develop/ui/views/components/dialogs)
and [AlertDialog.Builder](https://developer.android.com/reference/android/app/AlertDialog.Builder)
distinguish ordinary item lists from single-choice lists, whose selection does not
automatically dismiss the dialog. The helper closes only the dialog receiving the
accepted action. [CheckedTextView](https://developer.android.com/reference/android/widget/CheckedTextView)
supplies the native checked-row semantics used by the picker.
