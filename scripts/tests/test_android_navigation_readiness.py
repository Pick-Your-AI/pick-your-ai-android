"""Pure host startup admission tests; no Android process or device is executed."""

import copy
import hashlib
import subprocess
import unittest
from unittest.mock import Mock, patch

from test_android_emulator_smoke import (
    smoke, ReadinessClock, interactive_answers, ready_checks, complete_startup_display_fixture,
)
from test_android_system_bar_evidence import taskbar_text


def navigation_window_text(include_provider=True):
    # Synthetic selected sections using the actual fixed-rig text grammar.
    # The two source lines deliberately refer to the same source in distinct
    # InsetsState/provider scopes. This is not a copied or new device dump.
    source = ('id=48570001 type=navigationBars frame=[0,1196][720,1280] '
              'visible=true flags= sideHint=BOTTOM boundingRects=null')
    policy = ('    mNavigationBar=Window{75b587a u0 Taskbar}\n'
              '    mBottomGestureHost=Window{75b587a u0 Taskbar}\n') if include_provider else ''
    state = '        InsetsSource ' + source + '\n' if include_provider else ''
    provider = ('      InsetsSourceProvider\n'
                '        mSource=InsetsSource ' + source + '\n'
                '        mSourceFrame=Rect(0, 1196 - 720, 1280)\n'
                '        mWindowContainer=Window{75b587a u0 Taskbar}\n'
                '        mIsLeashReadyForDispatching=true mHasPendingPosition=false\n') if include_provider else ''
    return ('ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n'
            'Display #0 (activities from top to bottom):\n'
            '  DisplayFrames w=720 h=1280 r=0\n'
            '  DisplayPolicy\n' + policy +
            '  WindowInsetsStateController\n'
            '    InsetsState\n'
            '      mDisplayFrame=Rect(0, 0 - 720, 1280)\n' + state +
            '    InsetsSourceProviders:\n' + provider +
            '  mCurrentFocus=Window{abc u0 ' + smoke.HOME_WINDOW + '}\n'
            '  mFocusedWindow=Window{abc u0 ' + smoke.HOME_WINDOW + '}\n')


def initialized_taskbar_text():
    # Synthetic layout preserving the observed/reference peer ordering; the
    # earlier overview profile remains unrelated to Taskbar's own profile.
    return taskbar_text().replace('    TaskbarManager:\n',
        '    OverviewComponentObserver:\n'
        '      observed=true\n'
        '    OverviewCommandHelper:\n'
        '      observed=true\n'
        '    TaskAnimationManager:\n'
        '      observed=true\n'
        '    TaskbarManager:\n')


def navigation_answers(window=None, taskbar=None):
    return [subprocess.CompletedProcess([], 0, navigation_window_text() if window is None else window, ''),
            subprocess.CompletedProcess([], 0, initialized_taskbar_text() if taskbar is None else taskbar, '')]


def ready_navigation(source='a' * 40, apk='b' * 64):
    device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
    device.adb.side_effect = navigation_answers()
    with patch.object(smoke.time, 'monotonic', clock.monotonic):
        smoke.wait_for_navigation_provider(device, readiness, 180, source_sha=source, apk_sha=apk)
    return readiness['navigation_provider']


def attach_ready_navigation(receipt):
    receipt['guest_readiness'].update(
        navigation_provider=ready_navigation(receipt['source_commit'], receipt['unsigned_apk_sha256']),
        timeout_seconds=180, started_monotonic=0.0, deadline_monotonic=180.0)


