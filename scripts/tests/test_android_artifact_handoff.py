"""Check the workflow's archive layout against the unchanged strict APK gate.

The tiny ZIP models the pinned uploader's documented multi-path LCA and the
pinned downloader's destination semantics. It is not an APK, action execution,
host policy check or emulator run. All process calls are replaced with the
four exact read-only Git observations required by current_context.
"""

import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/core.yml").read_text())
SPEC = importlib.util.spec_from_file_location(
    "artifact_handoff_boundary", ROOT / "scripts/android_kvm_process.py")
boundary = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(boundary)

APK_PATH = Path("preview/build/outputs/apk/debug/preview-debug-unsigned.apk")
APK_BYTES = b"bounded host-test handoff payload; never installed\n"
APK_SHA256 = hashlib.sha256(APK_BYTES).hexdigest()
SOURCE = "a" * 40
TREE = "b" * 40


class ArtifactHandoffTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.producer = self.root / "producer"
        self.consumer = self.root / "consumer"
        self.producer.mkdir()
        self.consumer.mkdir()
        self.files = {
            APK_PATH: APK_BYTES,
            Path("preview/build/reports/lint-results-debug.xml"): b"<issues/>\n",
            Path("preview/build/reports/tests/testDebugUnitTest/index.html"): b"fixture report\n",
            Path("preview/build/test-results/testDebugUnitTest/TEST-fixture.xml"): b"<testsuite/>\n",
            Path("build/environment-selection/production.log"): b"fixture unavailable selection\n",
        }
        for relative, content in self.files.items():
            target = self.producer / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)

    def workflow_steps(self):
        uploads = [step for step in WORKFLOW["jobs"]["android-preview"]["steps"]
                   if step.get("id") == "upload-preview"]
        downloads = [step for step in WORKFLOW["jobs"]["android-installed"]["steps"]
                     if step.get("uses", "").startswith("actions/download-artifact@")]
        self.assertEqual(1, len(uploads))
        self.assertEqual(1, len(downloads))
        self.assertIs(True, downloads[0]["with"]["merge-multiple"])
        self.assertEqual("${{ needs.android-preview.outputs.unsigned_artifact_id }}",
                         downloads[0]["with"]["artifact-ids"])
        return uploads[0], downloads[0]

    def archive(self):
        upload, _ = self.workflow_steps()
        patterns = upload["with"]["path"].splitlines()
        search_roots = []
        files = set()
        for pattern in patterns:
            relative = Path(pattern)
            self.assertFalse(relative.is_absolute())
            self.assertNotIn("..", relative.parts)
            wildcard = next((index for index, part in enumerate(relative.parts)
                             if any(character in part for character in "*?[")), len(relative.parts))
            search_roots.append(self.producer.joinpath(*relative.parts[:wildcard]))
            for match in self.producer.glob(pattern):
                if match.is_dir():
                    files.update(path for path in match.rglob("*") if path.is_file())
                elif match.is_file():
                    files.add(match)
        # Same delimiter as getMultiPathLCA in the pinned upload action. The
        # accepted environment evidence is a sibling of preview/, so this is
        # the repository root, not preview/build.
        root = Path(os.path.commonpath(search_roots))
        self.assertEqual(self.producer, root)
        self.assertEqual({self.producer / relative for relative in self.files}, files)
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as output:
            for path in sorted(files):
                output.writestr(path.relative_to(root).as_posix(), path.read_bytes())
        return archive.getvalue()

    def extract(self, destination=None):
        _, download = self.workflow_steps()
        destination = Path(download["with"]["path"] if destination is None else destination)
        self.assertFalse(destination.is_absolute())
        self.assertNotIn("..", destination.parts)
        with zipfile.ZipFile(io.BytesIO(self.archive())) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual({path.as_posix() for path in self.files}, set(archive.namelist()))
            archive.extractall(self.consumer / destination)

    def current_context(self):
        environment = {"GITHUB_SHA": SOURCE, "PYAI_EXPECTED_APK_SHA256": APK_SHA256}
        allowed = {
            ("/usr/bin/git", "rev-parse", "HEAD"): SOURCE + "\n",
            ("/usr/bin/git", "rev-parse", "HEAD^{tree}"): TREE + "\n",
            ("/usr/bin/git", "diff", "--quiet"): "",
            ("/usr/bin/git", "diff", "--cached", "--quiet"): "",
        }

        def git_only(arguments, **kwargs):
            self.assertIn(tuple(arguments), allowed, "No host/device/privileged command is permitted")
            self.assertEqual({"env": environment, "timeout": 10, "capture_output": True,
                              "text": True, "check": False}, kwargs)
            return SimpleNamespace(returncode=0, stderr="", stdout=allowed[tuple(arguments)])

        with patch.object(boundary, "ROOT", self.consumer), \
                patch.object(boundary, "selected_environment", return_value=environment), \
                patch.object(boundary, "fixed_child_command", return_value=["/usr/bin/python3", "-I"]), \
                patch.object(boundary.Path, "cwd", return_value=self.consumer), \
                patch.object(boundary.platform, "system", return_value="Linux"), \
                patch.object(boundary.platform, "machine", return_value="x86_64"), \
                patch.object(boundary.platform, "freedesktop_os_release",
                             return_value={"ID": "ubuntu", "VERSION_ID": "24.04"}), \
                patch.object(boundary.subprocess, "run", side_effect=git_only) as run:
            try:
                return boundary.current_context()
            finally:
                self.assertEqual(4, run.call_count)

    def test_current_workflow_reaches_original_exact_apk_handoff(self):
        self.extract()
        try:
            context = self.current_context()
        except boundary.BoundaryFailure as error:
            self.fail("The current artifact destination misses the unchanged strict gate: " + str(error))
        self.assertEqual(SOURCE, context["source_commit"])
        self.assertEqual(TREE, context["source_tree"])
        self.assertEqual(APK_SHA256, context["unsigned_apk_sha256"])
        for relative, content in self.files.items():
            self.assertEqual(content, (self.consumer / relative).read_bytes())

    def test_previous_nested_destination_fails_at_original_file_guard(self):
        self.extract("preview/build")
        self.assertTrue((self.consumer / "preview/build" / APK_PATH).is_file())
        self.assertFalse((self.consumer / APK_PATH).exists())
        with self.assertRaisesRegex(boundary.BoundaryFailure, "Expected the original unsigned APK handoff"):
            self.current_context()

    def test_missing_or_foreign_named_apk_does_not_substitute(self):
        self.extract()
        apk = self.consumer / APK_PATH
        apk.rename(apk.with_name("another-unsigned.apk"))
        with self.assertRaisesRegex(boundary.BoundaryFailure, "Expected the original unsigned APK handoff"):
            self.current_context()

    def test_changed_apk_bytes_retain_exact_digest_rejection(self):
        self.extract()
        (self.consumer / APK_PATH).write_bytes(b"changed fixture bytes")
        with self.assertRaisesRegex(boundary.BoundaryFailure, "Unsigned APK differs from the successful producer"):
            self.current_context()

    def test_linked_apk_is_rejected_even_with_matching_target_bytes(self):
        self.extract()
        apk = self.consumer / APK_PATH
        target = apk.with_name("matching-fixture.bin")
        apk.rename(target)
        apk.symlink_to(target)
        self.assertEqual(APK_SHA256, hashlib.sha256(apk.read_bytes()).hexdigest())
        with self.assertRaisesRegex(boundary.BoundaryFailure, "Expected the original unsigned APK handoff"):
            self.current_context()


if __name__ == "__main__":
    unittest.main()
