"""Offline pip enforcement controls using harmless, locally generated wheels."""
import base64
import csv
import hashlib
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]


class ReferenceDependencyLockTest(unittest.TestCase):
    def test_lock_covers_exact_existing_versions_with_sha256_wheel_pins(self):
        versions = [line.strip() for line in (ROOT / "requirements-contracts.txt").read_text().splitlines()
                    if line.strip() and not line.lstrip().startswith("#")]
        logical = (ROOT / "requirements-contracts.lock").read_text().replace("\\\n", " ").splitlines()
        locked = {}
        for line in logical:
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            fields = line.split()
            self.assertRegex(fields[0], r"^[A-Za-z0-9_.-]+==[A-Za-z0-9_.+!-]+$")
            self.assertNotIn(fields[0], locked, "duplicate locked requirement")
            self.assertGreater(len(fields), 1, "every requirement needs an artifact hash")
            self.assertEqual(len(fields[1:]), len(set(fields[1:])), "duplicate hash")
            for field in fields[1:]:
                self.assertRegex(field, r"^--hash=sha256:[a-f0-9]{64}$")
            locked[fields[0]] = fields[1:]
        self.assertEqual(set(versions), set(locked))
        self.assertEqual(len(versions), len(locked))


class OfflinePipHashEnforcementTest(unittest.TestCase):
    def wheel(self, directory, name, payload="VALUE = 'synthetic original'\n", dependency=None):
        dist = name + "-1.0.dist-info"
        metadata = f"Metadata-Version: 2.1\nName: {name}\nVersion: 1.0\n"
        if dependency:
            metadata += f"Requires-Dist: {dependency}==1.0\n"
        files = {
            name + "/__init__.py": payload.encode(),
            dist + "/METADATA": (metadata + "\n").encode(),
            dist + "/WHEEL": b"Wheel-Version: 1.0\nGenerator: synthetic-test\nRoot-Is-Purelib: true\nTag: py3-none-any\n",
        }
        records = io.StringIO(newline="")
        writer = csv.writer(records, lineterminator="\n")
        for path, content in files.items():
            digest = base64.urlsafe_b64encode(hashlib.sha256(content).digest()).rstrip(b"=").decode()
            writer.writerow([path, "sha256=" + digest, len(content)])
        writer.writerow([dist + "/RECORD", "", ""])
        files[dist + "/RECORD"] = records.getvalue().encode()
        target = directory / f"{name}-1.0-py3-none-any.whl"
        with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path, content in files.items():
                archive.writestr(path, content)
        return hashlib.sha256(target.read_bytes()).hexdigest()

    def install(self, directory, requirement):
        lock = directory / "requirements.lock"
        lock.write_text(requirement)
        # No index, remote credentials, user configuration or installed-package shortcut.
        environment = {key: value for key, value in os.environ.items() if not key.startswith("PIP_")}
        environment.update(PIP_CONFIG_FILE=os.devnull, PIP_DISABLE_PIP_VERSION_CHECK="1", PIP_NO_INPUT="1")
        return subprocess.run(
            [sys.executable, "-m", "pip", "install", "--no-index", "--no-cache-dir", "--ignore-installed",
             "--only-binary=:all:", "--require-hashes", "--find-links", str(directory),
             "--target", str(directory / "installed"), "-r", str(lock)],
            env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60,
        )

    def test_expected_wheel_installs_but_substituted_same_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            name = "pyai_synthetic_dependency"
            expected = self.wheel(directory, name)
            requirement = f"{name}==1.0 --hash=sha256:{expected}\n"
            accepted = self.install(directory, requirement)
            self.assertEqual(accepted.returncode, 0, accepted.stdout)
            installed = directory / "installed" / name / "__init__.py"
            self.assertEqual(installed.read_text(), "VALUE = 'synthetic original'\n")
            substituted = self.wheel(directory, name, "VALUE = 'synthetic substituted'\n")
            self.assertNotEqual(expected, substituted)
            rejected = self.install(directory, requirement)
            self.assertNotEqual(rejected.returncode, 0, rejected.stdout)
            self.assertIn("DO NOT MATCH THE HASHES", rejected.stdout)
            self.assertEqual(installed.read_text(), "VALUE = 'synthetic original'\n")

    def test_unhashed_transitive_dependency_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.wheel(directory, "pyai_synthetic_child")
            parent = self.wheel(directory, "pyai_synthetic_parent", dependency="pyai_synthetic_child")
            result = self.install(directory, f"pyai_synthetic_parent==1.0 --hash=sha256:{parent}\n")
            self.assertNotEqual(result.returncode, 0, result.stdout)
            self.assertIn("Hashes are required", result.stdout)
            self.assertFalse((directory / "installed" / "pyai_synthetic_parent").exists())
            self.assertFalse((directory / "installed" / "pyai_synthetic_child").exists())

    def test_reused_environment_reverifies_wheel_and_preserves_install_on_hash_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            environment = {key: value for key, value in os.environ.items() if not key.startswith("PIP_")}
            environment.update(PIP_CONFIG_FILE=os.devnull, PIP_DISABLE_PIP_VERSION_CHECK="1", PIP_NO_INPUT="1")
            created = subprocess.run(
                [sys.executable, "-m", "venv", str(directory / "venv")], env=environment,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60,
            )
            self.assertEqual(created.returncode, 0, created.stdout)
            python = directory / "venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
            name = "pyai_synthetic_reused"
            digest = self.wheel(directory, name)
            lock = directory / "requirements.lock"
            lock.write_text(f"{name}==1.0 --hash=sha256:{digest}\n")
            command = [str(python), "-m", "pip", "install", "--no-index", "--no-cache-dir",
                       "--find-links", str(directory), "--require-hashes", "--only-binary=:all:",
                       "--force-reinstall", "-r", str(lock)]
            def install():
                return subprocess.run(command, env=environment, stdout=subprocess.PIPE,
                                      stderr=subprocess.STDOUT, text=True, timeout=60)
            accepted = install()
            self.assertEqual(accepted.returncode, 0, accepted.stdout)
            purelib = subprocess.check_output(
                [str(python), "-c", "import sysconfig; print(sysconfig.get_path('purelib'))"],
                env=environment, text=True, timeout=30,
            ).strip()
            installed = Path(purelib) / name / "__init__.py"
            installed.write_text("VALUE = 'synthetic altered installed bytes'\n")
            repaired = install()
            self.assertEqual(repaired.returncode, 0, repaired.stdout)
            self.assertEqual(installed.read_text(), "VALUE = 'synthetic original'\n")
            self.wheel(directory, name, "VALUE = 'synthetic substituted wheel'\n")
            rejected = install()
            self.assertNotEqual(rejected.returncode, 0, rejected.stdout)
            self.assertIn("DO NOT MATCH THE HASHES", rejected.stdout)
            self.assertEqual(installed.read_text(), "VALUE = 'synthetic original'\n")


if __name__ == "__main__":
    unittest.main()