class NavigationReadinessTest(unittest.TestCase):
    def test_window_requires_matching_visible_provider_in_current_default_display(self):
        valid = navigation_window_text()
        expected = {'source_id': '48570001', 'window': 'Window{75b587a u0 Taskbar}',
                    'frame': [0, 1196, 720, 1280]}
        self.assertEqual(expected, smoke.navigation_provider_state(valid))
        self.assertIsNone(smoke.navigation_provider_state(navigation_window_text(include_provider=False)))
        variants = [valid.replace('Display #0 ', 'Display #1 '), valid + valid,
                    valid.replace('r=0', 'r=1'), valid.replace('w=720', 'w=721'),
                    valid.replace('visible=true', 'visible=false'),
                    valid.replace('frame=[0,1196][720,1280]', 'frame=[0,1197][720,1280]', 1),
                    valid.replace('mSource=InsetsSource id=48570001', 'mSource=InsetsSource id=48570002'),
                    valid.replace('mWindowContainer=Window{75b587a', 'mWindowContainer=Window{75b587b'),
                    valid.replace('mBottomGestureHost=Window{75b587a', 'mBottomGestureHost=Window{75b587b'),
                    valid.replace('mIsLeashReadyForDispatching=true', 'mIsLeashReadyForDispatching=false'),
                    valid.replace('mHasPendingPosition=false', 'mHasPendingPosition=true'),
                    valid + '\nDUMP TIMEOUT\n', valid + '\x00']
        for candidate in variants:
            with self.subTest(candidate=candidate):
                self.assertIsNone(smoke.navigation_provider_state(candidate))
        foreign = valid[valid.index('Display #0 '):].replace('Display #0 ', 'Display #1 ', 1)
        self.assertIsNone(smoke.navigation_provider_state(navigation_window_text(False) + foreign))
        self.assertEqual(expected, smoke.navigation_provider_state(valid + foreign))

    def test_tis_requires_each_initialized_peer_before_complete_taskbar(self):
        valid = initialized_taskbar_text()
        self.assertTrue(smoke.initialized_taskbar_service_passes(valid))
        self.assertFalse(smoke.initialized_taskbar_service_passes(taskbar_text()))
        names = ('OverviewComponentObserver', 'OverviewCommandHelper', 'TaskAnimationManager')
        for name in names:
            line = '    ' + name + ':\n'
            variants = [valid.replace(line, ''), valid.replace(line, line + line),
                        valid.replace(line, '      ' + name + ':\n'),
                        valid.replace(line, '').replace('    AssistStateManager:\n',
                                                       line + '    AssistStateManager:\n')]
            for candidate in variants:
                with self.subTest(name=name, candidate=candidate):
                    self.assertFalse(smoke.initialized_taskbar_service_passes(candidate))
        reordered = valid.replace('OverviewComponentObserver:', 'TEMP:').replace(
            'OverviewCommandHelper:', 'OverviewComponentObserver:').replace('TEMP:', 'OverviewCommandHelper:')
        self.assertFalse(smoke.initialized_taskbar_service_passes(reordered))
        self.assertFalse(smoke.initialized_taskbar_service_passes(
            valid.replace('      TaskbarActivityContext:\n', '      TaskbarActivityContext: null\n')))

    def test_positive_admission_retains_complete_bound_observations_without_mutation(self):
        device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
        answers = navigation_answers()
        device.adb.side_effect = answers
        with patch.object(smoke.time, 'monotonic', clock.monotonic), patch.object(smoke.time, 'sleep') as sleep:
            smoke.wait_for_navigation_provider(device, readiness, 180, source_sha='a' * 40, apk_sha='b' * 64)
        state = readiness['navigation_provider']
        self.assertTrue(smoke.navigation_readiness_passes(state, 'a' * 40, 'b' * 64))
        self.assertEqual([smoke.HOME_FOCUS_QUERY, smoke.SYSTEM_BAR_TASKBAR_QUERY],
                         [call.args for call in device.adb.call_args_list])
        self.assertEqual([5, 5], [call.kwargs['timeout'] for call in device.adb.call_args_list])
        self.assertEqual(1, len(state['attempts']))
        for name, answer in zip(('window', 'taskbar'), answers):
            command = state['attempts'][0][name]['command']
            self.assertEqual(answer.stdout, command['stdout_tail'])
            self.assertEqual(hashlib.sha256(answer.stdout.encode()).hexdigest(), command['stdout']['text_sha256'])
        device.launch.assert_not_called()
        device.shell.assert_not_called()
        sleep.assert_not_called()

    def test_pending_provider_and_service_are_retained_until_same_attempt_has_both(self):
        device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
        absent = navigation_window_text(include_provider=False)
        device.adb.side_effect = (navigation_answers(window=absent)
                                 + navigation_answers(taskbar=taskbar_text()) + navigation_answers())
        with patch.object(smoke.time, 'monotonic', clock.monotonic), patch.object(smoke.time, 'sleep', clock.sleep):
            smoke.wait_for_navigation_provider(device, readiness, 180, source_sha='a' * 40, apk_sha='b' * 64)
        state = readiness['navigation_provider']
        self.assertTrue(smoke.navigation_readiness_passes(state, 'a' * 40, 'b' * 64))
        self.assertEqual(['not_ready', 'not_ready', 'ready'], [a['status'] for a in state['attempts']])
        self.assertEqual([1, 2, 3], [a['sequence'] for a in state['attempts']])
        self.assertIsNone(state['attempts'][0]['provider'])
        self.assertTrue(state['attempts'][0]['taskbar_initialized'])
        self.assertIsNotNone(state['attempts'][1]['provider'])
        self.assertFalse(state['attempts'][1]['taskbar_initialized'])
        self.assertEqual(10, state['seconds'])
        self.assertEqual(6, device.adb.call_count)
        self.assertEqual(absent, state['attempts'][0]['window']['command']['stdout_tail'])

    def test_missing_provider_expires_original_budget_with_unrun_cases_unchanged(self):
        device, receipt, clock = Mock(spec=smoke.Device), {
            'source_commit': 'a' * 40, 'unsigned_apk_sha256': 'b' * 64,
            'startup_display': {'profile': 'raster-720x1280-280'},
            'cases': [{'id': name, 'status': 'not_run'} for name in smoke.CASE_IDS]}, ReadinessClock()
        first = [subprocess.CompletedProcess([], 0, expected[-1], '')
                 for _, _, expected in smoke.GUEST_READINESS_COMMANDS] + interactive_answers()
        pending = navigation_answers(window=navigation_window_text(include_provider=False))
        device.adb.side_effect = first + pending * 3
        with patch.object(smoke.time, 'monotonic', clock.monotonic), patch.object(smoke.time, 'sleep', clock.sleep), \
                self.assertRaisesRegex(smoke.SmokeFailure, 'pre-install navigation provider/TIS readiness deadline'):
            smoke.wait_for_guest_ready(device, receipt, timeout_seconds=15)
        state = receipt['guest_readiness']
        self.assertFalse(state['ready'])
        self.assertTrue(smoke.guest_readiness_checks_pass(state['last_checks']))
        self.assertTrue(smoke.interactive_readiness_passes(state['interactive']))
        self.assertEqual(15, state['seconds'])
        self.assertEqual(3, len(state['navigation_provider']['attempts']))
        self.assertFalse(state['navigation_provider']['ready'])
        self.assertTrue(all(row['status'] == 'not_run' for row in receipt['cases']))
        self.assertEqual(1, sum(call.args == smoke.FRAMEWORK_BARRIER for call in device.adb.call_args_list))
        device.verify_identity.assert_called_once_with()
        device.launch.assert_not_called()
        device.shell.assert_not_called()

    def test_poll_count_is_finite_even_when_a_test_clock_never_advances(self):
        device, readiness = Mock(spec=smoke.Device), {}
        device.adb.side_effect = navigation_answers(taskbar=taskbar_text()) * smoke.NAVIGATION_READINESS_ATTEMPT_LIMIT
        with patch.object(smoke.time, 'monotonic', return_value=0), patch.object(smoke.time, 'sleep') as sleep, \
                self.assertRaisesRegex(smoke.SmokeFailure, 'bounded observations'):
            smoke.wait_for_navigation_provider(device, readiness, 180, source_sha='a' * 40, apk_sha='b' * 64)
        self.assertEqual(36, len(readiness['navigation_provider']['attempts']))
        self.assertEqual(72, device.adb.call_count)
        self.assertEqual(35, sleep.call_count)

    def test_timeout_preserves_partial_original_and_does_not_poll_again(self):
        for index in (0, 1):
            original = subprocess.TimeoutExpired(['adb'], 2, output=b'partial output', stderr=b'timeout detail')
            device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
            device.adb.side_effect = navigation_answers()[:index] + [original]
            with self.subTest(index=index), patch.object(smoke.time, 'monotonic', clock.monotonic), \
                    patch.object(smoke.time, 'sleep') as sleep, self.assertRaises(subprocess.TimeoutExpired) as raised:
                smoke.wait_for_navigation_provider(device, readiness, 2, source_sha='a' * 40, apk_sha='b' * 64)
            self.assertIs(original, raised.exception)
            state = readiness['navigation_provider']
            self.assertFalse(state['ready'])
            self.assertEqual(index + 1, device.adb.call_count)
            row = state['attempts'][0][('window', 'taskbar')[index]]['command']
            self.assertEqual('partial output', row['stdout_tail'])
            self.assertEqual('timeout detail', row['stderr']['prefix'])
            self.assertEqual(2, row['command_timeout_seconds'])
            sleep.assert_not_called()

    def test_complete_looking_output_never_rescues_transport_failure_or_server_error(self):
        for index in (0, 1):
            for kind in ('nonzero', 'stderr', 'truncated', 'server_io', 'server_remote'):
                device, readiness = Mock(spec=smoke.Device), {}
                answers = navigation_answers()
                result = answers[index]
                if kind == 'nonzero': result.returncode = 1
                elif kind == 'stderr': result.stderr = 'permission denied'
                elif kind == 'truncated': result.stdout = 'x' * smoke.SYSTEM_BAR_WINDOW_LIMIT + result.stdout
                elif kind == 'server_io': result.stdout += 'Failure while dumping the service: Timeout\n'
                else: result.stdout += 'Got a RemoteException while dumping the service\n'
                device.adb.side_effect = answers
                with self.subTest(index=index, kind=kind), patch.object(smoke.time, 'monotonic', return_value=0), \
                        patch.object(smoke.time, 'sleep') as sleep, self.assertRaises(smoke.SmokeFailure):
                    smoke.wait_for_navigation_provider(device, readiness, 180, source_sha='a' * 40, apk_sha='b' * 64)
                self.assertFalse(readiness['navigation_provider']['ready'])
                self.assertEqual(index + 1, device.adb.call_count)
                sleep.assert_not_called()

    def test_late_complete_service_cannot_cross_the_original_readiness_deadline(self):
        device, readiness, clock = Mock(spec=smoke.Device), {}, ReadinessClock()
        answers = iter(enumerate(navigation_answers()))
        def answer(*args, **kwargs):
            index, result = next(answers)
            clock.now = 1 if index == 0 else 2
            return result
        device.adb.side_effect = answer
        with patch.object(smoke.time, 'monotonic', clock.monotonic), self.assertRaises(smoke.SmokeFailure):
            smoke.wait_for_navigation_provider(device, readiness, 2, source_sha='a' * 40, apk_sha='b' * 64)
        self.assertEqual([2, 1], [call.kwargs['timeout'] for call in device.adb.call_args_list])
        self.assertFalse(readiness['navigation_provider']['ready'])
        self.assertFalse(smoke.navigation_readiness_passes(readiness['navigation_provider'], 'a' * 40, 'b' * 64))

    def test_receipt_recomputes_raw_state_bindings_intervals_and_attempt_history(self):
        valid = ready_navigation()
        mutations = [
            (('source_commit',), 'c' * 40), (('unsigned_apk_sha256',), 'c' * 64),
            (('profile',), 'pixel-2'), (('serial',), 'emulator-5556'), (('phase',), 'after_installation'),
            (('ready',), 1), (('attempt_limit',), 1), (('readiness_deadline',), float('inf')),
            (('ended',), 180.0), (('attempts', 0, 'sequence'), True),
            (('attempts', 0, 'provider'), None), (('attempts', 0, 'taskbar_initialized'), 1),
            (('attempts', 0, 'status'), 'not_ready'),
            (('attempts', 0, 'window', 'command', 'stdout', 'text_sha256'), '0' * 64),
            (('attempts', 0, 'taskbar', 'command', 'stdout_tail'), taskbar_text()),
            (('attempts', 0, 'taskbar', 'interval', 'start'), -1.0),
            (('attempts', 0, 'taskbar', 'command', 'timeout_seconds'), True),
        ]
        for path, value in mutations:
            candidate = copy.deepcopy(valid)
            target = candidate
            for key in path[:-1]: target = target[key]
            target[path[-1]] = value
            with self.subTest(path=path, value=value):
                self.assertFalse(smoke.navigation_readiness_passes(candidate, 'a' * 40, 'b' * 64))
        duplicated = copy.deepcopy(valid)
        duplicated['attempts'].append(copy.deepcopy(duplicated['attempts'][0]))
        duplicated['attempts'][1]['sequence'] = 2
        self.assertFalse(smoke.navigation_readiness_passes(duplicated, 'a' * 40, 'b' * 64))

    def test_current_receiving_receipt_requires_provider_even_with_qualified_pixels(self):
        from test_android_system_bar_evidence import SystemBarEvidenceTests
        receipt = complete_startup_display_fixture()
        SystemBarEvidenceTests.setUpClass()
        state = SystemBarEvidenceTests().passing_state()
        state['source_commit'] = receipt['source_commit']
        state['unsigned_apk_sha256'] = receipt['unsigned_apk_sha256']
        receipt['system_bar_evidence'] = state
        with self.assertRaisesRegex(smoke.SmokeFailure, 'pre-install navigation provider/TIS'):
            smoke.complete_receipt(receipt, 'a' * 40, expected_system_bar_evidence=True)
        attach_ready_navigation(receipt)
        smoke.complete_receipt(receipt, 'a' * 40, expected_system_bar_evidence=True)
        for name, value in (('timeout_seconds', 181), ('timeout_seconds', True),
                            ('started_monotonic', 1.0), ('started_monotonic', float('nan')),
                            ('deadline_monotonic', 181.0), ('deadline_monotonic', None)):
            candidate = copy.deepcopy(receipt)
            candidate['guest_readiness'][name] = value
            with self.subTest(name=name, value=value), self.assertRaisesRegex(smoke.SmokeFailure, 'original guest readiness budget'):
                smoke.complete_receipt(candidate, 'a' * 40, expected_system_bar_evidence=True)

    def test_existing_user_home_sequence_precedes_provider_and_legacy_profile_stays_separate(self):
        for profile in ('raster-720x1280-280', 'pixel-2'):
            device, receipt, clock = Mock(spec=smoke.Device), {
                'source_commit': 'a' * 40, 'unsigned_apk_sha256': 'b' * 64,
                'startup_display': {'profile': profile}}, ReadinessClock()
            first = [subprocess.CompletedProcess([], 0, expected[-1], '')
                     for _, _, expected in smoke.GUEST_READINESS_COMMANDS] + interactive_answers()
            device.adb.side_effect = first + (navigation_answers() if profile.startswith('raster') else [])
            with self.subTest(profile=profile), patch.object(smoke.time, 'monotonic', clock.monotonic):
                smoke.wait_for_guest_ready(device, receipt)
            self.assertTrue(receipt['guest_readiness']['ready'])
            self.assertEqual(0.0, receipt['guest_readiness']['started_monotonic'])
            self.assertEqual(180.0, receipt['guest_readiness']['deadline_monotonic'])
            self.assertEqual(ready_checks(), receipt['guest_readiness']['last_checks'])
            self.assertEqual(profile.startswith('raster'), 'navigation_provider' in receipt['guest_readiness'])
            self.assertEqual(16 if profile.startswith('raster') else 14, device.adb.call_count)
            device.launch.assert_not_called()
            device.shell.assert_not_called()

