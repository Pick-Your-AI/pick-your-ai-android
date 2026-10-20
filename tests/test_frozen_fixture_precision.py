"""Real CLI regressions for four frozen-schema fixture readers; no corpus edits.

Private cases preserve literal number tokens and every existing case's bytes.
Extreme-exponent controls exercise the Python schema boundary, not Android's
separate local parser budgets or cross-client runtime conformance.
"""
import ast
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
FIXTURES = Path('core/src/test/resources/contracts')
HELPER = Path('scripts/frozen_fixture_numbers.py')
PROFILES = (
    ('evaluation-report', 'check_frozen_evaluation_report_fixtures.py',
     'frozen-evaluation-report-cases.json',
     (('set', 'build_revision', 1), ('set', 'dependency_graph_revision', 1),
      ('summary_set', 'passed', 0), ('summary_set', 'failed', 0),
      ('summary_set', 'unknown', 0), ('summary_set', 'skipped', 0))),
    ('event', 'check_event_fixtures.py', 'event-cases.json',
     (('set', 'subject_revision', 1),)),
    ('quality-evaluation-run', 'check_quality_run_fixtures.py', 'quality-run-cases.json',
     (('set', 'build_revision', 1), ('set', 'sample_size', 1))),
    ('deployment-manifest', 'check_deployment_manifest_fixtures.py',
     'deployment-manifest-cases.json', (('set', 'build_revision', 1),)),
)


def number_case(name, operation, field, token, expected):
    marker = '__EXACT_LITERAL_NUMBER__'
    value = {'name': name, 'expected': expected, operation: {field: marker}}
    raw = json.dumps(value, separators=(',', ':'))
    if raw.count('"' + marker + '"') != 1:
        raise ValueError('Ambiguous private numeric fixture')
    return raw.replace('"' + marker + '"', token).encode('utf-8')


