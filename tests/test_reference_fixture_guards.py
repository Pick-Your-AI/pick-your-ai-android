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

    def run_checker(self, root, script, execution, *arguments):
        _, options, optimization = execution
        environment = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
        environment.pop('PYTHONOPTIMIZE', None)
        if optimization is not None:
            environment['PYTHONOPTIMIZE'] = optimization
        return subprocess.run(
            [sys.executable, '-B', *options, str(root / 'scripts' / script), *arguments],
            cwd=root, env=environment, text=True, capture_output=True, timeout=10,
        )

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
