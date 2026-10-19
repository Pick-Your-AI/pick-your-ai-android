"""Exercise the real reference-fixture CLIs with assertions enabled and disabled."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKERS = (
    ('check_report_fixtures.py', 'reference-reports.json',
     'Reference adapter fixtures differ; inspect upstream before regenerating',
     '4 reference report fixtures match frozen Python evaluator'),
    ('check_diagnostic_fixtures.py', 'diagnostic-cases.json',
     'Diagnostic goldens differ from the unchanged Python reference',
     '26 diagnostic cases match frozen Python reference'),
)
EXECUTIONS = (
    ('normal', (), None),
    ('optimized-flag', ('-O',), None),
    ('optimized-environment', (), '1'),
)
OUTPUT_DIRECTORY = Path('core/src/test/resources/evaluation')
INPUT_PATHS = (
    Path('contracts/v1/reference/evaluate.py'),
    *(Path(f'contracts/v1/examples/{name}.json') for name in ('build', 'catalog', 'ruleset')),
    *(Path(f'scripts/{name}') for name, _, _, _ in CHECKERS),
    Path('scripts/reference_fixture_output.py'),
    *(Path(f'core/src/main/kotlin/ai/pickyour/core/evaluation/{name}.kt') for name in (
        'RetainedSnapshotEvaluation', 'FrozenDiagnosticEvaluator', 'ReferenceReportAdapter')),
    *(path.relative_to(ROOT) for path in sorted((ROOT / 'contracts/v1/schemas').glob('*.schema.json'))),
)


class ReferenceFixtureGuardTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix='reference-fixture-guard-') as directory:
            root = Path(directory)
            for relative in (*INPUT_PATHS, *(OUTPUT_DIRECTORY / name for _, name, _, _ in CHECKERS)):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            original_inputs = self.input_hashes(root)
            try:
                yield root
            finally:
                # Both validation and explicit regeneration leave every source/reference input intact.
                self.assertEqual(original_inputs, self.input_hashes(root))

    def input_hashes(self, root):
        return {str(path): hashlib.sha256((root / path).read_bytes()).hexdigest() for path in INPUT_PATHS}

    def run_checker(self, root, script, execution, *arguments, timeout=10, replace_failure=False):
        _, options, optimization = execution
        environment = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
        environment.pop('PYTHONOPTIMIZE', None)
        if optimization is not None:
            environment['PYTHONOPTIMIZE'] = optimization
        command = [sys.executable, '-B', *options, str(root / 'scripts' / script), *arguments]
        if replace_failure:
            # Run the literal checker source with only the final filesystem operation failing.
            injection = (
                'from pathlib import Path\n'
                'from unittest.mock import patch\n'
                'import runpy, sys\n'
                'script = Path(sys.argv[1])\n'
                'sys.path.insert(0, str(script.parent))\n'
                'sys.argv = sys.argv[1:]\n'
                'with patch.object(Path, "replace", side_effect=OSError("controlled replacement failure")):\n'
                '    runpy.run_path(str(script), run_name="__main__")\n'
            )
            command = [sys.executable, '-B', *options, '-c', injection,
                       str(root / 'scripts' / script), *arguments]
        return subprocess.run(command, cwd=root, env=environment, text=True,
                              capture_output=True, timeout=timeout)

    def test_original_fixtures_match_in_every_execution_mode(self):
        for script, _, _, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    original = {name: (root / OUTPUT_DIRECTORY / name).read_bytes() for _, name, _, _ in CHECKERS}
                    result = self.run_checker(root, script, execution)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertIn(success, result.stdout)
                    self.assertEqual('', result.stderr)
                    self.assertEqual(original, {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                                for _, name, _, _ in CHECKERS})

    def test_changed_report_is_rejected_without_rewriting_it(self):
        for script, filename, failure, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    target = root / OUTPUT_DIRECTORY / filename
                    value = json.loads(target.read_text())
                    value[0]['report']['findings'][0]['outcome'] = 'controlled-drift'
                    changed = (json.dumps(value, indent=2) + '\n').encode()
                    target.write_bytes(changed)
                    result = self.run_checker(root, script, execution)
                    self.assertEqual(1, result.returncode)
                    self.assertIn(failure, result.stderr)
                    self.assertNotIn(success, result.stdout)
                    self.assertEqual(changed, target.read_bytes())

    def test_equivalent_json_with_different_bytes_is_rejected(self):
        for script, filename, failure, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    target = root / OUTPUT_DIRECTORY / filename
                    original = target.read_bytes()
                    changed = original + b'\n'
                    self.assertEqual(json.loads(original), json.loads(changed))
                    target.write_bytes(changed)
                    result = self.run_checker(root, script, execution)
                    self.assertEqual(1, result.returncode)
                    self.assertIn(failure, result.stderr)
                    self.assertNotIn(success, result.stdout)
                    self.assertEqual(changed, target.read_bytes())

    def test_missing_output_is_not_reported_as_a_match(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    target = root / OUTPUT_DIRECTORY / filename
                    target.unlink()
                    result = self.run_checker(root, script, execution)
                    self.assertEqual(1, result.returncode)
                    self.assertIn('FileNotFoundError', result.stderr)
                    self.assertNotIn(success, result.stdout)
                    self.assertFalse(target.exists())

    def test_explicit_write_restores_exact_original_and_rechecks_it(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    originals = {name: (root / OUTPUT_DIRECTORY / name).read_bytes() for _, name, _, _ in CHECKERS}
                    (root / OUTPUT_DIRECTORY / filename).write_bytes(b'controlled generated-output drift')
                    written = self.run_checker(root, script, execution, '--write')
                    self.assertEqual(0, written.returncode, written.stderr)
                    self.assertEqual(originals, {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                                for _, name, _, _ in CHECKERS})
                    verified = self.run_checker(root, script, execution)
                    self.assertEqual(0, verified.returncode, verified.stderr)
                    self.assertIn(success, verified.stdout)

    def require_valid_control(self, root, script, execution, success):
        result = self.run_checker(root, script, execution)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(success, result.stdout)
        self.assertEqual('', result.stderr)

    def test_write_rejects_symbolic_hard_and_dangling_output_links(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                for kind in ('symlink', 'hardlink', 'dangling-symlink'):
                    with self.subTest(script=script, mode=execution[0], kind=kind), self.copied_checkout() as root:
                        self.require_valid_control(root, script, execution, success)
                        originals = {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                     for _, name, _, _ in CHECKERS}
                        sentinel = root / 'controlled-unrelated-output'
                        sentinel.write_bytes(b'controlled unrelated sentinel')
                        missing = root / 'controlled-absent-output'
                        target = root / OUTPUT_DIRECTORY / filename
                        target.unlink()
                        if kind == 'hardlink':
                            os.link(sentinel, target)
                        else:
                            target.symlink_to(missing if kind == 'dangling-symlink' else sentinel)
                        result = self.run_checker(root, script, execution, '--write')
                        self.assertEqual(1, result.returncode, result.stderr)
                        self.assertIn('Generated output is not a singly linked regular file', result.stderr)
                        self.assertNotIn(success, result.stdout)
                        self.assertEqual(b'controlled unrelated sentinel', sentinel.read_bytes())
                        self.assertFalse(missing.exists())
                        self.assertEqual(kind != 'hardlink', target.is_symlink())
                        if kind == 'hardlink':
                            self.assertTrue(target.samefile(sentinel))
                            self.assertEqual(2, target.stat().st_nlink)
                        for name, original in originals.items():
                            if name != filename:
                                self.assertEqual(original, (root / OUTPUT_DIRECTORY / name).read_bytes())

    def test_write_rejects_aliased_output_ancestors(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                for ancestor in (Path('core'), OUTPUT_DIRECTORY):
                    with self.subTest(script=script, mode=execution[0], ancestor=str(ancestor)), self.copied_checkout() as root:
                        self.require_valid_control(root, script, execution, success)
                        original_outputs = {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                            for _, name, _, _ in CHECKERS}
                        relocated = root / 'controlled-aliased-parent'
                        (root / ancestor).rename(relocated)
                        (root / ancestor).symlink_to(relocated, target_is_directory=True)
                        target = root / OUTPUT_DIRECTORY / filename
                        target.write_bytes(b'controlled parent-alias sentinel')
                        result = self.run_checker(root, script, execution, '--write')
                        self.assertEqual(1, result.returncode, result.stderr)
                        self.assertIn('Generated output ancestor is not a directory', result.stderr)
                        self.assertNotIn(success, result.stdout)
                        self.assertTrue((root / ancestor).is_symlink())
                        self.assertEqual(b'controlled parent-alias sentinel', target.read_bytes())
                        for name, original in original_outputs.items():
                            if name != filename:
                                self.assertEqual(original, (root / OUTPUT_DIRECTORY / name).read_bytes())

    def test_read_checks_reject_symbolic_and_hard_linked_outputs(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                for kind in ('symlink', 'hardlink'):
                    with self.subTest(script=script, mode=execution[0], kind=kind), self.copied_checkout() as root:
                        self.require_valid_control(root, script, execution, success)
                        target = root / OUTPUT_DIRECTORY / filename
                        original = target.read_bytes()
                        sentinel = root / 'controlled-exact-output'
                        sentinel.write_bytes(original)
                        target.unlink()
                        if kind == 'hardlink':
                            os.link(sentinel, target)
                        else:
                            target.symlink_to(sentinel)
                        result = self.run_checker(root, script, execution)
                        self.assertEqual(1, result.returncode, result.stderr)
                        self.assertIn('Generated output is not a singly linked regular file', result.stderr)
                        self.assertNotIn(success, result.stdout)
                        self.assertEqual(original, sentinel.read_bytes())

    def test_nonregular_outputs_are_rejected_without_blocking(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                for kind in ('directory', 'fifo'):
                    for arguments in ((), ('--write',)):
                        with self.subTest(script=script, mode=execution[0], kind=kind, arguments=arguments), self.copied_checkout() as root:
                            self.require_valid_control(root, script, execution, success)
                            target = root / OUTPUT_DIRECTORY / filename
                            target.unlink()
                            if kind == 'directory':
                                target.mkdir()
                                (target / 'controlled-sentinel').write_bytes(b'controlled directory sentinel')
                            else:
                                os.mkfifo(target)
                            original_kind = target.lstat().st_mode
                            try:
                                result = self.run_checker(root, script, execution, *arguments, timeout=2)
                            except subprocess.TimeoutExpired:
                                self.fail('Actual checker timed out on the controlled nonregular output')
                            self.assertEqual(1, result.returncode, result.stderr)
                            self.assertIn('Generated output is not a singly linked regular file', result.stderr)
                            self.assertNotIn(success, result.stdout)
                            self.assertEqual(original_kind, target.lstat().st_mode)
                            if kind == 'directory':
                                self.assertEqual(b'controlled directory sentinel',
                                                 (target / 'controlled-sentinel').read_bytes())

    def test_explicit_write_recreates_a_missing_regular_output(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    self.require_valid_control(root, script, execution, success)
                    originals = {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                 for _, name, _, _ in CHECKERS}
                    (root / OUTPUT_DIRECTORY / filename).unlink()
                    result = self.run_checker(root, script, execution, '--write')
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertEqual(originals, {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                                for _, name, _, _ in CHECKERS})
                    self.require_valid_control(root, script, execution, success)

    def test_failed_replacement_preserves_output_and_removes_temporary_file(self):
        for script, filename, _, success in CHECKERS:
            for execution in EXECUTIONS:
                with self.subTest(script=script, mode=execution[0]), self.copied_checkout() as root:
                    self.require_valid_control(root, script, execution, success)
                    target = root / OUTPUT_DIRECTORY / filename
                    target.write_bytes(b'controlled old output')
                    originals = {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                 for _, name, _, _ in CHECKERS}
                    original_names = sorted(p.name for p in target.parent.iterdir())
                    result = self.run_checker(root, script, execution, '--write', replace_failure=True)
                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertIn('controlled replacement failure', result.stderr)
                    self.assertNotIn(success, result.stdout)
                    self.assertEqual(originals, {name: (root / OUTPUT_DIRECTORY / name).read_bytes()
                                                for _, name, _, _ in CHECKERS})
                    self.assertEqual(original_names, sorted(p.name for p in target.parent.iterdir()))


if __name__ == '__main__':
    unittest.main(verbosity=2)
