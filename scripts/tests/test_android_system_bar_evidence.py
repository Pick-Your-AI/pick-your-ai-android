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
                'startup_display': {'profile': 'raster-720x1280-280'}}

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
                                             (SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY, 5, False)])
            observed = receipt['system_bar_evidence']['window_observations'][name]
            self.assertEqual(observed['png_sha256'], hashlib.sha256(original).hexdigest())
            self.assertEqual((output / (name + '.png')).read_bytes(), original)
            self.assertEqual((output / observed['retained_text_file']).read_text(), device.result.stdout)
            with self.assertRaisesRegex(SMOKE.SmokeFailure, 'Duplicate'):
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 2)

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
            self.assertEqual(len(device.calls), 2)
            self.assertEqual((output / observed['retained_text_file']).read_text(), 'WINDOW MANAGER partial')

    def test_window_failure_does_not_prevent_later_modal_observations(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory); self.write_checkpoints(output)
            device, receipt = WindowDevice(), self.receipt()
            device.result.returncode = 1
            for name in SMOKE.SYSTEM_BAR_CHECKPOINTS:
                SMOKE.retain_system_bar_window(device, output, receipt, name)
            self.assertEqual(len(device.calls), 8)
            self.assertEqual(sum(call[1] for call in device.calls), 40)
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
                events.append('lightbar' if args == SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY else 'window')
                self.assertEqual((output / (name + '.png')).read_bytes(), self.ordinary)
                return original_query(*args, **kwargs)

            device.adb = query
            ui = SMOKE.Ui(device, output, receipt)
            with mock.patch.object(ui, 'snapshot', return_value=ET.fromstring(self.reference_xml)), \
                    mock.patch.object(SMOKE.subprocess, 'run', side_effect=capture):
                ui.checkpoint(name)
            self.assertEqual(events, ['png', 'window', 'lightbar'])
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
                                            (SMOKE.SYSTEM_BAR_LIGHTBAR_QUERY, 5, False)])
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
            self.assertEqual(len(device.calls), 8)
            self.assertEqual(sum(call[1] for call in device.calls), 40)
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



if __name__ == '__main__':
    unittest.main()
