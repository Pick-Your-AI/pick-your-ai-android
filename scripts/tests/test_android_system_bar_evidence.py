"""Host-only fixed-rig image/provenance guards; no SDK, ADB or privilege invocation.

The two compressed RGBA strips below are exact decoded pixels from original444
05/06, rectangle [0,1196,720,1280], not modified original screenshots. Synthetic
full canvases and XML wrap those strips only for portable unit fixtures. A real
corrected modal original remains a separate installed receiving requirement.
Original ZIP SHA256: e55d231e280b551c876595d4b4a9bf632664d0d4565ec530221480296f985577.
"""

from __future__ import annotations

import base64
import copy
import hashlib
import importlib.util
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zlib

SPEC = importlib.util.spec_from_file_location(
    "android_system_bar_subject", Path(__file__).parents[1] / "android_emulator_smoke.py")
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)

ORDINARY_BAR = (
    'eNrt3f1PVXUcwPG/LgFTURSwfNzExI0Hn42QVpotQQMfcGZb6Zahkg8s2tTlL06ztlwpOUXAlmaCD9jYhOkv3/je5VZJCnrv9XLu'
    '64fXGHDvOdtnu+e877nnnPv48ePwGAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAEisoaGhsG/fgVC5fEUonvlGqN+wMfx+6w+zIS9fCydOfpt6DSypqApzSheEwqKS1M/4e/z7'
    'yZOnU48zLwDIT6dOnQ7lcxeH16YU/0t5+eIwODhoRuSFW6PvF1taWsP0GeVPvRbGEh8XH9/fP2B+5JUrV7rDwUNHUsdc0iUuLy7X'
    'fIFc19vbF6qq1zyzEQ63HzMrEi32b3NzayiaOntc3fxf06aXhtbdn+po8sLevZ+/0OtkvPZ88pk5AznpwYM/w46de0JB4aznbssa'
    'm7abGYlu5+qatWnZ79fUrtPQJNrZs+fDlIKZGe3nKK7HvIFc8ejRo9DZeSKUli0c93bsoy3NZkciXbr0y5jnLb2MuLyLF7vMl0Ta'
    '1bo34+0cxfWYN5ALurt7wrLK2glvx/QzSTQ8PJK6VjYT+/6Vq+rCw4fD5kziNDa1ZKWftzTa7wCv1v37g6Fp6/YX/sxNP5NEHzfv'
    'yuj+Py7fnNHP+hmYfOdqHDnaEWaVvPlS2zH9TNL09PSN69z/lxGXf+PGTfNGP+tnYJLo6rqcuk9tOrZj+pmkaWj4ICsNsHHTltT7'
    'WDNHP+tnIHfduXM3bN7clNbtmH4maffbyMb9A6K4ntu3+80d/ayfgRw0MjIS2traw4zi8rRvx/QzSXK8ozMr+/8njh3/2tzRz/oZ'
    'yDEXLvwcFi2uzNh2TD+TJHV172W1nzc0bHIOB/pZPwM5dH3gtm07M74d088kybx5S7Laz3F9+hn9rJ+B/PocWj+TpPOcCotKstrP'
    'U1+fo5/Rz/oZyBHZOPasn0nUtYMDA1lt5yfiPdjNH/2sn4FXr6PjG/0MExC/c7BoanaPP8fj3cPDvosQ/ayfgVw5/3nrth36GSag'
    'rHyR859BPwPuv+H+GzBOVdVrstrPtSvW62f0s34GcvS6qLaDX4UZxXP1MzzDFwcOZbWf4/rMHf2sn4Hclfr+wQ+36mf4H9ev/5rV'
    'fu7ru27u6Gf9DEwCXV2XQ8XSav0MY6hcvjIrDVBds9a5G+hn/QxMsusLjxztCCWz5+tn+Ifvzv+QlQb48cJP5o1+1s/AJBTvPRvv'
    '01FQOEs/w9/Wv/1uRvf/9Rs2OvaMftbPwCTX3d0TllXW6mcYde/e/bBw4VsZ2fdXLK0Kd+/eM2f0s34GEnJOR2fniVBatlA/k/d+'
    'u3EzlM9dnNb9flxef/+A+aKf9TOQMIODD0JLy+5xndPR2NhiZiRWbN2a2nVpu15QO5NkZ86cC1MKZma8n+N6zBvIVb29fc/9Pol4'
    'X2mzIsni92u3tu594S6Iz4vP9z3d5IO16xoy2s6r19SbMzApnDp1eszPsadNL3M8jbxx5Ur3hO9tFx8fn2d+5M/7zZHQ3n4sdY3s'
    'qtV1abOhYVM4PLrcuHxzBiaLoaGhsH//l6M9sCLVBbPnzA/nzn1vNuTdNQLd13pCW1t7eKf+/bCkoirMKV0QCotKUj/j7/Hv8f9X'
    'r15zjw0AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    'AAAAAAAAAAAAnvIXkon7Zw=='
)

FAILED_MODAL_BAR = (
    'eNrt3etu6kYUBtCna+AEwh1zB3N5/1fwybZ6jtqKNhCMa8brxxIKgRlpS7Y/DzPjy+VSXAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIBknc/nYj5fF73+tHjrjIrBcF7kx5Pa'
    '0MpjYbXelcfAe29adLqj8piI1/g73l+tduXn1AsA2mm12hbdH+Pij7fh33S74+J0kqFphzw/FpPJoszK/zwWronPxeeP7jNpmf0+'
    'L7LFphxzqUq0F+2qL9B0h0Ne9D9m/5kR4pymVqQs8u9ksrwpM18T49KT6VKOphVms9W3j5VbTD/bV2egiU6nc3m9v2WcbTReqBlJ'
    'Z+ev7iFvFe3I0KRss9k/NTv/Ev2oN9Aky+X1uRryM22z2x3uOhZuEe1Fu+pLiqZPHns2Bg00b67GsVwbeO95TH4mzTWCl28dD7eO'
    'Q0f76kxqxpNFLfk5+lFv4P+eq/HIOU9+Rg5w/Qf5GWiLxXJTrm165DwmP5PibzF1ZIDYz0O9kZ/lZ+B15nXGPrVVnMfkZ1IzHGa1'
    'ZIDhKFNv5Gf5GXiBvQRGo2qzgfxMasdIHdd/Y9DIz/Iz0PS1UOciy9YPz9WQn2nDnKY683P0p+7Iz/Iz0CzbbXVzNeRnUjcYzGvN'
    'z+ZwID/Lz0CzPPLMNPmZNvpR8X7PX4n+1B35WX4G2vU7tPxMSvOcbnnmZpWiP7VHfpafgfaMPcvPJLV28FTv2sFfPNMb+Vl+Bpoh'
    'nsMtP8M948+X4q0zrH38Oca91R/5WX4G2nMuk59JSdf8Z5CfAftv2H8Dbtb/mNWan6M/dUd+lp+BZq6Lyhabp6yNkp9JyTxb15qf'
    'oz91R36Wn4EGr4+K5w+OF/Iz/IvDIa81P0d/6o78LD8DzbfbVTenQ34mNb3+zNwNkJ8Broo9ojvdsfwMf7HZ7mvJANvP+1j1Rn6W'
    'n4HXczqdy3PRd+dGy8+k6GPw3DHowXCuzsjP8jPw8vM+j0WvP5Wf4c/7yvf3yVOu/e+9iWemID/Lz0BC4rkr9+yBKz+T7D1lfqx8'
    'P+hoT3ZGfpafgTTH3uL537fM6RjLzyS+Z01Ve0JHO7IzSa8d2NSzdiD6UW+guXM68i+zQ+wrrVakbjpdPnS9j++rI+1YOzC3bw3A'
    'p9Xq+pyOGJ82nkZb7Pf53Xvbxefje+pHW5zPlyLL1uUa2ViHW5XhKCvHa6J9dQZe55x4Lubz9e81hp3uyG9otDNHH/Lf+SD2UY9j'
    'Ie4l4zX+jvfj/3IzAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
    'AAAAAAAAAAAAAAAAAAAAANf8BEPuCS0='
)


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)


def encoded_png(raw, *, width=720, height=1280, method=0, extra=b'', compressed_suffix=b''):
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'sRGB', b'\0')
            + chunk(b'sBIT', b'\x08' * 4) + extra + chunk(b'IDAT', zlib.compress(raw) + compressed_suffix)
            + chunk(b'IEND', b''))


def canvas(region):
    stride = 720 * 4
    body = bytes((250, 250, 250, 255)) * (720 * 1196) + bytes(region)
    return encoded_png(b''.join(b'\0' + body[start:start + stride] for start in range(0, len(body), stride)))


def hierarchy(*, modal=False, message='Permanently delete local ID guest-draft', button='DELETE LOCAL DRAFT'):
    root = ET.Element('hierarchy', rotation='0')
    ET.SubElement(root, 'node', package=SMOKE.PACKAGE, bounds='[0,0][720,1280]', enabled='true')
    if modal:
        ET.SubElement(root, 'node', package=SMOKE.PACKAGE, text=message, bounds='[36,500][684,700]',
                      enabled='true', **{'class': 'android.widget.TextView'})
        ET.SubElement(root, 'node', package=SMOKE.PACKAGE, text=button, bounds='[360,750][684,850]',
                      enabled='true', **{'class': SMOKE.BUTTON})
    else:
        ET.SubElement(root, 'node', package=SMOKE.PACKAGE, bounds='[0,1196][720,1280]', enabled='true',
                      **{'class': 'android.view.View', 'resource-id': 'android:id/navigationBarBackground'})
    return ET.tostring(root, encoding='utf-8', xml_declaration=True)


def white_glyph_fixture():
    # This is a synthetic unit positive, not a corrected installed screenshot.
    reference = zlib.decompress(base64.b64decode(ORDINARY_BAR))
    bad = bytearray(zlib.decompress(base64.b64decode(FAILED_MODAL_BAR)))
    for offset in range(0, len(reference), 4):
        if reference[offset:offset + 4] == bytes((69, 70, 79, 255)):
            bad[offset:offset + 4] = bytes((255, 255, 255, 255))
    return canvas(bad)


