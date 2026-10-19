"""Source/descriptor mutations must fail the real local evaluator profile gate, also under -O."""
from pathlib import Path
import shutil
import tempfile
import unittest

import check_diagnostic_fixtures as gate


class RetainedDiagnosticProfileTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        files = [gate.PROFILE_SOURCE, *gate.PROFILE_INPUTS.values()]
        files += [path.relative_to(gate.ROOT) for path in (gate.ROOT / 'contracts/v1/schemas').glob('*.schema.json')]
        for path in files:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(gate.ROOT / path, target)

    def test_actual_source_and_copied_profile_are_identical(self):
        self.assertEqual(gate.verify_profile(), gate.verify_profile(self.root))
        self.assertEqual('eaaf51a04c9bd0c7fce23e7ecc75724226e26b337cc1da04b88a59d2a912914f',
                         gate.verify_profile(self.root)['SCHEMAS_SHA256'])

    def test_changed_reference_evaluator_and_receiver_bytes_are_rejected(self):
        for relative in gate.PROFILE_INPUTS.values():
            with self.subTest(path=str(relative)):
                path = self.root / relative
                original = path.read_bytes()
                path.write_bytes(original + b'\n')
                with self.assertRaises(ValueError):
                    gate.verify_profile(self.root)
                path.write_bytes(original)
                gate.verify_profile(self.root)

    def test_missing_extra_or_changed_schema_cannot_keep_the_declared_descriptor(self):
        folder = self.root / 'contracts/v1/schemas'
        path = sorted(folder.glob('*.schema.json'))[0]
        original = path.read_bytes()
        path.unlink()
        with self.assertRaises(ValueError):
            gate.verify_profile(self.root)
        path.write_bytes(original + b'\n')
        with self.assertRaises(ValueError):
            gate.verify_profile(self.root)
        path.write_bytes(original)
        extra = folder / 'extra.schema.json'
        extra.write_bytes(b'{}')
        with self.assertRaises(ValueError):
            gate.verify_profile(self.root)
        extra.unlink()
        gate.verify_profile(self.root)

    def test_wrong_missing_and_duplicate_declared_pins_are_rejected(self):
        path = self.root / gate.PROFILE_SOURCE
        source = path.read_text()
        expected = gate.verify_profile(self.root)
        for key in ('REFERENCE_SHA256', 'EVALUATOR_SHA256', 'REPORT_RECEIVER_SHA256', 'SCHEMAS_SHA256'):
            declaration = f'    const val {key} = "{expected[key]}"'
            for changed in (source.replace(expected[key], '0' * 64), source.replace(declaration, ''), source + '\n' + declaration + '\n'):
                with self.subTest(key=key):
                    path.write_text(changed)
                    with self.assertRaises(ValueError):
                        gate.verify_profile(self.root)
            path.write_text(source)
        gate.verify_profile(self.root)

    def test_unreviewed_profile_name_and_wall_clock_relabeling_are_rejected(self):
        path = self.root / gate.PROFILE_SOURCE
        source = path.read_text()
        for original, replacement in [('local-retained-frozen-v1-no-external-evidence/1', 'current-qualified/2'),
                                      ('2026-10-16T09:00:00Z', '2026-10-18T22:00:00Z')]:
            with self.subTest(value=original):
                path.write_text(source.replace(original, replacement))
                with self.assertRaises(ValueError):
                    gate.verify_profile(self.root)
        path.write_text(source)
        gate.verify_profile(self.root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
