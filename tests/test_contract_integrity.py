"""Exercise the real command in isolated copies, never mutate the tracked frozen import."""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class IntegrityGateTest(unittest.TestCase):
    def assert_rejected(self, mutate):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / 'contracts', root / 'contracts', ignore=shutil.ignore_patterns('__pycache__'))
            (root / 'scripts').mkdir()
            shutil.copy2(ROOT / 'scripts/check_contracts.py', root / 'scripts/check_contracts.py')
            mutate(root / 'contracts/v1')
            result = subprocess.run([sys.executable, str(root / 'scripts/check_contracts.py')],
                                    text=True, capture_output=True, timeout=30)
            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn('Contract integrity failure:', result.stderr)
            self.assertNotIn('Ran 21 tests', result.stderr)

    def test_modified_schema_fails_before_reference_execution(self):
        self.assert_rejected(lambda base: (base / 'schemas/common.schema.json').write_text('{}'))

    def test_modified_manifest_cannot_redefine_accepted_hashes(self):
        self.assert_rejected(lambda base: (base / 'IMPORT_MANIFEST.json').write_text('{}'))

    def test_missing_artifact_fails(self):
        self.assert_rejected(lambda base: (base / 'examples/build.json').unlink())

    def test_extra_schema_is_not_silently_bundled(self):
        self.assert_rejected(lambda base: (base / 'schemas/unreviewed.schema.json').write_text('{}'))


if __name__ == '__main__':
    unittest.main()
