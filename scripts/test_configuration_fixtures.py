#!/usr/bin/env python3
"""Fail-closed controls for the additive configuration-golden verifier."""
import copy
import gzip
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import check_configuration_fixtures as checker


class ConfigurationFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        files = list(checker.PINNED) + [str(path.relative_to(checker.ROOT))
                for path in (checker.ROOT / 'contracts/v1/schemas').glob('*.schema.json')]
        files += [str(checker.FIXTURE), 'scripts/check_configuration_fixtures.py',
                  'scripts/reference_fixture_output.py']
        for name in files:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(checker.ROOT / name, target)
        self.original = gzip.decompress((self.root / checker.FIXTURE).read_bytes())

    def write(self, value):
        (self.root / checker.FIXTURE).write_bytes(gzip.compress(checker.encoded(value), mtime=0))

    def test_complete_corpus_reproduces_all_seventeen_full_reports(self):
        self.assertEqual(17, checker.verify(self.root))
        corpus = json.loads(self.original)
        self.assertEqual([name for name, _, _ in checker.CONFIGURATIONS],
                         [case['name'] for case in corpus['cases']])
        self.assertTrue(all(len(case['report']['findings']) == 45 for case in corpus['cases']))
        self.assertEqual('not_supplied', corpus['external_evidence']['cost_estimate'])
        self.assertEqual('not_supplied', corpus['external_evidence']['resource_estimate'])

    def test_missing_duplicate_reordered_and_renamed_cases_fail(self):
        original = json.loads(self.original)
        mutations = []
        for change in ('missing', 'duplicate', 'reordered', 'renamed'):
            value = copy.deepcopy(original)
            if change == 'missing':
                value['cases'].pop()
            elif change == 'duplicate':
                value['cases'].append(copy.deepcopy(value['cases'][0]))
            elif change == 'reordered':
                value['cases'][0], value['cases'][1] = value['cases'][1], value['cases'][0]
            else:
                value['cases'][0]['name'] = 'unreviewed-alias'
            mutations.append((change, value))
        for change, value in mutations:
            with self.subTest(change=change):
                self.write(value)
                with self.assertRaisesRegex(ValueError, 'Configuration goldens differ'):
                    checker.verify(self.root)

    def test_wrong_build_report_rule_detail_count_and_optional_intent_fail(self):
        original = json.loads(self.original)
        paths = [
            ('build', 'revision'), ('report', 'build_id'), ('report', 'evaluated_at'),
            ('report', 'findings', 0, 'outcome'), ('report', 'findings', 0, 'message'),
            ('report', 'summary', 'passed'), ('optional_tiers', 'T3'),
        ]
        for path in paths:
            value = copy.deepcopy(original)
            target = value['cases'][0]
            for key in path[:-1]:
                target = target[key]
            prior = target[path[-1]]
            target[path[-1]] = prior + 1 if isinstance(prior, int) else 'changed'
            with self.subTest(path=path):
                self.write(value)
                with self.assertRaisesRegex(ValueError, 'Configuration goldens differ'):
                    checker.verify(self.root)

    def test_profile_source_pin_and_evidence_declarations_cannot_be_reclassified(self):
        original = json.loads(self.original)
        for field in ('profile', 'inputs', 'schema_descriptor_sha256', 'external_evidence', 'rule_count', 'new_field'):
            value = copy.deepcopy(original)
            value[field] = 'unreviewed'
            with self.subTest(field=field):
                self.write(value)
                with self.assertRaisesRegex(ValueError, 'Configuration goldens differ'):
                    checker.verify(self.root)

    def test_changed_frozen_input_or_original_operator_corpus_is_refused_before_replay(self):
        for name in checker.PINNED:
            path = self.root / name
            original = path.read_bytes()
            path.write_bytes(original + b'\n')
            with self.subTest(path=name):
                with self.assertRaisesRegex(ValueError, 'Configuration input identity differs'):
                    checker.generate(self.root)
            path.write_bytes(original)
        schema = next((self.root / 'contracts/v1/schemas').glob('*.schema.json'))
        schema.write_bytes(schema.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'Configuration schema descriptor differs'):
            checker.generate(self.root)

    def test_truncated_corrupt_and_extra_gzip_members_are_refused(self):
        path = self.root / checker.FIXTURE
        raw = path.read_bytes()
        for bad in (raw[:-1], b'not-gzip', raw + gzip.compress(b'', mtime=0)):
            with self.subTest(length=len(bad)):
                path.write_bytes(bad)
                with self.assertRaisesRegex(ValueError, 'Configuration fixture gzip'):
                    checker.verify(self.root)

    def test_cli_rejects_a_changed_report_with_and_without_assertions(self):
        value = json.loads(self.original)
        value['cases'][1]['report']['findings'][0]['outcome'] = 'pass-from-caller'
        self.write(value)
        for options in ([], ['-O']):
            result = subprocess.run([sys.executable, *options,
                str(self.root / 'scripts/check_configuration_fixtures.py')],
                text=True, capture_output=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
            self.assertEqual(1, result.returncode)
            self.assertIn('Configuration goldens differ', result.stderr)
            self.assertNotIn('Traceback', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