class FrozenFixturePrecisionTests(unittest.TestCase):
    @contextmanager
    def copied_checkout(self):
        with tempfile.TemporaryDirectory(prefix='frozen-fixture-precision-') as directory:
            root = Path(directory)
            shutil.copytree(ROOT / 'contracts/v1', root / 'contracts/v1',
                            ignore=shutil.ignore_patterns('__pycache__'))
            paths = [Path('scripts') / profile[1] for profile in PROFILES]
            paths += [FIXTURES / profile[2] for profile in PROFILES]
            # The baseline checkers have no helper; corrected checkers use this
            # real sibling dependency. Its presence never substitutes a checker.
            if (ROOT / HELPER).is_file():
                paths.append(HELPER)
            for relative in paths:
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(ROOT / relative, target)
            yield root

    def file_hashes(self, root):
        return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sorted(root.rglob('*')) if path.is_file()}

    def append_cases(self, root, profile, cases):
        source = (ROOT / FIXTURES / profile[2]).read_bytes()
        fixture = json.loads(source)
        self.assertEqual('cases', list(fixture)[-1])
        position = source.rfind(b']')
        self.assertGreater(position, 0)
        changed = source[:position] + b',\n    ' + b',\n    '.join(cases) + b'\n  ' + source[position:]
        # Only the private suffix changes. Do not round-trip any original case.
        self.assertEqual(fixture['cases'], json.loads(changed)['cases'][:-len(cases)])
        (root / FIXTURES / profile[2]).write_bytes(changed)

    def run_checker(self, root, profile, *, options=(), optimization=None, program=None):
        environment = {**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'}
        environment.pop('PYTHONOPTIMIZE', None)
        if optimization is not None:
            environment['PYTHONOPTIMIZE'] = optimization
        command = [sys.executable, '-B', *options]
        script = str(root / 'scripts' / profile[1])
        command += [script] if program is None else ['-c', program, script]
        before = self.file_hashes(root)
        result = subprocess.run(command, cwd=root, env=environment, text=True,
                                capture_output=True, timeout=20)
        self.assertEqual(before, self.file_hashes(root), 'The CLI changed a source or fixture input')
        return result

    def assert_agrees(self, root, profile, result):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('', result.stderr)
        cases = json.loads((root / FIXTURES / profile[2]).read_bytes())['cases']
        expected = {key: sum(case['expected'] == key for case in cases)
                    for key in ('decoded', 'unsupported_version', 'schema_mismatch')}
        line, = result.stdout.splitlines()
        self.assertEqual(str(len(cases)), line.split()[0])
        self.assertEqual(expected, ast.literal_eval(line[line.index('{'):]))

    def assert_denied(self, result, message=None):
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertNotIn('cases agree with', result.stdout)
        if message is not None:
            self.assertIn(message, result.stderr)

    def test_canonical_corpora_keep_their_complete_outcome_counts(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_fractional_tokens_cannot_be_claimed_decoded_at_any_integer_field(self):
        for profile in PROFILES:
            for operation, field, _ in profile[3]:
                for token in ('9007199254740992.1', '1.0000000000000001'):
                    with self.subTest(family=profile[0], field=field, token=token), self.copied_checkout() as root:
                        self.append_cases(root, profile, [number_case(
                            'fractional claim', operation, field, token, 'decoded')])
                        self.assert_denied(self.run_checker(root, profile), 'reference')

    def test_exact_fractional_rejections_agree_at_all_ten_integer_fields(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [number_case(f'fractional {field} {index}', operation, field,
                                     token, 'schema_mismatch')
                         for operation, field, _ in profile[3]
                         for index, token in enumerate(('9007199254740992.1', '1.0000000000000001'))]
                self.append_cases(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_mathematically_integral_decimal_exponent_and_wide_tokens_agree(self):
        tokens = ('1.0', '1000e-3', '9007199254740993', '9007199254740993.0',
                  '123456789012345678901234567890', '1e1000', '1e1000000000')
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [number_case(f'integral {field} {index}', operation, field, token, 'decoded')
                         for operation, field, _ in profile[3]
                         for index, token in enumerate(tokens)]
                self.append_cases(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_tiny_nonzero_numbers_and_values_below_minima_are_not_rounded(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [number_case(f'minimum {field} {index}', operation, field,
                                     token, 'schema_mismatch')
                         for operation, field, _ in profile[3]
                         for index, token in enumerate(('-1e-9999', '1e-9999',
                                                        '-1e-1000000000', '0.99999999999999999999'))]
                self.append_cases(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_zero_forms_and_booleans_keep_the_existing_minimum_and_type_rules(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = []
                for operation, field, minimum in profile[3]:
                    for index, token in enumerate(('0', '-0.0', '0e1000000000')):
                        cases.append(number_case(f'zero {field} {index}', operation, field, token,
                                                 'decoded' if minimum == 0 else 'schema_mismatch'))
                    for token in ('true', 'false'):
                        cases.append(number_case(f'boolean {field} {token}', operation, field,
                                                 token, 'schema_mismatch'))
                self.append_cases(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_quality_numbers_remain_numbers_without_a_new_range_or_rounding_policy(self):
        profile = PROFILES[2]
        with self.copied_checkout() as root:
            cases = []
            for field in ('observed', 'threshold'):
                for index, token in enumerate(('1.0000000000000001', '-1e-1000000000',
                                               '1e1000000000', '1000e-3', '-0.0')):
                    cases.append(number_case(f'measurement {field} {index}', 'measurement_set',
                                             field, token, 'decoded'))
                for index, token in enumerate(('true', 'false', '"1.0"', 'null')):
                    cases.append(number_case(f'nonnumber {field} {index}', 'measurement_set',
                                             field, token, 'schema_mismatch'))
            self.append_cases(root, profile, cases)
            self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_non_json_constants_fail_even_when_schema_version_is_unsupported(self):
        for profile in PROFILES:
            for token in ('NaN', 'Infinity', '-Infinity'):
                with self.subTest(family=profile[0], token=token), self.copied_checkout() as root:
                    raw = number_case('constant', 'set', profile[3][0][1], token, 'unsupported_version')
                    # The malformed token must be rejected by the JSON reader,
                    # even on the checker's otherwise earlier version path.
                    raw = raw.replace(b'"set":{', b'"set":{"schema_version":"2.0.0",', 1)
                    self.append_cases(root, profile, [raw])
                    self.assert_denied(self.run_checker(root, profile), 'finite JSON numbers')

    def test_unrepresentable_exponents_fail_with_either_decimal_trap_setting(self):
        program = (
            'from decimal import getcontext, InvalidOperation\n'
            'import runpy, sys\n'
            'from pathlib import Path\n'
            'getcontext().traps[InvalidOperation] = False\n'
            'sys.path.insert(0, str(Path(sys.argv[1]).parent))\n'
            'runpy.run_path(sys.argv[1], run_name="__main__")\n'
        )
        for profile in PROFILES:
            for trap_disabled in (False, True):
                with self.subTest(family=profile[0], trap_disabled=trap_disabled), self.copied_checkout() as root:
                    raw = number_case('unrepresentable exponent', 'set', profile[3][0][1],
                                      '1e9223372036854775808', 'unsupported_version')
                    raw = raw.replace(b'"set":{', b'"set":{"schema_version":"2.0.0",', 1)
                    self.append_cases(root, profile, [raw])
                    self.assert_denied(self.run_checker(root, profile,
                                                       program=program if trap_disabled else None),
                                       'decimal representation')

    def test_reference_strings_formats_and_finite_version_precedence_remain(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                cases = [number_case('numeric identifier', 'set', 'id', '1.0', 'schema_mismatch')]
                value = {'name': 'bad referenced timestamp', 'expected': 'schema_mismatch',
                         'set': {('evaluated_at' if profile[0] == 'evaluation-report' else
                                  'occurred_at' if profile[0] == 'event' else
                                  'started_at' if profile[0] == 'quality-evaluation-run' else
                                  'requested_at'): 'not-a-timestamp'}}
                cases.append(json.dumps(value).encode())
                raw = number_case('finite version precedence', 'set', profile[3][0][1],
                                  '9007199254740992.1', 'unsupported_version')
                cases.append(raw.replace(b'"set":{', b'"set":{"schema_version":"2.0.0",', 1))
                self.append_cases(root, profile, cases)
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_fixture_duplicate_member_behavior_is_unchanged(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                field = profile[3][0][1]
                good = ('{"name":"last numeric value","expected":"decoded","set":{'
                        + json.dumps(field) + ':true,' + json.dumps(field) + ':1}}').encode()
                bad = ('{"name":"last boolean value","expected":"schema_mismatch","set":{'
                       + json.dumps(field) + ':1,' + json.dumps(field) + ':true}}').encode()
                self.append_cases(root, profile, [good, bad])
                self.assert_agrees(root, profile, self.run_checker(root, profile))

    def test_schema_hash_guard_still_rejects_equivalent_changed_bytes(self):
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                schema = root / 'contracts/v1/schemas' / (profile[0] + '.schema.json')
                raw = schema.read_bytes()
                schema.write_bytes(raw + b'\n')
                self.assertEqual(json.loads(raw), json.loads(schema.read_bytes()))
                self.assert_denied(self.run_checker(root, profile), 'input identity changed')

    def test_profile_and_unique_case_guards_remain_errors(self):
        for profile in PROFILES:
            for mutation in ('profile', 'duplicate-case'):
                with self.subTest(family=profile[0], mutation=mutation), self.copied_checkout() as root:
                    target = root / FIXTURES / profile[2]
                    if mutation == 'profile':
                        value = json.loads(target.read_bytes())
                        value['profile'] = 'unknown-profile'
                        target.write_text(json.dumps(value), encoding='utf-8')
                    else:
                        case = json.loads(target.read_bytes())['cases'][0]
                        self.append_cases(root, profile, [json.dumps(case).encode()])
                    self.assert_denied(self.run_checker(root, profile))

    def test_precision_rejection_survives_both_python_optimization_modes(self):
        for profile in PROFILES:
            for options, optimization in ((('-O',), None), ((), '2')):
                with self.subTest(family=profile[0], options=options, optimization=optimization), self.copied_checkout() as root:
                    self.append_cases(root, profile, [number_case(
                        'optimized fractional claim', 'set', profile[3][0][1],
                        '9007199254740992.1', 'decoded')])
                    self.assert_denied(self.run_checker(root, profile, options=options,
                                                       optimization=optimization), 'reference')

    def test_checker_imports_do_not_replace_the_global_validator(self):
        program = (
            'from decimal import Decimal\n'
            'from jsonschema import Draft202012Validator, validators\n'
            'import runpy, sys\n'
            'from pathlib import Path\n'
            'schema = {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "integer"}\n'
            'before = validators.validator_for(schema)\n'
            'if before is not Draft202012Validator or before(schema).is_valid(Decimal("1.0")):\n'
            '    raise RuntimeError("Unexpected stock validator control")\n'
            'sys.path.insert(0, str(Path(sys.argv[1]).parent))\n'
            'runpy.run_path(sys.argv[1], run_name="__main__")\n'
            'after = validators.validator_for(schema)\n'
            'if after is not before or after(schema).is_valid(Decimal("1.0")):\n'
            '    raise RuntimeError("Checker changed the global validator registry")\n'
        )
        for profile in PROFILES:
            with self.subTest(family=profile[0]), self.copied_checkout() as root:
                self.assert_agrees(root, profile, self.run_checker(root, profile, program=program))


if __name__ == '__main__':
    unittest.main(verbosity=2)
