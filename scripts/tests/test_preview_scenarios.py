"""Source-bundle negatives and actual frozen-reference observations; not Android execution."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('preview_scenarios', Path(__file__).resolve().parents[1] / 'check_preview_scenarios.py')
scenarios = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scenarios)


class PreviewScenarioFixtureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.outputs, cls.assets, cls.manifest, cls.reports = scenarios.generated()
        cls.manifest_bytes = cls.outputs[scenarios.ASSET_DIRECTORY / 'manifest.json']

    def admit(self, manifest=None, assets=None):
        scenarios.check_supplied(self.manifest_bytes if manifest is None else manifest,
                                 self.assets if assets is None else assets, self.assets, self.manifest_bytes)

    def test_actual_frozen_reference_retains_unknowns_and_real_context_failures(self):
        expected = {
            'core_build': {'passed': 21, 'failed': 0, 'unknown': 3, 'skipped': 21},
            'unknown_evidence': {'passed': 38, 'failed': 0, 'unknown': 3, 'skipped': 4},
            'failed_rule': {'passed': 19, 'failed': 2, 'unknown': 3, 'skipped': 21},
        }
        for name, report in self.reports.items():
            self.assertEqual(expected[name], report['summary'])
            self.assertEqual('blocked', report['deployment_readiness'])
            self.assertEqual({'rule.vram.estimate.fit', 'rule.vram.per.device.fit', 'rule.budget.monthly.cap'},
                             {f['rule_id'] for f in report['findings'] if f['outcome'] == 'unknown'})
        self.assertEqual({'rule.model.context.fit', 'rule.context.policy.limit'},
                         {f['rule_id'] for f in self.reports['failed_rule']['findings'] if f['outcome'] == 'fail'})

    def test_empty_is_negative_and_partial_retains_original_storage_evidence(self):
        empty = json.loads(self.assets['EMPTY_BUILD'])
        self.assertEqual({}, empty['objectives'])
        self.assertEqual([], empty['selections'])
        self.assertEqual([], empty['links'])
        original = json.loads((scenarios.ROOT / 'contracts/v1/examples/cost-estimate.json').read_bytes())
        partial = json.loads(self.assets['PARTIAL_ESTIMATE'])
        self.assertEqual([line for line in original['line_items'] if line['selection_id'] == 'sel_storage'], partial['line_items'])
        self.assertEqual(['sel_compute_profile'], partial['unpriced_selection_ids'])
        self.assertEqual('partial', partial['coverage'])
        self.assertEqual(13, len(self.manifest['scenarios'][-1]['expected']['unclassified_active_selection_ids']))
        self.assertEqual('2.50', partial['total']['amount'])
        self.assertNotEqual(original['build_id'], partial['build_id'])

    def test_manifest_rejects_changed_versions_provenance_paths_and_dispositions(self):
        mutations = []
        for key, value in (('fixture_set', 'android-preview-scenarios-2'), ('schema_version', '2.0.0'),
                           ('classification', 'production')):
            candidate = copy.deepcopy(self.manifest)
            candidate[key] = value
            mutations.append(candidate)
        for parent, key, value in (
            ('source', 'android_adoption_commit', '0' * 40),
            ('source', 'registry_sha256', '0' * 64),
        ):
            candidate = copy.deepcopy(self.manifest)
            candidate[parent][key] = value
            mutations.append(candidate)
        for path in ('../build.json', 'https://example.invalid/build.json', '/tmp/build.json'):
            candidate = copy.deepcopy(self.manifest)
            candidate['assets'][0]['asset_path'] = path
            mutations.append(candidate)
        candidate = copy.deepcopy(self.manifest)
        candidate['scenarios'][0]['disposition'] = 'frozen_diagnostic'
        mutations.append(candidate)
        for candidate in mutations:
            with self.subTest(candidate=candidate.get('fixture_set')):
                with self.assertRaisesRegex(ValueError, 'Manifest identity rejected'):
                    self.admit(manifest=scenarios.encoded(candidate))

    def test_manifest_rejects_duplicate_keys_malformed_utf8_trailing_data_and_unknown_inventory(self):
        for candidate in (b'', b'{', self.manifest_bytes + b'{}', b'\xff' + self.manifest_bytes,
                          self.manifest_bytes.replace(b'"schema_version":', b'"schema_version":"2.0.0","schema_version":', 1)):
            with self.subTest(size=len(candidate)):
                with self.assertRaises(ValueError):
                    self.admit(manifest=candidate)
        for assets in ({}, self.assets | {'EXTERNAL_BUILD': self.assets['ORIGINAL_BUILD']}):
            with self.assertRaisesRegex(ValueError, 'Asset inventory rejected'):
                self.admit(assets=assets)

    def test_each_missing_swapped_or_changed_asset_rejects_the_entire_set(self):
        for key in self.assets:
            with self.subTest(key=key):
                missing = dict(self.assets)
                del missing[key]
                with self.assertRaisesRegex(ValueError, 'Asset inventory rejected'):
                    self.admit(assets=missing)
                changed = self.assets | {key: self.assets[key] + b' '}
                with self.assertRaisesRegex(ValueError, 'Asset identity rejected'):
                    self.admit(assets=changed)
        swapped = self.assets | {'CORE_BUILD': self.assets['ORIGINAL_BUILD']}
        with self.assertRaisesRegex(ValueError, 'Asset identity rejected'):
            self.admit(assets=swapped)

    def test_updating_untrusted_manifest_hash_cannot_rebind_a_payload(self):
        changed = self.assets | {'EMPTY_BUILD': self.assets['CORE_BUILD']}
        manifest = copy.deepcopy(self.manifest)
        row = next(a for a in manifest['assets'] if a['key'] == 'EMPTY_BUILD')
        row['bytes'], row['sha256'] = len(changed['EMPTY_BUILD']), scenarios.digest(changed['EMPTY_BUILD'])
        with self.assertRaisesRegex(ValueError, 'Manifest identity rejected'):
            self.admit(manifest=scenarios.encoded(manifest), assets=changed)

    def test_manifest_document_and_total_bounds_reject_before_any_reference_result(self):
        with self.assertRaisesRegex(ValueError, 'Manifest identity rejected'):
            self.admit(manifest=b' ' * (scenarios.MANIFEST_LIMIT + 1))
        with self.assertRaisesRegex(ValueError, 'Asset identity rejected'):
            self.admit(assets=self.assets | {'CATALOG': b' ' * (scenarios.DOCUMENT_LIMIT + 1)})
        with self.assertRaisesRegex(ValueError, 'Bundle size rejected'):
            self.admit(assets={key: b' ' * scenarios.DOCUMENT_LIMIT for key in self.assets})

    def test_generated_source_pin_and_asset_inventory_are_checked(self):
        # Only small generated outputs are copied; original frozen inputs stay read-only.
        with tempfile.TemporaryDirectory(prefix='preview-scenario-check-') as name:
            root = Path(name)
            (root / 'contracts').symlink_to(scenarios.ROOT / 'contracts', target_is_directory=True)
            for path, value in self.outputs.items():
                (root / path).parent.mkdir(parents=True, exist_ok=True)
                (root / path).write_bytes(value)
            scenarios.check(root)
            table = root / scenarios.PIN_TABLE
            table.write_bytes(table.read_bytes() + b'// altered pin table\n')
            with self.assertRaisesRegex(ValueError, 'Generated preview output differs'):
                scenarios.check(root)

            table.write_bytes(self.outputs[scenarios.PIN_TABLE])
            extra = root / scenarios.ASSET_DIRECTORY / 'unlisted.json'
            extra.write_bytes(b'{}')
            with self.assertRaisesRegex(ValueError, 'Preview asset inventory changed'):
                scenarios.check(root)
            extra.unlink()
            target = root / scenarios.ASSET_DIRECTORY / 'empty-build.json'
            target.unlink()
            target.symlink_to(root / scenarios.ASSET_DIRECTORY / 'core-build.json')
            with self.assertRaisesRegex(ValueError, 'Generated output is not a singly linked regular file'):
                scenarios.check(root)

    def test_required_format_assertions_cannot_be_silently_omitted(self):
        missing = scenarios.FormatChecker(formats=['ipv4'])
        with patch.object(scenarios, 'FormatChecker', return_value=missing):
            with self.assertRaisesRegex(ValueError, 'Required frozen date-time/URI format assertions unavailable'):
                scenarios.validators(scenarios.ROOT)

    def copied_checkout(self, directory):
        root = directory / 'checkout'
        (root / 'scripts').mkdir(parents=True)
        for name in ('check_preview_scenarios.py', 'check_contracts.py'):
            shutil.copy2(scenarios.ROOT / 'scripts' / name, root / 'scripts' / name)
        (root / 'contracts').symlink_to(scenarios.ROOT / 'contracts', target_is_directory=True)
        for path, value in self.outputs.items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_bytes(value)
        return root

    def run_checker(self, root, *arguments):
        return subprocess.run([sys.executable, str(root / 'scripts/check_preview_scenarios.py'), *arguments],
                              capture_output=True, text=True, timeout=10)

    def test_cli_rejects_output_ancestor_symlinks_before_reading_or_writing(self):
        for ancestor in (Path('preview'), scenarios.ASSET_DIRECTORY, scenarios.PIN_TABLE.parent):
            with self.subTest(ancestor=str(ancestor)), tempfile.TemporaryDirectory(prefix='preview-output-ancestor-') as name:
                directory = Path(name)
                root = self.copied_checkout(directory)
                external = directory / 'external'
                shutil.move(root / ancestor, external)
                (root / ancestor).symlink_to(external, target_is_directory=True)
                self.assertNotEqual(0, self.run_checker(root).returncode)
                next(p for p in external.rglob('*') if p.is_file()).write_bytes(b'controlled external sentinel')
                original = {p.relative_to(external): p.read_bytes() for p in external.rglob('*') if p.is_file()}
                self.assertNotEqual(0, self.run_checker(root, '--write').returncode)
                self.assertTrue((root / ancestor).is_symlink())
                self.assertEqual(original, {p.relative_to(external): p.read_bytes()
                                            for p in external.rglob('*') if p.is_file()})

    def test_cli_preflights_late_output_alias_without_replacing_earlier_output(self):
        for alias in ('symlink', 'hardlink', 'dangling-symlink'):
            with self.subTest(alias=alias), tempfile.TemporaryDirectory(prefix='preview-output-alias-') as name:
                directory = Path(name)
                root = self.copied_checkout(directory)
                first = root / scenarios.ASSET_DIRECTORY / 'manifest.json'
                first.write_bytes(b'earlier controlled output')
                external = directory / 'external.kt'
                external.write_bytes(b'controlled external sentinel')
                target = root / scenarios.PIN_TABLE
                target.unlink()
                if alias == 'hardlink':
                    os.link(external, target)
                else:
                    target.symlink_to(external if alias == 'symlink' else directory / 'missing.kt')
                result = self.run_checker(root, '--write')
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b'earlier controlled output', first.read_bytes())
                self.assertEqual(b'controlled external sentinel', external.read_bytes())
                self.assertEqual(alias != 'hardlink', target.is_symlink())
                self.assertFalse((directory / 'missing.kt').exists())

    def test_cli_rejects_nonregular_late_output_without_blocking_or_partial_write(self):
        for kind in ('directory', 'fifo'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='preview-output-type-') as name:
                root = self.copied_checkout(Path(name))
                first = root / scenarios.ASSET_DIRECTORY / 'manifest.json'
                first.write_bytes(b'earlier controlled output')
                target = root / scenarios.PIN_TABLE
                target.unlink()
                if kind == 'directory':
                    target.mkdir()
                else:
                    os.mkfifo(target)
                result = self.run_checker(root, '--write')
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(b'earlier controlled output', first.read_bytes())

    def test_cli_regenerates_missing_outputs_and_replaces_only_owned_regular_files(self):
        with tempfile.TemporaryDirectory(prefix='preview-output-regenerate-') as name:
            directory = Path(name)
            root = self.copied_checkout(directory)
            shutil.rmtree(root / 'preview')
            for _ in range(2):
                result = self.run_checker(root, '--write')
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(self.outputs, {p.relative_to(root): p.read_bytes()
                                              for p in (root / 'preview').rglob('*') if p.is_file()})
                self.assertEqual(0, self.run_checker(root).returncode)

    def test_failed_replacement_preserves_existing_output_and_removes_temporary_file(self):
        with tempfile.TemporaryDirectory(prefix='preview-output-failure-') as name:
            root = self.copied_checkout(Path(name))
            first = root / scenarios.ASSET_DIRECTORY / 'manifest.json'
            first.write_bytes(b'earlier controlled output')
            original = {p.relative_to(root): p.read_bytes() for p in (root / 'preview').rglob('*') if p.is_file()}
            with patch.object(Path, 'replace', side_effect=OSError('controlled replacement failure')):
                with self.assertRaisesRegex(OSError, 'controlled replacement failure'):
                    scenarios.write_outputs(root, self.outputs)
            self.assertEqual(original, {p.relative_to(root): p.read_bytes()
                                        for p in (root / 'preview').rglob('*') if p.is_file()})


if __name__ == '__main__':
    unittest.main()
