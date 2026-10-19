"""Exercise the actual graph-fixture CLI against disposable output namespaces."""
from contextlib import contextmanager
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path('scripts/check_dependency_graph_fixtures.py')
OUTPUT = Path('core/src/test/resources/contracts/dependency-graph-cases.json')
INPUT_PATHS = (
    SCRIPT,
    Path('scripts/reference_fixture_output.py'),
    *(path.relative_to(ROOT) for path in sorted((ROOT / 'contracts/v1').rglob('*'))
      if path.is_file()),
)
EXECUTIONS = (
    ('normal', (), None),
    ('optimized-flag', ('-O',), None),
    ('optimized-environment', (), '1'),
)
SUCCESS = ("159 dependency graph cases agree with the frozen Python schema: "
           "{'decoded': 104, 'unsupported_version': 4, 'schema_mismatch': 50, 'invalid_input': 1}\n")
SENTINEL = b'controlled unrelated retained bytes\n'


class DependencyGraphFixtureGuardTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix='graph-fixture-guard-') as directory:
            root = Path(directory)
            for relative in (*INPUT_PATHS, OUTPUT):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            original = self.input_hashes(root)
            try:
                yield root
            finally:
                self.assertEqual(original, self.input_hashes(root))

    def input_hashes(self, root):
        return {str(path): hashlib.sha256((root / path).read_bytes()).hexdigest()
                for path in INPUT_PATHS}

    def run_checker(self, root, execution, *arguments, fault=None, timeout=10):
        _, options, optimization = execution
        environment = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
        environment.pop('PYTHONOPTIMIZE', None)
        if optimization is not None:
            environment['PYTHONOPTIMIZE'] = optimization
        command = [sys.executable, '-B', *options, str(root / SCRIPT), *arguments]
        if fault is not None:
            # Execute unchanged CLI source; inject only the named unavailable dependency,
            # reference rejection or final filesystem failure, never an expected fixture.
            patches = {
                'format': 'patch.dict(FormatChecker.checkers, {}, clear=True)',
                'reference': 'patch.object(Draft202012Validator, "is_valid", return_value=False)',
                'replace': ('patch.object(Path, "replace", '
                            'side_effect=OSError("controlled replacement failure"))'),
            }
            injection = (
                'from pathlib import Path\n'
                'from unittest.mock import patch\n'
                'from jsonschema import Draft202012Validator, FormatChecker\n'
                'import runpy, sys\n'
                'script = Path(sys.argv[1])\n'
                'sys.path.insert(0, str(script.parent))\n'
                'sys.argv = sys.argv[1:]\n'
                f'with {patches[fault]}:\n'
                '    runpy.run_path(str(script), run_name="__main__")\n'
            )
            command = [sys.executable, '-B', *options, '-c', injection,
                       str(root / SCRIPT), *arguments]
        return subprocess.run(command, cwd=root, env=environment, text=True,
                              capture_output=True, timeout=timeout)

    def require_success(self, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(SUCCESS, result.stdout)
        self.assertEqual('', result.stderr)

    def require_failure(self, result, message):
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn(message, result.stderr)
        self.assertEqual('', result.stdout)

    def test_regular_check_drift_and_explicit_recovery_preserve_exact_bytes(self):
        for execution in EXECUTIONS:
            with self.subTest(mode=execution[0]), self.copied_checkout() as root:
                target = root / OUTPUT
                original = target.read_bytes()
                self.require_success(self.run_checker(root, execution))
                self.assertEqual(original, target.read_bytes())
                changed = original + b'\n'
                target.write_bytes(changed)
                self.require_failure(self.run_checker(root, execution),
                                     'Graph fixtures changed; explicitly regenerate and review them')
                self.assertEqual(changed, target.read_bytes())
                self.require_success(self.run_checker(root, execution, '--write'))
                self.assertEqual(original, target.read_bytes())
                target.unlink()
                self.require_failure(self.run_checker(root, execution), 'FileNotFoundError')
                self.assertFalse(target.exists())
                self.require_success(self.run_checker(root, execution, '--write'))
                self.assertEqual(original, target.read_bytes())
                self.require_success(self.run_checker(root, execution))

    def test_output_aliases_are_rejected_without_changing_other_files(self):
        for execution in EXECUTIONS:
            for kind in ('symlink', 'hardlink', 'dangling-symlink'):
                with self.subTest(mode=execution[0], kind=kind), self.copied_checkout() as root:
                    target = root / OUTPUT
                    original = target.read_bytes()
                    self.require_success(self.run_checker(root, execution))
                    retained = root / 'retained-output'
                    retained.write_bytes(original)
                    missing = root / 'absent-output'
                    target.unlink()
                    if kind == 'hardlink':
                        os.link(retained, target)
                    else:
                        target.symlink_to(missing if kind == 'dangling-symlink' else retained)
                    self.require_failure(self.run_checker(root, execution),
                                         'Generated output is not a singly linked regular file')
                    retained.write_bytes(SENTINEL)
                    self.require_failure(self.run_checker(root, execution, '--write'),
                                         'Generated output is not a singly linked regular file')
                    self.assertEqual(SENTINEL, retained.read_bytes())
                    self.assertFalse(missing.exists())
                    self.assertEqual(kind != 'hardlink', target.is_symlink())
                    if kind == 'hardlink':
                        self.assertTrue(target.samefile(retained))
                        self.assertEqual(2, target.stat().st_nlink)
                    target.unlink()
                    self.require_success(self.run_checker(root, execution, '--write'))
                    self.assertEqual(original, target.read_bytes())
                    self.assertEqual(SENTINEL, retained.read_bytes())

    def test_aliased_output_ancestors_are_rejected(self):
        for execution in EXECUTIONS:
            for ancestor in (Path('core'), OUTPUT.parent):
                with self.subTest(mode=execution[0], ancestor=str(ancestor)), self.copied_checkout() as root:
                    target = root / OUTPUT
                    original = target.read_bytes()
                    self.require_success(self.run_checker(root, execution))
                    relocated = root / 'retained-directory'
                    (root / ancestor).rename(relocated)
                    (root / ancestor).symlink_to(relocated, target_is_directory=True)
                    self.require_failure(self.run_checker(root, execution),
                                         'Generated output ancestor is not a directory')
                    target.write_bytes(SENTINEL)
                    self.require_failure(self.run_checker(root, execution, '--write'),
                                         'Generated output ancestor is not a directory')
                    self.assertTrue((root / ancestor).is_symlink())
                    self.assertEqual(SENTINEL, target.read_bytes())
                    (root / ancestor).unlink()
                    relocated.rename(root / ancestor)
                    self.require_success(self.run_checker(root, execution, '--write'))
                    self.assertEqual(original, target.read_bytes())

    def test_nonregular_outputs_are_rejected_without_blocking(self):
        for execution in EXECUTIONS:
            for kind in ('directory', 'fifo'):
                with self.subTest(mode=execution[0], kind=kind), self.copied_checkout() as root:
                    target = root / OUTPUT
                    original = target.read_bytes()
                    self.require_success(self.run_checker(root, execution))
                    target.unlink()
                    if kind == 'directory':
                        target.mkdir()
                        (target / 'retained-child').write_bytes(SENTINEL)
                    else:
                        os.mkfifo(target)
                    original_mode = target.lstat().st_mode
                    for arguments in ((), ('--write',)):
                        self.require_failure(self.run_checker(root, execution, *arguments, timeout=2),
                                             'Generated output is not a singly linked regular file')
                        self.assertEqual(original_mode, target.lstat().st_mode)
                    if kind == 'directory':
                        self.assertEqual(SENTINEL, (target / 'retained-child').read_bytes())
                        (target / 'retained-child').unlink()
                        target.rmdir()
                    else:
                        self.assertTrue(stat.S_ISFIFO(target.lstat().st_mode))
                        target.unlink()
                    self.require_success(self.run_checker(root, execution, '--write'))
                    self.assertEqual(original, target.read_bytes())

    def validation_failure_preserves_output(self, fault, message):
        for execution in EXECUTIONS:
            for present in (True, False):
                with self.subTest(mode=execution[0], present=present), self.copied_checkout() as root:
                    target = root / OUTPUT
                    original = target.read_bytes()
                    self.require_success(self.run_checker(root, execution))
                    if present:
                        target.write_bytes(SENTINEL)
                    else:
                        target.unlink()
                    names = sorted(path.name for path in target.parent.iterdir())
                    self.require_failure(self.run_checker(root, execution, '--write', fault=fault), message)
                    self.assertEqual(present, target.exists())
                    if present:
                        self.assertEqual(SENTINEL, target.read_bytes())
                    self.assertEqual(names, sorted(path.name for path in target.parent.iterdir()))
                    self.require_success(self.run_checker(root, execution, '--write'))
                    self.assertEqual(original, target.read_bytes())
                    self.require_success(self.run_checker(root, execution))

    def test_missing_format_checker_cannot_rewrite_or_create_output(self):
        self.validation_failure_preserves_output('format', 'Frozen graph format checkers unavailable')

    def test_reference_rejection_cannot_rewrite_or_create_output(self):
        self.validation_failure_preserves_output('reference',
                                                'Frozen graph reference outcome differs: canonical-fixture')

    def test_failed_replacement_preserves_output_and_cleans_temporary_files(self):
        for execution in EXECUTIONS:
            with self.subTest(mode=execution[0]), self.copied_checkout() as root:
                target = root / OUTPUT
                original = target.read_bytes()
                self.require_success(self.run_checker(root, execution))
                target.write_bytes(SENTINEL)
                names = sorted(path.name for path in target.parent.iterdir())
                self.require_failure(self.run_checker(root, execution, '--write', fault='replace'),
                                     'controlled replacement failure')
                self.assertEqual(SENTINEL, target.read_bytes())
                self.assertEqual(names, sorted(path.name for path in target.parent.iterdir()))
                self.require_success(self.run_checker(root, execution, '--write'))
                self.assertEqual(original, target.read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
