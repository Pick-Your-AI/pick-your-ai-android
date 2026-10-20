"""Exercise graph number admission and the real CLI with private extra vectors."""
from contextlib import contextmanager
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path('scripts/check_dependency_graph_fixtures.py')
OUTPUT = Path('core/src/test/resources/contracts/dependency-graph-cases.json')
INPUT_PATHS = (
    SCRIPT,
    Path('scripts/frozen_fixture_numbers.py'),
    Path('scripts/reference_fixture_output.py'),
    *(path.relative_to(ROOT) for path in sorted((ROOT / 'contracts/v1').rglob('*'))
      if path.is_file()),
)
EXECUTIONS = (
    ('normal', (), None),
    ('optimized-flag', ('-O',), None),
    ('optimized-environment', (), '1'),
)
SENTINEL = b'controlled retained graph bytes\n'
FRACTIONS = (
    '1.0000000000000001', '9007199254740992.1',
    '0.99999999999999999999', '1e-9999', '-1e-9999',
    '123456789012345678901234567890123456789.000000001',
)
INTEGERS = (
    '1.0', '1000e-3', '9007199254740993', '9007199254740993.0',
    '90071992547409930e-1', '1e9999',
    '123456789012345678901234567890123456789.000',
)


class DependencyGraphFixturePrecisionTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix='graph-fixture-number-') as directory:
            root = Path(directory)
            for relative in (*INPUT_PATHS, OUTPUT):
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            before = self.input_hashes(root)
            try:
                yield root
            finally:
                self.assertEqual(before, self.input_hashes(root))

    def input_hashes(self, root):
        return {str(path): hashlib.sha256((root / path).read_bytes()).hexdigest()
                for path in INPUT_PATHS}

    def run_checker(self, root, execution, cases=(), *, write=False, disable_trap=False):
        _, options, optimization = execution
        environment = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
        environment.pop('PYTHONOPTIMIZE', None)
        if optimization is not None:
            environment['PYTHONOPTIMIZE'] = optimization
        # Source is copied unchanged. Inject only private generated input vectors
        # before calling its actual CLI main; parsing, pins, schema validation,
        # format checks, output admission and publication all remain real.
        driver = (
            'from decimal import InvalidOperation, getcontext\n'
            'import importlib.util, json, sys\n'
            'from pathlib import Path\n'
            'script = Path(sys.argv[1])\n'
            'cases = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))\n'
            'if sys.argv[3] == "disable": getcontext().traps[InvalidOperation] = False\n'
            'sys.path.insert(0, str(script.parent))\n'
            'spec = importlib.util.spec_from_file_location("graph_cli", script)\n'
            'module = importlib.util.module_from_spec(spec)\n'
            'spec.loader.exec_module(module)\n'
            'original_vectors = module.vectors\n'
            'module.vectors = lambda schema, original: original_vectors(schema, original) + cases\n'
            'sys.argv = [str(script), *sys.argv[4:]]\n'
            'module.main()\n'
        )
        private_vectors = root / 'private-number-vectors.json'
        private_vectors.write_text(json.dumps(list(cases)), encoding='utf-8')
        command = [sys.executable, '-B', *options, '-c', driver,
                   str(root / SCRIPT), str(private_vectors),
                   'disable' if disable_trap else 'default', *(['--write'] if write else [])]
        return subprocess.run(command, cwd=root, env=environment, text=True,
                              capture_output=True, timeout=15)

    @staticmethod
    def revision_case(token, expected, *, root=False, name='private-number-probe'):
        if root:
            text = (ROOT / 'contracts/v1/examples/dependency-graph.json').read_text()
            marker = '"revision": 1'
            if text.count(marker) != 1:
                raise ValueError('Unexpected frozen example revision marker')
            token = text.replace(marker, '"revision": ' + token)
        return {'name': name, 'path': [] if root else ['revision'],
                'operation': 'set', 'expected': expected, 'json': token}

    def test_exact_values_survive_both_mutation_paths(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            spec = importlib.util.spec_from_file_location('exact_graph', ROOT / SCRIPT)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            original = json.loads((ROOT / 'contracts/v1/examples/dependency-graph.json').read_text())
            untouched = json.dumps(original, sort_keys=True)
            for token in (*FRACTIONS, *INTEGERS, '-0.00'):
                for root in (False, True):
                    with self.subTest(token=token, root=root):
                        value = module.mutate(original, self.revision_case(token, 'decoded', root=root))
                        self.assertEqual(Decimal(token), value['revision'])
                        self.assertNotIsInstance(value['revision'], float)
                        self.assertEqual(untouched, json.dumps(original, sort_keys=True))
        finally:
            sys.path.pop(0)

    def test_correct_fraction_and_integer_outcomes_publish_original_raw_tokens(self):
        cases = [self.revision_case(token, expected, root=root, name=f'private-{i}-{root}')
                 for i, (token, expected) in enumerate(
                     [(token, 'schema_mismatch') for token in (*FRACTIONS, '-0.00', 'true', '0', '-1e9999')]
                     + [(token, 'decoded') for token in INTEGERS])
                 for root in (False, True)]
        for execution in EXECUTIONS:
            with self.subTest(mode=execution[0]), self.copied_checkout() as root:
                target = root / OUTPUT
                original = target.read_bytes()
                result = self.run_checker(root, execution, cases, write=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('', result.stderr)
                written = json.loads(target.read_text())
                self.assertEqual(cases, written['cases'][-len(cases):])
                self.assertEqual(json.loads(original)['cases'], written['cases'][:-len(cases)])
                self.assertEqual(0, self.run_checker(root, execution, cases).returncode)
                self.assertEqual(0, self.run_checker(root, execution, write=True).returncode)
                self.assertEqual(original, target.read_bytes())

    def test_false_fraction_acceptance_cannot_replace_retained_output(self):
        for execution in EXECUTIONS:
            for token in FRACTIONS[:3]:
                for root_value in (False, True):
                    with self.subTest(mode=execution[0], token=token, root=root_value), self.copied_checkout() as root:
                        target = root / OUTPUT
                        target.write_bytes(SENTINEL)
                        names = sorted(path.name for path in target.parent.iterdir())
                        wrong = self.revision_case(token, 'decoded', root=root_value)
                        result = self.run_checker(root, execution, [wrong], write=True)
                        self.assertEqual(1, result.returncode, result.stderr)
                        self.assertIn('Frozen graph reference outcome differs: private-number-probe', result.stderr)
                        self.assertEqual('', result.stdout)
                        self.assertEqual(SENTINEL, target.read_bytes())
                        self.assertEqual(names, sorted(path.name for path in target.parent.iterdir()))

    def test_non_json_constants_reject_before_version_or_publication(self):
        for execution in EXECUTIONS:
            for token in ('NaN', 'Infinity', '-Infinity'):
                for root_value in (False, True):
                    with self.subTest(mode=execution[0], token=token, root=root_value), self.copied_checkout() as root:
                        case = self.revision_case(token, 'schema_mismatch')
                        if root_value:
                            case = {'name': 'private-non-json-root', 'path': [], 'operation': 'set',
                                    'expected': 'unsupported_version',
                                    'json': '{"schema_version":"2.0.0","extra":' + token + '}'}
                        target = root / OUTPUT
                        target.write_bytes(SENTINEL)
                        result = self.run_checker(root, execution, [case], write=True)
                        self.assertEqual(1, result.returncode, result.stderr)
                        self.assertIn('Fixture inputs require finite JSON numbers', result.stderr)
                        self.assertEqual('', result.stdout)
                        self.assertEqual(SENTINEL, target.read_bytes())

    def test_unrepresentable_decimal_rejects_with_either_decimal_trap_setting(self):
        token = '1e9999999999999999999999999999999999999999'
        for execution in EXECUTIONS:
            for disable_trap in (False, True):
                with self.subTest(mode=execution[0], disabled=disable_trap), self.copied_checkout() as root:
                    target = root / OUTPUT
                    target.write_bytes(SENTINEL)
                    result = self.run_checker(root, execution,
                                              [self.revision_case(token, 'decoded')],
                                              write=True, disable_trap=disable_trap)
                    self.assertEqual(1, result.returncode, result.stderr)
                    self.assertIn('Fixture number exceeds decimal representation', result.stderr)
                    self.assertEqual('', result.stdout)
                    self.assertEqual(SENTINEL, target.read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
