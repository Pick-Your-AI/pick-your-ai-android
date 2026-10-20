"""Real paired-output controls with CD1 reference execution deliberately stubbed.

The fixed-reference reproduction remains a separate full-generator check. These
tests exercise the actual CLI path and filesystem without changing its inputs.
Each file replacement is atomic; the pair is not a multi-file transaction.
"""
import contextlib
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
        'answer_profile_output_builder', SCRIPTS / 'build_answer_profile_fixtures.py')
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
finally:
    sys.path.pop(0)

RELATIVE = Path('core/src/test/resources/evidence/answers')
NAMES = ('cd1-reference.json', 'cd1-reference-lock.json')
EXPECTED = (b'{"controlled_observation":"complete fixture bytes"}\n',
            b'{"fixture":{"cases":1,"sha256":"controlled output boundary"}}\n')
PREVIOUS = (b'Previously retained fixture.\n', b'Previously retained fixture lock.\n')


class AnswerProfileOutputGuards(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='answer-output-test-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'checkout'
        self.root.mkdir()
        self.destination = self.root / RELATIVE
        self.destination.mkdir(parents=True)
        self.targets = tuple(self.destination / name for name in NAMES)
        self.foreign = self.base / 'foreign'
        self.foreign.mkdir()
        self.peer = self.foreign / 'unrelated.txt'
        self.peer.write_bytes(b'Unrelated controlled peer.\n')

    def retain(self, contents=PREVIOUS):
        for path, raw in zip(self.targets, contents):
            if path.exists():
                path.chmod(0o600)
            path.write_bytes(raw)

    def invoke(self, *, write, root=None, producer=None):
        root = self.root if root is None else root
        stdout, stderr = io.StringIO(), io.StringIO()
        code = 0
        with patch.object(builder, 'ROOT', root, create=True), \
                patch.object(builder, 'DESTINATION', root / RELATIVE), \
                patch.object(builder, 'generate', return_value=EXPECTED,
                             side_effect=producer) as generated, \
                patch.object(sys, 'argv', ['build_answer_profile_fixtures.py',
                                          '--research-root', str(self.foreign)] +
                             (['--write'] if write else [])), \
                contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            try:
                builder.main()
            except SystemExit as error:
                code = error.code
            except (OSError, ValueError) as error:
                code = type(error).__name__
        return code, stdout.getvalue(), stderr.getvalue(), generated.call_count

    def rejected(self, result, *, calls=0):
        code, stdout, stderr, generated = result
        self.assertEqual(1, code)
        self.assertIn('CD1 reference output failed:', stderr)
        self.assertNotIn('Traceback', stderr)
        self.assertNotIn('CD1_REFERENCE_PASS', stdout)
        self.assertEqual(calls, generated)
        self.assertEqual(b'Unrelated controlled peer.\n', self.peer.read_bytes())

    def no_temporary_outputs(self):
        for name in NAMES:
            self.assertEqual([], list(self.destination.glob('.' + name + '.*')))

    def test_both_modes_reject_either_final_alias_before_reference(self):
        for write in (False, True):
            for index in range(2):
                for kind in ('symlink', 'hardlink', 'dangling'):
                    with self.subTest(write=write, index=index, kind=kind):
                        contents = PREVIOUS if write else EXPECTED
                        self.retain(contents)
                        target = self.targets[index]
                        target.unlink()
                        outside = self.foreign / f'{write}-{index}-{kind}.json'
                        if kind != 'dangling':
                            outside.write_bytes(contents[index])
                        if kind == 'hardlink':
                            os.link(outside, target)
                        else:
                            target.symlink_to(outside)
                        try:
                            self.rejected(self.invoke(write=write))
                            self.assertEqual(contents[1 - index], self.targets[1 - index].read_bytes())
                            if kind == 'dangling':
                                self.assertFalse(outside.exists())
                            else:
                                self.assertEqual(contents[index], outside.read_bytes())
                            self.no_temporary_outputs()
                        finally:
                            target.unlink()

    def test_both_modes_reject_aliased_roots_and_ancestors_before_reference(self):
        for write in (False, True):
            for depth in (0, 1, len(RELATIVE.parts)):
                with self.subTest(write=write, depth=depth):
                    root = self.base / f'root-{write}-{depth}'
                    foreign = self.foreign / f'directory-{write}-{depth}'
                    foreign.mkdir()
                    if depth == 0:
                        root.symlink_to(foreign, target_is_directory=True)
                        destination = foreign / RELATIVE
                    else:
                        alias = root.joinpath(*RELATIVE.parts[:depth])
                        alias.parent.mkdir(parents=True)
                        alias.symlink_to(foreign, target_is_directory=True)
                        destination = foreign.joinpath(*RELATIVE.parts[depth:])
                    destination.mkdir(parents=True, exist_ok=True)
                    contents = PREVIOUS if write else EXPECTED
                    for name, raw in zip(NAMES, contents):
                        (destination / name).write_bytes(raw)
                    self.rejected(self.invoke(write=write, root=root))
                    self.assertEqual(list(contents), [(destination / name).read_bytes() for name in NAMES])

    def test_nonregular_final_paths_and_ancestors_fail_before_reference(self):
        for write in (False, True):
            for kind in ('fixture_directory', 'lock_directory', 'ancestor_file', 'root_file'):
                with self.subTest(write=write, kind=kind):
                    root = self.base / f'{kind}-{write}'
                    if kind == 'root_file':
                        retained = root
                        retained.write_bytes(PREVIOUS[0])
                    elif kind == 'ancestor_file':
                        root.mkdir()
                        retained = root / RELATIVE.parts[0]
                        retained.write_bytes(PREVIOUS[0])
                    else:
                        destination = root / RELATIVE
                        destination.mkdir(parents=True)
                        index = 0 if kind == 'fixture_directory' else 1
                        retained = destination / NAMES[index] / 'retained.txt'
                        retained.parent.mkdir()
                        retained.write_bytes(PREVIOUS[0])
                        (destination / NAMES[1 - index]).write_bytes(PREVIOUS[1 - index])
                    self.rejected(self.invoke(write=write, root=root))
                    self.assertEqual(PREVIOUS[0], retained.read_bytes())

    def test_regular_and_missing_pairs_publish_exact_binary_bytes(self):
        for kind in ('replace', 'missing_lock', 'missing_files', 'missing_parents'):
            with self.subTest(kind=kind):
                root = self.base / kind
                root.mkdir()
                destination = root / RELATIVE
                if kind != 'missing_parents':
                    destination.mkdir(parents=True)
                if kind in ('replace', 'missing_lock'):
                    (destination / NAMES[0]).write_bytes(PREVIOUS[0])
                    (destination / NAMES[0]).chmod(0o600)
                if kind == 'replace':
                    (destination / NAMES[1]).write_bytes(PREVIOUS[1])
                    (destination / NAMES[1]).chmod(0o400)
                code, stdout, stderr, calls = self.invoke(write=True, root=root)
                self.assertEqual((0, '', 1), (code, stderr, calls))
                self.assertIn('CD1_REFERENCE_PASS', stdout)
                for name, raw in zip(NAMES, EXPECTED):
                    target = destination / name
                    self.assertEqual(raw, target.read_bytes())
                    self.assertEqual(0o644, target.stat().st_mode & 0o777)
                self.assertEqual(sorted(NAMES), sorted(path.name for path in destination.iterdir()))

    def test_verification_checks_both_files_without_mutation(self):
        for wrong in (None, 0, 1):
            with self.subTest(wrong=wrong):
                contents = list(EXPECTED)
                if wrong is not None:
                    contents[wrong] = PREVIOUS[wrong]
                self.retain(contents)
                for path in self.targets:
                    path.chmod(0o400)
                before = [(path.read_bytes(), path.stat().st_ino, path.stat().st_mode,
                           path.stat().st_mtime_ns) for path in self.targets]
                entries = sorted(path.name for path in self.destination.iterdir())
                code, stdout, stderr, calls = self.invoke(write=False)
                self.assertEqual(1, calls)
                if wrong is None:
                    self.assertEqual((0, ''), (code, stderr))
                    self.assertIn('CD1_REFERENCE_PASS', stdout)
                else:
                    self.assertEqual('Fixture differs' if wrong == 0 else 'Fixture lock differs', code)
                    self.assertNotIn('CD1_REFERENCE_PASS', stdout)
                after = [(path.read_bytes(), path.stat().st_ino, path.stat().st_mode,
                          path.stat().st_mtime_ns) for path in self.targets]
                self.assertEqual(before, after)
                self.assertEqual(entries, sorted(path.name for path in self.destination.iterdir()))

    def test_failed_generation_retains_the_complete_previous_pair(self):
        for failure in (OSError('controlled generation failure'),
                        SystemExit('controlled pinned-source rejection')):
            with self.subTest(failure=type(failure).__name__):
                self.retain()
                result = self.invoke(write=True, producer=failure)
                if isinstance(failure, OSError):
                    self.rejected(result, calls=1)
                else:
                    self.assertEqual('controlled pinned-source rejection', result[0])
                    self.assertNotIn('CD1_REFERENCE_PASS', result[1])
                    self.assertEqual(1, result[3])
                self.assertEqual(list(PREVIOUS), [path.read_bytes() for path in self.targets])
                self.no_temporary_outputs()

    def test_post_generation_pair_admission_protects_the_first_peer(self):
        for write in (False, True):
            for kind in ('symlink', 'hardlink'):
                with self.subTest(write=write, kind=kind):
                    contents = PREVIOUS if write else EXPECTED
                    self.retain(contents)
                    outside = self.foreign / f'late-{write}-{kind}.json'
                    outside.write_bytes(contents[1])

                    def introduce_late_alias(_research_root):
                        self.targets[1].unlink()
                        if kind == 'symlink':
                            self.targets[1].symlink_to(outside)
                        else:
                            os.link(outside, self.targets[1])
                        return EXPECTED

                    try:
                        self.rejected(self.invoke(write=write, producer=introduce_late_alias), calls=1)
                        self.assertEqual(contents[0], self.targets[0].read_bytes())
                        self.assertEqual(contents[1], outside.read_bytes())
                        self.no_temporary_outputs()
                    finally:
                        self.targets[1].unlink()

    def test_each_replace_failure_preserves_selected_file_without_claiming_pair_atomicity(self):
        original_replace = Path.replace
        for index in range(2):
            with self.subTest(index=index):
                self.retain()

                def replace(path, destination):
                    if Path(destination) == self.targets[index]:
                        raise OSError('controlled replacement failure')
                    return original_replace(path, destination)

                with patch.object(Path, 'replace', new=replace):
                    self.rejected(self.invoke(write=True), calls=1)
                expected = list(PREVIOUS)
                if index == 1:
                    expected[0] = EXPECTED[0]
                self.assertEqual(expected, [path.read_bytes() for path in self.targets])
                self.no_temporary_outputs()

    def test_each_partial_private_write_failure_cleans_up_and_retains_selected_file(self):
        original_temporary = outputs.tempfile.NamedTemporaryFile
        for index in range(2):
            with self.subTest(index=index):
                self.retain()
                prefix = '.' + NAMES[index] + '.'

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

                def temporary(**kwargs):
                    if kwargs.get('prefix') == prefix:
                        return PartialFailure(**kwargs)
                    return original_temporary(**kwargs)

                with patch.object(outputs.tempfile, 'NamedTemporaryFile', side_effect=temporary):
                    self.rejected(self.invoke(write=True), calls=1)
                expected = list(PREVIOUS)
                if index == 1:
                    expected[0] = EXPECTED[0]
                self.assertEqual(expected, [path.read_bytes() for path in self.targets])
                self.no_temporary_outputs()


if __name__ == '__main__':
    unittest.main()
