"""Real filesystem admission/publication controls, with reference work stubbed.

The original configuration suite separately executes all 17 complete reference
reports. These small controls require invalid output paths to fail before that
work and exercise actual files, aliases and temporary-file failures.
"""
import contextlib
import gzip
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
try:
    import reference_fixture_output as outputs
    spec = importlib.util.spec_from_file_location(
        'configuration_output_checker', SCRIPTS / 'check_configuration_fixtures.py')
    checker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(checker)
finally:
    sys.path.pop(0)


class ConfigurationOutputGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='configuration-output-test-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'checkout'
        self.root.mkdir()
        self.target = self.root / checker.FIXTURE
        self.target.parent.mkdir(parents=True)
        self.foreign = self.base / 'foreign'
        self.foreign.mkdir()
        self.payload = {'synthetic_output_boundary': 'complete new bytes'}
        self.expected = gzip.compress(checker.encoded(self.payload), compresslevel=9, mtime=0)
        self.previous = b'Controlled previously retained fixture bytes.\n'
        self.peer = self.foreign / 'unrelated.txt'
        self.peer.write_bytes(b'Unrelated controlled peer.\n')

    def invoke(self, *, write, root=None, producer=None):
        root = self.root if root is None else root
        original_verify = checker.verify
        stdout, stderr = io.StringIO(), io.StringIO()
        code = 0
        with patch.object(checker, 'ROOT', root), \
                patch.object(checker, 'generate', return_value=self.payload,
                             side_effect=producer) as generated, \
                patch.object(checker, 'verify', side_effect=lambda: original_verify(root)), \
                patch.object(sys, 'argv', ['check_configuration_fixtures.py'] +
                             (['--write-configurations'] if write else [])), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                checker.main()
            except SystemExit as error:
                code = error.code
        return code, stdout.getvalue(), stderr.getvalue(), generated.call_count

    def rejected(self, result, *, before_reference=True):
        code, stdout, stderr, calls = result
        self.assertEqual(1, code)
        self.assertIn('Configuration verification failed:', stderr)
        self.assertNotIn('Traceback', stderr)
        self.assertNotIn('complete configuration reports match', stdout)
        if before_reference:
            self.assertEqual(0, calls)
        self.assertEqual(b'Unrelated controlled peer.\n', self.peer.read_bytes())

    def assert_no_temporary_output(self):
        self.assertEqual([], list(self.target.parent.glob('.' + self.target.name + '.*')))

    def test_verifier_rejects_final_symlinks_and_hardlinks_before_reference(self):
        for kind in ('symlink', 'hardlink'):
            with self.subTest(kind=kind):
                outside = self.foreign / (kind + '.gz')
                outside.write_bytes(self.expected)
                if kind == 'symlink':
                    self.target.symlink_to(outside)
                else:
                    os.link(outside, self.target)
                try:
                    self.rejected(self.invoke(write=False))
                    self.assertEqual(self.expected, outside.read_bytes())
                finally:
                    self.target.unlink()

    def test_writer_rejects_final_links_without_changing_or_creating_foreign_data(self):
        for kind in ('symlink', 'hardlink', 'dangling'):
            with self.subTest(kind=kind):
                outside = self.foreign / (kind + '.gz')
                if kind != 'dangling':
                    outside.write_bytes(self.previous)
                if kind == 'hardlink':
                    os.link(outside, self.target)
                else:
                    self.target.symlink_to(outside)
                try:
                    self.rejected(self.invoke(write=True))
                    if kind == 'dangling':
                        self.assertFalse(outside.exists())
                    else:
                        self.assertEqual(self.previous, outside.read_bytes())
                    self.assert_no_temporary_output()
                finally:
                    self.target.unlink()

    def test_both_modes_reject_aliased_roots_and_ancestors_before_reference(self):
        for depth in (0, 1, len(checker.FIXTURE.parts) - 1):
            for write in (False, True):
                with self.subTest(depth=depth, write=write):
                    location = self.base / ('alias-' + str(depth) + '-' + str(write))
                    destination = self.foreign / ('tree-' + str(depth) + '-' + str(write))
                    destination.mkdir()
                    if depth == 0:
                        location.symlink_to(destination, target_is_directory=True)
                        outside = destination / checker.FIXTURE
                    else:
                        alias = location.joinpath(*checker.FIXTURE.parts[:depth])
                        alias.parent.mkdir(parents=True)
                        alias.symlink_to(destination, target_is_directory=True)
                        outside = destination.joinpath(*checker.FIXTURE.parts[depth:])
                    outside.parent.mkdir(parents=True, exist_ok=True)
                    before = self.previous if write else self.expected
                    outside.write_bytes(before)
                    self.rejected(self.invoke(write=write, root=location))
                    self.assertEqual(before, outside.read_bytes())

    def test_nonregular_destinations_and_ancestors_fail_before_reference(self):
        for kind in ('destination_directory', 'ancestor_file', 'root_file'):
            for write in (False, True):
                with self.subTest(kind=kind, write=write):
                    root = self.base / (kind + '-' + str(write))
                    if kind == 'root_file':
                        retained = root
                        retained.write_bytes(self.previous)
                    elif kind == 'ancestor_file':
                        root.mkdir()
                        retained = root / checker.FIXTURE.parts[0]
                        retained.write_bytes(self.previous)
                    else:
                        retained = root / checker.FIXTURE / 'retained.txt'
                        retained.parent.mkdir(parents=True)
                        retained.write_bytes(self.previous)
                    self.rejected(self.invoke(write=write, root=root))
                    self.assertEqual(self.previous, retained.read_bytes())

    def test_regular_and_missing_outputs_publish_exact_binary_bytes(self):
        for kind in ('replace', 'missing_file', 'missing_parents'):
            with self.subTest(kind=kind):
                root = self.base / kind
                root.mkdir()
                target = root / checker.FIXTURE
                if kind != 'missing_parents':
                    target.parent.mkdir(parents=True)
                if kind == 'replace':
                    target.write_bytes(self.previous)
                    target.chmod(0o600)
                code, stdout, stderr, calls = self.invoke(write=True, root=root)
                self.assertEqual((0, '', 1), (code, stderr, calls))
                self.assertIn('complete configuration reports match', stdout)
                self.assertEqual(self.expected, target.read_bytes())
                self.assertEqual(0o644, target.stat().st_mode & 0o777)
                self.assertEqual([], list(target.parent.glob('.' + target.name + '.*')))

    def test_failed_generation_compression_or_replace_preserves_previous_output(self):
        for stage in ('generation', 'compression', 'replace'):
            with self.subTest(stage=stage):
                self.target.write_bytes(self.previous)
                failure = OSError('controlled ' + stage + ' failure')
                if stage == 'generation':
                    result = self.invoke(write=True, producer=failure)
                else:
                    owner, name = (checker.gzip, 'compress') if stage == 'compression' else (Path, 'replace')
                    with patch.object(owner, name, side_effect=failure):
                        result = self.invoke(write=True)
                self.rejected(result, before_reference=False)
                self.assertEqual(self.previous, self.target.read_bytes())
                self.assert_no_temporary_output()

    def test_changed_destination_is_checked_again_before_publication(self):
        for kind in ('symlink', 'hardlink'):
            with self.subTest(kind=kind):
                self.target.write_bytes(self.previous)
                outside = self.foreign / (kind + '-later.gz')
                outside.write_bytes(self.previous)

                def change_after_admission():
                    self.target.unlink()
                    if kind == 'symlink':
                        self.target.symlink_to(outside)
                    else:
                        os.link(outside, self.target)
                    return self.payload

                try:
                    self.rejected(self.invoke(write=True, producer=change_after_admission),
                                  before_reference=False)
                    self.assertEqual(self.previous, outside.read_bytes())
                    self.assert_no_temporary_output()
                finally:
                    self.target.unlink()

    def test_partial_private_write_failure_preserves_previous_output_and_cleans_up(self):
        self.target.write_bytes(self.previous)
        original_temporary = outputs.tempfile.NamedTemporaryFile

        class PartialFailure:
            def __init__(self, **kwargs):
                self.context = original_temporary(**kwargs)

            def __enter__(self):
                self.file = self.context.__enter__()
                self.name = self.file.name
                return self

            def write(self, raw):
                self.file.write(raw[:7])
                raise OSError('controlled partial temporary write')

            def __exit__(self, *args):
                return self.context.__exit__(*args)

        with patch.object(outputs.tempfile, 'NamedTemporaryFile', side_effect=PartialFailure):
            self.rejected(self.invoke(write=True), before_reference=False)
        self.assertEqual(self.previous, self.target.read_bytes())
        self.assert_no_temporary_output()

    def test_ordinary_verification_preserves_bytes_mode_and_directory_contents(self):
        self.target.write_bytes(self.expected)
        self.target.chmod(0o400)
        before = self.target.stat()
        entries = sorted(path.name for path in self.target.parent.iterdir())
        code, stdout, stderr, calls = self.invoke(write=False)
        self.assertEqual((0, '', 1), (code, stderr, calls))
        self.assertIn('complete configuration reports match', stdout)
        self.assertEqual(self.expected, self.target.read_bytes())
        after = self.target.stat()
        self.assertEqual((before.st_mode, before.st_ino, before.st_mtime_ns),
                         (after.st_mode, after.st_ino, after.st_mtime_ns))
        self.assertEqual(entries, sorted(path.name for path in self.target.parent.iterdir()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