def taskbar_text():
    # Synthetic selected-subsection layout, not a captured or full guest dump.
    # The earlier overview profile is deliberately distinct from Taskbar's own.
    return (
        'SERVICE com.android.launcher3/com.android.quickstep.TouchInteractionService abc pid=1332 user=0\n'
        '  Client:\n'
        '    DeviceProfile:\n'
        '      widthPx: 1280.0px (731.4286dp)\n'
        '    TaskbarManager:\n'
        '      TaskbarActivityContext:\n'
        '        mNavMode=THREE_BUTTONS\n'
        '        mImeDrawsImeNavBar=false\n'
        '        mIsUserSetupComplete=true\n'
        '        mWindowLayoutParams.height=84px\n'
        '        mBindInProgress=false\n'
        '        TaskbarControllers:\n'
        '          NavbarButtonsViewController:\n'
        '            mState=FLAG_SMALL_SCREEN\n'
        '            mTaskbarNavButtonDarkIntensity=0.0\n'
        '            mSlideInViewVisibleNavButtonColorOverride=0.0\n'
        '            mOnTaskbarBackgroundNavButtonColorOverride=0.0\n'
        '            mOnBackgroundNavButtonColorOverrideMultiplier=1.0\n'
        '          OtherController:\n'
        '            mState=unchanged\n'
        '      DeviceProfile:\n'
        '        1 dp = 1.75 px\n'
        '        isTablet:false\n'
        '        isPhone:true\n'
        '        transposeLayoutWithOrientation:true\n'
        '        isGestureMode:false\n'
        '        isLandscape:false\n'
        '        isMultiWindowMode:false\n'
        '        isTwoPanels:false\n'
        '        isLeftRightSplit:false\n'
        '        widthPx: 720.0px (411.42856dp)\n'
        '        heightPx: 1280.0px (731.4286dp)\n'
        '        availableWidthPx: 720.0px (411.42856dp)\n'
        '        availableHeightPx: 1196.0px (683.4286dp)\n'
        '        isTaskbarPresent:false\n'
        '        isTaskbarPresentInApps:false\n'
        '    AssistStateManager:\n'
    )

class WindowDevice:
    def __init__(self):
        self.calls = []
        self.result = subprocess.CompletedProcess([], 0,
            'WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
            'mCurrentFocus=Window{123 u0 synthetic}\n'
            'mNavBarColorWindowCandidate=Window{123 u0 synthetic}\n'
            'mNavBarBackgroundWindowCandidate=Window{456 u0 synthetic}\n', '')
        self.error = None
        self.light_error = None
        self.taskbar_error = None
        self.taskbar_result = subprocess.CompletedProcess([], 0, taskbar_text(), '')
        self.light_result = subprocess.CompletedProcess([], 0,
            'SERVICE com.android.systemui/.SystemUIService synthetic pid=123 user=0\n'
            'Dump starting: synthetic fixture\nLightBarController:\n'
            ' mAppearance=LIGHT_NAVIGATION_BARS\n mNavigationLight=true\n'
            ' mHasLightNavigationBar=true\n mForceDarkForScrim=false\n'
            ' mForceLightForScrim=false\n mNavbarColorManagedByIme=false\n'
            ' NavigationBarTransitionsController:\n'
            '  mTransitionDeferring=false  mTransitionPending=false mTintChangePending=false\n'
            '  mPendingDarkIntensity=1.0 mDarkIntensity=1.0 mNextDarkIntensity=1.0\n'
            'Dump took 1ms\n', '')

    def adb(self, *arguments, timeout, check):
        self.calls.append((arguments, timeout, check))
        if arguments == getattr(SMOKE, 'SYSTEM_BAR_TASKBAR_QUERY', None):
            if self.taskbar_error:
                raise self.taskbar_error
            return self.taskbar_result
        if arguments == SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY:
            if self.light_error:
                raise self.light_error
            return self.light_result
        if self.error:
            raise self.error
        return self.result


class SystemBarEvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ordinary_region = zlib.decompress(base64.b64decode(ORDINARY_BAR))
        cls.bad_region = zlib.decompress(base64.b64decode(FAILED_MODAL_BAR))
        cls.ordinary = canvas(cls.ordinary_region)
        cls.bad = canvas(cls.bad_region)
        cls.good = white_glyph_fixture()
        cls.reference_xml = hierarchy()
        cls.modal_xml = hierarchy(modal=True)

    def measure(self, *, reference=None, reference_xml=None, modal=None, modal_xml=None):
        return SMOKE.measure_navigation_pair(
            self.ordinary if reference is None else reference,
            self.reference_xml if reference_xml is None else reference_xml,
            self.bad if modal is None else modal,
            self.modal_xml if modal_xml is None else modal_xml,
            'Permanently delete local ID guest-draft', 'Delete local draft')

    def write_checkpoints(self, output, *, bad=False):
        for index, name in enumerate(SMOKE.SYSTEM_BAR_CHECKPOINTS):
            (output / (name + '.png')).write_bytes(self.ordinary if index % 2 == 0 else self.bad if bad else self.good)
            xml = self.reference_xml
            if index == 1:
                xml = self.modal_xml
            elif index == 3:
                xml = hierarchy(modal=True, message='No local draft directories found.', button='CLOSE')
            (output / (name + '.xml')).write_bytes(xml)

    def receipt(self):
        return {'source_commit': '1' * 40, 'unsigned_apk_sha256': '2' * 64,
                'startup_display': {'profile': 'raster-720x1280-280'},
                'system_bar_png_captures': {name: {'clock': 'host_monotonic', 'start': 0.0, 'end': 0.001}
                                            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS}}

    def passing_state(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            self.write_checkpoints(output)
            receipt = self.receipt()
            device = WindowDevice()
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            SMOKE.qualify_system_bar_evidence(output, receipt)
            return receipt['system_bar_evidence']

    def test_exact_original_pixel_strips_have_fixed_hashes(self):
        self.assertEqual(hashlib.sha256(self.ordinary_region).hexdigest(),
                         'cd25e42a03bcf947066c357d217189f3766a67fe0db4f76b5452c625de3c1eb8')
        self.assertEqual(hashlib.sha256(self.bad_region).hexdigest(),
                         '6c77153b7f69892c8dc3acb3c13db22260c8198db1d36b25a828244798528821')

    def test_observed_bad_modal_is_a_real_contrast_failure(self):
        measured = self.measure()
        self.assertEqual(measured['status'], 'failed_contrast')
        self.assertEqual(measured['reference_contrast'], 8.97386058887348)
        self.assertEqual([g['contrast'] for g in measured['glyphs']], [1.5828461084972716] * 3)
        self.assertEqual([g['reference_solid_pixels'] for g in measured['glyphs']], [418, 601, 619])

    def test_ordinary_high_contrast_pixels_are_a_positive_control(self):
        measured = self.measure(modal=self.ordinary)
        self.assertEqual(measured['status'], 'passed')
        self.assertTrue(all(g['contrast'] == 8.97386058887348 for g in measured['glyphs']))

    def test_synthetic_white_glyphs_pass_without_claiming_installed_positive(self):
        measured = self.measure(modal=self.good)
        self.assertEqual(measured['status'], 'passed')
        self.assertTrue(all(g['contrast'] > 5 for g in measured['glyphs']))

    def test_blank_modal_bar_cannot_pass_as_high_contrast(self):
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'absent'):
            self.measure(modal=canvas(bytes((100, 100, 100, 255)) * 60480))

    def test_shifted_modal_glyphs_are_unqualified(self):
        shifted = bytearray(self.bad_region)
        for row in range(84):
            source = self.bad_region[row * 2880:(row + 1) * 2880]
            shifted[row * 2880:(row + 1) * 2880] = bytes((100, 100, 100, 255)) * 8 + source[:-32]
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'geometry'):
            self.measure(modal=canvas(shifted))

    def test_mixed_modal_glyph_colors_are_unqualified(self):
        mixed = bytearray(self.bad_region)
        for offset in range(0, len(mixed), 4):
            if offset // 4 % 720 < 240 and self.ordinary_region[offset:offset + 4] == bytes((69, 70, 79, 255)):
                mixed[offset:offset + 4] = bytes((255, 255, 255, 255))
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'inconsistent'):
            self.measure(modal=canvas(mixed))

    def test_missing_reference_glyph_is_unqualified(self):
        missing = bytearray(self.ordinary_region)
        for offset in range(0, len(missing), 4):
            if offset // 4 % 720 < 240:
                missing[offset:offset + 4] = bytes((250, 250, 250, 255))
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'geometry'):
            self.measure(reference=canvas(missing))

    def test_foreign_duplicate_shifted_or_rotated_hierarchy_is_unqualified(self):
        for change in ('foreign', 'duplicate', 'bounds', 'rotation'):
            with self.subTest(change=change):
                root = ET.fromstring(self.reference_xml)
                bar = list(root)[1]
                if change == 'foreign':
                    bar.set('package', 'foreign.app')
                elif change == 'duplicate':
                    root.append(copy.deepcopy(bar))
                elif change == 'bounds':
                    bar.set('bounds', '[0,1200][720,1280]')
                else:
                    root.set('rotation', '1')
                with self.assertRaises(SMOKE.SmokeFailure):
                    self.measure(reference_xml=ET.tostring(root))

    def test_modal_text_button_and_missing_ordinary_bar_are_not_interchangeable(self):
        for xml in (self.reference_xml, hierarchy(modal=True, button='KEEP EDITS'),
                    hierarchy(modal=True, message='Other modal')):
            with self.subTest(xml=xml):
                with self.assertRaises(SMOKE.SmokeFailure):
                    self.measure(modal_xml=xml)
        with self.assertRaises(SMOKE.SmokeFailure):
            self.measure(reference_xml=self.modal_xml)
        for bounds in ('[0,0][0,0]', '[700,100][900,200]'):
            root = ET.fromstring(self.modal_xml)
            list(root)[2].set('bounds', bounds)
            with self.subTest(bounds=bounds), self.assertRaises(SMOKE.SmokeFailure):
                self.measure(modal_xml=ET.tostring(root))

    def test_transparency_is_unqualified(self):
        region = bytearray(self.bad_region)
        region[3] = 254
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'transparent'):
            self.measure(modal=canvas(region))

    def test_png_crc_truncation_trailing_data_and_unknown_color_profile_reject(self):
        corrupted = bytearray(self.ordinary)
        corrupted[-5] ^= 1
        raw = b'\0' + bytes(2880)
        unknown = encoded_png(raw * 1280, extra=chunk(b'iCCP', b'unknown'))
        for data in (bytes(corrupted), self.ordinary[:-1], self.ordinary + b'x', unknown):
            with self.subTest(length=len(data)):
                with self.assertRaises(SMOKE.SmokeFailure):
                    SMOKE.decode_navigation_png(data)

    def test_png_wrong_size_depth_interlace_and_order_reject(self):
        def changed_header(values):
            return self.ordinary[:8] + chunk(b'IHDR', struct.pack('>IIBBBBB', *values)) + self.ordinary[33:]
        for values in ((1280, 720, 8, 6, 0, 0, 0), (720, 1280, 16, 6, 0, 0, 0),
                       (720, 1280, 8, 2, 0, 0, 0), (720, 1280, 8, 6, 0, 0, 1)):
            with self.subTest(values=values):
                with self.assertRaises(SMOKE.SmokeFailure):
                    SMOKE.decode_navigation_png(changed_header(values))
        with self.assertRaises(SMOKE.SmokeFailure):
            SMOKE.decode_navigation_png(self.ordinary[:33] + self.ordinary[46:])

    def test_png_inflation_limit_and_concatenated_stream_reject(self):
        expected = 1280 * (2880 + 1)
        for raw, suffix in ((bytes(expected + 1), b''), (bytes(expected - 1), b''),
                            (bytes(expected), zlib.compress(b'extra'))):
            with self.subTest(raw_length=len(raw), suffix=bool(suffix)):
                with self.assertRaises(SMOKE.SmokeFailure):
                    SMOKE.decode_navigation_png(encoded_png(raw, compressed_suffix=suffix))

    def test_split_and_empty_idat_chunks_decode_with_linear_parser_work(self):
        data = self.ordinary
        offset = 8
        while data[offset + 4:offset + 8] != b'IDAT':
            offset += 12 + struct.unpack_from('>I', data, offset)[0]
        length = struct.unpack_from('>I', data, offset)[0]
        payload = data[offset + 8:offset + 8 + length]
        middle = len(payload) // 2
        pieces = [b''] * 128 + [payload[:middle], b'', payload[middle:], b'']
        split = data[:offset] + b''.join(chunk(b'IDAT', part) for part in pieces) + data[offset + length + 12:]
        expected = SMOKE.decode_navigation_png(data)
        examined = [0]
        def counted_all(values):
            items = list(values)
            examined[0] += len(items)
            return all(items)
        with mock.patch.object(SMOKE, 'all', side_effect=counted_all, create=True):
            actual = SMOKE.decode_navigation_png(split)
        self.assertEqual(actual, expected)
        self.assertLessEqual(examined[0], len(pieces))

    def test_all_five_png_filters_decode_known_constant_rows(self):
        pixel = bytes((1, 10, 100, 255)); half = bytes((1, 5, 50, 128)); zero = bytes(2880)
        expected = pixel * (720 * 1280)
        for method in range(5):
            with self.subTest(method=method):
                if method == 0:
                    first = later = pixel * 720
                elif method == 1:
                    first = later = pixel + bytes(2876)
                elif method == 2:
                    first, later = pixel * 720, zero
                elif method == 3:
                    first, later = pixel + half * 719, half + bytes(2876)
                else:
                    first, later = pixel + bytes(2876), zero
                raw = bytes((method,)) + first + (bytes((method,)) + later) * 1279
                width, height, decoded = SMOKE.decode_navigation_png(encoded_png(raw))
                self.assertEqual((width, height), (720, 1280))
                self.assertEqual(decoded, expected)

    def test_window_capture_is_once_after_originals_with_exact_command_and_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
            original = (output / (name + '.png')).read_bytes()
            SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(device.calls, [(SMOKE.SYSTEM_BAR_WINDOW_QUERY, 5, False),
                                             (SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY, 5, False),
                                             (SMOKE.SYSTEM_BAR_TASKBAR_QUERY, 5, False)])
            observed = receipt['system_bar_evidence']['window_observations'][name]
            self.assertEqual(observed['png_sha256'], hashlib.sha256(original).hexdigest())
            self.assertEqual((output / (name + '.png')).read_bytes(), original)
            self.assertEqual((output / observed['retained_text_file']).read_text(), device.result.stdout)
            with self.assertRaisesRegex(SMOKE.SmokeFailure, 'Duplicate'):
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 3)

    def test_observed_window_leading_linefeed_preserves_complete_raw_evidence(self):
        # Original0a1 full dumpsys window output has one leading LF. The PNGs
        # here remain synthetic positives, not a corrected installed result.
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            device.result.stdout = '\nWINDOW MANAGER LAST ANR (dumpsys window lastanr)\n' + device.result.stdout
            original_text = device.result.stdout.encode('utf-8')
            originals = {p.name: p.read_bytes() for p in output.iterdir()}
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
                observed = receipt['system_bar_evidence']['window_observations'][name]
                self.assertEqual((output / observed['retained_text_file']).read_bytes(), original_text)
                self.assertEqual(observed['retained_text_sha256'], hashlib.sha256(original_text).hexdigest())
                self.assertEqual(observed['command']['stdout_tail'], device.result.stdout)
            self.assertEqual({name: (output / name).read_bytes() for name in originals}, originals)
            failure = None
            try:
                SMOKE.qualify_system_bar_evidence(output, receipt)
            except SMOKE.SmokeFailure as error:
                failure = str(error)
            self.assertIsNone(failure, 'Complete observed leading-LF window output must qualify')
            self.assertTrue(SMOKE.system_bar_evidence_passes(receipt['system_bar_evidence']))
            self.assertEqual(len(device.calls), 12)

    def test_window_header_does_not_admit_unobserved_prefixes(self):
        state = self.passing_state()
        for prefix in ('\n\n', ' ', '\t', '\r', '\r\n', '\ufeff', 'unrelated output\n'):
            with self.subTest(prefix=repr(prefix)):
                candidate = copy.deepcopy(state)
                observed = candidate['window_observations'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]
                command = observed['command']; text = prefix + command['stdout_tail']
                command.update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
                observed['retained_text_sha256'] = hashlib.sha256(text.encode('utf-8')).hexdigest()
                self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_observed_window_header_keeps_failed_modal_pixels_failed(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output, bad=True)
            device, receipt = WindowDevice(), self.receipt()
            device.result.stdout = '\n' + device.result.stdout
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            with self.assertRaisesRegex(SMOKE.SmokeFailure, 'navigation images or window observations'):
                SMOKE.qualify_system_bar_evidence(output, receipt)
            state = receipt['system_bar_evidence']
            self.assertEqual([row['status'] for row in state['pairs'].values()],
                             ['failed_contrast', 'failed_contrast'])
            self.assertFalse(SMOKE.system_bar_evidence_passes(state))

    def test_window_timeout_retains_partial_output_and_does_not_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            device.error = subprocess.TimeoutExpired(['owned'], 5, output=b'WINDOW MANAGER partial', stderr=b'late')
            name = SMOKE.SYSTEM_BAR_CHECKPOINTS[1]
            SMOKE.retain_system_bar_window(device, output, receipt, name)
            observed = receipt['system_bar_evidence']['window_observations'][name]
            self.assertEqual(observed['command']['stdout_tail'], 'WINDOW MANAGER partial')
            self.assertEqual(observed['command']['stderr']['prefix'], 'late')
            self.assertIn('command_timeout_seconds', observed['command'])
            self.assertEqual(len(device.calls), 3)
            self.assertEqual((output / observed['retained_text_file']).read_text(), 'WINDOW MANAGER partial')

    def test_window_failure_does_not_prevent_later_modal_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            device.result.returncode = 1
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 12)
            self.assertEqual(sum(call[1] for call in device.calls), 60)
            with self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.qualify_system_bar_evidence(output, receipt)
            self.assertEqual(receipt['system_bar_evidence']['status'], 'failed')
            self.assertTrue(all(row['status'] == 'passed' for row in receipt['system_bar_evidence']['pairs'].values()))

    def test_bad_images_fail_after_both_pairs_preserving_four_product_results(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output, bad=True)
            device, receipt = WindowDevice(), self.receipt()
            receipt['cases'] = [{'id': name, 'status': 'passed'} for name in SMOKE.CASE_IDS]
            original_cases = copy.deepcopy(receipt['cases'])
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            with self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.qualify_system_bar_evidence(output, receipt)
            self.assertEqual(receipt['cases'], original_cases)
            state = receipt['system_bar_evidence']
            self.assertEqual(state['status'], 'failed')
            self.assertEqual([row['status'] for row in state['pairs'].values()], ['failed_contrast', 'failed_contrast'])
            self.assertFalse(SMOKE.system_bar_evidence_passes(state))

    def test_complete_synthetic_receipt_passes_but_missing_or_forged_evidence_rejects(self):
        state = self.passing_state()
        self.assertTrue(SMOKE.system_bar_evidence_passes(state))
        for alter in (
                lambda s: s['window_observations'].pop(SMOKE.SYSTEM_BAR_CHECKPOINTS[0]),
                lambda s: s['pairs'][SMOKE.SYSTEM_BAR_CHECKPOINTS[1]].update(status='failed_contrast'),
                lambda s: s['pairs'][SMOKE.SYSTEM_BAR_CHECKPOINTS[1]]['glyphs'][0].update(contrast=999),
                lambda s: s['pairs'][SMOKE.SYSTEM_BAR_CHECKPOINTS[1]]['glyphs'][0].update(contrast=float('inf')),
                lambda s: s['pairs'][SMOKE.SYSTEM_BAR_CHECKPOINTS[1]]['glyphs'][0].update(matching_modal_pixels=True),
                lambda s: s['pairs'][SMOKE.SYSTEM_BAR_CHECKPOINTS[1]]['modal'].update(png_sha256='0' * 64),
                lambda s: s['window_observations'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]['command'].update(exit_code=True),
                lambda s: s['window_observations'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]['command'].update(seconds=5.001),
                lambda s: s.update(source_commit='unknown'),
                lambda s: s.update(profile='pixel-2')):
            candidate = copy.deepcopy(state); alter(candidate)
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_diagnostic_stderr_truncation_and_dump_timeout_are_unqualified(self):
        state = self.passing_state()
        for text, stderr, truncated in (('WINDOW MANAGER DUMP TIMEOUT', '', False),
                                      ('WINDOW MANAGER WINDOWS', 'diagnostic error', False),
                                      ('WINDOW MANAGER WINDOWS', '', True)):
            candidate = copy.deepcopy(state)
            observed = candidate['window_observations'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]
            command = observed['command']
            command.update(stdout=SMOKE.ui_stream_observation(text), stderr=SMOKE.ui_stream_observation(stderr),
                           stdout_tail=text, stdout_tail_truncated=truncated)
            observed['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_actual_checkpoint_keeps_original_capture_before_window_query(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            device, receipt, events = WindowDevice(), self.receipt(), []
            receipt.pop('system_bar_png_captures')
            device.adb_path, device.env = Path('/never-executed/adb'), {}
            name = '05-pending-portrait'
            original_query = device.adb

            def capture(*args, **kwargs):
                events.append('png')
                self.assertTrue((output / (name + '.xml')).is_file())
                self.assertFalse((output / (name + '.png')).exists())
                self.assertEqual(args[0], ['/never-executed/adb', '-s', SMOKE.SERIAL, 'exec-out', 'screencap', '-p'])
                self.assertEqual(kwargs['timeout'], 15)
                return subprocess.CompletedProcess(args[0], 0, self.ordinary, b'')

            def query(*args, **kwargs):
                events.append('lightbar' if args == SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY else
                              'taskbar' if args == SMOKE.SYSTEM_BAR_TASKBAR_QUERY else 'window')
                self.assertEqual((output / (name + '.png')).read_bytes(), self.ordinary)
                return original_query(*args, **kwargs)

            device.adb = query
            ui = SMOKE.Ui(device, output, receipt)
            with mock.patch.object(ui, 'snapshot', return_value=ET.fromstring(self.reference_xml)), \
                    mock.patch.object(SMOKE.subprocess, 'run', side_effect=capture):
                ui.checkpoint(name)
            self.assertEqual(events, ['png', 'window', 'lightbar', 'taskbar'])
            self.assertEqual((output / (name + '.png')).read_bytes(), self.ordinary)


    def test_lightbar_once_after_window_retains_bound_target_without_pixel_inference(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            name = SMOKE.SYSTEM_BAR_CHECKPOINTS[1]
            original = (output / (name + '.png')).read_bytes()
            SMOKE.retain_system_bar_window(device, output, receipt, name)
            state = receipt['system_bar_evidence']
            light, window = state['light_bar_observations'][name], state['window_observations'][name]
            self.assertEqual(device.calls, [(SMOKE.SYSTEM_BAR_WINDOW_QUERY, 5, False),
                                            (SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY, 5, False),
                                             (SMOKE.SYSTEM_BAR_TASKBAR_QUERY, 5, False)])
            self.assertEqual(light['phase'], 'after_original_png_and_window_observation')
            self.assertEqual(light['png_sha256'], window['png_sha256'])
            self.assertEqual(light['xml_sha256'], window['xml_sha256'])
            self.assertEqual(light['retained_text_sha256'], hashlib.sha256(device.light_result.stdout.encode()).hexdigest())
            self.assertEqual((output / light['retained_text_file']).read_text(), device.light_result.stdout)
            self.assertEqual((output / (name + '.png')).read_bytes(), original)
            # The synthetic diagnostic says dark tint even though this synthetic
            # image is white. It is retained evidence, never a substitute for pixels.
            self.assertNotIn('navigation_light', light)

    def test_lightbar_timeout_keeps_partial_and_later_original_pairs(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output, bad=True)
            device, receipt = WindowDevice(), self.receipt()
            device.light_error = subprocess.TimeoutExpired(['owned'], 5,
                output=b'SERVICE com.android.systemui/.SystemUIService partial', stderr=b'late tint')
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 12)
            self.assertEqual(sum(call[1] for call in device.calls), 60)
            for row in receipt['system_bar_evidence']['light_bar_observations'].values():
                self.assertIn('command_timeout_seconds', row['command'])
                self.assertEqual(row['command']['stderr']['prefix'], 'late tint')
                self.assertEqual((output / row['retained_text_file']).read_text(),
                                 'SERVICE com.android.systemui/.SystemUIService partial')
            with self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.qualify_system_bar_evidence(output, receipt)
            self.assertEqual([row['status'] for row in receipt['system_bar_evidence']['pairs'].values()],
                             ['failed_contrast', 'failed_contrast'])

    def test_lightbar_missing_partial_foreign_and_unbound_state_is_unqualified(self):
        state = self.passing_state()
        self.assertTrue(SMOKE.system_bar_evidence_passes(state))
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        for alter in (
                lambda s: s.pop('light_bar_observations'),
                lambda s: s['light_bar_observations'].pop(name),
                lambda s: s['light_bar_observations'][name].update(png_sha256='0' * 64),
                lambda s: s['light_bar_observations'][name]['command'].update(exit_code=True),
                lambda s: s['light_bar_observations'][name]['command'].update(seconds=5.001)):
            candidate = copy.deepcopy(state); alter(candidate)
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
        original = state['light_bar_observations'][name]['command']['stdout_tail']
        for text, stderr, truncated in (
                (original.replace('mNavigationLight=true', ''), '', False),
                (original.replace('mDarkIntensity=1.0', 'mDarkIntensity=unknown'), '', False),
                (original.replace('NavigationBarTransitionsController:', 'StatusBarTransitionsController:'), '', False),
                (original.replace('com.android.systemui/.SystemUIService', 'foreign/.Service'), '', False),
                (original.replace('Dump took 1ms', ''), '', False),
                (original + 'DUMP TIMEOUT\n', '', False),
                (original, 'read diagnostic', False),
                (original, '', True)):
            candidate = copy.deepcopy(state)
            row = candidate['light_bar_observations'][name]
            row['command'].update(stdout=SMOKE.ui_stream_observation(text), stderr=SMOKE.ui_stream_observation(stderr),
                                  stdout_tail=text, stdout_tail_truncated=truncated)
            row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
        # ViewDebug's present appearance label can have no flags; that is not a
        # missing observation and is not silently converted into a false value.
        candidate = copy.deepcopy(state); row = candidate['light_bar_observations'][name]
        text = original.replace('mAppearance=LIGHT_NAVIGATION_BARS', 'mAppearance=')
        row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
        row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
        self.assertTrue(SMOKE.system_bar_evidence_passes(candidate))

    def test_lightbar_java_scientific_float_output_retains_finite_values(self):
        state = self.passing_state()
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        original = state['light_bar_observations'][name]['command']['stdout_tail']
        for token in ('0.0001', '1.0E-4', '9.999999E-4', '0.0', '1.0', '-0.0', '1E0', '0E-4', '1.0E-45'):
            with self.subTest(token=token):
                candidate = copy.deepcopy(state)
                row = candidate['light_bar_observations'][name]
                text = re.sub(r'\b(mPendingDarkIntensity|mDarkIntensity|mNextDarkIntensity)=\S+',
                              lambda match: match.group(1) + '=' + token, original)
                row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
                row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
                self.assertTrue(SMOKE.system_bar_evidence_passes(candidate))
                self.assertEqual(row['command']['stdout_tail'], text)
                self.assertEqual(candidate['pairs'], state['pairs'])

    def test_lightbar_nonfinite_out_of_range_and_malformed_intensities_reject(self):
        state = self.passing_state()
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        original = state['light_bar_observations'][name]['command']['stdout_tail']
        for token in ('NaN', 'Infinity', '-Infinity', '1e999', '1.0000001', '-0.0000001',
                      '-1e-999', '1_0', '1..0', '0.1E', '+.x', '0x1.0p-4', '1.0f', '0.' + '0' * 32):
            with self.subTest(token=token):
                candidate = copy.deepcopy(state)
                row = candidate['light_bar_observations'][name]
                text = re.sub(r'\bmDarkIntensity=\S+', 'mDarkIntensity=' + token, original)
                row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
                row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
                self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
        candidate = copy.deepcopy(state); row = candidate['light_bar_observations'][name]
        text = original.replace('mDarkIntensity=1.0', 'mDarkIntensity=1.0 mDarkIntensity=1.0')
        row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
        row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
        self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_lightbar_empty_assignments_cannot_hide_duplicate_fields(self):
        state = self.passing_state()
        self.assertTrue(SMOKE.system_bar_evidence_passes(state))
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        original = state['light_bar_observations'][name]['command']['stdout_tail']
        for field in ('mPendingDarkIntensity', 'mDarkIntensity', 'mNextDarkIntensity'):
            for suffix in (' \n', '\n', '\t\n'):
                with self.subTest(field=field, suffix=repr(suffix)):
                    candidate = copy.deepcopy(state)
                    row = candidate['light_bar_observations'][name]
                    text = original.replace('Dump took 1ms', field + '=' + suffix + 'Dump took 1ms')
                    row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
                    row['retained_text_sha256'] = hashlib.sha256(text.encode()).hexdigest()
                    self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
                    self.assertEqual(row['command']['stdout_tail'], text)
                    self.assertEqual(candidate['pairs'], state['pairs'])
        for field in ('mPendingDarkIntensity', 'mDarkIntensity', 'mNextDarkIntensity'):
            for suffix in ('', ' ', '\n', '\t'):
                with self.subTest(field=field, helper_suffix=repr(suffix)):
                    self.assertFalse(SMOKE.lightbar_intensity_fields_pass(original + field + '=' + suffix))

    def test_current_receiving_gate_requires_explicit_image_evidence(self):
        from test_android_emulator_smoke import complete_startup_display_fixture
        receipt = complete_startup_display_fixture()
        SMOKE.complete_receipt(receipt, 'a' * 40)
        with self.assertRaisesRegex(SMOKE.SmokeFailure, 'navigation'):
            SMOKE.complete_receipt(receipt, 'a' * 40, expected_system_bar_evidence=True)

    def test_receiving_gate_binds_qualified_images_to_source_and_apk(self):
        from test_android_emulator_smoke import complete_startup_display_fixture
        receipt = complete_startup_display_fixture()
        state = self.passing_state()
        state['source_commit'] = receipt['source_commit']
        state['unsigned_apk_sha256'] = receipt['unsigned_apk_sha256']
        receipt['system_bar_evidence'] = state
        SMOKE.complete_receipt(receipt, 'a' * 40, expected_system_bar_evidence=True)
        for key, wrong in (('source_commit', 'f' * 40), ('unsigned_apk_sha256', 'f' * 64)):
            changed = copy.deepcopy(receipt)
            changed['system_bar_evidence'][key] = wrong
            with self.assertRaisesRegex(SMOKE.SmokeFailure, 'another source or APK'):
                SMOKE.complete_receipt(changed, 'a' * 40, expected_system_bar_evidence=True)

    def test_receiving_images_reject_missing_or_mismatched_startup_profile(self):
        from test_android_emulator_smoke import complete_startup_display_fixture, startup_display_fixture
        receipt = complete_startup_display_fixture()
        state = self.passing_state()
        state['source_commit'] = receipt['source_commit']
        state['unsigned_apk_sha256'] = receipt['unsigned_apk_sha256']
        receipt['system_bar_evidence'] = state
        for explicit in (False, True):
            SMOKE.complete_receipt(receipt, 'a' * 40, expected_system_bar_evidence=explicit)
            for display in (None, startup_display_fixture('pixel-2')):
                changed = copy.deepcopy(receipt)
                if display is None:
                    changed.pop('startup_display')
                else:
                    changed['startup_display'] = display
                with self.subTest(explicit=explicit, display=display):
                    with self.assertRaisesRegex(SMOKE.SmokeFailure, 'matching qualified startup display'):
                        SMOKE.complete_receipt(changed, 'a' * 40, expected_system_bar_evidence=explicit)

    def test_historical_receipts_without_image_evidence_keep_previous_display_admission(self):
        from test_android_emulator_smoke import complete_startup_display_fixture, startup_display_fixture
        for display in (None, startup_display_fixture('pixel-2'), startup_display_fixture()):
            receipt = complete_startup_display_fixture()
            if display is None:
                receipt.pop('startup_display')
            else:
                receipt['startup_display'] = display
            SMOKE.complete_receipt(receipt, 'a' * 40)


    def rebound_taskbar(self, state, text, *, name=None):
        name = name or SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        row = state['taskbar_observations'][name]
        row['command'].update(stdout=SMOKE.ui_stream_observation(text), stdout_tail=text)
        row['retained_text_sha256'] = hashlib.sha256(text.encode('utf-8')).hexdigest()
        return row

    def test_taskbar_is_required_even_when_previous_images_and_queries_pass(self):
        state = self.passing_state()
        state.pop('taskbar_observations', None)
        self.assertFalse(SMOKE.system_bar_evidence_passes(state),
                         'SystemUI evidence cannot replace the actual Taskbar observation')

    def test_taskbar_is_captured_once_after_existing_queries_without_replacing_originals(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            original = {p.name: p.read_bytes() for p in output.iterdir()}
            device, receipt = WindowDevice(), self.receipt()
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            state = receipt['system_bar_evidence']
            self.assertIn('taskbar_observations', state, 'Each original needs its actual renderer observation')
            self.assertEqual(device.calls, [(query, 5, False) for _ in range(4) for query in
                (SMOKE.SYSTEM_BAR_WINDOW_QUERY, SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY, SMOKE.SYSTEM_BAR_TASKBAR_QUERY)])
            for name, row in state['taskbar_observations'].items():
                self.assertEqual(row['command']['stdout_tail'], device.taskbar_result.stdout)
                self.assertEqual((output / row['retained_text_file']).read_bytes(),
                                 device.taskbar_result.stdout.encode('utf-8'))
                self.assertEqual(row['retained_text_sha256'],
                                 hashlib.sha256(device.taskbar_result.stdout.encode('utf-8')).hexdigest())
                self.assertEqual(row['png_sha256'], hashlib.sha256(original[name + '.png']).hexdigest())
                self.assertEqual(row['xml_sha256'], hashlib.sha256(original[name + '.xml']).hexdigest())
            self.assertEqual({name: (output / name).read_bytes() for name in original}, original)
            SMOKE.qualify_system_bar_evidence(output, receipt)

    def test_taskbar_timeout_retains_partial_error_and_all_later_original_checkpoints(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output, bad=True)
            device, receipt = WindowDevice(), self.receipt()
            device.taskbar_error = subprocess.TimeoutExpired(['owned'], 5,
                output=b'SERVICE com.android.launcher3/com.android.quickstep.TouchInteractionService partial',
                stderr=b'late taskbar')
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 12)
            for row in receipt['system_bar_evidence']['taskbar_observations'].values():
                self.assertEqual(row['error']['type'], 'TimeoutExpired')
                self.assertIn('command_timeout_seconds', row['command'])
                self.assertEqual(row['command']['stderr']['prefix'], 'late taskbar')
                self.assertEqual((output / row['retained_text_file']).read_text(),
                                 device.taskbar_error.stdout.decode('utf-8'))
                self.assertTrue(SMOKE.system_bar_interval_passes(row['timing'], 5))
            with self.assertRaises(SMOKE.SmokeFailure):
                SMOKE.qualify_system_bar_evidence(output, receipt)
            self.assertEqual([p['status'] for p in receipt['system_bar_evidence']['pairs'].values()],
                             ['failed_contrast', 'failed_contrast'])

    def test_taskbar_failed_transport_and_unbound_identity_never_qualify(self):
        original = self.passing_state()
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        for mutate in (
                lambda s: s['taskbar_observations'].pop(name),
                lambda s: s['taskbar_observations'][name].update(png_sha256='0' * 64),
                lambda s: s['taskbar_observations'][name].update(xml_sha256='0' * 64),
                lambda s: s['taskbar_observations'][name].update(retained_text_sha256='0' * 64),
                lambda s: s['taskbar_observations'][name].update(retained_text_file='foreign.txt'),
                lambda s: s['taskbar_observations'][name].update(phase='before_original_png'),
                lambda s: s['taskbar_observations'][name].update(error={'type': 'OSError'}),
                lambda s: s['taskbar_observations'][name]['command'].update(exit_code=1),
                lambda s: s['taskbar_observations'][name]['command'].update(exit_code=True),
                lambda s: s['taskbar_observations'][name]['command'].update(timeout_seconds=6),
                lambda s: s['taskbar_observations'][name]['command'].update(seconds=5.001),
                lambda s: s['taskbar_observations'][name]['command'].update(stdout_tail_truncated=True),
                lambda s: s['taskbar_observations'][name]['command'].update(stderr=SMOKE.ui_stream_observation('error')),
                lambda s: s['taskbar_observations'][name]['command'].update(arguments=['shell', 'dumpsys', 'activity'])):
            candidate = copy.deepcopy(original); mutate(candidate)
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
        for marker in ('DUMP TIMEOUT', 'Error dumping service info:'):
            candidate = copy.deepcopy(original)
            self.rebound_taskbar(candidate, taskbar_text() + marker + '\n')
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_taskbar_unambiguous_target_and_controller_scope_are_required(self):
        original = self.passing_state()
        raw = taskbar_text()
        candidates = (
            raw.replace('com.android.launcher3/', 'foreign.package/'),
            raw.replace('user=0', 'user=10'),
            raw.replace('pid=1332', 'pid=0'),
            raw + raw.splitlines()[0] + '\n',
            '\n'.join(raw.splitlines()[1:]) + '\n' + raw.splitlines()[0] + '\n',
            raw.replace('TaskbarActivityContext:\n', 'TaskbarActivityContext: null\n'),
            raw.replace('TaskbarControllers:\n', 'DestroyedTaskbarControllers:\n'),
            raw.replace('      TaskbarControllers:\n', '  TaskbarControllers:\n'),
            raw.replace('    TaskbarActivityContext:\n', '    OtherContext:\n') + 'TaskbarActivityContext:\n',
            raw.replace('          mTaskbarNavButtonDarkIntensity=', '          unrelated.mTaskbarNavButtonDarkIntensity='),
            raw + 'NavbarButtonsViewController:\n',
            raw.replace('          mTaskbarNavButtonDarkIntensity=0.0\n', '') +
                'mTaskbarNavButtonDarkIntensity=0.0\n',
            raw.replace('          mState=FLAG_SMALL_SCREEN\n', ''),
            raw.replace('          mState=FLAG_SMALL_SCREEN\n', '          mState=\n          mState=\n'),
        )
        for text in candidates:
            candidate = copy.deepcopy(original); self.rebound_taskbar(candidate, text)
            with self.subTest(text=text):
                self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_taskbar_finite_fields_accept_reference_float_forms_without_defaulting(self):
        original = self.passing_state()
        fields = ('mTaskbarNavButtonDarkIntensity', 'mSlideInViewVisibleNavButtonColorOverride',
                  'mOnTaskbarBackgroundNavButtonColorOverride', 'mOnBackgroundNavButtonColorOverrideMultiplier')
        for field in fields:
            for value in ('0.0', '1.0', '1.0E-4', '9.999999E-4', '+.5', '-0.0'):
                candidate = copy.deepcopy(original)
                raw = re.sub(r'\b' + field + r'=\S*', field + '=' + value, taskbar_text())
                self.rebound_taskbar(candidate, raw)
                with self.subTest(field=field, value=value):
                    self.assertTrue(SMOKE.system_bar_evidence_passes(candidate))
        # These values record a possible override; they are not interpreted as
        # a pass/fail tint prediction. Only the paired original pixels decide it.
        candidate = copy.deepcopy(original)
        raw = taskbar_text().replace('mOnTaskbarBackgroundNavButtonColorOverride=0.0',
                                    'mOnTaskbarBackgroundNavButtonColorOverride=1.0')
        self.rebound_taskbar(candidate, raw)
        self.assertTrue(SMOKE.system_bar_evidence_passes(candidate))

    def test_taskbar_missing_duplicate_blank_or_invalid_floats_fail_full_admission(self):
        original = self.passing_state()
        fields = ('mTaskbarNavButtonDarkIntensity', 'mSlideInViewVisibleNavButtonColorOverride',
                  'mOnTaskbarBackgroundNavButtonColorOverride', 'mOnBackgroundNavButtonColorOverrideMultiplier')
        for field in fields:
            for value in ('', 'NaN', 'Infinity', '-0.0001', '1.0001', '1e999', '1..0', '0,5', '1.0f'):
                candidate = copy.deepcopy(original)
                raw = re.sub(r'\b' + field + r'=\S*', field + '=' + value, taskbar_text())
                self.rebound_taskbar(candidate, raw)
                with self.subTest(field=field, value=value):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
            for duplicate in (field + '=\n', field + '=0.0\n'):
                candidate = copy.deepcopy(original)
                raw = taskbar_text().replace('        OtherController:\n', '          ' + duplicate +
                                             '        OtherController:\n')
                self.rebound_taskbar(candidate, raw)
                self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_host_intervals_preserve_png_window_lightbar_taskbar_order(self):
        original = self.passing_state()
        name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
        for kind in ('png_capture', 'window', 'lightbar', 'taskbar'):
            for value in (None, {}, {'clock': 'guest_uptime', 'start': 1.0, 'end': 2.0},
                          {'clock': 'host_monotonic', 'start': True, 'end': 2.0},
                          {'clock': 'host_monotonic', 'start': float('nan'), 'end': 2.0},
                          {'clock': 'host_monotonic', 'start': 1.0, 'end': float('inf')},
                          {'clock': 'host_monotonic', 'start': 2.0, 'end': 1.0},
                          {'clock': 'host_monotonic', 'start': -1.0, 'end': 0.0},
                          {'clock': 'host_monotonic', 'start': 1.0, 'end': 16.001}):
                candidate = copy.deepcopy(original)
                if kind == 'png_capture':
                    candidate['window_observations'][name]['png_capture'] = value
                else:
                    key = {'window': 'window_observations', 'lightbar': 'light_bar_observations',
                           'taskbar': 'taskbar_observations'}[kind]
                    candidate[key][name]['timing'] = value
                with self.subTest(kind=kind, value=value):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))
        for earlier, later in (('capture', 'window'), ('window', 'lightbar'), ('lightbar', 'taskbar')):
            candidate = copy.deepcopy(original)
            rows = {'capture': candidate['window_observations'][name]['png_capture'],
                    'window': candidate['window_observations'][name]['timing'],
                    'lightbar': candidate['light_bar_observations'][name]['timing'],
                    'taskbar': candidate['taskbar_observations'][name]['timing']}
            rows[later].update(start=rows[earlier]['start'], end=rows[earlier]['start'])
            self.assertFalse(SMOKE.system_bar_evidence_passes(candidate))

    def test_actual_capture_records_monotonic_bounds_and_unchanged_screencap_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); device, receipt = WindowDevice(), self.receipt()
            receipt.pop('system_bar_png_captures')
            device.adb_path, device.env = Path('/never-executed/adb'), {}
            ui = SMOKE.Ui(device, output, receipt)
            name = SMOKE.SYSTEM_BAR_CHECKPOINTS[0]
            clock = iter(float(n) / 100 for n in range(100))
            with mock.patch.object(SMOKE.time, 'monotonic', side_effect=lambda: next(clock)), \
                    mock.patch.object(ui, 'snapshot', return_value=ET.fromstring(self.reference_xml)), \
                    mock.patch.object(SMOKE.subprocess, 'run',
                                      return_value=subprocess.CompletedProcess([], 0, self.ordinary, b'')) as run:
                ui.checkpoint(name)
            self.assertEqual(run.call_args.args[0],
                ['/never-executed/adb', '-s', SMOKE.SERIAL, 'exec-out', 'screencap', '-p'])
            self.assertEqual(run.call_args.kwargs['timeout'], 15)
            self.assertTrue(run.call_args.kwargs['check'])
            state = receipt['system_bar_evidence']
            times = [state['window_observations'][name]['png_capture']] + [
                state[k][name]['timing'] for k in
                ('window_observations', 'light_bar_observations', 'taskbar_observations')]
            self.assertEqual(times[0], {'clock': 'host_monotonic', 'start': 0.0, 'end': 0.01})
            self.assertTrue(all(SMOKE.system_bar_interval_passes(t, 15 if i == 0 else 5)
                                for i, t in enumerate(times)))
            self.assertTrue(all(a['end'] <= b['start'] for a, b in zip(times, times[1:])))
            self.assertEqual((output / (name + '.png')).read_bytes(), self.ordinary)

    def test_failed_original_png_retains_its_interval_without_starting_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); device, receipt = WindowDevice(), self.receipt()
            receipt.pop('system_bar_png_captures')
            device.adb_path, device.env = Path('/never-executed/adb'), {}
            ui = SMOKE.Ui(device, output, receipt)
            original = subprocess.TimeoutExpired(['owned'], 15, output=b'partial png')
            with mock.patch.object(ui, 'snapshot', return_value=ET.fromstring(self.reference_xml)), \
                    mock.patch.object(SMOKE.subprocess, 'run', side_effect=original):
                with self.assertRaises(subprocess.TimeoutExpired) as raised:
                    ui.checkpoint(SMOKE.SYSTEM_BAR_CHECKPOINTS[0])
            self.assertIs(raised.exception, original)
            self.assertEqual(device.calls, [])
            self.assertNotIn('system_bar_evidence', receipt)
            capture = receipt['system_bar_png_captures'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]
            self.assertTrue(SMOKE.system_bar_interval_passes(capture, 15))



    def test_taskbar_wrapper_failures_reject_even_after_complete_clean_stdout(self):
        original = self.passing_state()
        self.assertTrue(SMOKE.system_bar_evidence_passes(original))
        for marker in ('Failure while dumping the service: java.io.IOException: Timeout',
                       'Got a RemoteException while dumping the service'):
            state = copy.deepcopy(original)
            raw = taskbar_text() + '    ' + marker + '\n'
            self.rebound_taskbar(state, raw)
            observation = state['taskbar_observations'][SMOKE.SYSTEM_BAR_CHECKPOINTS[0]]
            retained = copy.deepcopy(observation)
            with self.subTest(marker=marker):
                self.assertEqual(observation['command']['exit_code'], 0)
                self.assertFalse(SMOKE.taskbar_state_fields_pass(raw))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(observation, retained)

    def test_taskbar_prefix_eof_cannot_replace_its_completed_subsection(self):
        original = self.passing_state()
        for boundary in ('          OtherController:\n', '      DeviceProfile:\n',
                         '        isTaskbarPresent:false\n', '    AssistStateManager:\n'):
            state = copy.deepcopy(original)
            raw = taskbar_text().split(boundary)[0]
            self.rebound_taskbar(state, raw)
            with self.subTest(boundary=boundary):
                self.assertFalse(SMOKE.taskbar_state_fields_pass(raw))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
        # This peer proves only that TaskbarManager returned, not that every
        # later TouchInteractionService / SystemUiProxy line was produced.
        self.assertTrue(SMOKE.system_bar_evidence_passes(original))

    def test_taskbar_client_context_profile_and_peer_boundary_are_uniquely_scoped(self):
        original = self.passing_state()
        raw = taskbar_text()
        profile = raw[raw.index('      DeviceProfile:\n'):raw.index('    AssistStateManager:\n')]
        candidates = {
            'missing_client': raw.replace('  Client:\n', ''),
            'null_client': raw.replace('  Client:\n', '  Client: null\n'),
            'duplicate_client': raw.replace('  Client:\n', '  Client:\n  Client:\n'),
            'null_duplicate_context': raw + '      TaskbarActivityContext: null\n',
            'missing_context_profile': raw.replace(profile, ''),
            'foreign_context_profile': raw.replace('      DeviceProfile:\n', '      OtherDeviceProfile:\n'),
            'null_context_profile': raw.replace('      DeviceProfile:\n', '      DeviceProfile: null\n'),
            'duplicate_context_profile': raw.replace(profile, profile + profile),
            'profile_at_overview_depth': raw.replace(profile, profile.replace('      ', '    ', 1)),
            'profile_before_controller': raw.replace(profile, '').replace('        TaskbarControllers:\n',
                                                                           profile + '        TaskbarControllers:\n'),
            'missing_peer': raw.replace('    AssistStateManager:\n', ''),
            'duplicate_peer': raw + '    AssistStateManager:\n',
            'null_peer': raw.replace('    AssistStateManager:\n', '    AssistStateManager: null\n'),
            'nested_peer': raw.replace('    AssistStateManager:\n', '        AssistStateManager:\n'),
            'foreign_sibling_before_profile': raw.replace(profile, '    ForeignTaskbar:\n' + profile),
            'foreign_sibling_inside_profile': raw.replace('        isPhone:true\n',
                                                          '      ForeignProfile:\n        isPhone:true\n'),
        }
        for label, text in candidates.items():
            state = copy.deepcopy(original); self.rebound_taskbar(state, text)
            with self.subTest(label=label):
                self.assertFalse(SMOKE.taskbar_state_fields_pass(text))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))

    def test_taskbar_context_fields_are_explicit_without_defaulting_runtime_values(self):
        original = self.passing_state()
        fields = {'mNavMode': 'THREE_BUTTONS', 'mImeDrawsImeNavBar': 'false',
                  'mIsUserSetupComplete': 'true', 'mWindowLayoutParams.height': '84px',
                  'mBindInProgress': 'false'}
        for field, value in fields.items():
            line = '        ' + field + '=' + value + '\n'
            for replacement in ('', '        ' + field + '=\n',
                                line + line, line + '        ' + field + '=\n',
                                '          ' + field + '=' + value + '\n'):
                state = copy.deepcopy(original)
                self.rebound_taskbar(state, taskbar_text().replace(line, replacement))
                with self.subTest(field=field, replacement=replacement):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(state))
        for value in ('84.0px', '2147483648px', '-2147483649px', 'NaNpx', '84', 'null'):
            state = copy.deepcopy(original)
            self.rebound_taskbar(state, taskbar_text().replace('height=84px', 'height=' + value))
            with self.subTest(height=value):
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
        for old, new in (('mImeDrawsImeNavBar=false', 'mImeDrawsImeNavBar=true'),
                         ('mIsUserSetupComplete=true', 'mIsUserSetupComplete=false'),
                         ('mBindInProgress=false', 'mBindInProgress=true'),
                         ('mWindowLayoutParams.height=84px', 'mWindowLayoutParams.height=-1px')):
            state = copy.deepcopy(original)
            self.rebound_taskbar(state, taskbar_text().replace(old, new))
            self.assertTrue(SMOKE.system_bar_evidence_passes(state))

    def test_taskbar_own_profile_requires_positive_finite_literal_dimensions(self):
        original = self.passing_state()
        source = taskbar_text()
        profile_start = source.index('      DeviceProfile:\n')
        prefix, profile = source[:profile_start], source[profile_start:]
        values = {'1 dp = ': '1.75 px', 'widthPx: ': '720.0px (411.42856dp)',
                  'heightPx: ': '1280.0px (731.4286dp)',
                  'availableWidthPx: ': '720.0px (411.42856dp)',
                  'availableHeightPx: ': '1196.0px (683.4286dp)'}
        for name, value in values.items():
            line = '        ' + name + value + '\n'
            for replacement in ('', '        ' + name + '\n', line + line,
                                line + '        ' + name + '\n',
                                '          ' + name + value + '\n'):
                state = copy.deepcopy(original)
                self.rebound_taskbar(state, prefix + profile.replace(line, replacement))
                with self.subTest(name=name, replacement=replacement):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(state))
        for value in ('0', '-1', 'NaN', 'Infinity', '1e999999999999999999999999999999999999',
                      '1..0', '1,75', '1.75f'):
            for old, replacement in (('1.75 px', value + ' px'),
                                     ('720.0px', value + 'px'),
                                     ('411.42856dp', value + 'dp')):
                state = copy.deepcopy(original)
                self.rebound_taskbar(state, prefix + profile.replace(old, replacement))
                with self.subTest(value=value, target=old):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(state))
        for old, replacement in (('1.75 px', '1.75E0 px'),
                                 ('720.0px', '7.2E2px'),
                                 ('411.42856dp', '4.1142856E2dp'),
                                 ('1280.0px', '2560.0px')):
            state = copy.deepcopy(original)
            self.rebound_taskbar(state, prefix + profile.replace(old, replacement))
            self.assertTrue(SMOKE.system_bar_evidence_passes(state))

    def test_taskbar_profile_flags_are_explicit_and_not_replaced_by_overview_flags(self):
        original = self.passing_state()
        source = taskbar_text(); start = source.index('      DeviceProfile:\n')
        prefix, profile = source[:start], source[start:]
        names = ('isTablet', 'isPhone', 'transposeLayoutWithOrientation', 'isGestureMode',
                 'isLandscape', 'isMultiWindowMode', 'isTwoPanels', 'isLeftRightSplit',
                 'isTaskbarPresent', 'isTaskbarPresentInApps')
        for name in names:
            line = re.search(r'^        ' + name + r':(?:true|false)\n', profile, re.M)[0]
            for replacement in ('', '        ' + name + ':\n', line + line,
                                line + '        ' + name + ':\n',
                                '        ' + name + ':null\n', '        ' + name + ':1\n'):
                state = copy.deepcopy(original)
                # A complete-looking value in the unrelated earlier profile
                # must never supply this context's missing or malformed value.
                raw = prefix.replace('    DeviceProfile:\n', '    DeviceProfile:\n' + line)
                self.rebound_taskbar(state, raw + profile.replace(line, replacement))
                with self.subTest(name=name, replacement=replacement):
                    self.assertFalse(SMOKE.system_bar_evidence_passes(state))
            state = copy.deepcopy(original)
            alternate = line.replace('true', 'false') if ':true' in line else line.replace('false', 'true')
            self.rebound_taskbar(state, prefix + profile.replace(line, alternate))
            self.assertTrue(SMOKE.system_bar_evidence_passes(state))

    def test_taskbar_reference_tabs_and_unrelated_earlier_profile_remain_admitted(self):
        original = self.passing_state()
        raw = taskbar_text()
        # ActiveServices contributes four spaces; nested Launcher dump prefixes
        # add tabs. Preserve that real lexical layout as well as expanded spaces.
        lines = []
        for line in raw.splitlines():
            spaces = len(line) - len(line.lstrip(' '))
            lines.append('    ' + '\t' * ((spaces - 4) // 2) + line.lstrip(' ')
                         if spaces >= 4 else line)
        tabbed = '\n'.join(lines) + '\n'
        for text in (raw, tabbed, tabbed.expandtabs(8)):
            state = copy.deepcopy(original); self.rebound_taskbar(state, text)
            with self.subTest(text=text):
                self.assertTrue(SMOKE.taskbar_state_fields_pass(text))
                self.assertTrue(SMOKE.system_bar_evidence_passes(state))


    def test_taskbar_observed_unrelated_property_prefix_keeps_original_raw_text(self):
        original = self.passing_state()
        source = taskbar_text()
        tabbed = []
        for line in source.splitlines():
            spaces = len(line) - len(line.lstrip(' '))
            tabbed.append('    ' + '\t' * ((spaces - 4) // 2) + line.lstrip(' ')
                          if spaces >= 4 else line)
        for raw in (source, '\n'.join(tabbed) + '\n'):
            # This single scalar spelling/prefix is from original298 dump08.
            # The surrounding fixture is synthetic; no original is rewritten.
            marker = '    AssistStateManager:\n'
            outlier = '\t\tfolderChildIconSizePx: 98.0px (56.0dp)\n'
            raw = raw.replace(marker, outlier + marker)
            state = copy.deepcopy(original); self.rebound_taskbar(state, raw)
            saved = copy.deepcopy(state)
            with self.subTest(raw=raw):
                self.assertTrue(SMOKE.taskbar_state_fields_pass(raw))
                self.assertTrue(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)

    def test_taskbar_property_outlier_cannot_hide_structural_or_required_field_loss(self):
        original = self.passing_state()
        outlier = '\t\tfolderChildIconSizePx: 98.0px (56.0dp)\n'
        source = taskbar_text().replace('    AssistStateManager:\n',
                                       outlier + '    AssistStateManager:\n')
        candidates = {
            'foreign_property': source.replace('folderChildIconSizePx:', 'ForeignProfile:'),
            'one_tab': source.replace(outlier, outlier[1:]),
            'spaces_instead_of_observed_tabs': source.replace(outlier, outlier.replace('\t', ' ')),
            'duplicate_scalar': source.replace(outlier, outlier + outlier),
            'duplicate_correct_prefix': source.replace(outlier, '        ' + outlier.lstrip('\t') + outlier),
            'malformed_scalar': source.replace('98.0px (56.0dp)', 'not-a-dimension'),
            'nonfinite_scalar': source.replace('98.0px', 'NaNpx'),
            'before_own_profile': source.replace(outlier, '').replace('      DeviceProfile:\n',
                                                                     outlier + '      DeviceProfile:\n'),
            'foreign_boundary': source.replace('        isPhone:true\n',
                                                '      ForeignProfile:\n        isPhone:true\n'),
            'missing_peer': source.replace('    AssistStateManager:\n', ''),
            'missing_own_profile': source.replace('      DeviceProfile:\n', ''),
            'missing_required_dimension': source.replace('        widthPx: 720.0px (411.42856dp)\n', ''),
            'duplicate_required_dimension': source.replace('        widthPx: 720.0px (411.42856dp)\n',
                '        widthPx: 720.0px (411.42856dp)\n        widthPx: 720.0px (411.42856dp)\n'),
            'missing_required_flag': source.replace('        isTaskbarPresentInApps:false\n', ''),
            'actual_wrapper_failure': source + '    Failure while dumping the service: java.io.IOException: Timeout\n',
            'actual_remote_failure': source + '    Got a RemoteException while dumping the service\n',
        }
        for label, raw in candidates.items():
            state = copy.deepcopy(original); self.rebound_taskbar(state, raw)
            saved = copy.deepcopy(state)
            with self.subTest(label=label):
                self.assertFalse(SMOKE.taskbar_state_fields_pass(raw))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)


    def test_taskbar_optional_measurement_prefixes_do_not_depend_on_one_property_name(self):
        source = taskbar_text()
        tabbed = []
        for line in source.splitlines():
            spaces = len(line) - len(line.lstrip(' '))
            tabbed.append('    ' + '\t' * ((spaces - 4) // 2) + line.lstrip(' ')
                          if spaces >= 4 else line)
        metrics = (
            '\t\tfolderChildIconSizePx: 98.0px (56.0dp)\n',
            '\t\tdesiredWorkspaceHorizontalMarginPx: 14.0px (8.0dp)\n',
            '\t\tadditionalMeasurementPx: 1.0E2px (5.0E1dp)\n',
        )
        for reference in (source, '\n'.join(tabbed) + '\n'):
            for subset in (metrics[:1], metrics[1:2], metrics[2:], metrics):
                raw = reference.replace('    AssistStateManager:\n',
                                        ''.join(subset) + '    AssistStateManager:\n')
                state = self.passing_state()
                self.rebound_taskbar(state, raw)
                saved = copy.deepcopy(state)
                with self.subTest(reference=reference, metrics=subset):
                    self.assertTrue(SMOKE.taskbar_state_fields_pass(raw))
                    self.assertTrue(SMOKE.system_bar_evidence_passes(state))
                    self.assertEqual(state, saved)

    def test_taskbar_optional_measurement_prefix_never_supplies_authoritative_fields(self):
        metric = '\t\tdesiredWorkspaceHorizontalMarginPx: 14.0px (8.0dp)\n'
        reference = taskbar_text()
        source = reference.replace('    AssistStateManager:\n',
                                   metric + '    AssistStateManager:\n')
        candidates = {
            'duplicate_unprefixed': source.replace(metric, metric + metric),
            'duplicate_regular_prefix': source.replace(metric, '        ' + metric.lstrip('\t') + metric),
            'foreign_heading': source.replace('desiredWorkspaceHorizontalMarginPx:', 'ForeignProfile:'),
            'capitalized_name': source.replace('desiredWorkspaceHorizontalMarginPx:', 'DesiredWorkspaceHorizontalMarginPx:'),
            'underscore_name': source.replace('desiredWorkspaceHorizontalMarginPx:', 'workspace_marginPx:'),
            'overlong_name': source.replace('desiredWorkspaceHorizontalMarginPx:', 'a' * 66 + 'Px:'),
            'one_tab': source.replace(metric, metric[1:]),
            'spaces_only': source.replace(metric, metric.replace('\t', ' ')),
            'three_tabs': source.replace(metric, '\t' + metric),
            'missing_value': source.replace('14.0px (8.0dp)', ''),
            'extra_suffix': source.replace('14.0px (8.0dp)', '14.0px (8.0dp) extra'),
            'nonfinite_px': source.replace('14.0px', 'NaNpx'),
            'nonfinite_dp': source.replace('8.0dp', 'Infinitydp'),
            'overlong_number': source.replace('14.0px', '1' * 33 + 'px'),
            'before_profile': source.replace(metric, '').replace('      DeviceProfile:\n',
                                                                 metric + '      DeviceProfile:\n'),
            'foreign_boundary': source.replace('        isPhone:true\n',
                                                '      ForeignProfile:\n        isPhone:true\n'),
            'missing_peer': source.replace('    AssistStateManager:\n', ''),
            'missing_own_profile': source.replace('      DeviceProfile:\n', ''),
            'missing_required_flag': source.replace('        isTaskbarPresentInApps:false\n', ''),
            'actual_io_failure': source + '    Failure while dumping the service: java.io.IOException: Timeout\n',
            'actual_remote_failure': source + '    Got a RemoteException while dumping the service\n',
        }
        for name in ('widthPx', 'heightPx', 'availableWidthPx', 'availableHeightPx'):
            existing = next(line for line in source.splitlines(True) if line.startswith('        ' + name + ':'))
            unprefixed = '\t\t' + existing.lstrip(' ')
            candidates['required_depth_' + name] = source.replace(existing, unprefixed)
            candidates['required_duplicate_' + name] = source.replace(existing, existing + unprefixed)
        for label, raw in candidates.items():
            state = self.passing_state()
            self.rebound_taskbar(state, raw)
            saved = copy.deepcopy(state)
            with self.subTest(label=label):
                self.assertFalse(SMOKE.taskbar_state_fields_pass(raw))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)

    def test_taskbar_optional_measurement_handles_reference_transfer_pipe_read_boundary(self):
        # A direct transcription of Android15-r1 TransferPipe.run's byte loop,
        # limited to this harmless in-memory fixture; no pipe/process is opened.
        def transfer(chunks):
            output = bytearray()
            need_prefix = True
            for buffer in chunks:
                self.assertTrue(0 < len(buffer) <= 1024)
                start = 0
                index = 0
                while index < len(buffer):
                    if buffer[index] != 10:
                        if index > start:
                            output.extend(buffer[start:index])
                        start = index
                        if need_prefix:
                            output.extend(b'    ')
                            need_prefix = False
                        index += 1
                        while index < len(buffer) and buffer[index] != 10:
                            index += 1
                        if index < len(buffer):
                            need_prefix = True
                    index += 1
                if len(buffer) > start:
                    output.extend(buffer[start:])
            return bytes(output)

        def bounded_chunks(segment):
            chunks = []
            while len(segment) > 1024:
                cut = segment.rfind(b'\n', 0, 1024) + 1
                self.assertGreater(cut, 0)
                chunks.append(segment[:cut])
                segment = segment[cut:]
            if segment:
                chunks.append(segment)
            return chunks

        lines = []
        for line in taskbar_text().splitlines():
            spaces = len(line) - len(line.lstrip(' '))
            lines.append('    ' + '\t' * ((spaces - 4) // 2) + line.lstrip(' ')
                         if spaces >= 4 else line)
        raw = '\n'.join(lines) + '\n'
        header, client_body = raw.split('  Client:\n', 1)
        header += '  Client:\n'
        client_body = ''.join(line[4:] if line.startswith('    ') else line
                              for line in client_body.splitlines(True))
        metric = '\t\tdesiredWorkspaceHorizontalMarginPx: 14.0px (8.0dp)\n'
        client_body = client_body.replace('AssistStateManager:\n', metric + 'AssistStateManager:\n')
        payload = client_body.encode()
        split = payload.index(metric.encode()) - 1
        self.assertEqual(payload[split:split + 1], b'\n')
        normal = header + transfer(bounded_chunks(payload[:split + 1]) +
                                   bounded_chunks(payload[split + 1:])).decode()
        lost_wrapper = header + transfer(bounded_chunks(payload[:split]) +
                                         bounded_chunks(payload[split:])).decode()
        self.assertIn('    ' + metric, normal)
        self.assertEqual(lost_wrapper, normal.replace('    ' + metric, metric, 1))
        for result in (normal, lost_wrapper):
            state = self.passing_state()
            self.rebound_taskbar(state, result)
            saved = copy.deepcopy(state)
            with self.subTest(raw=result):
                self.assertTrue(SMOKE.taskbar_state_fields_pass(result))
                self.assertTrue(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)


    def test_taskbar_optional_finite_zero_signed_and_dotted_measurements_keep_raw_data(self):
        # Five spellings/values come from qualified555 data. The surrounding
        # fixture and prefix-removal variants are synthetic, not installed captures.
        observed = (
            'iconSizePx: 0.0px (0.0dp)',
            'mHotseatWidthPx: 0.0px (0.0dp)',
            'taskbarHeight: 0.0px (0.0dp)',
            'workspacePadding.bottom: 135.0px (77.14286dp)',
            'windowX: 0.0px (0.0dp)',
        )
        # Preserve the exact former optional-value/name negatives as positives.
        # Their values cannot supply any required dimension or state.
        synthetic = (
            'folderChildIconSizePx: 0px (56.0dp)',
            'folderChildIconSizePx: 98.0px (-1dp)',
            'desiredWorkspaceHorizontalMargin: 14.0px (8.0dp)',
            'desiredWorkspaceHorizontalMarginPx: 0px (8.0dp)',
            'desiredWorkspaceHorizontalMarginPx: 14.0px (-8.0dp)',
            'windowOffset.left: -1.0E2px (+0.0dp)',
        )
        groups = [(line,) for line in observed + synthetic] + [observed]
        for fields in groups:
            raw = taskbar_text().replace('    AssistStateManager:\n',
                ''.join('\t\t' + line + '\n' for line in fields) + '    AssistStateManager:\n')
            state = self.passing_state()
            self.rebound_taskbar(state, raw)
            saved = copy.deepcopy(state)
            with self.subTest(fields=fields):
                self.assertTrue(SMOKE.taskbar_state_fields_pass(raw))
                self.assertTrue(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)

    def test_taskbar_optional_finite_measurements_exclude_authority_and_malformed_names(self):
        metric = '\t\tworkspacePadding.bottom: 135.0px (77.14286dp)\n'
        source = taskbar_text().replace('    AssistStateManager:\n',
                                       metric + '    AssistStateManager:\n')
        names = (
            '.workspacePadding', 'workspacePadding.', 'workspacePadding..bottom',
            'workspacePadding.Bottom', 'workspace_padding.bottom', 'workspacePadding[0]',
            'a' * 65, 'widthPx', 'heightPx', 'availableWidthPx', 'availableHeightPx',
            'isTablet', 'isPhone', 'transposeLayoutWithOrientation', 'isGestureMode',
            'isLandscape', 'isMultiWindowMode', 'isTwoPanels', 'isLeftRightSplit',
            'isTaskbarPresent', 'isTaskbarPresentInApps', 'mNavMode', 'mImeDrawsImeNavBar',
            'mIsUserSetupComplete', 'mBindInProgress', 'mWindowLayoutParams.height', 'mState',
            'mTaskbarNavButtonDarkIntensity', 'mSlideInViewVisibleNavButtonColorOverride',
            'mOnTaskbarBackgroundNavButtonColorOverride', 'mOnBackgroundNavButtonColorOverrideMultiplier',
        )
        candidates = {name: source.replace('workspacePadding.bottom:', name + ':') for name in names}
        candidates['duplicate_dotted_name'] = source.replace(metric, metric + metric)
        candidates['duplicate_prefixed_dotted_name'] = source.replace(metric, '    ' + metric + metric)
        candidates['missing_scalar_px'] = source.replace('135.0px', 'px')
        candidates['missing_scalar_dp'] = source.replace('77.14286dp', 'dp')
        candidates['infinite_scalar'] = source.replace('135.0px', '-Infinitypx')
        candidates['nonliteral_scalar'] = source.replace('135.0px', '0x10px')
        candidates['oversized_exponent'] = source.replace('135.0px', '1e' + '9' * 31 + 'px')
        for label, raw in candidates.items():
            state = self.passing_state()
            self.rebound_taskbar(state, raw)
            saved = copy.deepcopy(state)
            with self.subTest(label=label):
                self.assertFalse(SMOKE.taskbar_state_fields_pass(raw))
                self.assertFalse(SMOKE.system_bar_evidence_passes(state))
                self.assertEqual(state, saved)

if __name__ == '__main__':
    unittest.main()
